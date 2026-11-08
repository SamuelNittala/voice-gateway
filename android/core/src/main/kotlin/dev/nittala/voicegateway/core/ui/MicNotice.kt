package dev.nittala.voicegateway.core.ui

import dev.nittala.voicegateway.core.loop.LoopState
import dev.nittala.voicegateway.core.loop.MicFailure
import dev.nittala.voicegateway.core.permission.MicDenial
import dev.nittala.voicegateway.core.permission.MicGateState

/** The microphone messages the screen shows. */
object MicTexts {
    const val PERMISSION_NEEDED = "Microphone permission is needed to listen"
    const val TAP_TO_ALLOW = "Tap again to allow it."
    const val ALLOW_IN_SETTINGS = "Android will not ask again. Allow the microphone in the app settings, then come back."
    const val NOT_AVAILABLE = "The microphone is not available"
    const val SILENCED = "The microphone is silenced"
    const val TAP_TO_RETRY = "Tap to try again."
}

/**
 * Pure: the permission message for the gate's state. Null without a denial; a denial that may be asked again says to
 * tap again; a denial for good offers the app settings.
 */
fun micNoticeUi(gate: MicGateState): MicNoticeUi? = when (gate.denial) {
    null -> null
    MicDenial.Denied -> MicNoticeUi(MicTexts.PERMISSION_NEEDED, MicTexts.TAP_TO_ALLOW)
    MicDenial.DeniedForever -> MicNoticeUi(MicTexts.PERMISSION_NEEDED, MicTexts.ALLOW_IN_SETTINGS, openSettings = true)
}

/** Pure: the loop's own microphone message ([LoopState.micProblem], set in the new-worktree flow), with a retry hint. */
fun micNoticeUi(loop: LoopState): MicNoticeUi? = when (loop.micProblem) {
    null -> null
    MicFailure.Unavailable -> MicNoticeUi(MicTexts.NOT_AVAILABLE, MicTexts.TAP_TO_RETRY)
    MicFailure.Silenced -> MicNoticeUi(MicTexts.SILENCED, MicTexts.TAP_TO_RETRY)
}

/** Pure: [this] screen with the gate's permission message on top; a permission message wins over the loop's own. */
fun VoiceUiState.withMicGate(gate: MicGateState): VoiceUiState =
    micNoticeUi(gate)?.let { copy(micNotice = it) } ?: this
