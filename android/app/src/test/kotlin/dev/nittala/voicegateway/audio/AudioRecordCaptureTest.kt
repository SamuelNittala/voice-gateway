package dev.nittala.voicegateway.audio

import dev.nittala.voicegateway.core.speech.DetectorConfig
import dev.nittala.voicegateway.core.speech.Wav
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Spec (A8): [AudioRecordCapture] with a scripted mic, a scripted voice model and a fake silence monitor, on a real
 * background thread (the mic read blocks, as AudioRecord's does).
 */
class AudioRecordCaptureTest {
    private val journal = Journal()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "capture-test") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val monitor = FakeMicSilenceMonitor(journal)
    private val sources = mutableListOf<ScriptedFrameSource>()
    private val vads = mutableListOf<ScriptedVoiceProbability>()
    private val readThread = AtomicReference<String>()

    @After
    fun tearDown() {
        dispatcher.close()
    }

    private fun capture(
        frames: List<ShortArray> = emptyList(),
        probabilities: List<Float> = emptyList(),
        openSource: (() -> FrameSource)? = null,
        failStart: Boolean = false,
        readError: Int = -1,
    ) = AudioRecordCapture(
        openSource = openSource ?: {
            readThread.set(Thread.currentThread().name)
            ScriptedFrameSource(frames, journal, "source${sources.size}", failStart = failStart, readError = readError)
                .also { sources += it }
        },
        openVoiceProbability = { ScriptedVoiceProbability(probabilities, journal).also { vads += it } },
        monitor = monitor,
        dispatcher = dispatcher,
    )

    private fun speech(): Pair<List<ShortArray>, List<Float>> {
        val frames = List(5) { Frames.tone(20) } + List(40) { Frames.LOUD } + List(60) { Frames.tone(20) }
        val probs = List(5) { 0.1f } + List(40) { 0.9f } + List(60) { 0.05f }
        return frames to probs
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private fun AudioRecordCapture.next(n: Int): List<CaptureEvent> = await { events.take(n).toList() }

    /** Waits until the session thread has finished releasing everything. */
    private fun drainThread() = await { withContext(dispatcher) { } }

    @Test
    fun scriptedSpeechFlowsEndToEndIntoWavBytes() {
        val (frames, probs) = speech()
        val c = capture(frames, probs)
        assertTrue(c.start())
        val events = c.next(2)
        assertTrue(events[0] is CaptureEvent.SpeechStarted)
        val ready = events[1] as CaptureEvent.UtteranceReady
        assertEquals(Wav.HEADER_BYTES + (5 + 40 + DetectorConfig().tailFrames) * Frames.SIZE * 2, ready.wav.size)
        assertEquals(1280L, ready.speechMs)
        drainThread()
        // The session ended itself and released everything, in this order.
        assertEquals(
            listOf("source0.start", "monitor.start(42)", "monitor.stop", "source0.stop", "source0.release", "vad.close"),
            journal.snapshot(),
        )
        assertTrue(readThread.get().startsWith("capture-test")) // the dispatcher's thread, not the caller's
    }

    @Test
    fun aNewSessionCanStartAfterOneEnded() {
        val (frames, probs) = speech()
        val c = capture(frames, probs)
        c.start()
        c.next(2)
        drainThread()
        assertTrue(c.start())
        assertTrue(c.next(2)[1] is CaptureEvent.UtteranceReady)
        assertEquals(2, sources.size)
    }

    @Test
    fun startIsIdempotentWhileRunning() {
        val c = capture()
        assertTrue(c.start())
        assertFalse(c.start())
        assertFalse(c.start())
        c.stop()
        drainThread()
        assertEquals(1, sources.size)
    }

    @Test
    fun stopReleasesEverythingAndIsIdempotent() {
        val c = capture()
        c.stop() // before any start: harmless
        c.start()
        await { while (sources.firstOrNull()?.reads != 1) delay(5) } // blocked in read
        c.stop()
        c.stop()
        drainThread()
        val source = sources.single()
        assertEquals(1, source.released)
        assertEquals(1, vads.single().closed)
        assertEquals(1, monitor.stops)
        assertTrue(journal.snapshot().contains("source0.release"))
    }

    @Test
    fun noEventIsDeliveredAfterStop() {
        val (frames, probs) = speech()
        val c = capture(frames, probs)
        c.start()
        c.stop()
        drainThread()
        val pending = runBlocking { withTimeoutOrNull(300) { c.events.first() } }
        assertNull(pending)
    }

    @Test
    fun aNewSessionWaitsForThePreviousOneToRelease() {
        val c = capture()
        c.start()
        await { while (sources.firstOrNull()?.reads != 1) delay(5) }
        c.stop()
        assertTrue(c.start())
        await { while (sources.size < 2 || sources[1].reads < 1) delay(5) }
        c.stop()
        drainThread()
        val log = journal.snapshot()
        assertTrue(log.indexOf("source0.release") < log.indexOf("source1.start"))
    }

    @Test
    fun missingPermissionFailsCleanly() {
        val c = capture(openSource = { throw SecurityException("RECORD_AUDIO is not granted") })
        c.start()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Failed(CaptureFailure.NoPermission)), c.next(1))
        drainThread()
        assertTrue(vads.isEmpty())
        assertEquals(null, monitor.watching)
    }

    @Test
    fun aMicThatCannotOpenFailsAsUnavailable() {
        val c = capture(openSource = { throw IllegalStateException("busy") })
        c.start()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Failed(CaptureFailure.Unavailable)), c.next(1))
    }

    @Test
    fun aMicThatCannotStartIsReleased() {
        val c = capture(failStart = true)
        c.start()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Failed(CaptureFailure.Unavailable)), c.next(1))
        drainThread()
        assertEquals(1, sources.single().released)
        assertEquals(1, vads.single().closed)
    }

    @Test
    fun aReadErrorFailsAndReleases() {
        val (frames, probs) = speech()
        val c = capture(frames, probs, readError = 3)
        c.start()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Failed(CaptureFailure.ReadError)), c.next(1))
        drainThread()
        assertEquals(1, sources.single().released)
    }

    @Test
    fun theSystemSilencingTheMicIsReported() {
        monitor.silencedFromCheck = 4
        val (frames, probs) = speech()
        val c = capture(frames, probs)
        c.start()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.MicSilenced), c.next(1))
        drainThread()
        assertEquals(1, monitor.stops)
        assertTrue(journal.snapshot().contains("monitor.start(42)"))
    }

    @Test
    fun anAllZeroCaptureIsReportedAsSilenced() {
        val frames = List(CaptureFold.SILENCED_AFTER_FRAMES) { Frames.zeros() }
        val c = capture(frames, List(frames.size) { 0f })
        c.start()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.MicSilenced), c.next(1))
    }

    @Test
    fun finishUtteranceIsAppliedOnTheNextFrame() {
        val frames = List(30) { Frames.LOUD } + List(10) { Frames.LOUD }
        val probs = List(40) { 0.9f }
        val arrived = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val c = AudioRecordCapture(
            openSource = {
                val inner = ScriptedFrameSource(frames, journal)
                object : FrameSource by inner {
                    private var n = 0
                    override fun read(into: ShortArray): Int {
                        if (n++ == 30) {
                            arrived.countDown()
                            gate.await()
                        }
                        return inner.read(into)
                    }
                }
            },
            openVoiceProbability = { ScriptedVoiceProbability(probs, journal) },
            monitor = monitor,
            dispatcher = dispatcher,
        )
        c.start()
        assertTrue(c.next(1).single() is CaptureEvent.SpeechStarted)
        assertTrue(arrived.await(5, TimeUnit.SECONDS)) // 30 frames folded, the 31st read is waiting
        c.finishUtterance()
        gate.countDown()
        val ready = c.next(1).single() as CaptureEvent.UtteranceReady
        assertEquals(30 * 32L, ready.speechMs)
    }
}
