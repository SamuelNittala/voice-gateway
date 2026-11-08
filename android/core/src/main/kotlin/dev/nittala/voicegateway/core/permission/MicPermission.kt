package dev.nittala.voicegateway.core.permission

/*
 * Spec (microphone permission gate): every user action that will open the microphone goes through ONE gate.
 *
 * - RECORD_AUDIO granted: the action runs at once.
 * - Not granted and Android may still show its prompt: the action is remembered and the prompt is shown; the action
 *   runs once the user allows it, and is dropped if the user denies it.
 * - Not granted and Android will not show the prompt any more (denied for good: the denial came back with no
 *   rationale wanted): nothing runs; the screen says so and offers the system app settings.
 * - A later grant (from the settings, seen when the app comes back) clears the message; the next tap runs normally.
 *
 * POST_NOTIFICATIONS is asked together with the microphone but never decides anything here.
 * Pure: no Android. The Android calls sit behind `MicPermissionPort` in :app.
 */

/** An action that will open the microphone, remembered while Android shows its prompt. */
sealed interface MicAction {
    /** A code for the DEBUG line: the action's name only, never its text. */
    val code: String get() = this::class.simpleName ?: "Action"

    /** The orb in Idle, Paused or Error. */
    data object Start : MicAction

    /** The orb in a listening or flow mode (the flow's "tap to speak" too). */
    data object TapOrb : MicAction

    /** The orb while the agent's reply plays. */
    data object Interrupt : MicAction

    /** Yes / No of a tap-only confirmation (the loop listens after the reply). */
    data class Confirm(val yes: Boolean) : MicAction

    /** "Send now" of kept text. */
    data object SendHeld : MicAction

    /** A prompt typed in the sheet (the loop listens after the reply). */
    data class SendTyped(val text: String) : MicAction {
        override fun toString(): String = "SendTyped(${text.length} chars)"
    }

    /** "New worktree": the request is listened for at once. */
    data object NewWorktree : MicAction
}

/** What the gate does with one action. */
sealed interface MicDecision {
    /** The permission is there: run [action] now. */
    data class Proceed(val action: MicAction) : MicDecision

    /** Show Android's prompt; run [action] if the user allows it. */
    data class Request(val action: MicAction) : MicDecision

    /** Android will not show the prompt: run nothing, offer the app settings. */
    data object ShowSettings : MicDecision
}

/**
 * Pure: the gate's decision. [granted]: RECORD_AUDIO is granted now. [deniedForever]: the last answer was a denial
 * after which Android wanted no rationale (it will not show the prompt again).
 */
fun micDecision(granted: Boolean, deniedForever: Boolean, action: MicAction): MicDecision = when {
    granted -> MicDecision.Proceed(action)
    deniedForever -> MicDecision.ShowSettings
    else -> MicDecision.Request(action)
}

/** How the user last refused the microphone. */
enum class MicDenial {
    /** Refused once; the prompt can be shown again on the next tap. */
    Denied,

    /** Refused for good: only the app settings can grant it. */
    DeniedForever,
}

/**
 * The gate's state. [pending] is the action waiting on Android's prompt (non-null exactly while the prompt is up);
 * [denial] is shown on screen until a grant.
 */
data class MicGateState(val pending: MicAction? = null, val denial: MicDenial? = null)

/** One gate step: the new [state], whether to show Android's prompt ([request]) and the action to run now ([run]). */
data class MicGateStep(val state: MicGateState, val request: Boolean = false, val run: MicAction? = null)

/**
 * Pure: the gate as a small state machine.
 * - [onAction]: [micDecision] for the tapped action. Proceed clears any message. Request shows the prompt once: a tap
 *   while the prompt is already up only replaces the remembered action. ShowSettings forgets any pending action.
 * - [onResult]: Android's answer. Granted runs the remembered action (once) and clears the message; denied runs
 *   nothing and remembers how ([rationale] true: may ask again; false: denied for good).
 * - [onReturn]: the app is back in front (from the settings): a grant clears the message.
 */
object MicGate {
    fun onAction(state: MicGateState, granted: Boolean, action: MicAction): MicGateStep =
        when (micDecision(granted, state.denial == MicDenial.DeniedForever, action)) {
            is MicDecision.Proceed -> MicGateStep(MicGateState(), run = action)
            is MicDecision.Request -> MicGateStep(state.copy(pending = action), request = state.pending == null)
            MicDecision.ShowSettings -> MicGateStep(MicGateState(denial = MicDenial.DeniedForever))
        }

    fun onResult(state: MicGateState, granted: Boolean, rationale: Boolean): MicGateStep =
        if (granted) MicGateStep(MicGateState(), run = state.pending)
        else MicGateStep(MicGateState(denial = if (rationale) MicDenial.Denied else MicDenial.DeniedForever))

    fun onReturn(state: MicGateState, granted: Boolean): MicGateState = if (granted) state.copy(denial = null) else state
}
