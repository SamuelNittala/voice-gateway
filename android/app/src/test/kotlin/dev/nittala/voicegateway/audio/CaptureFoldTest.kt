package dev.nittala.voicegateway.audio

import dev.nittala.voicegateway.core.speech.DetectorConfig
import dev.nittala.voicegateway.core.speech.EndReason
import dev.nittala.voicegateway.core.speech.Utterance
import dev.nittala.voicegateway.core.speech.UtteranceStats
import dev.nittala.voicegateway.core.speech.Wav
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Spec (A8): [CaptureFold], [judge] and [levelOf], pure, driven by scripted frames and probabilities. */
class CaptureFoldTest {
    private val config = DetectorConfig()
    private val fold = CaptureFold(config)

    /** Feeds [frames] with [probabilities] and returns every event, stopping after the first that ends the session. */
    private fun run(frames: List<ShortArray>, probabilities: List<Float>, silencedAt: Int = -1): List<CaptureEvent> {
        var state = fold.initial
        val events = mutableListOf<CaptureEvent>()
        frames.forEachIndexed { i, pcm ->
            val step = fold.next(state, pcm, probabilities[i], clientSilenced = i == silencedAt)
            state = step.state
            step.event?.let { events += it }
            if (step.event?.endsSession == true) return events
        }
        return events
    }

    private fun List<ShortArray>.withProbability(p: Float) = map { it to p }

    private fun script(vararg parts: List<Pair<ShortArray, Float>>): Pair<List<ShortArray>, List<Float>> {
        val all = parts.toList().flatten()
        return all.map { it.first } to all.map { it.second }
    }

    private fun many(n: Int, pcm: () -> ShortArray) = List(n) { pcm() }

