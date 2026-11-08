package dev.nittala.voicegateway.audio

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper

/**
 * Spec (A8): tells whether Android is silencing this app's recording (another app holds the mic, a call, the
 * privacy toggle), so the loop can say so instead of listening to silence.
 *
 * - [start] begins watching the recording with audio session [sessionId]; calling it again replaces the id.
 * - [isSilenced] is safe from any thread; false when not watching.
 * - [stop] stops watching and forgets the state; idempotent.
 */
interface MicSilenceMonitor {
    fun start(sessionId: Int)
    fun isSilenced(): Boolean
    fun stop()
}

/** One active recording as the system reports it, reduced to what matters here. */
data class RecordingInfo(val sessionId: Int, val silenced: Boolean)

/** Pure: whether the recording [sessionId] is among [recordings] and marked silenced. */
fun clientSilenced(recordings: List<RecordingInfo>, sessionId: Int): Boolean =
    recordings.any { it.sessionId == sessionId && it.silenced }

/**
 * [MicSilenceMonitor] on `AudioManager.AudioRecordingCallback` and
 * [AudioRecordingConfiguration.isClientSilenced] (API 29+, so always present at minSdk 31). The current
 * configurations are read at [start] as well, since the callback fires only on changes. Callbacks run on the main
 * thread; [start] and [stop] may be called from any thread.
 */
class RecordingCallbackSilenceMonitor(private val audioManager: AudioManager) : MicSilenceMonitor {
    @Volatile private var sessionId: Int = NO_SESSION
    @Volatile private var silenced: Boolean = false
    private var registered = false

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = update(configs)
    }

    @Synchronized
    override fun start(sessionId: Int) {
        this.sessionId = sessionId
        silenced = false
        if (!registered) {
            audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            registered = true
        }
        update(audioManager.activeRecordingConfigurations)
    }

    override fun isSilenced(): Boolean = silenced

    @Synchronized
    override fun stop() {
        if (registered) {
            audioManager.unregisterAudioRecordingCallback(callback)
            registered = false
        }
        sessionId = NO_SESSION
        silenced = false
    }

    @Synchronized
    private fun update(configs: List<AudioRecordingConfiguration>) {
        val id = sessionId
        if (id == NO_SESSION) return
        silenced = clientSilenced(configs.map { RecordingInfo(it.clientAudioSessionId, it.isClientSilenced) }, id)
    }

    private companion object {
        const val NO_SESSION = Int.MIN_VALUE
    }
}
