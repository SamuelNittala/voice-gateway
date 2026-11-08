package dev.nittala.voicegateway.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec (A9): the pure rules under [ExoSpeaker]: [clipStep] and [NoRetryPolicy]. */
class ExoSpeakerRulesTest {
    @Test
    fun firstPlayingIsTheStartAndLaterOnesAreNothing() {
        assertEquals(ClipEvent.Started, clipStep(started = false, signal = PlayerSignal.Playing))
        assertNull(clipStep(started = true, signal = PlayerSignal.Playing))
    }

    @Test
    fun endedAfterTheStartIsFinishedAndBeforeItAFailure() {
        assertEquals(ClipEvent.Finished, clipStep(true, PlayerSignal.Ended))
        assertEquals(true, clipStep(false, PlayerSignal.Ended) is ClipEvent.Failed)
    }

    @Test
    fun anErrorIsAFailureEitherWay() {
        assertEquals(ClipEvent.Failed("ERROR_CODE_IO_BAD_HTTP_STATUS"), clipStep(false, PlayerSignal.Error("ERROR_CODE_IO_BAD_HTTP_STATUS")))
        assertEquals(ClipEvent.Failed("cut"), clipStep(true, PlayerSignal.Error("cut")))
    }

    /**
     * A load error of the [count]th try. Built without its constructor: a real one needs an `android.net.Uri`, which
     * the JVM's stub android.jar cannot make, and the policy must not look at it anyway.
     */
    private fun errorInfo(count: Int): LoadErrorHandlingPolicy.LoadErrorInfo {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        val info = allocate.invoke(unsafe, LoadErrorHandlingPolicy.LoadErrorInfo::class.java) as LoadErrorHandlingPolicy.LoadErrorInfo
        LoadErrorHandlingPolicy.LoadErrorInfo::class.java.getDeclaredField("errorCount").apply { isAccessible = true }.setInt(info, count)
        return info
    }

    @Test
    fun noLoadIsEverRetried() {
        (1..10).forEach { assertEquals(C.TIME_UNSET, NoRetryPolicy.getRetryDelayMsFor(errorInfo(it))) }
    }

    @Test
    fun noFallbackSelection() {
        assertNull(NoRetryPolicy.getFallbackSelectionFor(LoadErrorHandlingPolicy.FallbackOptions(1, 0, 1, 0), errorInfo(1)))
    }

    @Test
    fun noMinimumRetries() {
        assertEquals(0, NoRetryPolicy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }
}
