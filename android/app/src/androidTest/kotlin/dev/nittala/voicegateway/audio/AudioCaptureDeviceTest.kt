package dev.nittala.voicegateway.audio

import android.Manifest
import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.nittala.voicegateway.core.speech.DetectorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec (A8), on a device or emulator (virtual mic): with RECORD_AUDIO granted, AudioRecord opens at 16 kHz mono
 * PCM16 and delivers 512-sample frames at the real rate (32 ms each); the bundled Silero model loads and answers a
 * probability; the full capture starts, stops and starts again without leaking the mic.
 */
@RunWith(AndroidJUnit4::class)
class AudioCaptureDeviceTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val frameSamples = DetectorConfig().frameSamples

    @Test
    fun audioRecordDeliversFullFramesAtTheRealRate() {
        val source = AudioRecordFrameSource.open(context)
        try {
            source.start()
            val buffer = ShortArray(frameSamples)
            repeat(10) { assertEquals(frameSamples, source.read(buffer)) } // let the start-up buffer drain
            val frames = 100
            val started = System.nanoTime()
            repeat(frames) { assertEquals(frameSamples, source.read(buffer)) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            val expectedMs = frames * 32.0
            assertTrue("100 frames took $elapsedMs ms, expected about $expectedMs", elapsedMs in expectedMs * 0.8..expectedMs * 1.25)
            assertTrue(source.sessionId > 0)
        } finally {
            source.stop()
            source.release()
        }
    }

    @Test
    fun aStopMakesABlockedReadReturn() {
        val source = AudioRecordFrameSource.open(context)
        try {
            source.start()
            val stopper = Thread { Thread.sleep(100); source.stop() }.apply { start() }
            val buffer = ShortArray(frameSamples * 100) // 3.2 s: would block that long without the stop
            val started = System.nanoTime()
            val n = source.read(buffer)
            stopper.join()
            assertTrue(n < buffer.size)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        } finally {
            source.release()
        }
    }

    @Test
    fun sileroGivesAProbabilityBetweenZeroAndOne() {
        SileroVoiceProbability(context).use { vad ->
            repeat(5) {
                val p = vad.probability(ShortArray(frameSamples))
                assertTrue("probability $p", p in 0f..1f)
            }
        }
    }

    @Test
    fun captureStartsStopsAndStartsAgain() {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val capture = AudioRecordCapture(
            openSource = { AudioRecordFrameSource.open(context) },
            openVoiceProbability = { SileroVoiceProbability(context) },
            monitor = RecordingCallbackSilenceMonitor(audioManager),
        )
        assertTrue(capture.start())
        assertFalse(capture.start())
        Thread.sleep(500)
        capture.stop()
        capture.stop()
        assertTrue(capture.start())
        Thread.sleep(500)
        capture.stop()
    }
}
