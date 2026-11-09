package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.api.Refusal
import dev.nittala.voicegateway.core.model.CreateAnswer
import dev.nittala.voicegateway.core.model.PlanAnswer
import dev.nittala.voicegateway.core.model.SessionView
import dev.nittala.voicegateway.core.speech.Utterance

/**
 * Spec (A6): everything that can happen to the loop. The controller turns user actions, capture, network results and
 * fired timers into these. Results carry the worktree they belong to so a late answer for another worktree (G8) is
 * dropped. An input that means nothing in the current phase changes nothing.
 */
sealed interface LoopInput {
    /** The orb. */
    data object Tap : LoopInput

    data object LongPress : LoopInput

    /** Media-session headset button: acts as [Tap] (never a Yes). */
    data object HeadsetPress : LoopInput

    /** Headset long-press: Stop in AgentWorking, else as [LongPress]. */
    data object HeadsetLongPress : LoopInput

    data object SpeechStart : LoopInput

    data class SpeechEnd(val utterance: Utterance) : LoopInput

    data class Transcribed(val worktree: String, val text: String) : LoopInput

    data class TranscribeFailed(val worktree: String, val failure: TranscribeFailure) : LoopInput

    data class SendOk(val session: String) : LoopInput

    data class SendRefused(val session: String, val refusal: Refusal) : LoopInput

    /** No answer to the prompt POST. [maybeSent]: it was written in full (never resend); false: nothing delivered. */
    data class SendFailed(val session: String, val maybeSent: Boolean) : LoopInput

    data class View(val view: SessionView) : LoopInput

    data class PlaybackDone(val eventId: String) : LoopInput

    /** Every engine of the chain failed for this event (given up). */
    data class PlaybackFailed(val eventId: String) : LoopInput

    /** Permanent audio focus loss (a call). */
    data object FocusLost : LoopInput

    /**
     * The capture reported a microphone problem (plan section 7: a silenced client, a mic that cannot open). In
     * Listening, AwaitingAnswer and Capturing the loop pauses (keeping a question), says why and never listens to
     * nothing; elsewhere it changes nothing.
     */
    data class MicFailed(val failure: MicFailure) : LoopInput

    /**
     * A prompt typed in the details sheet. Sent with `voice = false` (today's typed rules, no voice guards), only
     * from Idle, Listening, AwaitingAnswer, AwaitingTap, Paused, Held and Error, never mid-turn; blank text is
     * ignored. It then follows the normal path (Sending, AgentWorking, the reply is spoken).
     */
    data class TypedSend(val text: String) : LoopInput

    data object NetworkLost : LoopInput

    data object NetworkBack : LoopInput

    data class WorktreeChanged(val worktree: String) : LoopInput

    /** 401 from any call. */
    data object Unauthorized : LoopInput

    data object LoggedIn : LoopInput

    /** The large Stop button: always available. */
    data object StopTap : LoopInput

    /** Restart, after the sheet's confirm dialog. */
    data object RestartTap : LoopInput

    /** A key tap in the needs-you sheet (1, 2, up, down, enter, esc). */
    data class KeyTap(val key: String) : LoopInput

    data class KeyRefused(val session: String, val refusal: Refusal) : LoopInput

    /** The on-screen Yes/No of AwaitingTap. Never produced by the mic or the headset button. */
    data class ConfirmTap(val yes: Boolean) : LoopInput

    /** Held: "Send now". */
    data object SendNowTap : LoopInput

    /** Held: "Discard". */
    data object DiscardTap : LoopInput

    data class MuteChanged(val muted: Boolean) : LoopInput

    /**
     * The "Speak progress updates" setting (persisted by the app, default on). Off: progress lines are acknowledged
     * silently and a line being spoken stops. Taken in every phase, LoggedOut too: it is a setting, not an action.
     */
    data class ProgressChanged(val spoken: Boolean) : LoopInput

    /** The activity became visible or hidden (the service keeps the loop running either way). */
    data class AppVisible(val visible: Boolean) : LoopInput

    /** The service is being destroyed (system kill, End): the loop is gone. */
    data object AppStopped : LoopInput

    data class TimerFired(val timer: Timer) : LoopInput

    // ---- W6: a new worktree from the phone (the rules are WorktreeFlowRules) --------------------------------------

    /**
     * "New worktree" on the voice screen or in the picker. Accepted in Idle, Listening, AwaitingAnswer, Paused, Held,
     * Error, Stopped and AgentWorking; ignored mid-turn (Capturing, Transcribing, Sending, Speaking) and while a
     * dialog or a confirmation waits (NeedsYou, AwaitingTap). An open question stays kept for its worktree.
     */
    data object NewWorktreeTap : LoopInput

    /** The request typed in the flow's box (planned with voice false). Blank text is ignored. */
    data class NewWorktreeTyped(val text: String) : LoopInput

    data class Planned(val answer: PlanAnswer) : LoopInput

    data class PlanRefused(val refusal: Refusal) : LoopInput

    /** No usable answer to a plan or re-plan (network, or a body the client could not read). */
    data object PlanFailed : LoopInput

    /** A repo tapped in the candidates list. */
    data class CandidateTap(val repoId: String) : LoopInput

    /** A base chip tapped on the card. */
    data class BaseTap(val name: String) : LoopInput

    /** Another base typed on the card ("Use"). */
    data class BaseTyped(val name: String) : LoopInput

    /** Create (or, on an expired card or after a failure, "Plan again"). */
    data object CreateTap : LoopInput

    /** "Create from the downloaded copy" after a failed fetch. Tap only. */
    data object OfflineCreateTap : LoopInput

    /** Cancel in the flow. */
    data object CancelTap : LoopInput

    data class Created(val answer: CreateAnswer) : LoopInput

    data class CreateRefused(val refusal: Refusal) : LoopInput

    /** The create POST was written in full and no answer came: it may have run. Never retried. */
    data object CreateMaybeDone : LoopInput
}

/** What went wrong with the microphone. */
enum class MicFailure {
    /** Android is feeding silence instead of the mic. */
    Silenced,

    /** No permission, the mic is busy or broken, or a read failed. */
    Unavailable,
}

/** Why an upload did not give text. A 401 comes as [LoopInput.Unauthorized] instead. */
sealed interface TranscribeFailure {
    /** No answer: offline, timeout, reset, or the app's own budget could not wait. */
    data object Network : TranscribeFailure

    data class Refused(val refusal: Refusal) : TranscribeFailure
}

/** The loop's timers. Starting one that runs replaces it. */
enum class Timer {
    /** 45 s in Listening, 20 s in AwaitingAnswer and in the new-worktree flow's Request and Card. */
    NoSpeech,

    /** 60 s in AwaitingTap, once. */
    ConfirmReminder,

    /** 60 s in NeedsYou, once. */
    NeedsYouReminder,

    /** 3 s before the one resend after `repeat_window` / `action_running`. */
    SendRetry,

    /** 2 s before the one re-upload after 429 `transcribe_busy`. */
    TranscribeRetry,

    /** 20 s settled with no final event after a send. */
    Settled,

    /** 10 minutes in Paused ends the loop. */
    PauseLimit,

    /** W6: the plan card's `expires_in`; then Create becomes "Plan again". */
    PlanExpiry,

    /** W6: 120 s waiting for the new orchestrator; then the request is kept in Held. */
    HandoffLimit,

    /** The soft working cue: runs exactly while in AgentWorking, when [LoopConfig.workingCueMs] is set. */
    WorkingCue,
}
