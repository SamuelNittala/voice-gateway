package dev.nittala.voicegateway.core.speech

import kotlin.math.roundToLong

/**
 * End-of-speech settings (plan section 2). Times are turned into whole frames: a minimum or a wait rounds **up**
 * (at least the stated time), the hard cap rounds **down** (never more than the stated time). With the defaults a
 * frame is 32 ms, so: start 3 frames, end 38 frames (1216 ms), cap 875 frames (28 000 ms), no-speech 1407 frames
 * (45 024 ms), pre-roll 16 frames (512 ms), tail 16 frames, voiced minimum 25 frames (800 ms).
 *
 * @property startProbability a frame at or above this is voiced (plan: "probability >= 0.6"; G2 counts the same).
 * @property startFrames consecutive voiced frames that start speech (plan: 3, about 96 ms).
 * @property endProbability a frame below this is silence (plan: "frames below 0.35").
 * @property endSilenceMs silence that ends the utterance (plan: 1.2 s; the setting allows 0.8-2.0 s).
 * @property maxUtteranceMs hard cap of the whole utterance, pre-roll included, so the upload stays small (28 s).
 * @property noSpeechTimeoutMs Listening with no speech start for this long reports [DetectorEvent.NoSpeech]
 *   (plan: 45 s in Listening; AwaitingAnswer uses 20 s, section 7, by passing a different config).
 * @property preRollMs audio kept from before speech start so the first syllable is not cut (plan: 500 ms).
 * @property tailMs audio kept after the last non-silent frame; the rest of the end silence is dropped.
 * @property minVoicedMs G2: at least this much voiced time (plan: 0.8 s).
 * @property minVoicedShare G2: voiced frames over the speech span (plan: at least 40%).
 */
data class DetectorConfig(
    val sampleRate: Int = Wav.SAMPLE_RATE,
    val frameSamples: Int = 512,
    val startProbability: Float = 0.6f,
    val startFrames: Int = 3,
    val endProbability: Float = 0.35f,
    val endSilenceMs: Int = 1_200,
    val maxUtteranceMs: Int = 28_000,
    val noSpeechTimeoutMs: Int = 45_000,
    val preRollMs: Int = 500,
    val tailMs: Int = 500,
    val minVoicedMs: Int = 800,
    val minVoicedShare: Double = 0.4,
) {
    val frameMs: Double get() = frameSamples * 1000.0 / sampleRate
    val endSilenceFrames: Int get() = framesAtLeast(endSilenceMs)
    val maxUtteranceFrames: Int get() = (maxUtteranceMs.toLong() * sampleRate / (1000L * frameSamples)).toInt()
    val noSpeechFrames: Int get() = framesAtLeast(noSpeechTimeoutMs)
    val preRollFrames: Int get() = framesAtLeast(preRollMs)
    val tailFrames: Int get() = framesAtLeast(tailMs)
    val minVoicedFrames: Int get() = framesAtLeast(minVoicedMs)

    /** Milliseconds of audio in [frames] frames (exact for the defaults: 32 ms a frame). */
    fun millis(frames: Int): Long = frames.toLong() * frameSamples * 1000L / sampleRate

    /** Whole frames covering at least [ms], in integers so 1200 ms is 38 frames and 800 ms exactly 25. */
    private fun framesAtLeast(ms: Int): Int {
        val samples = ms.toLong() * sampleRate
        val perFrame = 1000L * frameSamples
        return ((samples + perFrame - 1) / perFrame).toInt()
    }
}

/** One capture frame: [pcm] is exactly `frameSamples` samples, [probability] the caller's VAD speech probability. */
class Frame(val pcm: ShortArray, val probability: Float)

/** Why an utterance ended. */
enum class EndReason { Silence, Cap, Forced }

/**
 * Frame counts of an ended utterance. The **speech span** runs from the first of the start frames to the last frame
 * at or above `endProbability`; pre-roll and tail are outside it. G2 measures the span only, so the kept silence
 * around it does not count against the voiced share.
 */
data class UtteranceStats(
    val preRollFrames: Int,
    val spanFrames: Int,
    val voicedFrames: Int,
    val tailFrames: Int,
    val frameMs: Double,
) {
    val voicedMs: Long get() = (voicedFrames * frameMs).roundToLong()
    val spanMs: Long get() = (spanFrames * frameMs).roundToLong()
    val totalFrames: Int get() = preRollFrames + spanFrames + tailFrames
}

/** An ended utterance: [pcm] is pre-roll + span + tail, ready for [Wav.encode] and [EnergyGate]. */
class Utterance(val pcm: ShortArray, val stats: UtteranceStats, val reason: EndReason)

/** What a frame caused, if anything. */
sealed interface DetectorEvent {
    /** Speech has started: the loop goes to Capturing. */
    data object SpeechStarted : DetectorEvent

    /** The utterance is over. */
    class Ended(val utterance: Utterance) : DetectorEvent

    /** No speech start for `noSpeechTimeoutMs` in Listening. The detector starts counting again from zero. */
    data object NoSpeech : DetectorEvent
}

/** Detector state; immutable, replaced on every frame. */
sealed interface DetectorState {
    /**
     * Waiting for speech. [history] holds the last `preRollFrames + startFrames - 1` frames (oldest first),
     * [run] the current count of consecutive voiced frames, [waited] the frames seen since listening began.
     */
    data class Listening(val history: List<Frame>, val run: Int, val waited: Int) : DetectorState

