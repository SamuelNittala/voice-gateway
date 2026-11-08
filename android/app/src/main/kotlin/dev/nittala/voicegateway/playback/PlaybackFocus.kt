package dev.nittala.voicegateway.playback

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dev.nittala.voicegateway.audio.FocusRequester

/**
 * Spec (A9): the playback side of focus, for a [dev.nittala.voicegateway.audio.SystemAudioFocusGate] that
 * [ChainSpeaker] holds while an event plays (plan section 4: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, so music only
 * ducks under the voice). `USAGE_ASSISTANT`, `CONTENT_TYPE_SPEECH`, no delayed gain (a late grant would speak after
 * the moment has passed). Changes arrive on the main thread.
 */
class PlaybackFocusRequester(private val audioManager: AudioManager) : FocusRequester {
    private var current: AudioFocusRequest? = null

    @Synchronized
    override fun request(onChange: (Int) -> Unit): Int {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { onChange(it) }
            .build()
        current = request
        return audioManager.requestAudioFocus(request)
    }

    @Synchronized
    override fun abandon() {
        current?.let { audioManager.abandonAudioFocusRequest(it) }
        current = null
    }
}
