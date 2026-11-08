package dev.nittala.voicegateway.audio

import dev.nittala.voicegateway.core.speech.DetectorConfig
import dev.nittala.voicegateway.core.speech.DetectorEvent
import dev.nittala.voicegateway.core.speech.DetectorState
import dev.nittala.voicegateway.core.speech.EnergyGate
import dev.nittala.voicegateway.core.speech.Frame
import dev.nittala.voicegateway.core.speech.Utterance
import dev.nittala.voicegateway.core.speech.UtteranceDetector
import dev.nittala.voicegateway.core.speech.VoicedGate
import dev.nittala.voicegateway.core.speech.Wav
import kotlinx.coroutines.flow.Flow

/** Why a finished utterance was not turned into an upload. Holds no audio, so it is safe to log. */
enum class CaptureRejection {
    /** G1 ([EnergyGate]): RMS or peak of the utterance's speech span at or below the floor. */
    TooQuiet,

    /** G2 ([VoicedGate]): too little voiced time, or too small a voiced share of the speech span. */
    TooLittleVoice,
}

/** Why a capture session could not run. */
enum class CaptureFailure {
    /** RECORD_AUDIO is not granted (or was revoked). */
    NoPermission,

    /** The microphone or the voice model could not be opened or started (busy, missing, broken). */
    Unavailable,

    /** A read returned an error or a short frame (the device went away mid-capture). */
    ReadError,
}

/**
 * What a capture session reports, in order. Every event except [SpeechStarted] ends the session: the microphone is
 * released and nothing more is emitted until the next [AudioCapture.start] (one start = at most one utterance).
 */
sealed interface CaptureEvent {
    /** True when the session is over after this event. */
    val endsSession: Boolean get() = true

    /** Speech began (the loop goes to Capturing). [level] is the mic level 0..1 of the starting frame ([levelOf]). */
    data class SpeechStarted(val level: Float) : CaptureEvent {
        override val endsSession: Boolean get() = false
    }

    /**
     * An utterance passed G1 and G2. [wav] is [Wav.encode] of pre-roll + speech + at most `tailMs` of tail;
     * [speechMs] is the speech span ([dev.nittala.voicegateway.core.speech.UtteranceStats.spanMs]); [voicedMs] the
     * voiced time that `TranscriptGuard.judge` needs for its word-rate check. [utterance] is the utterance itself
     * (the loop's `SpeechEnd` input re-checks G1/G2 on it and uploads `Wav.encode` of its PCM).
     */
    class UtteranceReady(
        val wav: ByteArray,
        val speechMs: Long,
        val voicedMs: Long,
        val utterance: Utterance,
        val stats: CaptureStats? = null,
    ) : CaptureEvent

    /**
     * An utterance ended but failed G1 or G2: nothing is uploaded. [stats] are diagnostics only (DEBUG line), so they
     * take no part in equality.
     */
    class Rejected(val reason: CaptureRejection, val stats: CaptureStats? = null) : CaptureEvent {
        override fun equals(other: Any?): Boolean = other is Rejected && other.reason == reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "Rejected(reason=$reason)"
    }

    /**
     * Diagnostics while still waiting for speech, every `progressEveryFrames` frames (only when [CaptureFold] is
     * asked to, which the app does in DEBUG builds only). Never ends the session; the loop ignores it.
     */
    data class ListenProgress(val stats: CaptureStats) : CaptureEvent {
        override val endsSession: Boolean get() = false
    }

    /** No speech start for `noSpeechTimeoutMs` (the loop pauses, plan section 2). */
    data object NoSpeechPause : CaptureEvent

    /**
     * Android is feeding silence instead of the mic: the system reports this client silenced
     * ([MicSilenceMonitor]), or [CaptureFold.SILENCED_AFTER_FRAMES] frames in a row were exactly zero. The loop
     * says so instead of listening to nothing.
     */
    data object MicSilenced : CaptureEvent

    /** The session could not start or broke; see [reason]. */
    data class Failed(val reason: CaptureFailure) : CaptureEvent
}

