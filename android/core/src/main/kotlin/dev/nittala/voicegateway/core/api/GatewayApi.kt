package dev.nittala.voicegateway.core.api

import dev.nittala.voicegateway.core.model.CreateAnswer
import dev.nittala.voicegateway.core.model.OfflinePlan
import dev.nittala.voicegateway.core.model.PlanAnswer
import dev.nittala.voicegateway.core.model.PromptAnswer
import dev.nittala.voicegateway.core.model.SessionView
import dev.nittala.voicegateway.core.model.SessionsView
import dev.nittala.voicegateway.core.model.TranscribeAnswer
import dev.nittala.voicegateway.core.model.WorktreeSetup

/**
 * Spec (A4): the gateway's HTTP API as the app uses it. One implementation talks to the real gateway
 * ([HttpGatewayApi]); tests and the loop use fakes. `/api/audio` is not here (A9).
 *
 * Every call answers an [ApiResult] and never throws for network or server trouble.
 * - Any route but login answering 401 -> [ApiResult.LoggedOut] (the cookie is dead: the gateway restarted or it
 *   expired). Login answering 401 is a wrong passcode -> [ApiResult.Refused] with status 401.
 * - Other 4xx/5xx -> [ApiResult.Refused] with a [Refusal].
 * - A POST written in full with no answer -> [ApiResult.MaybeSent]. Never retried by this layer, by OkHttp, or by
 *   anyone automatically: the prompt may already be in the agent.
 */
interface GatewayApi {
    /** `POST /api/login {"passcode"}`. Ok keeps the `vg_login` cookie. 429 carries `retryAfterSeconds`. */
    suspend fun login(passcode: String): ApiResult<Unit>

    /** `GET /api/sessions`. */
    suspend fun sessions(): ApiResult<SessionsView>

    /** `GET /api/session?name=<name>`, with `&earlier=1` when [earlier]. */
    suspend fun session(name: String, earlier: Boolean = false): ApiResult<SessionView>

    /**
     * `POST /api/prompt {"session", "text", "voice"}`. `voice` is always sent: true from the loop, false typed.
     * [confirmTap] adds `"confirm": "tap"` (a Yes or No tap of AwaitingTap); absent otherwise.
     */
    suspend fun prompt(session: String, text: String, voice: Boolean, confirmTap: Boolean = false): ApiResult<PromptAnswer>

    /** `POST /api/keys {"session", "key", "screen_token"}` (`null` when there is none: the server refuses it). */
    suspend fun keys(session: String, key: String, screenToken: String?): ApiResult<Unit>

    /** `POST /api/stop {"session"}`. */
    suspend fun stop(session: String): ApiResult<Unit>

    /** `POST /api/restart {"session"}`. */
    suspend fun restart(session: String): ApiResult<Unit>

    /** `POST /api/announced {"ids"}`: acknowledges events so they are not repeated. */
    suspend fun announced(ids: List<String>): ApiResult<Unit>

    /**
     * `POST /api/transcribe` with the raw recording as the body (not JSON): [contentType] (`audio/wav` from the
     * phone) and a Content-Length, never chunked. The server caps it at 2 MiB, one at a time, 12 a minute.
     */
    suspend fun transcribe(audio: ByteArray, contentType: String = WAV): ApiResult<TranscribeAnswer>

    /** `GET /api/worktree/setup`: whether new worktrees can be planned here. */
    suspend fun worktreeSetup(): ApiResult<WorktreeSetup>

    /**
     * `POST /api/worktree/plan {"query"}`, plus `"repo_id"` only when [repoId] is given (a candidate tap). The whole
     * call may take [Timeouts.planMillis] (the interpreter, then `git ls-remote`). Planning creates nothing, so a
     * plan with no answer is simply asked again by the user.
     */
    suspend fun plan(query: String, repoId: String? = null): ApiResult<PlanAnswer>

    /** `POST /api/worktree/plan {"plan_id", "base"}`: the same repo and branch with that base (a tap or typed). */
    suspend fun replan(planId: String, base: String): ApiResult<PlanAnswer>

    /**
     * `POST /api/worktree/create {"plan_id"}`. Up to [Timeouts.createMillis] (fetch, then `wt new`). No answer is
     * [ApiResult.MaybeSent]: never retried (the plan is used up on the server anyway).
     */
    suspend fun create(planId: String): ApiResult<CreateAnswer>

    companion object {
        const val WAV = "audio/wav"
    }
}

/** The outcome of one call. */
sealed interface ApiResult<out T> {
    /** 2xx with a body the models could read. */
    data class Ok<out T>(val value: T) : ApiResult<T>

