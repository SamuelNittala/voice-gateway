package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.speech.Utterance

/**
 * Spec (A6): what the controller must do, in list order. Contract: a [Say] finishes before the next effect runs, so
 * `StopCapture, Say, StartCapture` never records the phone's own voice.
 */
sealed interface Effect {
    /** Open the mic; [answerMode] relaxes G3. Start after [delayMs] (300 ms speaker tail after an interrupt). */
    data class StartCapture(val answerMode: Boolean, val delayMs: Long = 0) : Effect

    data object StopCapture : Effect

    /** End the utterance now (tap while capturing); the detector then yields `SpeechEnd`. */
    data object FinishCapture : Effect

    /** `POST /api/transcribe` of [utterance] as WAV, for [worktree]. */
    data class Upload(val worktree: String, val utterance: Utterance) : Effect

    /**
     * `POST /api/prompt`. Always `voice = true` from the loop. [confirmTap] (`"confirm": "tap"`) only for the Yes or
     * No of AwaitingTap, so the server tells the agent the answer came from a tap, not a misheard voice.
     */
    data class Send(val session: String, val text: String, val voice: Boolean = true, val confirmTap: Boolean = false) : Effect

    /** Play [event] through the engine chain; [withName] for another worktree's event. */
    data class Play(val event: Event, val withName: Boolean) : Effect

    data object StopPlayback : Effect

    /** `POST /api/announced`. */
    data class Ack(val ids: List<String>) : Effect

    data class Earcon(val kind: EarconKind) : Effect

    /** Phone voice (app messages and question re-says). Dropped while muted. */
    data class Say(val text: String) : Effect

    data object Vibrate : Effect

    /** Update the ongoing notification's text. */
    data class Notify(val text: String) : Effect

    /** Poll `GET /api/session` every [intervalMs]; null: stop polling. Emitted only when the interval changes. */
    data class SetPoll(val intervalMs: Long?) : Effect

    /** Open the details sheet (dialog keys, restart). */
    data object ShowSheet : Effect

    data class SendKey(val session: String, val key: String, val screenToken: String?) : Effect

    data class SendStop(val session: String) : Effect

    data class SendRestart(val session: String) : Effect

    data class StartTimer(val timer: Timer, val ms: Long) : Effect

    data class CancelTimer(val timer: Timer) : Effect

    /** Stop the foreground service and release the wake lock. */
    data object EndService : Effect

    /** W6: `POST /api/worktree/plan` of [query] (with [repoId] after a candidate tap). */
    data class PlanWorktree(val query: String, val repoId: String? = null) : Effect

    /** W6: `POST /api/worktree/plan` of [planId] with [base] (a base tap or typed base). */
    data class ReplanBase(val planId: String, val base: String) : Effect

    /** W6: `POST /api/worktree/create` of [planId]. Never retried. */
    data class CreateWorktree(val planId: String) : Effect
}

/** [Working]: the soft working cue, every [LoopConfig.workingCueMs] while the agent works (plays while muted). */
enum class EarconKind { Listen, YourTurn, Sent, Rejected, Error, Paused, Working }

/** Every phone-voice line of the loop. */
object Lines {
    const val DIDNT_CATCH = "Didn't catch that"
    const val DIDNT_CATCH_ANSWER = "Didn't catch that, please answer again"
    const val TOO_MUCH_NOISE = "Paused, too much noise"
    const val NO_COMMANDS = "Commands starting with slash are not sent by voice"
    const val BUSY_KEPT = "The agent is busy, I kept your message"
    const val DRAFT_AT_MAC = "There is unsent text at the Mac"
    const val COULDNT_SEND = "Couldn't send, I kept your message"
    const val MAYBE_NOT_SENT = "May not have been sent; check the output"
    const val DONE_CHECK_SCREEN = "Done, check the screen"
    const val COULDNT_TRANSCRIBE = "Couldn't transcribe"
    const val WAITING_FOR_ANSWER = "The agent is waiting for your answer"
    const val LOOK_TO_CONFIRM = "Look at the phone to confirm"
    const val LOOK_TO_ANSWER = "Look at the phone to answer"
    const val SCREEN_CHANGED = "The screen changed, tap again"
    const val MIC_SILENCED = "The microphone is silenced"
    const val MIC_UNAVAILABLE = "The microphone is not available"

    /** What the Yes and No buttons of AwaitingTap send, as voice prompts with the tap mark. */
    const val CONFIRM_YES = "yes, go ahead"
    const val CONFIRM_NO = "no, do not do that"

    fun stopped(worktree: String) = "$worktree stopped"
    fun waitSeconds(seconds: Int) = "Wait $seconds seconds"
    fun askedQuestion(worktree: String) = "$worktree asked a question"
}
