package dev.nittala.voicegateway.audio

import dev.nittala.voicegateway.core.speech.DetectorConfig
import dev.nittala.voicegateway.core.speech.EnergyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec (debug diagnostics): every capture result carries [CaptureStats], numbers only, so a DEBUG build can show why
 * an utterance was not uploaded. [CaptureFold] counts the frames it fed, the highest voice probability and the
 * loudest frame of the session; the utterance's RMS, peak, speech and voiced time come from the ended utterance.
 */
class CaptureStatsTest {
    private val config = DetectorConfig()

    private fun run(fold: CaptureFold, frames: List<Pair<ShortArray, Float>>): List<CaptureEvent> {
        var state = fold.initial
        val events = mutableListOf<CaptureEvent>()
        for ((pcm, p) in frames) {
            val step = fold.next(state, pcm, p, clientSilenced = false)
            state = step.state
            step.event?.let { events += it }
            if (step.event?.endsSession == true) break
        }
        return events
    }

    private fun frames(n: Int, pcm: () -> ShortArray, p: Float) = List(n) { pcm() to p }

    @Test
    fun aTooQuietRejectionCarriesItsNumbers() {
        // 40 quiet voiced frames (speech starts on the 3rd), then silence: the end comes on the 38th silent frame.
        val events = run(CaptureFold(config), frames(40, { Frames.QUIET }, 0.9f) + frames(60, { Frames.QUIET }, 0f))
        val rejected = events.last() as CaptureEvent.Rejected
        assertEquals(CaptureRejection.TooQuiet, rejected.reason)
        val stats = rejected.stats!!
        val quiet = EnergyGate.measure(Frames.QUIET)
        assertEquals(40 + config.endSilenceFrames, stats.framesFed)
        assertEquals(0.9f, stats.maxProbability, 0f)
        assertEquals(quiet.rmsDbfs, stats.rmsDbfs, 1e-9)
        assertEquals(quiet.peakDbfs, stats.peakDbfs, 1e-9)
        assertEquals(quiet.rmsDbfs, stats.loudestFrameDbfs, 1e-9)
        assertEquals(40 * 32L, stats.speechMs)
        assertEquals(40 * 32L, stats.voicedMs)
        assertEquals(1.0, stats.voicedShare, 1e-9)
    }

    @Test
    fun aTooLittleVoiceRejectionCarriesTheVoicedShare() {
        val events = run(
            CaptureFold(config),
            frames(3, { Frames.LOUD }, 0.9f) + frames(27, { Frames.LOUD }, 0.4f) + frames(40, { Frames.LOUD }, 0f),
        )
        val rejected = events.last() as CaptureEvent.Rejected
        assertEquals(CaptureRejection.TooLittleVoice, rejected.reason)
        val stats = rejected.stats!!
        assertEquals(30 * 32L, stats.speechMs)
        assertEquals(3 * 32L, stats.voicedMs)
        assertEquals(0.1, stats.voicedShare, 1e-9)
        assertEquals(EnergyGate.measure(Frames.LOUD).rmsDbfs, stats.rmsDbfs, 1e-9)
    }

    @Test
    fun aReadyUtteranceCarriesItsNumbersToo() {
        val events = run(
            CaptureFold(config),
            frames(5, { Frames.tone(20) }, 0.1f) + frames(40, { Frames.LOUD }, 0.95f) + frames(60, { Frames.tone(20) }, 0f),
        )
        val ready = events.last() as CaptureEvent.UtteranceReady
        val stats = ready.stats!!
        assertEquals(5 + 40 + config.endSilenceFrames, stats.framesFed)
        assertEquals(0.95f, stats.maxProbability, 0f)
        // rms/peak are G1's numbers: the speech span only, not the quiet pre-roll and tail around it.
        assertEquals(EnergyGate.measure(Frames.LOUD).rmsDbfs, stats.rmsDbfs, 1e-9)
        assertEquals(EnergyGate.measure(Frames.LOUD).peakDbfs, stats.peakDbfs, 1e-9)
        assertEquals(EnergyGate.measure(Frames.LOUD).rmsDbfs, stats.loudestFrameDbfs, 1e-9)
        assertEquals(ready.speechMs, stats.speechMs)
        assertEquals(ready.voicedMs, stats.voicedMs)
    }

    @Test
    fun aTapEndCarriesTheNumbers() {
        val fold = CaptureFold(config)
        var state = fold.initial
        repeat(30) { state = fold.next(state, Frames.LOUD, 0.8f, clientSilenced = false).state }
        val event = fold.finish(state).event as CaptureEvent.UtteranceReady
        assertEquals(30, event.stats!!.framesFed)
        assertEquals(0.8f, event.stats!!.maxProbability, 0f)
    }

    @Test
    fun aNaNProbabilityCountsAsZero() {
        val events = run(
            CaptureFold(config, progressEveryFrames = 4),
            frames(4, { Frames.tone(20) }, Float.NaN),
        )
        val progress = events.single() as CaptureEvent.ListenProgress
        assertEquals(0f, progress.stats.maxProbability, 0f)
    }

    @Test
    fun listeningReportsProgressEveryNFramesOnlyWhenAskedTo() {
        val quietRoom = frames(25, { Frames.tone(20) }, 0.2f)
        assertTrue(run(CaptureFold(config), quietRoom).isEmpty())

        val progress = run(CaptureFold(config, progressEveryFrames = 10), quietRoom)
        assertEquals(2, progress.size)
        progress.forEach { assertFalse("progress must not end the session", it.endsSession) }
        val stats = progress.map { (it as CaptureEvent.ListenProgress).stats }
        assertEquals(listOf(10, 20), stats.map { it.framesFed })
        assertEquals(0.2f, stats[1].maxProbability, 0f)
        assertEquals(EnergyGate.measure(Frames.tone(20)).rmsDbfs, stats[1].loudestFrameDbfs, 1e-9)
        assertEquals(0L, stats[1].speechMs)
        assertTrue(stats[1].rmsDbfs.isNaN())
    }

    @Test
    fun noProgressWhileCapturing() {
        val events = run(CaptureFold(config, progressEveryFrames = 5), frames(20, { Frames.LOUD }, 0.9f))
        assertEquals(1, events.size)
        assertTrue(events.single() is CaptureEvent.SpeechStarted)
    }
}
