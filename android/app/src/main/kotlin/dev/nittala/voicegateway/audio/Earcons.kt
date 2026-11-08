package dev.nittala.voicegateway.audio

import android.media.AudioManager
import android.media.ToneGenerator
import dev.nittala.voicegateway.core.loop.EarconKind
import dev.nittala.voicegateway.service.Earcons
import kotlinx.coroutines.delay

/** Pure: the system tone and its length for each earcon (no sound files: plan "Files to create", ToneGenerator). */
fun toneOf(kind: EarconKind): Pair<Int, Int> = when (kind) {
    EarconKind.Listen -> ToneGenerator.TONE_PROP_BEEP to 150
    EarconKind.YourTurn -> ToneGenerator.TONE_PROP_BEEP2 to 250
    EarconKind.Sent -> ToneGenerator.TONE_PROP_ACK to 150
    EarconKind.Rejected -> ToneGenerator.TONE_PROP_NACK to 250
    EarconKind.Error -> ToneGenerator.TONE_SUP_ERROR to 300
    EarconKind.Paused -> ToneGenerator.TONE_PROP_PROMPT to 200
}

/** [Earcons] on [ToneGenerator]; returns once the tone is over. A tone that cannot play is skipped silently. */
class ToneEarcons : Earcons {
    override suspend fun play(kind: EarconKind) {
        val (tone, ms) = toneOf(kind)
        val generator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
        } catch (_: RuntimeException) {
            return
        }
        try {
            generator.startTone(tone, ms)
            delay(ms + TAIL_MS)
        } finally {
            generator.release()
        }
    }

    private companion object {
        const val VOLUME = 70
        const val TAIL_MS = 50L
    }
}
