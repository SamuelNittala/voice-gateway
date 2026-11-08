package dev.nittala.voicegateway.playback

import dev.nittala.voicegateway.service.PhoneVoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [PhoneVoice] for the loop's own lines (`Say`) on the phone's TTS: returns when the line is done or failed, or after
 * [limitMs] at most (a stuck engine never holds the loop). Cancelling stops the voice. Never logs the text.
 */
class TtsPhoneVoice(private val voice: TtsVoice, private val limitMs: Long = 15_000) : PhoneVoice {
    private var count = 0

    override suspend fun say(text: String) {
        val over = CompletableDeferred<Unit>()
        val id = "say-${++count}"
        try {
            voice.speak(text, id) { signal -> if (signal != TtsSignal.Started) over.complete(Unit) }
            if (withTimeoutOrNull(limitMs) { over.await() } == null) voice.stop()
        } catch (e: Exception) {
            voice.stop()
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }
}
