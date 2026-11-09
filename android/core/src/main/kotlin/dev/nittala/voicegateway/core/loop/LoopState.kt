package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.speech.Utterance
import dev.nittala.voicegateway.core.worktree.WorktreeFlow

/*
 * Spec (A6, plan section 7): the voice loop's state, immutable. [LoopState] is the whole state the reducer sees;
 * [Phase] is the loop's state machine proper (the plan's "states"). Everything else in [LoopState] is context that
 * survives phase changes: the current worktree, the last view of it, the event queue, kept questions, counters.
 */

/** The loop's phase. Sixteen phases: the plan's state list, plus [NewWorktree] (W6). */
sealed interface Phase {
    /** Loop off. */
    data object Idle : Phase

    /** Mic on, waiting for speech (45 s silence timer). */
    data object Listening : Phase

    /** Mic on in answer mode after the agent asked [question] (`ask: true`); 20 s silence timer. */
    data class AwaitingAnswer(val question: Event, val reminded: Boolean = false) : Phase

    /** Mic OFF: the agent asked a tap-only confirmation (`ask: "confirm"` or any unknown `ask`). Yes/No buttons. */
    data class AwaitingTap(val question: Event, val reminded: Boolean = false) : Phase

    /**
     * Speech detected. [worktree] is the worktree current when speech started (G8); [answering] keeps answer mode;
     * [purpose] says where the text goes ([CapturePurpose.Prompt]: the agent; otherwise back to the new-worktree flow).
     */
    data class Capturing(
        val worktree: String,
        val answering: Event? = null,
        val purpose: CapturePurpose = CapturePurpose.Prompt,
    ) : Phase

    /**
     * The utterance is uploading. [utterance] is kept for the one retry; [retried] is true once that retry is used;
     * [waitingForNetwork] when the upload failed offline and waits for `NetworkBack`; [retryPending] when a 2 s retry
     * timer runs (429 `transcribe_busy`).
     */
    data class Transcribing(
        val worktree: String,
        val utterance: Utterance,
        val answering: Event? = null,
        val retried: Boolean = false,
        val waitingForNetwork: Boolean = false,
        val retryPending: Boolean = false,
        val purpose: CapturePurpose = CapturePurpose.Prompt,
    ) : Phase

    /**
     * The prompt is going out. [retried] once the one 3 s retry is used; [retryPending] while its timer runs.
     * [voice] is false only for a prompt typed in the sheet ([LoopInput.TypedSend]); the retry keeps it.
     * [confirmTap] only for the Yes or No of AwaitingTap (`"confirm": "tap"`); the retry keeps it too.
     */
    data class Sending(
        val worktree: String,
        val text: String,
        val answering: Event? = null,
        val retried: Boolean = false,
        val retryPending: Boolean = false,
        val voice: Boolean = true,
        val confirmTap: Boolean = false,
    ) : Phase

    /**
     * Waiting for the agent. [sent] is the prompt this turn sent (null: just waiting for the agent to be free);
     * [settledTimer] while the 20 s "settled with no final event" timer runs.
     */
    data class AgentWorking(val sent: Sent? = null, val settledTimer: Boolean = false) : Phase

    /** Playing [event]; [resume] is the phase to return to after an event of another worktree. Mic off. */
    data class Speaking(val event: Event, val resume: Phase) : Phase

    /**
     * The agent shows a dialog: taps only, mic off. [screenToken] goes with every key. [handoff] when the dialog came
     * up while waiting to send a new worktree's first prompt (a trust dialog): leaving the dialog goes back to it.
     */
    data class NeedsYou(
        val screenToken: String?,
        val output: String,
        val reminded: Boolean = false,
        val sent: Sent? = null,
        val handoff: NewWorktree? = null,
    ) : Phase

    /**
     * Text kept and shown, never sent automatically. [text] is null when there was nothing to keep. [voice] is false
     * when the kept text was typed, so Send now sends it typed again (never as a voice prompt). [confirmTap] when it
     * was a Yes or No tap, so Send now sends it as a tap again.
     */
    data class Held(
        val text: String?,
        val reason: HeldReason,
        val agentFree: Boolean = false,
        val voice: Boolean = true,
        val confirmTap: Boolean = false,
    ) : Phase

    /** Loop paused; a kept question of the current worktree stays in [LoopState.questions]. */
    data class Paused(val why: PauseReason) : Phase

    /** The worktree is stopped. [restarting] after RestartTap, until a view shows it running. */
    data class Stopped(val restarting: Boolean = false) : Phase

    /** A problem the user must look at; the loop is paused. [message] is the server's text for people. */
    data class Error(val kind: ErrorKind, val message: String) : Phase

    /** 401: log in again. Loop and service stop. */
    data object LoggedOut : Phase

