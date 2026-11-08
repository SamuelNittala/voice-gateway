package dev.nittala.voicegateway.core.ui

import dev.nittala.voicegateway.core.config.BuildVariant
import dev.nittala.voicegateway.core.speech.Engine

/*
 * Spec (A11): what the screens show, as plain immutable data. Pure Kotlin (no Android, no Compose) so the rules are
 * JVM-tested in :core; the Compose screens in :app only draw these values and call `VoiceUiActions`.
 *
 * Text rules: every string here is plain text. Output and event text from the gateway is passed through unchanged
 * and the screens draw it as literal text, never as markup or HTML. No passcode, cookie or screen token is ever put
 * in these models (the screen token stays in the loop state; keys are sent by the loop with it).
 */

/**
 * The screen the voice view shows: exactly one per loop phase (`Phase`, sixteen). Each has its own label and orb
 * motion, so state never depends on colour alone.
 */
enum class VoiceMode {
    Idle, Listening, AwaitingAnswer, AwaitingTap, Capturing, Transcribing, Sending, Working, Speaking, NeedsYou,
    Held, Paused, Stopped, Error, LoggedOut, NewWorktree,
}

/**
 * What a tap on the orb does in this mode, so the screen calls the matching action and plays a haptic on
 * [Interrupt].
 * - [Start]: Idle, Paused, Error (starts or resumes the loop).
 * - [Interrupt]: Speaking (stops the audio and listens).
 * - [Tap]: Listening, AwaitingAnswer (pause), Capturing (end the utterance now), AwaitingTap (re-say the question).
 * - [None]: every other mode (the orb is not a button there).
 */
enum class OrbTap { Start, Interrupt, Tap, None }

/** Which group of the sessions list a worktree is in. */
enum class WorktreeGroup { Attached, Running, Stopped }

/**
 * One row of the worktree picker.
 * - [agentStatus]: the orchestrator's Herdr status (idle, done, working, blocked, unknown), null when none.
 * - [problem]: the server's error for this session (Herdr could not be read), null when none.
 * - [current]: the worktree the loop drives.
 * - [openQuestion]: the loop keeps an unanswered question or confirmation for it.
 * - [waiting]: a voice prompt sent to it has no final event yet.
 */
data class WorktreeItem(
    val name: String,
    val group: WorktreeGroup,
    val agentStatus: String?,
    val problem: String? = null,
    val current: Boolean = false,
    val openQuestion: Boolean = false,
    val waiting: Boolean = false,
)

/**
 * The kept message of Held. [text] is null when nothing could be kept (could not transcribe); [reason] is the plain
 * reason always shown with it; [canSendNow] only when there is text (sending is always a tap, never automatic).
 */
data class HeldUi(val text: String?, val reason: String, val canSendNow: Boolean)

/**
 * The voice view, whole.
 * - [mode]: the screen, one per phase.
 * - [title]: short status line ("Listening"); [detail]: one plain sentence or null.
 * - [question]: shown large: the open question in AwaitingAnswer, AwaitingTap, Paused (if one is kept) and while
 *   capturing or transcribing an answer.
 * - [micOn]: the mic is open (Listening, AwaitingAnswer, Capturing). [level]: mic level 0..1, 0 when the mic is off.
 * - [confirmButtons]: the large Yes and No; true ONLY in AwaitingTap.
 * - [keys]: the dialog keys (subset of [ALLOWED_KEYS], in that order); non-empty ONLY in NeedsYou with a screen
 *   token, and only keys the gateway allows.
 * - [canStop]: the large Stop; true only while the agent works (Working, or the last view says working).
 * - [canRestart]: only in Stopped and not already restarting (the screen asks to confirm first).
 * - [held]: non-null only in Held.
 * - [output]: recent output of the current worktree, literal.
 * - [speakingFrom]: in Speaking, the other worktree whose event is playing; null for the current one.
 * - [orbTap]: see [OrbTap]. [openSheet]: modes where the details sheet should open by itself (NeedsYou, Stopped).
 * - [canType]: the gateway accepts a typed prompt now.
 * - [engineInfo], [dictationInfo]: plain lines for the sheet.
 * - [announcement]: what TalkBack announces when the screen changes (title plus question or detail).
 * - [newWorktree]: the new-worktree flow's screen part (W6), non-null in NewWorktree and while capturing or
 *   transcribing for it; [canStartNewWorktree]: the "New worktree" button is offered (the loop accepts the tap).
 * - [micNotice]: a persistent microphone message (permission refused, or the mic failed in the new-worktree flow);
 *   null when there is nothing to say.
 */
data class VoiceUiState(
    val worktree: String,
    val mode: VoiceMode,
    val title: String,
    val detail: String? = null,
    val question: String? = null,
    val micOn: Boolean = false,
    val level: Float = 0f,
    val confirmButtons: Boolean = false,
    val keys: List<String> = emptyList(),
    val canStop: Boolean = false,
    val canRestart: Boolean = false,
    val held: HeldUi? = null,
    val output: String = "",
    val speakingFrom: String? = null,
    val orbTap: OrbTap = OrbTap.None,
    val openSheet: Boolean = false,
    val canType: Boolean = false,
    val muted: Boolean = false,
    val online: Boolean = true,
    val worktrees: List<WorktreeItem> = emptyList(),
    val engineInfo: String = "",
    val dictationInfo: String = "",
    val announcement: String = "",
    val newWorktree: NewWorktreeUi? = null,
    val canStartNewWorktree: Boolean = false,
    val micNotice: MicNoticeUi? = null,
) {
    companion object {
        /** The keys a needs-you dialog may get, in display order (gateway.py's allowed keys). */
        val ALLOWED_KEYS = listOf("1", "2", "up", "down", "enter", "esc")
    }
}

