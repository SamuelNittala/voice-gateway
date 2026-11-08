package dev.nittala.voicegateway.ui

import dev.nittala.voicegateway.core.loop.AgentView
import dev.nittala.voicegateway.core.loop.ErrorKind
import dev.nittala.voicegateway.core.loop.HeldReason
import dev.nittala.voicegateway.core.loop.LoopState
import dev.nittala.voicegateway.core.loop.MicFailure
import dev.nittala.voicegateway.core.loop.PauseReason
import dev.nittala.voicegateway.core.loop.Phase
import dev.nittala.voicegateway.core.loop.Sent
import dev.nittala.voicegateway.core.model.AgentRow
import dev.nittala.voicegateway.core.model.AskKind
import dev.nittala.voicegateway.core.model.CreateAnswer
import dev.nittala.voicegateway.core.model.CreatedBase
import dev.nittala.voicegateway.core.model.Launch
import dev.nittala.voicegateway.core.model.OfflinePlan
import dev.nittala.voicegateway.core.model.PlanAnswer
import dev.nittala.voicegateway.core.model.PlanBase
import dev.nittala.voicegateway.core.model.RepoRef
import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.model.EventKind
import dev.nittala.voicegateway.core.model.SessionRow
import dev.nittala.voicegateway.core.model.SessionsView
import dev.nittala.voicegateway.core.speech.EndReason
import dev.nittala.voicegateway.core.speech.Utterance
import dev.nittala.voicegateway.core.speech.UtteranceStats
import dev.nittala.voicegateway.core.permission.MicDenial
import dev.nittala.voicegateway.core.permission.MicGateState
import dev.nittala.voicegateway.core.ui.VoiceMode
import dev.nittala.voicegateway.core.ui.VoiceUiState
import dev.nittala.voicegateway.core.ui.voiceUiState
import dev.nittala.voicegateway.core.ui.withMicGate
import dev.nittala.voicegateway.core.worktree.WorktreeFlow

/**
 * Sample voice states, one per [VoiceMode], built through the real mapper from loop states. Used by the previews
 * and the Compose UI tests. Fake data only (no real hosts, tokens or passcodes).
 */
object SampleStates {
    const val WT = "wt-scratch"
    const val OUTPUT = "<b>Bash</b>(rm -rf build) **Allow?**\n1. Yes\n2. No &amp; tell Claude"

    private fun event(id: String, text: String, ask: AskKind = AskKind.None, session: String = WT) =
        Event(id, EventKind.Summary, session, session.removePrefix("wt-"), text, ask)

    val question = event("q1", "Did you mean wt-api or wt-web?", AskKind.Answer)
    val confirm = event("c1", "Delete the branch wt-old? This cannot be undone.", AskKind.Confirm)

    private val utterance = Utterance(ShortArray(0), UtteranceStats(0, 0, 0, 0, 32.0), EndReason.Silence)

    private val sessions = SessionsView(
        attached = listOf(SessionRow("wt-api", "attached", listOf(AgentRow("builder", "working", true)))),
        running = listOf(SessionRow(WT, "running", listOf(AgentRow("claude", "idle", true)))),
        stopped = listOf(SessionRow("wt-old", "stopped")),
        events = emptyList(),
        waiting = emptyList(),
    )

    private val idle = AgentView(state = "running", status = "idle", output = "All 42 tests pass.")
    private val working = idle.copy(status = "working")
    private val blocked = AgentView(state = "running", status = "blocked", screenToken = "sample", output = OUTPUT)

    private fun of(phase: Phase, agent: AgentView = idle, level: Float = 0f, questions: Map<String, Event> = emptyMap()) =
        voiceUiState(LoopState(WT, phase, agent, questions = questions), sessions, micLevel = level)

    /** One state per mode, in [VoiceMode] order. */
    val byMode: Map<VoiceMode, VoiceUiState> by lazy { byModeNow() }

    private fun byModeNow(): Map<VoiceMode, VoiceUiState> = mapOf(
        VoiceMode.Idle to of(Phase.Idle),
        VoiceMode.Listening to of(Phase.Listening, level = 0.6f),
        VoiceMode.AwaitingAnswer to of(Phase.AwaitingAnswer(question), level = 0.2f),
        VoiceMode.AwaitingTap to of(Phase.AwaitingTap(confirm)),
        VoiceMode.Capturing to of(Phase.Capturing(WT), level = 0.9f),
        VoiceMode.Transcribing to of(Phase.Transcribing(WT, utterance)),
        VoiceMode.Sending to of(Phase.Sending(WT, "run the unit tests please")),
        VoiceMode.Working to of(Phase.AgentWorking(Sent("run the unit tests please")), working),
        VoiceMode.Speaking to of(Phase.Speaking(event("e1", "All 42 tests pass."), Phase.Listening)),
        VoiceMode.NeedsYou to of(Phase.NeedsYou("sample", OUTPUT), blocked),
        VoiceMode.Held to of(Phase.Held("run the unit tests please", HeldReason.AgentBusy), working),
        VoiceMode.Paused to of(Phase.Paused(PauseReason.Question), questions = mapOf(WT to question)),
        VoiceMode.Stopped to of(Phase.Stopped(), AgentView(state = "stopped")),
        VoiceMode.Error to of(Phase.Error(ErrorKind.NoOrchestrator, "No orchestrator pane in wt-scratch.")),
        VoiceMode.LoggedOut to of(Phase.LoggedOut),
        VoiceMode.NewWorktree to of(nw(WorktreeFlow.Card(readyPlan, REQUEST, voice = true, listening = true))),
    )