    @Test
    fun scriptedSpeechBecomesWavWithPreRollAndTrimmedTail() {
        // 10 quiet-room frames, 40 voiced loud frames, then silence long enough to end (38 frames) and more.
        val (frames, probs) = script(
            many(10) { Frames.tone(20) }.withProbability(0.1f),
            many(40) { Frames.LOUD }.withProbability(0.9f),
            many(60) { Frames.tone(20) }.withProbability(0.05f),
        )
        val events = run(frames, probs)
        assertEquals(2, events.size)
        val started = events[0] as CaptureEvent.SpeechStarted
        assertTrue(started.level > 0.7f)
        val ready = events[1] as CaptureEvent.UtteranceReady
        // pre-roll 10 frames (all there was) + span 40 + tail trimmed to tailMs (500 ms -> 16 frames).
        val samples = (10 + 40 + config.tailFrames) * Frames.SIZE
        assertEquals(Wav.HEADER_BYTES + samples * 2, ready.wav.size)
        assertEquals("RIFF", String(ready.wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(ready.wav, 8, 4, Charsets.US_ASCII))
        assertEquals(40 * 32L, ready.speechMs)
        assertEquals(40 * 32L, ready.voicedMs)
        // The first data sample is the first pre-roll sample (20), the 11th frame starts the loud speech.
        val data = ByteBuffer.wrap(ready.wav, Wav.HEADER_BYTES, samples * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(20.toShort(), data.get(0))
        assertEquals(8_000.toShort(), data.get(10 * Frames.SIZE))
    }

    @Test
    fun wavPayloadIsExactlyTheUtterancePcm() {
        val (frames, probs) = script(
            many(30) { Frames.LOUD }.withProbability(0.9f),
            many(40) { Frames.tone(10) }.withProbability(0.0f),
        )
        val ready = run(frames, probs).last() as CaptureEvent.UtteranceReady
        val expected = Wav.encode(frames.take(30 + config.tailFrames).flatMap { it.asList() }.toShortArray())
        assertArrayEquals(expected, ready.wav)
        // The loop gets the utterance itself (PCM and frame counts) for its own gates and the transcript's word rate.
        assertArrayEquals(expected, Wav.encode(ready.utterance.pcm))
        assertEquals(ready.voicedMs, ready.utterance.stats.voicedMs)
        assertEquals(ready.speechMs, ready.utterance.stats.spanMs)
    }

    @Test
    fun quietSpeechIsRejectedByTheEnergyGate() {
        val (frames, probs) = script(
            many(40) { Frames.QUIET }.withProbability(0.9f),
            many(40) { Frames.QUIET }.withProbability(0.0f),
        )
        assertEquals(CaptureEvent.Rejected(CaptureRejection.TooQuiet), run(frames, probs).last())
    }

    @Test
    fun energyIsMeasuredOnTheSpeechNotThePreRollAndTail() {
        // Near-silent room (-90 dBFS) around 30 voiced frames of quiet real speech on VOICE_RECOGNITION (-53 dBFS body,
        // one -35 dBFS peak per frame, about -52.5 RMS): pre-roll + speech + tail averages under the G1 floor (about
        // -55.7), the speech alone is above it.
        val (frames, probs) = script(
            many(20) { Frames.tone(1) }.withProbability(0.1f),
            many(30) { Frames.tone(73).also { it[0] = 600 } }.withProbability(0.9f),
            many(60) { Frames.tone(1) }.withProbability(0.0f),
        )
        val ready = run(frames, probs).last() as CaptureEvent.UtteranceReady
        assertFalse(dev.nittala.voicegateway.core.speech.EnergyGate.passes(ready.utterance.pcm))
        assertEquals(config.preRollFrames, ready.utterance.stats.preRollFrames)
        assertEquals(config.tailFrames, ready.utterance.stats.tailFrames)
    }

    @Test
    fun aLoudBlipWithLittleVoiceIsRejectedByTheVoicedGate() {
        // 3 voiced frames start speech, then 0.4 (not voiced, not silence) keeps the span going: 3 voiced of 30.
        val (frames, probs) = script(
            many(3) { Frames.LOUD }.withProbability(0.9f),
            many(27) { Frames.LOUD }.withProbability(0.4f),
            many(40) { Frames.LOUD }.withProbability(0.0f),
        )
        assertEquals(CaptureEvent.Rejected(CaptureRejection.TooLittleVoice), run(frames, probs).last())
    }

    @Test
    fun energyIsJudgedBeforeVoice() {
        val (frames, probs) = script(
            many(3) { Frames.QUIET }.withProbability(0.9f),
            many(40) { Frames.QUIET }.withProbability(0.0f),
        )
        assertEquals(CaptureEvent.Rejected(CaptureRejection.TooQuiet), run(frames, probs).last())
    }

    @Test
    fun noSpeechForTheTimeoutPauses() {
        val short = CaptureFold(DetectorConfig(noSpeechTimeoutMs = 320)) // 10 frames
        var state = short.initial
        val events = (1..10).mapNotNull {
            short.next(state, Frames.tone(20), 0.2f, clientSilenced = false).also { state = it.state }.event
        }
        assertEquals(listOf<CaptureEvent>(CaptureEvent.NoSpeechPause), events)
    }

    @Test
    fun aSecondOfExactZerosMeansTheMicIsSilenced() {
        val events = run(many(CaptureFold.SILENCED_AFTER_FRAMES) { Frames.zeros() }, List(CaptureFold.SILENCED_AFTER_FRAMES) { 0f })
        assertEquals(listOf<CaptureEvent>(CaptureEvent.MicSilenced), events)
    }

    @Test
    fun anyNonZeroFrameResetsTheZeroRun() {
        val n = CaptureFold.SILENCED_AFTER_FRAMES
        val frames = many(n - 1) { Frames.zeros() } + listOf(Frames.tone(1)) + many(n - 1) { Frames.zeros() }
        assertTrue(run(frames, List(frames.size) { 0f }).isEmpty())
    }

    @Test
    fun theSystemSilencingTheClientEndsAtOnceEvenMidUtterance() {
        val frames = many(20) { Frames.LOUD }
        val events = run(frames, List(20) { 0.9f }, silencedAt = 10)
        assertEquals(listOf(CaptureEvent.SpeechStarted::class, CaptureEvent.MicSilenced::class), events.map { it::class })
    }

    @Test
    fun finishEndsACaptureAndJudgesIt() {
        var state = fold.initial
        repeat(30) { state = fold.next(state, Frames.LOUD, 0.9f, clientSilenced = false).state }
        val ready = fold.finish(state).event as CaptureEvent.UtteranceReady
        assertEquals(30 * 32L, ready.speechMs)
        assertEquals(Wav.HEADER_BYTES + 30 * Frames.SIZE * 2, ready.wav.size)
    }

    @Test
    fun finishWhileListeningDoesNothing() {
        assertNull(fold.finish(fold.initial).event)
    }

    @Test
    fun onlySpeechStartedKeepsTheSessionOpen() {
        assertFalse(CaptureEvent.SpeechStarted(0.5f).endsSession)
        listOf(
            CaptureEvent.UtteranceReady(ByteArray(0), 0, 0, Utterance(ShortArray(0), UtteranceStats(0, 0, 0, 0, 32.0), EndReason.Forced)),
            CaptureEvent.Rejected(CaptureRejection.TooQuiet),
            CaptureEvent.NoSpeechPause,
            CaptureEvent.MicSilenced,
            CaptureEvent.Failed(CaptureFailure.ReadError),
        ).forEach { assertTrue(it.toString(), it.endsSession) }
    }

    @Test
    fun levelMapsMinus60To0DbfsOntoZeroToOne() {
        assertEquals(0f, levelOf(Frames.zeros()), 0f)
        assertEquals(0f, levelOf(ShortArray(0)), 0f)
        assertEquals(1f, levelOf(Frames.tone(32_767)), 0.001f)
        assertEquals(0.5f, levelOf(Frames.tone(1036)), 0.01f) // about -30 dBFS
        assertEquals(0f, levelOf(Frames.tone(10)), 0f) // about -70 dBFS, clamped
    }

    @Test
    fun pcmToModelInputScalesBy32767() {
        assertArrayEquals(floatArrayOf(0f, 1f, -1f, 0.5f), pcmToModelInput(shortArrayOf(0, 32_767, -32_767, 16_384)), 0.0001f)
    }
}