/**
 * W6: the new-worktree flow as the screen shows it, the same content as the web page's plan card. Display strings and
 * flags only: never a plan id (it stays in the loop state, like the screen token). A candidate keeps its repo id, which
 * the tap sends back.
 */
sealed interface NewWorktreeUi {
    /** Say or type the request. [listening]: the mic is on. */
    data class Request(val listening: Boolean) : NewWorktreeUi

    /** "Working it out..." for [query]; [previous] is the old card, greyed, while a base re-plan runs. */
    data class Planning(val query: String, val previous: CardUi?) : NewWorktreeUi

    /** The gateway's [question] above the repos to tap. */
    data class Choosing(val question: String, val candidates: List<CandidateUi>) : NewWorktreeUi

    data class Card(val card: CardUi) : NewWorktreeUi

    /** "Creating the worktree..." with the card it came from (null for the downloaded-copy plan). */
    data class Creating(val card: CardUi?) : NewWorktreeUi

    /**
     * Not created. [offlineLabel] "Create from the downloaded copy (dated ...)" when that is offered; [canPlanAgain]
     * offers "Plan again"; [openPicker] when it may have been created after all (the list is opened to check).
     */
    data class Failed(
        val message: String,
        val offlineLabel: String?,
        val canPlanAgain: Boolean,
        val openPicker: Boolean,
    ) : NewWorktreeUi

    /**
     * Created: waiting for [session]'s orchestrator before the request goes. [started] is where the new branch
     * started ("Started from origin/x at abc1234 subject (fetched just now)."), null for an existing branch;
     * [launch] what `wt` started the orchestrator with, null when it did not say.
     */
    data class Handoff(val session: String, val request: String, val started: String?, val launch: String?) : NewWorktreeUi
}

/**
 * A microphone message that stays on screen until it no longer applies. [message] is the headline, [hint] what to do
 * next; [openSettings] offers the button that opens the system app settings (only when Android will not ask again).
 */
data class MicNoticeUi(val message: String, val hint: String, val openSettings: Boolean = false)

/** One repo to tap: [label] is "name (where)". */
data class CandidateUi(val repoId: String, val label: String)

/**
 * The plan card (web page: repo, branch, mode, base line, notes, base chips, another base, path, Create/Cancel).
 * - [repo] "name (where)"; [branch] "Branch: x"; [mode] "New branch from origin/x (last used here)", "New branch:
 *   pick a base" or "Existing branch, checked out as it is (the base does not apply)".
 * - [baseLine] "sha subject; local copy: downloaded <date>" (or "commit of <date>"), "sha on origin, not downloaded
 *   here yet", or for an existing branch "sha subject"; null when no base is picked.
 * - [note] the server's message; [fetchNote] "Fetched from origin just before creating." for a new branch with a base.
 * - [choices] base names offered as taps (shown as `origin/<name>`), [currentChoice] the planned one (not tappable);
 *   [otherBase] the typed-base box and Use are offered (new branch only).
 * - [createEnabled] only when ready and not expired; [createLabel] "Create", or "Plan again" once [expired].
 * - [expiresInSeconds] for the countdown the screen runs from when the card appears.
 * - [greyed] while a base re-plan runs; [listening] the mic is open for "create it" or "cancel" ([hint] says so).
 */
data class CardUi(
    val repo: String,
    val branch: String,
    val mode: String,
    val baseLine: String?,
    val note: String?,
    val fetchNote: String?,
    val choices: List<String>,
    val currentChoice: String?,
    val otherBase: Boolean,
    val path: String,
    val createEnabled: Boolean,
    val createLabel: String,
    val expired: Boolean,
    val expiresInSeconds: Int,
    val greyed: Boolean = false,
    val listening: Boolean = false,
    val hint: String? = null,
)

/**
 * User settings the voice view reflects. [speaker] null means the gateway default (Chatterbox, then Deepgram, then
 * the phone). [engineNote] is `EngineChain.note()` of the last played event ("" when the first choice played).
 * [endOfSpeechMs] is the silence that ends an utterance.
 */
data class UiSettings(val speaker: Engine? = null, val engineNote: String = "", val endOfSpeechMs: Int = 1_200)

/**
 * The Login screen. [baseUrl] is the stored one or "" (the passcode is never in here: it lives only in the text
 * field and is not saved across process death). [error] is a plain sentence from the controller ("Wrong passcode").
 * [variant] lets the screen explain a refused URL live with [baseUrlProblem].
 */
data class LoginUiState(
    val baseUrl: String = "",
    val rememberPasscode: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val variant: BuildVariant = BuildVariant.RELEASE,
)

/** The Settings screen. Remember passcode is off by default; [speaker] null means the gateway default. */
data class SettingsUiState(
    val baseUrl: String = "",
    val rememberPasscode: Boolean = false,
    val muted: Boolean = false,
    val speaker: Engine? = null,
    val variant: BuildVariant = BuildVariant.RELEASE,
)