    val all: List<VoiceUiState> get() = byMode.values.toList()

    // ---- W6: the new-worktree flow, one sample per screen state (fake data, the shapes of the gateway's answers) ----

    const val REQUEST = "a worktree in web app from production 4.6.2 for the login timeout"
    private const val SESSION = "wt-web-app-fix-login-timeout"
    private const val DATE = 1793523600L
    private val webApp = RepoRef("a1b2c3d4e5f6", "web-app", "code")
    private val notes = RepoRef("f6e5d4c3b2a1", "notes", "code/group")

    val readyPlan = PlanAnswer.Plan(
        planId = "0".repeat(32), repo = webApp, branch = "fix/login-timeout", mode = "new", ready = true,
        base = PlanBase(
            applies = true, ref = "origin/production-4.6.2", sha = "9998b76", subject = "fix the session timeout",
            message = "", name = "production-4.6.2", how = "named", committedAt = DATE, fetchedAt = DATE, local = true,
            said = "production 4.6.2", choices = listOf("main", "develop", "uat"),
        ),
        path = "~/worktrees/web-app/fix-login-timeout", expiresIn = 300,
    )
    private val needsBase = readyPlan.copy(
        ready = false,
        base = PlanBase(
            applies = true, ref = null, sha = null, subject = null, local = false,
            message = "Pick a base: nothing was created here from the phone before, and origin/HEAD is not set.",
            choices = listOf("develop", "uat", "main"),
        ),
    )
    private val existing = readyPlan.copy(
        mode = "existing",
        base = PlanBase(
            applies = false, ref = "fix/login-timeout", sha = "c476f9d", subject = "on develop",
            message = "The base does not apply: the existing branch is checked out as it is.",
        ),
    )
    private val ask = PlanAnswer.Ask(true, "Which repo did you mean?", listOf(webApp, notes))
    private val created = CreateAnswer(
        ok = true, session = SESSION, listed = true, path = readyPlan.path,
        base = CreatedBase("origin/production-4.6.2", "9998b76", "fix the session timeout", true, false, "9998b76"),
        launch = Launch("claude-opus-5-5", "high", true, false, "written"),
    )
    private val offline = OfflinePlan("1".repeat(32), "origin/production-4.6.2", "9998b76", "fix the session timeout", DATE, DATE)

    private fun nw(flow: WorktreeFlow) = Phase.NewWorktree(flow)

    /** Every screen of the flow, by name, for the previews and the UI tests. */
    val newWorktree: Map<String, VoiceUiState> by lazy {
        val card = WorktreeFlow.Card(readyPlan, REQUEST, voice = true, listening = true)
        mapOf(
            "request" to of(nw(WorktreeFlow.Request(listening = true)), level = 0.4f),
            "request typed" to of(nw(WorktreeFlow.Request(listening = false))),
            "planning" to of(nw(WorktreeFlow.Planning(REQUEST, voice = true))),
            "choosing" to of(nw(WorktreeFlow.Choosing(REQUEST, voice = true, ask = ask))),
            "card ready" to of(nw(card), level = 0.3f),
            "card needs base" to of(nw(WorktreeFlow.Card(needsBase, REQUEST, voice = true))),
            "card existing" to of(nw(WorktreeFlow.Card(existing, REQUEST, voice = false))),
            "card expired" to of(nw(WorktreeFlow.Card(readyPlan, REQUEST, voice = true, expired = true))),
            "replanning" to of(nw(WorktreeFlow.Planning(REQUEST, voice = true, previous = card))),
            "creating" to of(nw(WorktreeFlow.Creating(readyPlan.planId, REQUEST, voice = true, card = card))),
            "fetch failed" to of(nw(WorktreeFlow.Failed(
                "Could not fetch origin/production-4.6.2: origin could not be reached. Nothing was created.", REQUEST,
                voice = true, offline = offline,
            ))),
            "maybe created" to of(nw(WorktreeFlow.Failed("Not sure it was created, check the list", null, voice = true, maybeCreated = true))),
            "handoff" to of(nw(WorktreeFlow.Handoff(SESSION, REQUEST, voice = true, created = created))),
        )
    }

    /** The microphone messages: refused (Android may ask again), refused for good (settings), failed in the flow. */
    val micNotices: Map<String, VoiceUiState> by lazy {
        mapOf(
            "permission denied" to of(Phase.Idle).withMicGate(MicGateState(denial = MicDenial.Denied)),
            "permission denied forever" to of(Phase.Idle).withMicGate(MicGateState(denial = MicDenial.DeniedForever)),
            "mic failed in the flow" to voiceUiState(
                LoopState(WT, nw(WorktreeFlow.Request(listening = false)), idle, micProblem = MicFailure.Unavailable), sessions,
            ),
        )
    }
}
