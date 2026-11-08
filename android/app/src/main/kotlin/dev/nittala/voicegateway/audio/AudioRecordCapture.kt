package dev.nittala.voicegateway.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.nittala.voicegateway.core.speech.DetectorConfig
import dev.nittala.voicegateway.core.speech.Wav
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spec (A8): the raw microphone, so [AudioRecordCapture]'s session logic runs on the JVM with a fake.
 *
 * - Opening (the factory) throws [SecurityException] without RECORD_AUDIO and [IllegalStateException] when the
 *   mic cannot be set up.
 * - [start] begins recording (throws [IllegalStateException] if it does not).
 * - [read] blocks until [into] is full and returns its size, or returns a smaller or negative number on error or
 *   once [stop] was called.
 * - [stop] may be called from another thread and makes a blocked [read] return; idempotent.
 * - [release] frees the mic; call once, after the last [read] returned.
 */
interface FrameSource {
    val sessionId: Int
    fun start()
    fun read(into: ShortArray): Int
    fun stop()
    fun release()
}

/**
 * [AudioCapture] on a [FrameSource] (AudioRecord in the app), a [VoiceProbability] (Silero) and a
 * [MicSilenceMonitor], with the per-frame rules in [CaptureFold].
 *
 * A session runs on [dispatcher] (never the caller's thread): open the source, open the voice model, start, watch
 * for silencing, then read and fold frames until an event ends the session, a read fails, or [stop]. The monitor,
 * the source and the model are released on every path. A new session waits for the previous one to finish
 * releasing, so two recordings never overlap. A new source and model are opened per session, so nothing is held
 * between listening turns.
 */
class AudioRecordCapture(
    private val openSource: () -> FrameSource,
    private val openVoiceProbability: () -> VoiceProbability,
    private val monitor: MicSilenceMonitor,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val silencedAfterFrames: Int = CaptureFold.SILENCED_AFTER_FRAMES,
    /** [CaptureFold.progressEveryFrames]: 0 (no progress events) unless a DEBUG build asks for them. */
    private val progressEveryFrames: Int = 0,
) : AudioCapture {
    private val channel = Channel<CaptureEvent>(Channel.UNLIMITED)
    override val events: Flow<CaptureEvent> = channel.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private var current: Session? = null

    /** One session's flags, shared between the caller's thread and the reading thread. */
    private class Session(val config: DetectorConfig) {
        @Volatile var stopped = false
        @Volatile var ended = false
        @Volatile var source: FrameSource? = null
        val finishRequested = AtomicBoolean(false)
        var job: Job? = null
    }

    override fun start(config: DetectorConfig): Boolean = synchronized(lock) {
        val previous = current
        if (previous != null && !previous.stopped && !previous.ended) return false
        val session = Session(config)
        val previousJob = previous?.job
        session.job = scope.launch {
            previousJob?.join()
            run(session)
        }
        current = session
        true
    }

    override fun finishUtterance() {
        synchronized(lock) { current }?.finishRequested?.set(true)
    }

    override fun stop() {
        synchronized(lock) {
            val session = current ?: return
            if (session.stopped) return
            session.stopped = true
            while (channel.tryReceive().isSuccess) {
                // Drop what the stopped session left unread: none of it may reach the loop now.
            }
            session.source
        }?.stop() // Outside the lock: makes a blocked read return, then the session releases on its own thread.
    }

    /**
     * Sends [event] unless the session was stopped; true when the session must end. A session counts as ended as
     * soon as its last event is sent, so the loop can [start] again at once (the new session waits for the release).
     */
    private fun emit(session: Session, event: CaptureEvent): Boolean = synchronized(lock) {
        if (session.stopped) return true
        if (event.endsSession) session.ended = true
        channel.trySend(event)
        event.endsSession
    }

    private fun run(session: Session) {
        var source: FrameSource? = null
        var voice: VoiceProbability? = null
        var watching = false
        try {
            source = openSource()
            session.source = source
            if (session.stopped) return
            voice = openVoiceProbability()
            source.start()
            monitor.start(source.sessionId)
            watching = true
            readFrames(session, source, voice)
        } catch (_: SecurityException) {
            emit(session, CaptureEvent.Failed(CaptureFailure.NoPermission))
        } catch (_: Exception) {
            emit(session, CaptureEvent.Failed(CaptureFailure.Unavailable))
        } finally {
            if (watching) monitor.stop()
            source?.let {
                it.stop()
                it.release()
            }
            voice?.close()
        }
    }

    private fun readFrames(session: Session, source: FrameSource, voice: VoiceProbability) {
        val fold = CaptureFold(session.config, silencedAfterFrames, progressEveryFrames)
        var state = fold.initial
        val buffer = ShortArray(session.config.frameSamples)
        while (!session.stopped) {
            val n = source.read(buffer)
            if (session.stopped) return
            if (n != buffer.size) {
                emit(session, CaptureEvent.Failed(CaptureFailure.ReadError))
                return
            }
            val step = if (session.finishRequested.getAndSet(false)) {
                fold.finish(state)
            } else {
                val pcm = buffer.copyOf()
                fold.next(state, pcm, voice.probability(pcm), monitor.isSilenced())
            }
            state = step.state
            val event = step.event ?: continue
            if (emit(session, event)) return
        }
    }
}

/**
 * [FrameSource] on [AudioRecord]: `VOICE_RECOGNITION`, 16 kHz, mono, PCM16 (plan section 2), blocking reads.
 * The buffer holds at least 8 frames so a short stall in the reading thread loses nothing.
 */
class AudioRecordFrameSource private constructor(private val record: AudioRecord) : FrameSource {
    override val sessionId: Int get() = record.audioSessionId

    override fun start() {
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "the microphone did not start" }
    }

    override fun read(into: ShortArray): Int {
        var filled = 0
        while (filled < into.size) {
            val n = record.read(into, filled, into.size - filled, AudioRecord.READ_BLOCKING)
            if (n <= 0) return if (n < 0) n else filled
            filled += n
        }
        return filled
    }

    override fun stop() {
        try {
            record.stop()
        } catch (_: IllegalStateException) {
            // Not recording (never started, or already stopped): nothing to stop.
        }
    }

    override fun release() = record.release()

    companion object {
        /** Opens the mic for [frameSamples]-sample frames. See [FrameSource] for what it throws. */
        @SuppressLint("MissingPermission") // checked just below, and AudioRecord throws SecurityException anyway
        fun open(context: Context, frameSamples: Int = DetectorConfig().frameSamples): AudioRecordFrameSource {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("RECORD_AUDIO is not granted")
            }
            val minBytes = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, CHANNEL, ENCODING)
            check(minBytes > 0) { "16 kHz mono PCM16 capture is not supported" }
            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(Wav.SAMPLE_RATE)
                            .setChannelMask(CHANNEL)
                            .setEncoding(ENCODING)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBytes, frameSamples * 2 * 8))
                    .build()
            } catch (e: UnsupportedOperationException) {
                throw IllegalStateException("the microphone cannot be set up", e)
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("the microphone did not initialise")
            }
            return AudioRecordFrameSource(record)
        }

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
