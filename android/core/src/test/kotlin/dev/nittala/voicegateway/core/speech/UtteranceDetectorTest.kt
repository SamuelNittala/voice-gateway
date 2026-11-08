package dev.nittala.voicegateway.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class UtteranceDetectorTest {
    private val cfg = DetectorConfig()
    private val det = UtteranceDetector(cfg)

    /** A frame whose first sample is its global index, so tests can see which frames reached the audio. */
    private fun frame(index: Int, p: Float) = Frame(ShortArray(cfg.frameSamples) { if (it == 0) index.toShort() else 1000 }, p)

    private class Run(val state: DetectorState, val events: List<Pair<Int, DetectorEvent>>) {
        val ended get() = events.map { it.second }.filterIsInstance<DetectorEvent.Ended>().map { it.utterance }
        fun indexOf(e: DetectorEvent) = events.single { it.second == e }.first
    }

    private fun run(probs: List<Float>, from: DetectorState = det.initial, offset: Int = 0, d: UtteranceDetector = det): Run {
        var state = from
        val events = mutableListOf<Pair<Int, DetectorEvent>>()
        probs.forEachIndexed { i, p ->
            val step = d.next(state, frame(offset + i, p))
            state = step.state
            step.event?.let { events += (offset + i) to it }
        }
        return Run(state, events)
    }

    private fun ids(u: Utterance) = (0 until u.pcm.size / cfg.frameSamples).map { u.pcm[it * cfg.frameSamples].toInt() }

    private fun rep(p: Float, n: Int) = List(n) { p }

    private val silent = 0.05f
    private val voiced = 0.9f

    @Test
    fun configInFramesRoundsWaitsUpAndTheCapDown() {
        assertEquals(32L, cfg.millis(1))
        assertEquals(38, cfg.endSilenceFrames) // 1216 ms >= 1.2 s
        assertEquals(875, cfg.maxUtteranceFrames) // 28 000 ms exactly
        assertEquals(1407, cfg.noSpeechFrames) // 45 024 ms >= 45 s
        assertEquals(16, cfg.preRollFrames) // 512 ms >= 500 ms
        assertEquals(16, cfg.tailFrames)
        assertEquals(25, cfg.minVoicedFrames) // 800 ms
        assertEquals(0.6f, cfg.startProbability)
        assertEquals(0.35f, cfg.endProbability)
        assertEquals(3, cfg.startFrames)
        assertEquals(0.4, cfg.minVoicedShare, 0.0)
        assertEquals(20, DetectorConfig(noSpeechTimeoutMs = 640).noSpeechFrames)
        assertEquals(25, DetectorConfig(endSilenceMs = 800).endSilenceFrames)
    }

    @Test
    fun speechStartsOnTheThirdConsecutiveVoicedFrame() {
        val r = run(rep(silent, 5) + rep(voiced, 3))
        assertEquals(listOf(7 to DetectorEvent.SpeechStarted), r.events)
        assertTrue(r.state is DetectorState.Capturing)
        assertTrue(run(rep(voiced, 2)).events.isEmpty())
    }

    @Test
    fun startNeedsAnUnbrokenRunAtOrAboveSixTenths() {
        assertTrue(run(listOf(0.9f, 0.9f, 0.59f, 0.9f, 0.9f, 0.5f, 0.99f, 0.7f)).events.isEmpty())
        assertTrue(run(rep(0.5999f, 200)).events.isEmpty())
        assertEquals(2, run(rep(0.6f, 3)).indexOf(DetectorEvent.SpeechStarted))
    }

    @Test
    fun nanProbabilityIsNotSpeech() {
        assertTrue(run(rep(Float.NaN, 100)).events.isEmpty())
        val r = run(rep(voiced, 30) + rep(Float.NaN, 38))
        assertEquals(EndReason.Silence, r.ended.single().reason)
    }

    @Test
    fun preRollKeepsUpTo16FramesBeforeTheRun() {
        val r = run(rep(silent, 30) + rep(voiced, 30) + rep(silent, 38))
        val u = r.ended.single()
        assertEquals(16, u.stats.preRollFrames)
        assertEquals(14, ids(u).first()) // frames 14..29 before the run that starts at 30
        assertEquals(30, u.stats.spanFrames)

        val short = run(rep(silent, 5) + rep(voiced, 30) + rep(silent, 38)).ended.single()
        assertEquals(5, short.stats.preRollFrames)
        assertEquals(0, ids(short).first())

        val none = run(rep(voiced, 30) + rep(silent, 38)).ended.single()
        assertEquals(0, none.stats.preRollFrames)
    }

    @Test
    fun endsAfter38FramesBelowThirtyFiveHundredths() {
        val speech = rep(silent, 16) + rep(voiced, 40)
        assertTrue(run(speech + rep(0.3499f, 37)).ended.isEmpty())
        val r = run(speech + rep(0.3499f, 38))
        assertEquals(EndReason.Silence, r.ended.single().reason)
        assertEquals(16 + 40 + 37, r.events.last().first)
        assertEquals(det.initial, r.state)
    }

    @Test
    fun aFrameAtThirtyFiveHundredthsResetsTheSilence() {
        val r = run(rep(voiced, 30) + rep(silent, 37) + listOf(0.35f) + rep(silent, 37))
        assertTrue(r.ended.isEmpty())
        val u = run(rep(voiced, 30) + rep(silent, 37) + listOf(0.35f) + rep(silent, 38)).ended.single()
        assertEquals(30 + 37 + 1, u.stats.spanFrames) // the span runs to the 0.35 frame
        assertEquals(30, u.stats.voicedFrames) // in-between frames are not voiced
    }

    @Test
    fun audioIsPreRollSpanAndAtMost16TailFrames() {
        val u = run(rep(silent, 20) + rep(voiced, 30) + rep(silent, 38)).ended.single()
        assertEquals(16, u.stats.tailFrames)
        assertEquals(16 + 30 + 16, u.stats.totalFrames)
        assertEquals((4 until 66).toList(), ids(u))
        assertEquals(u.stats.totalFrames * cfg.frameSamples, u.pcm.size)
    }

    @Test
    fun capEndsAt875FramesPreRollIncluded() {
        val r = run(rep(silent, 10) + rep(voiced, 2000))
        val first = r.ended.first()
        assertEquals(EndReason.Cap, first.reason)
        assertEquals(875, first.stats.totalFrames)
        assertEquals(10, first.stats.preRollFrames)
        assertEquals(0, first.stats.tailFrames)
        assertEquals(865, first.stats.voicedFrames)
        assertEquals(874, r.events.first { it.second is DetectorEvent.Ended }.first)
        assertTrue(Wav.encode(first.pcm).size < Wav.SERVER_MAX_BYTES)
        assertEquals(28_000L, cfg.millis(first.stats.totalFrames))
        // Speech going on starts a new utterance three frames later (fresh state, no pre-roll carried over).
        val started = r.events.filter { it.second == DetectorEvent.SpeechStarted }.map { it.first }
        assertEquals(listOf(12, 877), started.take(2))
    }

    @Test
    fun capWithFullPreRollStillStopsAt875() {
        val u = run(rep(silent, 100) + rep(voiced, 900)).ended.single()
        assertEquals(875, u.stats.totalFrames)
        assertEquals(16, u.stats.preRollFrames)
    }

    @Test
    fun noSpeechAfter1407FramesThenCountsAgain() {
        val r = run(rep(silent, 1406))
        assertTrue(r.events.isEmpty())
        val r2 = run(rep(silent, 2814))
        assertEquals(listOf(1406 to DetectorEvent.NoSpeech, 2813 to DetectorEvent.NoSpeech), r2.events)
    }

    @Test
    fun brokenRunsAndNoiseDoNotDelayTheNoSpeechTimeout() {
        val noisy = List(1407) { i -> if (i % 3 == 2) 0.2f else 0.8f } // two voiced, one not, forever
        assertEquals(listOf(1406 to DetectorEvent.NoSpeech), run(noisy).events)
    }

    @Test
    fun noSpeechCountRestartsAfterAnUtterance() {
        val r = run(rep(voiced, 30) + rep(silent, 38) + rep(silent, 1407))
        val endAt = r.events.first { it.second is DetectorEvent.Ended }.first
        assertEquals(endAt + 1407, r.indexOf(DetectorEvent.NoSpeech))
    }

    @Test
    fun answerModeTimeoutIsJustAnotherConfig() {
        val answer = UtteranceDetector(DetectorConfig(noSpeechTimeoutMs = 20_000))
        assertEquals(625, answer.config.noSpeechFrames)
        assertEquals(624, run(rep(silent, 700), from = answer.initial, d = answer).indexOf(DetectorEvent.NoSpeech))
    }

    @Test
    fun finishEndsNowInCapturingAndDoesNothingInListening() {
        val capturing = run(rep(silent, 4) + rep(voiced, 30) + rep(silent, 5)).state
        val step = det.finish(capturing)
        val u = (step.event as DetectorEvent.Ended).utterance
        assertEquals(EndReason.Forced, u.reason)
        assertEquals(4, u.stats.preRollFrames)
        assertEquals(30, u.stats.spanFrames)
        assertEquals(5, u.stats.tailFrames)
        assertEquals(det.initial, step.state)

        val listening = run(rep(silent, 10)).state
        val idle = det.finish(listening)
        assertNull(idle.event)
        assertSame(listening, idle.state)
    }

    @Test
    fun wrongFrameSizeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { det.next(det.initial, Frame(ShortArray(511), 0.9f)) }
        assertThrows(IllegalArgumentException::class.java) { det.next(det.initial, Frame(ShortArray(0), 0.9f)) }
    }

    @Test
    fun statsCountVoicedFramesInsideTheSpan() {
        // 3 voiced start frames, 10 frames at 0.4 (speech-ish, not voiced), 22 voiced: 25 voiced of a 35-frame span.
        val u = run(rep(silent, 16) + rep(voiced, 3) + rep(0.4f, 10) + rep(voiced, 22) + rep(silent, 38)).ended.single()
        assertEquals(35, u.stats.spanFrames)
        assertEquals(25, u.stats.voicedFrames)
        assertEquals(800L, u.stats.voicedMs)
        assertEquals(1120L, u.stats.spanMs)
        assertTrue(VoicedGate.passes(u.stats))
    }

    @Test
    fun aNoiseBurstWithOneSpeechLikeBlipFailsG2() {
        val u = run(rep(silent, 16) + rep(voiced, 4) + rep(0.45f, 60) + rep(silent, 38)).ended.single()
        assertEquals(4, u.stats.voicedFrames)
        assertFalse(VoicedGate.passes(u.stats))
    }

    @Test
    fun aShortRealWordIsBelowTheVoicedMinimum() {
        val u = run(rep(silent, 16) + rep(voiced, 24) + rep(silent, 38)).ended.single()
        assertFalse(VoicedGate.passes(u.stats))
    }

    @Test
    fun propertyRandomFramesKeepTheInvariants() {
        val rnd = Random(20261108)
        repeat(200) { trial ->
            // Bursty input: runs of random length at a random level, so every rule gets exercised.
            val probs = buildList {
                while (size < 3000) {
                    val p = when (rnd.nextInt(5)) {
                        0 -> Float.NaN.takeIf { rnd.nextInt(20) == 0 } ?: 0.0f
                        1 -> rnd.nextFloat() * 0.35f
                        2 -> 0.35f + rnd.nextFloat() * 0.25f
                        else -> 0.6f + rnd.nextFloat() * 0.4f
                    }
                    repeat(rnd.nextInt(1, if (rnd.nextInt(10) == 0) 1500 else 60)) { add(p) }
                }
            }
            val r = run(probs)
            assertEquals("deterministic, trial $trial", r.events.map { it.first to it.second::class }, run(probs).events.map { it.first to it.second::class })
            var capturing = false
            var lastEnd = -1
            for ((index, event) in r.events) {
                when (event) {
                    DetectorEvent.SpeechStarted -> { assertFalse(capturing); capturing = true }
                    DetectorEvent.NoSpeech -> assertFalse(capturing)
                    is DetectorEvent.Ended -> {
                        assertTrue(capturing); capturing = false
                        val u = event.utterance
                        val s = u.stats
                        assertEquals(s.totalFrames * cfg.frameSamples, u.pcm.size)
                        assertTrue(s.preRollFrames in 0..16)
                        assertTrue(s.tailFrames in 0..16)
                        assertTrue(s.totalFrames <= 875)
                        assertTrue(s.spanFrames >= 3)
                        assertTrue(s.voicedFrames in 3..s.spanFrames)
                        val got = ids(u)
                        assertEquals("consecutive frames, trial $trial", (got.first()..got.last()).toList(), got)
                        assertTrue(got.first() > lastEnd)
                        assertTrue(got.last() <= index)
                        assertEquals(VoicedGate.passes(s), s.voicedFrames >= 25 && s.voicedFrames >= 0.4 * s.spanFrames)
                        if (u.reason == EndReason.Silence) assertEquals(index - 38, got.last() - s.tailFrames)
                        lastEnd = index
                    }
                }
            }
        }
    }

    @Test
    fun inputFramesAreNotChanged() {
        val frames = List(80) { frame(it, if (it in 10..50) voiced else silent) }
        val copies = frames.map { it.pcm.copyOf() }
        var state = det.initial
        frames.forEach { state = det.next(state, it).state }
        frames.zip(copies).forEach { (f, c) -> assertTrue(f.pcm.contentEquals(c)) }
    }
}