/**
 * Spec (A8): the microphone side of the voice loop (plan section 2), 16 kHz mono PCM16 in 32 ms frames.
 *
 * - [start] opens the mic and begins a session with [DetectorConfig] (45 s no-speech in Listening, 20 s in
 *   AwaitingAnswer). Returns false and does nothing if a session is already running (idempotent).
 * - [finishUtterance] ends a capture in progress now (a tap while Capturing); in Listening it does nothing.
 * - [stop] ends the session and releases the mic; harmless when nothing runs (idempotent). Once [stop] returns, no
 *   further event of that session is delivered through [events].
 * - [events] is a single-consumer stream of [CaptureEvent]s; events are buffered, never dropped, until read.
 * - Mic reads never happen on the caller's thread, and every resource is released on every path.
 */
interface AudioCapture {
    val events: Flow<CaptureEvent>
    fun start(config: DetectorConfig = DetectorConfig()): Boolean
    fun finishUtterance()
    fun stop()
}

/**
 * Numbers about one capture session, for the DEBUG diagnostic line. No audio and no text, so safe to log.
 *
 * @property framesFed frames given to the detector this session.
 * @property maxProbability highest voice probability of those frames (NaN counts as 0).
 * @property loudestFrameDbfs highest RMS of a single 32 ms frame this session, dBFS.
 * @property rmsDbfs RMS of the ended utterance's speech span (no pre-roll, no tail), the number G1 judges; NaN while
 *   listening.
 * @property peakDbfs peak of the speech span, G1's other number; NaN while listening.
 * @property speechMs the speech span; [voicedMs] its voiced time; [voicedShare] voiced over span (G2's numbers).
 */
data class CaptureStats(
    val framesFed: Int,
    val maxProbability: Float,
    val loudestFrameDbfs: Double,
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val speechMs: Long,
    val voicedMs: Long,
    val voicedShare: Double,
)

/**
 * Pure fold state: the detector's state, the count of consecutive all-zero frames, and the session diagnostics
 * ([framesFed], [maxProbability], [loudestFrameDbfs]; see [CaptureStats]).
 */
data class CaptureState(
    val detector: DetectorState,
    val zeroRun: Int,
    val framesFed: Int = 0,
    val maxProbability: Float = 0f,
    val loudestFrameDbfs: Double = Double.NEGATIVE_INFINITY,
)

/** The state after one frame and what it caused, if anything. */
data class CaptureStep(val state: CaptureState, val event: CaptureEvent?)

/**
 * Spec (A8), pure: one capture session as a fold over frames, with no threads and no I/O.
 *
 * Per frame, in this order:
 * 1. `clientSilenced` (the system says this recording is silenced): [CaptureEvent.MicSilenced].
 * 2. A frame whose samples are all exactly zero extends the zero run, any other frame resets it; at
 *    [silencedAfterFrames] in a row: [CaptureEvent.MicSilenced] (a live mic is never exactly zero for ~1 s).
 * 3. The frame goes to [UtteranceDetector] with its probability:
 *    - SpeechStarted -> [CaptureEvent.SpeechStarted] with [levelOf] the frame;
 *    - Ended -> [judge] (G1, G2, WAV);
 *    - NoSpeech -> [CaptureEvent.NoSpeechPause].
 *
 * [finish] is the tap: it ends a capture in progress and judges it like any other end.
 *
 * Every result carries [CaptureStats]. With [progressEveryFrames] > 0, a frame that leaves the detector listening
 * with no other event emits [CaptureEvent.ListenProgress] every [progressEveryFrames] frames (0: never).
 */
