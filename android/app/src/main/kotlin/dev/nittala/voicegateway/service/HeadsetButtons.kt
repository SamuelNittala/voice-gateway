package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.core.loop.LoopInput

/** The parts of a media-button `KeyEvent` that matter (plain values, so the rule is JVM-tested). */
data class MediaKey(val code: Int, val down: Boolean, val repeatCount: Int, val longPress: Boolean)

/** Fold state: a press is down and not yet turned into an input; [consumed] once its long press fired. */
data class HeadsetState(val pressed: Boolean = false, val consumed: Boolean = false)

/**
 * Spec (A10, plan section 4 and G10): the headset button through the media session.
 * - Only the play/pause family counts: `KEYCODE_HEADSETHOOK` (79), `KEYCODE_MEDIA_PLAY_PAUSE` (85),
 *   `KEYCODE_MEDIA_PLAY` (126), `KEYCODE_MEDIA_PAUSE` (127). Any other key: no input, state unchanged.
 * - A long press (a repeated down flagged long-press) gives [LoopInput.HeadsetLongPress] once; its release gives
 *   nothing more.
 * - A short press gives [LoopInput.HeadsetPress] on release (never on the down, so a long press is not also a tap).
 * - A release with no press seen gives nothing.
 * Pure: returns the next state and the input, if any.
 */
object HeadsetButtons {
    val KEYS = setOf(79, 85, 126, 127)

    fun next(state: HeadsetState, key: MediaKey): Pair<HeadsetState, LoopInput?> = when {
        key.code !in KEYS -> state to null
        key.down && key.longPress && state.pressed && !state.consumed ->
            state.copy(consumed = true) to LoopInput.HeadsetLongPress
        key.down && key.repeatCount == 0 -> HeadsetState(pressed = true) to null
        key.down -> state to null
        state.pressed && !state.consumed -> HeadsetState() to LoopInput.HeadsetPress
        else -> HeadsetState() to null
    }
}