    /** 401 from any route but login: log in again. The stored cookie has been cleared. */
    data object LoggedOut : ApiResult<Nothing>

    /** The gateway (or its request guards) said no. */
    data class Refused(val refusal: Refusal) : ApiResult<Nothing>

    /**
     * A POST whose request was written in full but no answer came (timeout, connection cut): it may have been
     * delivered. Surfaced as is and never retried automatically.
     */
    data object MaybeSent : ApiResult<Nothing>

    /** Nothing was delivered: the request failed before it was written, or a GET got no answer. Safe to try later. */
    data object NetworkFailure : ApiResult<Nothing>

    /** An answer the client does not expect: a redirect (never followed) or a 2xx body that does not parse. */
    data class Unexpected(val httpStatus: Int) : ApiResult<Nothing>
}

/**
 * One refusal, from [refusalOf]. [reason] is null for a request guard's body (401, 403, 411, 415, 400, 413 before
 * the route) or a non-gateway answer: go by [httpStatus] then. The extra fields are set only with their reasons:
 * [agentStatus] (wire `status`) with `not_settled`, `not_blocked`, `not_working`; [output] and [screenToken] with
 * `screen_changed`; [retryAfterSeconds] with `transcribe_busy`, `transcribe_rate` and a login lockout; [offline] only
 * with `fetch_failed` (the downloaded-copy plan, offered as its own tap).
 * [message] is the server's text for people; it never holds prompt text.
 */
data class Refusal(
    val httpStatus: Int,
    val reason: RefusalReason?,
    val message: String,
    val agentStatus: String? = null,
    val output: String? = null,
    val screenToken: String? = null,
    val retryAfterSeconds: Int? = null,
    val offline: OfflinePlan? = null,
)

/**
 * The S1 reasons and the new-worktree reasons, by their wire names. A reason the app does not know is [Unknown],
 * never dropped.
 */
enum class RefusalReason(val wire: String) {
    BadText("bad_text"),
    NotListed("not_listed"),
    Stopped("stopped"),
    NoOrchestrator("no_orchestrator"),
    PaneChanged("pane_changed"),
    NotSettled("not_settled"),
    NotBlocked("not_blocked"),
    NotWorking("not_working"),
    DraftPresent("draft_present"),
    ScreenChanged("screen_changed"),
    RepeatWindow("repeat_window"),
    ActionRunning("action_running"),
    HerdrRefused("herdr_refused"),
    HerdrTimeoutMaybeSent("herdr_timeout_maybe_sent"),
    HerdrFailed("herdr_failed"),
    AlreadyRunning("already_running"),
    RestartRefused("restart_refused"),
    RestartFailed("restart_failed"),
    VoiceCommand("voice_command"),
    VoiceNoise("voice_noise"),
    SpeechOff("speech_off"),
    BadAudioType("bad_audio_type"),
    AudioEmpty("audio_empty"),
    AudioTooLarge("audio_too_large"),
    TranscribeBusy("transcribe_busy"),
    TranscribeRate("transcribe_rate"),
    SpeechFailed("speech_failed"),

    // New worktrees (TASKS.md "Orchestrator launch and new worktree from the app"): plan, re-plan and create.
    BadQuery("bad_query"),
    BadRepo("bad_repo"),
    BadPlan("bad_plan"),
    BadBase("bad_base"),
    RepoGone("repo_gone"),
    PlanGone("plan_gone"),
    WorktreesOff("worktrees_off"),
    InterpreterOff("interpreter_off"),
    NoRepos("no_repos"),
    PlanBusy("plan_busy"),
    PlanExpired("plan_expired"),
    CreateBusy("create_busy"),
    RepoUnlisted("repo_unlisted"),
    NotMainCheckout("not_main_checkout"),
    BranchCheckedOut("branch_checked_out"),
    NoCommit("no_commit"),
    TargetExists("target_exists"),
    RepoChanged("repo_changed"),
    BranchUnusable("branch_unusable"),
    BaseUnusable("base_unusable"),
    NotReady("not_ready"),
    WtMissing("wt_missing"),
    WtUnrunnable("wt_unrunnable"),
    GitFailed("git_failed"),
    FetchFailed("fetch_failed"),
    WtFailed("wt_failed"),
    WtTimeout("wt_timeout"),
    NoFolder("no_folder"),
    NoSessionName("no_session_name"),
    Unknown("");

    companion object {
        /** Pure: null stays null (a guard's body); a listed name maps to its entry; anything else is [Unknown]. */
        fun of(wire: String?): RefusalReason? =
            wire?.let { name -> entries.firstOrNull { it != Unknown && it.wire == name } ?: Unknown }
    }
}