class CaptureFold(
    val config: DetectorConfig = DetectorConfig(),
    val silencedAfterFrames: Int = SILENCED_AFTER_FRAMES,
    val progressEveryFrames: Int = 0,
) {
    private val detector = UtteranceDetector(config)

    val initial: CaptureState get() = CaptureState(detector.initial, zeroRun = 0)

    fun next(state: CaptureState, pcm: ShortArray, probability: Float, clientSilenced: Boolean): CaptureStep {
        if (clientSilenced) return CaptureStep(state, CaptureEvent.MicSilenced)
        val zeroRun = if (pcm.all { it == ZERO }) state.zeroRun + 1 else 0
        if (zeroRun >= silencedAfterFrames) return CaptureStep(state.copy(zeroRun = zeroRun), CaptureEvent.MicSilenced)
        val step = detector.next(state.detector, Frame(pcm, probability))
        val p = if (probability.isNaN()) 0f else probability
        val next = CaptureState(
            detector = step.state,
            zeroRun = zeroRun,
            framesFed = state.framesFed + 1,
            maxProbability = maxOf(state.maxProbability, p),
            loudestFrameDbfs = maxOf(state.loudestFrameDbfs, EnergyGate.measure(pcm).rmsDbfs),
        )
        val event = when (val e = step.event) {
            null -> progress(next)
            DetectorEvent.SpeechStarted -> CaptureEvent.SpeechStarted(levelOf(pcm))
            is DetectorEvent.Ended -> judge(e.utterance, config, next)
            DetectorEvent.NoSpeech -> CaptureEvent.NoSpeechPause
        }
        return CaptureStep(next, event)
    }

    fun finish(state: CaptureState): CaptureStep {
        val step = detector.finish(state.detector)
        val event = (step.event as? DetectorEvent.Ended)?.let { judge(it.utterance, config, state) }
        return CaptureStep(state.copy(detector = step.state), event)
    }

    private fun progress(state: CaptureState): CaptureEvent? {
        if (progressEveryFrames <= 0 || state.detector !is DetectorState.Listening) return null
        if (state.framesFed % progressEveryFrames != 0) return null
        return CaptureEvent.ListenProgress(captureStats(state, null))
    }

    companion object {
        /** About one second of 32 ms frames. */
        const val SILENCED_AFTER_FRAMES = 32

        private const val ZERO: Short = 0
    }
}

/**
 * Pure: G1 ([EnergyGate.passes] on the utterance's speech span) first, then G2 ([VoicedGate.passes]); an utterance passing
 * both becomes [CaptureEvent.UtteranceReady] with [Wav.encode] of its PCM. With a [session] state the result carries
 * its [CaptureStats].
 */
fun judge(utterance: Utterance, config: DetectorConfig, session: CaptureState? = null): CaptureEvent {
    val energy = EnergyGate.measureSpeech(utterance, config.frameSamples)
    val stats = session?.let { captureStats(it, utterance, config.frameSamples) }
    return when {
        !EnergyGate.passes(energy) -> CaptureEvent.Rejected(CaptureRejection.TooQuiet, stats)
        !VoicedGate.passes(utterance.stats, config) -> CaptureEvent.Rejected(CaptureRejection.TooLittleVoice, stats)
        else -> CaptureEvent.UtteranceReady(
            Wav.encode(utterance.pcm), utterance.stats.spanMs, utterance.stats.voicedMs, utterance, stats,
        )
    }
}

/**
 * Pure: the [CaptureStats] of a session [state] and, once it ended, its [utterance] (null while listening); RMS and
 * peak are G1's, measured on the speech span with [frameSamples] samples per frame.
 */
fun captureStats(
    state: CaptureState,
    utterance: Utterance?,
    frameSamples: Int = DetectorConfig().frameSamples,
): CaptureStats {
    val energy = utterance?.let { EnergyGate.measureSpeech(it, frameSamples) }
    val span = utterance?.stats?.spanFrames ?: 0
    return CaptureStats(
        framesFed = state.framesFed,
        maxProbability = state.maxProbability,
        loudestFrameDbfs = state.loudestFrameDbfs,
        rmsDbfs = energy?.rmsDbfs ?: Double.NaN,
        peakDbfs = energy?.peakDbfs ?: Double.NaN,
        speechMs = utterance?.stats?.spanMs ?: 0,
        voicedMs = utterance?.stats?.voicedMs ?: 0,
        voicedShare = if (span > 0) utterance!!.stats.voicedFrames.toDouble() / span else 0.0,
    )
}

/** Pure: mic level for the orb, RMS mapped linearly from -60 dBFS (0) to 0 dBFS (1), clamped; silence is 0. */
fun levelOf(pcm: ShortArray): Float {
    val rms = EnergyGate.measure(pcm).rmsDbfs
    if (rms.isInfinite() || rms.isNaN()) return 0f
    return ((rms - LEVEL_FLOOR_DBFS) / -LEVEL_FLOOR_DBFS).coerceIn(0.0, 1.0).toFloat()
}

private const val LEVEL_FLOOR_DBFS = -60.0
