package dev.nittala.voicegateway.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec (A8): [clientSilenced], the rule behind [RecordingCallbackSilenceMonitor]. */
class MicSilenceMonitorTest {
    @Test
    fun onlyThisSessionBeingSilencedCounts() {
        val others = listOf(RecordingInfo(sessionId = 7, silenced = true), RecordingInfo(sessionId = 42, silenced = false))
        assertFalse(clientSilenced(others, 42))
        assertTrue(clientSilenced(others + RecordingInfo(42, true), 42))
    }

    @Test
    fun noRecordingListedIsNotSilenced() {
        assertFalse(clientSilenced(emptyList(), 42))
    }
}
