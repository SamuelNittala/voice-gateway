package dev.nittala.voicegateway.audio

import android.media.AudioManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec (A8): [SystemAudioFocusGate] over a fake requester, and [focusEventOf]. */
class AudioFocusGateTest {
    private val requester = FakeFocusRequester(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    private val gate = SystemAudioFocusGate(requester)

    private fun events(n: Int) = runBlocking { gate.events.take(n).toList() }
    private fun noEvent() = runBlocking { withTimeoutOrNull(100) { gate.events.first() } }

    @Test
    fun acquireRequestsOnceWhileHeld() {
        assertEquals(FocusResult.Granted, gate.acquire())
        assertEquals(FocusResult.Granted, gate.acquire())
        assertEquals(1, requester.requests)
    }

    @Test
    fun aRefusedRequestIsDeniedAndAskedAgainNextTime() {
        requester.answer = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        assertEquals(FocusResult.Denied, gate.acquire())
        requester.answer = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        assertEquals(FocusResult.Granted, gate.acquire())
        assertEquals(2, requester.requests)
    }

    @Test
    fun aDelayedGrantCountsAsDeniedAndIsAbandoned() {
        requester.answer = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        assertEquals(FocusResult.Denied, gate.acquire())
        assertEquals(1, requester.abandons)
    }

    @Test
    fun permanentLossIsReportedAbandonedAndNotHeld() {
        gate.acquire()
        requester.change(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(listOf<FocusEvent>(FocusEvent.Lost(permanent = true)), events(1))
        assertEquals(1, requester.abandons)
        gate.acquire()
        assertEquals(2, requester.requests)
    }

    @Test
    fun transientLossIsReportedAndStillHeld() {
        gate.acquire()
        requester.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        requester.change(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(FocusEvent.Lost(permanent = false), FocusEvent.Regained), events(2))
        assertEquals(0, requester.abandons)
        gate.acquire()
        assertEquals(1, requester.requests)
    }

    @Test
    fun duckingIsReportedAndKeepsListening() {
        gate.acquire()
        requester.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(listOf<FocusEvent>(FocusEvent.Ducked), events(1))
        assertEquals(0, requester.abandons)
    }

    @Test
    fun releaseAbandonsOnceAndDropsLateChanges() {
        gate.release() // nothing held: no abandon
        gate.acquire()
        gate.release()
        gate.release()
        assertEquals(1, requester.abandons)
        requester.change(AudioManager.AUDIOFOCUS_LOSS)
        assertNull(noEvent())
    }

    @Test
    fun focusChangesMapToEvents() {
        assertEquals(FocusEvent.Lost(true), focusEventOf(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(FocusEvent.Lost(false), focusEventOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(FocusEvent.Ducked, focusEventOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(FocusEvent.Regained, focusEventOf(AudioManager.AUDIOFOCUS_GAIN))
        assertNull(focusEventOf(12345))
    }
}