    /**
     * In an utterance. [frames] are all frames so far (pre-roll first), [preRoll] how many of them are pre-roll,
     * [lastSpeech] the index of the last frame at or above `endProbability`, [voiced] voiced frames since speech
     * start, [silence] consecutive frames below `endProbability` at the end.
     */
    data class Capturing(
        val frames: List<Frame>,
        val preRoll: Int,
        val lastSpeech: Int,
        val voiced: Int,
        val silence: Int,
    ) : DetectorState
}

/** The new state and the event the frame caused (or null). */
data class DetectorStep(val state: DetectorState, val event: DetectorEvent?)

/**
 * End of speech on the phone (plan section 2), a pure fold over frames: no threads, no clock (time is the frame
 * count). The VAD probability comes from the caller (Silero stays behind an interface in `:app`).
 *
 * Rules, per frame:
 * - Listening: a frame with probability >= `startProbability` extends the run, anything else resets it. On the
 *   `startFrames`-th voiced frame in a row: [DetectorEvent.SpeechStarted], state Capturing with the history as
 *   pre-roll (at most `preRollFrames` frames before the run). Otherwise, once `noSpeechFrames` frames have passed:
 *   [DetectorEvent.NoSpeech] and a fresh Listening.
 * - Capturing: voiced frames are counted; a frame >= `endProbability` resets the silence count. When the silence
 *   count reaches `endSilenceFrames`: ended with [EndReason.Silence]; else when the utterance (pre-roll included)
 *   reaches `maxUtteranceFrames`: ended with [EndReason.Cap]. After an end the state is a fresh Listening.
 * - [finish] (a tap in Capturing) ends the utterance at once with [EndReason.Forced]; in Listening it does nothing.
 *
 * The ended audio is the pre-roll, the span and at most `tailFrames` frames after the span.
 * A probability that is NaN counts as 0 (fail safe: not speech).
 */
class UtteranceDetector(val config: DetectorConfig = DetectorConfig()) {
    /** A fresh Listening state. */
    val initial: DetectorState get() = DetectorState.Listening(emptyList(), run = 0, waited = 0)

    /** Pure: the state after [frame]. Throws [IllegalArgumentException] if the frame has the wrong size. */
    fun next(state: DetectorState, frame: Frame): DetectorStep {
        require(frame.pcm.size == config.frameSamples) {
            "a frame must have ${config.frameSamples} samples, not ${frame.pcm.size}"
        }
        return when (state) {
            is DetectorState.Listening -> listen(state, frame)
            is DetectorState.Capturing -> capture(state, frame)
        }
    }

    /** Pure: end the utterance now (Forced), or no event in Listening. */
    fun finish(state: DetectorState): DetectorStep = when (state) {
        is DetectorState.Listening -> DetectorStep(state, null)
        is DetectorState.Capturing -> end(state, EndReason.Forced)
    }

    private fun listen(s: DetectorState.Listening, frame: Frame): DetectorStep {
        val run = if (isVoiced(frame)) s.run + 1 else 0
        if (run >= config.startFrames) {
            val frames = s.history + frame
            val capturing = DetectorState.Capturing(
                frames = frames,
                preRoll = frames.size - config.startFrames,
                lastSpeech = frames.lastIndex,
                voiced = config.startFrames,
                silence = 0,
            )
            return DetectorStep(capturing, DetectorEvent.SpeechStarted)
        }
        val waited = s.waited + 1
        if (waited >= config.noSpeechFrames) return DetectorStep(initial, DetectorEvent.NoSpeech)
        val history = (s.history + frame).takeLast(config.preRollFrames + config.startFrames - 1)
        return DetectorStep(DetectorState.Listening(history, run, waited), null)
    }

    private fun capture(s: DetectorState.Capturing, frame: Frame): DetectorStep {
        val frames = s.frames + frame
        val speech = probability(frame) >= config.endProbability
        val next = s.copy(
            frames = frames,
            lastSpeech = if (speech) frames.lastIndex else s.lastSpeech,
            voiced = if (isVoiced(frame)) s.voiced + 1 else s.voiced,
            silence = if (speech) 0 else s.silence + 1,
        )
        return when {
            next.silence >= config.endSilenceFrames -> end(next, EndReason.Silence)
            frames.size >= config.maxUtteranceFrames -> end(next, EndReason.Cap)
            else -> DetectorStep(next, null)
        }
    }

    private fun end(s: DetectorState.Capturing, reason: EndReason): DetectorStep {
        val keep = minOf(s.frames.size, s.lastSpeech + 1 + config.tailFrames)
        val kept = s.frames.subList(0, keep)
        val pcm = ShortArray(keep * config.frameSamples)
        kept.forEachIndexed { i, f -> f.pcm.copyInto(pcm, i * config.frameSamples) }
        val stats = UtteranceStats(
            preRollFrames = s.preRoll,
            spanFrames = s.lastSpeech + 1 - s.preRoll,
            voicedFrames = s.voiced,
            tailFrames = keep - (s.lastSpeech + 1),
            frameMs = config.frameMs,
        )
        return DetectorStep(initial, DetectorEvent.Ended(Utterance(pcm, stats, reason)))
    }

    private fun probability(frame: Frame): Float = if (frame.probability.isNaN()) 0f else frame.probability

    private fun isVoiced(frame: Frame): Boolean = probability(frame) >= config.startProbability
}

/**
 * G2, the voiced gate (plan section 3): at least `minVoicedFrames` voiced frames **and** voiced frames at least
 * `minVoicedShare` of the speech span. Both inclusive.
 */
object VoicedGate {
    fun passes(stats: UtteranceStats, config: DetectorConfig = DetectorConfig()): Boolean =
        stats.voicedFrames >= config.minVoicedFrames &&
            stats.spanFrames > 0 &&
            stats.voicedFrames.toDouble() / stats.spanFrames >= config.minVoicedShare
}