    /**
     * W6: making a new worktree from the phone; the rules are `WorktreeFlowRules`. [wasLooping] when it was entered
     * from a running turn (Listening, AwaitingAnswer, AgentWorking, Held): Cancel then resumes the loop; otherwise
     * Cancel goes back to Idle.
     */
    data class NewWorktree(val flow: WorktreeFlow, val wasLooping: Boolean = false) : Phase
}

/** Where a capture's text goes. */
sealed interface CapturePurpose {
    /** To the agent as a prompt (every capture before W6). */
    data object Prompt : CapturePurpose

    /** Back to the new-worktree flow [back] (its Request or its Card), never to the agent. */
    data class Worktree(val back: Phase.NewWorktree) : CapturePurpose
}

/**
 * A prompt this loop sent. [maybeSent] when no answer came: it may or may not be in the agent. Never resent.
 * [voice] is false for a typed prompt, so text held after "may not have been sent" is offered typed again;
 * [confirmTap] keeps a Yes or No tap's mark the same way.
 */
data class Sent(val text: String, val maybeSent: Boolean = false, val voice: Boolean = true, val confirmTap: Boolean = false)

sealed interface HeldReason {
    /** `not_settled`, or a view showed the agent working before the send went out. */
    data object AgentBusy : HeldReason

    /** 409 `draft_present`: unsent text in Claude's box at the Mac. */
    data object DraftAtMac : HeldReason

    /** `repeat_window` / `action_running` again after the one retry. */
    data object RetryFailed : HeldReason

    /** The request failed before it was written: nothing was delivered. */
    data object NotSent : HeldReason

    /** Sent with no answer, and the agent stayed settled with no record for 20 s. */
    data object MaybeNotSent : HeldReason

    /** The upload failed again after the one retry (no text). */
    data object CouldNotTranscribe : HeldReason

    /** Any other refusal of the prompt; [message] is the server's. */
    data class Refused(val message: String) : HeldReason

    /** W6: a new worktree's first prompt, kept because its orchestrator was not ready within 120 s. */
    data object NewWorktreeNotReady : HeldReason

    /** W6: a new worktree's first prompt, kept for a tap (the hand-off is switched off, or was cancelled). */
    data object NewWorktreeRequest : HeldReason
}

/** Why the loop paused. [MicSilenced]: Android feeds silence (a call, another app, the privacy switch); [MicUnavailable]:
 * the mic could not be opened or broke (no permission, busy, read error). */
enum class PauseReason { User, NoSpeech, Question, Noise, FocusLost, MicSilenced, MicUnavailable }

enum class ErrorKind { PaneChanged, NoOrchestrator, SpeechOff }

/** What the last view of the current worktree said. [state] is the session state, [status] the orchestrator's. */
data class AgentView(
    val state: String? = null,
    val status: String? = null,
    val waiting: Boolean = false,
    val screenToken: String? = null,
    val output: String = "",
) {
    val stopped: Boolean get() = state == "stopped"
    val settled: Boolean get() = !stopped && (status == "idle" || status == "done")
    val working: Boolean get() = !stopped && status == "working"
    val blocked: Boolean get() = !stopped && status == "blocked"

    companion object {
        val Unknown = AgentView()
    }
}

/**
 * The reducer's whole state.
 * - [worktree]: the session every send goes to (G8).
 * - [agent]: the last view of [worktree] (reset to [AgentView.Unknown] on a worktree change).
 * - [rejections]: guard rejections in a row; [LoopConfig.maxRejections] pauses.
 * - [queue]: unacknowledged events not yet played, in arrival order; [heard] ids acknowledged (or being), so a view
 *   that still lists them does not replay them.
 * - [questions]: the open question (ask or confirm event) per worktree, kept across pauses and worktree switches.
 * - [micProblem]: the mic failed in the new-worktree flow (its Request or Card stays, mic off): shown until the mic is
 *   opened again or the flow is no longer on a Request or Card. Elsewhere `Paused(MicSilenced/MicUnavailable)` says it.
 * - [spokenProgress]: the "Speak progress updates" setting ([LoopInput.ProgressChanged]).
 * - [lastProgress]: the current worktree's latest progress line of this turn, for the working screen's subtitle;
 *   only kept while in AgentWorking (or speaking with AgentWorking to come back to).
 */
data class LoopState(
    val worktree: String,
    val phase: Phase = Phase.Idle,
    val agent: AgentView = AgentView.Unknown,
    val rejections: Int = 0,
    val muted: Boolean = false,
    val online: Boolean = true,
    val visible: Boolean = true,
    val queue: List<Event> = emptyList(),
    val heard: Set<String> = emptySet(),
    val questions: Map<String, Event> = emptyMap(),
    val micProblem: MicFailure? = null,
    val spokenProgress: Boolean = true,
    val lastProgress: String? = null,
) {
    /** The open question of the current worktree, if any. */
    val question: Event? get() = questions[worktree]
}

/** One step's result. */
data class Transition(val state: LoopState, val effects: List<Effect> = emptyList())
