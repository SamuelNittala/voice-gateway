package dev.nittala.voicegateway.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class EnergyGateTest {
    private fun amplitudeFor(dbfs: Double) = (32768.0 * 10.0.pow(dbfs / 20.0))

    /** A square wave of +-amplitude: RMS equals peak. */
    private fun square(amplitude: Int, n: Int = 1600) =
        ShortArray(n) { (if (it % 2 == 0) amplitude else -amplitude).toShort() }

    private fun sine(amplitude: Double, n: Int = 16000) =
        ShortArray(n) { (amplitude * sin(2 * PI * 440 * it / 16000.0)).roundToInt().toShort() }

    @Test
    fun thresholdsAreThePlans() {
        // Measured 2026-11-08 on a Nothing Phone (1), VOICE_RECOGNITION source (no automatic gain).
        assertEquals(-55.0, EnergyGate.MIN_RMS_DBFS, 0.0)
        assertEquals(-40.0, EnergyGate.MIN_PEAK_DBFS, 0.0)
    }

    @Test
    fun theRealUtteranceFromThePhonePassesWithMargin() {
        // `cap TooQuiet rms=-48.0 peak=-29.5 ...` with Silero pmax 0.99: real speech, wrongly stopped by the old gate.
        val real = Energy(rmsDbfs = -48.0, peakDbfs = -29.5)
        assertTrue(EnergyGate.passes(real))
        assertTrue(real.rmsDbfs - EnergyGate.MIN_RMS_DBFS >= 5.0)
        assertTrue(real.peakDbfs - EnergyGate.MIN_PEAK_DBFS >= 5.0)
        // Its loudest single frame (-38.3) alone would pass too.
        assertTrue(EnergyGate.passes(Energy(-38.3, -38.3 + 3.0)))
    }

    @Test
    fun theRoomNoiseFromThePhoneFails() {
        // Ambient room while listening: loudest 32 ms frame -58.6 dBFS. Synthetic: steady -60 dBFS with -58 peaks.
        val room = square(amplitudeFor(-60.0).roundToInt(), 16000)
        for (i in 0 until room.size step 400) room[i] = amplitudeFor(-58.0).roundToInt().toShort()
        val e = EnergyGate.measure(room)
        assertEquals(-60.0, e.rmsDbfs, 0.3)
        assertEquals(-58.0, e.peakDbfs, 0.1)
        assertFalse(EnergyGate.passes(room))
        assertFalse(EnergyGate.passes(Energy(rmsDbfs = -58.6, peakDbfs = -58.6)))
    }

    @Test
    fun silenceAndEmptyAreMinusInfinityAndFail() {
        for (pcm in listOf(ShortArray(0), ShortArray(16000))) {
            val e = EnergyGate.measure(pcm)
            assertEquals(Double.NEGATIVE_INFINITY, e.rmsDbfs, 0.0)
            assertEquals(Double.NEGATIVE_INFINITY, e.peakDbfs, 0.0)
            assertFalse(EnergyGate.passes(pcm))
        }
    }

    @Test
    fun fullScaleIsZeroDbfs() {
        val e = EnergyGate.measure(shortArrayOf(Short.MIN_VALUE, Short.MIN_VALUE))
        assertEquals(0.0, e.rmsDbfs, 1e-9)
        assertEquals(0.0, e.peakDbfs, 1e-9)
    }

    @Test
    fun clippedAudioPasses() {
        assertTrue(EnergyGate.passes(square(32767)))
        assertTrue(EnergyGate.passes(ShortArray(1600) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }))
    }

    @Test
    fun sineRmsIsThreeDbBelowPeak() {
        val e = EnergyGate.measure(sine(amplitudeFor(-10.0)))
        assertEquals(-10.0, e.peakDbfs, 0.05)
        assertEquals(-13.01, e.rmsDbfs, 0.05)
    }

    @Test
    fun rmsMustBeAboveMinus55() {
        // Quiet steady hum at -56 dBFS with a click at -24 dBFS: peak passes, RMS fails.
        val hum = square(amplitudeFor(-56.0).roundToInt(), 16000)
        hum[100] = 2000
        val e = EnergyGate.measure(hum)
        assertTrue(e.peakDbfs > -40.0)
        assertTrue(e.rmsDbfs < -55.0)
        assertFalse(EnergyGate.passes(hum))
    }

    @Test
    fun peakMustBeAboveMinus40() {
        // Steady -42 dBFS square: RMS -42 passes, peak -42 fails.
        assertFalse(EnergyGate.passes(square(amplitudeFor(-42.0).roundToInt())))
        assertTrue(EnergyGate.passes(square(amplitudeFor(-39.0).roundToInt())))
    }

    @Test
    fun boundariesAreStrict() {
        assertFalse(EnergyGate.passes(Energy(rmsDbfs = -55.0, peakDbfs = -10.0)))
        assertFalse(EnergyGate.passes(Energy(rmsDbfs = -20.0, peakDbfs = -40.0)))
        assertTrue(EnergyGate.passes(Energy(rmsDbfs = -54.999, peakDbfs = -39.999)))
        assertFalse(EnergyGate.passes(Energy(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)))
    }

    private val config = DetectorConfig()
    private val fs = config.frameSamples

    /** An utterance of [pre] pre-roll frames of [preAmp], [span] frames of [spanAmp] and [tail] frames of [tailAmp]. */
    private fun utterance(pre: Int, preAmp: Int, span: Int, spanAmp: Int, tail: Int, tailAmp: Int, voiced: Int = span) =
        Utterance(
            square(preAmp, pre * fs) + square(spanAmp, span * fs) + square(tailAmp, tail * fs),
            UtteranceStats(pre, span, voiced, tail, config.frameMs),
            EndReason.Silence,
        )

    @Test
    fun speechPcmIsTheSpanWithoutPreRollAndTail() {
        val u = utterance(pre = 16, preAmp = 1, span = 10, spanAmp = 700, tail = 16, tailAmp = 2)
        val speech = EnergyGate.speechPcm(u, fs)
        assertEquals(10 * fs, speech.size)
        assertTrue(speech.all { it == 700.toShort() || it == (-700).toShort() })
    }

    @Test
    fun g1JudgesTheSpeechOnlySoSilencePaddingDoesNotDiluteIt() {
        // Quiet speech (-53 dBFS body with one -35 dBFS peak per frame, about -52.5 RMS) for 10 frames between 16
        // frames of digital silence on each side: the whole buffer is about -59 dBFS RMS (fails), the speech passes.
        val u = utterance(pre = 16, preAmp = 0, span = 10, spanAmp = amplitudeFor(-53.0).roundToInt(), tail = 16, tailAmp = 0)
        for (f in 16 until 26) u.pcm[f * fs] = 600
        assertFalse(EnergyGate.passes(u.pcm))
        val e = EnergyGate.measureSpeech(u, fs)
        assertEquals(-52.5, e.rmsDbfs, 0.3)
        assertEquals(-34.7, e.peakDbfs, 0.1)
        assertTrue(EnergyGate.passes(u, fs))
    }

    @Test
    fun aLoudPreRollOrTailDoesNotLiftQuietSpeech() {
        // A door slam in the pre-roll and the tail, room noise (-60 dBFS) as the "speech": fails.
        val u = utterance(pre = 16, preAmp = 20000, span = 30, spanAmp = amplitudeFor(-60.0).roundToInt(), tail = 16, tailAmp = 20000)
        assertTrue(EnergyGate.passes(u.pcm))
        assertFalse(EnergyGate.passes(u, fs))
    }

    @Test
    fun digitalSilenceAndAnEmptyUtteranceFail() {
        assertFalse(EnergyGate.passes(utterance(16, 0, 40, 0, 16, 0), fs))
        val empty = Utterance(ShortArray(0), UtteranceStats(0, 0, 0, 0, 32.0), EndReason.Forced)
        assertEquals(0, EnergyGate.speechPcm(empty, fs).size)
        assertFalse(EnergyGate.passes(empty, fs))
    }

    @Test
    fun aShortBufferIsClampedToWhatIsThere() {
        // Frame counts reaching past the PCM (test fixtures): the span is cut at the end of the buffer.
        val u = Utterance(square(8000, 1000), UtteranceStats(1, 60, 50, 0, 32.0), EndReason.Silence)
        assertEquals(1000 - fs, EnergyGate.speechPcm(u, fs).size)
        assertTrue(EnergyGate.passes(u, fs))
        val past = Utterance(square(8000, 100), UtteranceStats(1, 60, 50, 0, 32.0), EndReason.Silence)
        assertEquals(0, EnergyGate.speechPcm(past, fs).size)
    }

    @Test
    fun aClippedBurstWithoutVoiceStillStoppedByG2() {
        // G1 is only a floor: a clipped bang passes it; G2 (unchanged) stops it on too little voice.
        val bang = utterance(pre = 16, preAmp = 10, span = 30, spanAmp = 32767, tail = 16, tailAmp = 10, voiced = 3)
        assertTrue(EnergyGate.passes(bang, fs))
        assertFalse(VoicedGate.passes(bang.stats, config))
    }

    @Test
    fun veryShortBlocks() {
        assertTrue(EnergyGate.passes(shortArrayOf(20000)))
        assertFalse(EnergyGate.passes(shortArrayOf(100)))
        assertEquals(EnergyGate.measure(shortArrayOf(-20000)), EnergyGate.measure(shortArrayOf(20000)))
    }

    @Test
    fun propertyLouderNeverFailsWhereQuieterPasses() {
        var lastPassed = false
        for (amp in 1..32767 step 37) {
            val passed = EnergyGate.passes(sine(amp.toDouble(), 1600))
            if (lastPassed) assertTrue("amplitude $amp", passed)
            lastPassed = passed
        }
        assertTrue(lastPassed)
    }
}
