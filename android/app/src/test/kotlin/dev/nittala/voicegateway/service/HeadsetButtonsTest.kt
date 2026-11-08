package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.core.loop.LoopInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec (A10): [HeadsetButtons], the media-session headset button as loop inputs. */
class HeadsetButtonsTest {
    private val hook = 79

    private fun run(vararg keys: MediaKey): List<LoopInput> {
        var state = HeadsetState()
        val out = mutableListOf<LoopInput>()
        for (k in keys) {
            val (next, input) = HeadsetButtons.next(state, k)
            state = next
            input?.let(out::add)
        }
        return out
    }

    @Test
    fun aShortPressIsAHeadsetPressOnRelease() {
        assertNull(HeadsetButtons.next(HeadsetState(), MediaKey(hook, down = true, repeatCount = 0, longPress = false)).second)
        assertEquals(
            listOf(LoopInput.HeadsetPress),
            run(MediaKey(hook, true, 0, false), MediaKey(hook, false, 0, false)),
        )
    }

    @Test
    fun aLongPressIsOneHeadsetLongPressAndItsReleaseIsNothing() {
        assertEquals(
            listOf(LoopInput.HeadsetLongPress),
            run(
                MediaKey(hook, true, 0, false), MediaKey(hook, true, 1, true), MediaKey(hook, true, 2, true),
                MediaKey(hook, false, 0, false),
            ),
        )
    }

    @Test
    fun everyPlayPauseKeyCountsAndOtherKeysDoNot() {
        for (code in listOf(79, 85, 126, 127)) {
            assertEquals("$code", listOf(LoopInput.HeadsetPress), run(MediaKey(code, true, 0, false), MediaKey(code, false, 0, false)))
        }
        assertEquals(emptyList<LoopInput>(), run(MediaKey(87, true, 0, false), MediaKey(87, false, 0, false)))
    }

    @Test
    fun aReleaseWithNoPressIsNothing() {
        assertEquals(emptyList<LoopInput>(), run(MediaKey(hook, false, 0, false)))
    }
}
