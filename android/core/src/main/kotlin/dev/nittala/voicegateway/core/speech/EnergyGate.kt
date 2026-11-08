package dev.nittala.voicegateway.core.speech

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Loudness of a block of 16-bit PCM in dBFS (decibels below full scale, full scale = 32768).
 * Silence and an empty block are [Double.NEGATIVE_INFINITY].
 */
data class Energy(val rmsDbfs: Double, val peakDbfs: Double)

/**
 * G1, the energy gate (plan section 3): an utterance is uploaded only if the RMS of its **speech span** is above
 * [MIN_RMS_DBFS] **and** its peak is above [MIN_PEAK_DBFS]. The span is the utterance without the pre-roll and the
 * kept tail ([speechPcm]), so the silence kept around the speech does not dilute the RMS. Both strictly above: a
 * value equal to the threshold fails. It is a floor only; loud or clipped audio passes (Whisper copes with clipping;
 * quiet noise is the danger, and G2 stops a loud noise without voice).
 *
 * The thresholds were measured on 2026-11-08 on a Nothing Phone (1), Android 15, with the `VOICE_RECOGNITION` source
 * (no automatic gain, so levels are low): normal speech at normal distance came out at RMS -48 / peak -29.5 dBFS
 * (loudest frame -38), the room's loudest 32 ms frame at -58.6 dBFS. The floors sit between the two.
 */
object EnergyGate {
    /** Plan section 3, G1: "RMS above -55 dBFS" (speech span only; measured, see the class doc). */
    const val MIN_RMS_DBFS = -55.0

    /** Plan section 3, G1: "peak above -40 dBFS" (speech span only; measured, see the class doc). */
    const val MIN_PEAK_DBFS = -40.0

    /** Pure: RMS and peak of [pcm] in dBFS. Peak uses |sample| (so -32768 is 0 dBFS). */
    fun measure(pcm: ShortArray): Energy {
        if (pcm.isEmpty()) return Energy(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        val sumSquares = pcm.fold(0.0) { acc, s -> acc + s.toDouble() * s.toDouble() }
        val peak = pcm.maxOf { abs(it.toInt()) }
        return Energy(dbfs(sqrt(sumSquares / pcm.size)), dbfs(peak.toDouble()))
    }

    /**
     * Pure: the speech span of [utterance], its PCM without the `preRollFrames` before and the `tailFrames` after,
     * with [frameSamples] samples per frame. Frame counts reaching past the PCM are cut at its end.
     */
    fun speechPcm(utterance: Utterance, frameSamples: Int): ShortArray {
        val size = utterance.pcm.size
        val from = (utterance.stats.preRollFrames.toLong() * frameSamples).coerceIn(0L, size.toLong()).toInt()
        val to = ((utterance.stats.preRollFrames.toLong() + utterance.stats.spanFrames) * frameSamples)
            .coerceIn(from.toLong(), size.toLong()).toInt()
        return utterance.pcm.copyOfRange(from, to)
    }

    /** Pure: G1's numbers for [utterance], [measure] of its [speechPcm]. */
    fun measureSpeech(utterance: Utterance, frameSamples: Int): Energy = measure(speechPcm(utterance, frameSamples))

    /** Pure: whether [utterance] passes G1, judged on its speech span. An empty span fails. */
    fun passes(utterance: Utterance, frameSamples: Int): Boolean = passes(measureSpeech(utterance, frameSamples))

    /** Pure: whether [pcm] passes G1. An empty block fails. */
    fun passes(pcm: ShortArray): Boolean = passes(measure(pcm))

    /** Pure: whether a measured [energy] passes G1. */
    fun passes(energy: Energy): Boolean = energy.rmsDbfs > MIN_RMS_DBFS && energy.peakDbfs > MIN_PEAK_DBFS

    private const val FULL_SCALE = 32768.0

    /** log10(0) is -Infinity, so silence comes out as -Infinity dBFS. */
    private fun dbfs(amplitude: Double): Double = 20.0 * log10(amplitude / FULL_SCALE)
}
