package dev.nittala.voicegateway.playback

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.speech.Engine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/** What the phone's TTS reports for one utterance. */
sealed interface TtsSignal {
    data object Started : TtsSignal

    data object Done : TtsSignal

    data class Error(val reason: String) : TtsSignal
}

/**
 * The system side of the phone's voice, so [PhoneTtsSpeaker]'s rules run on the JVM. [speak] queues [text] (flushing
 * anything queued) and reports through [onSignal], from any thread; [stop] silences it at once.
 */
interface TtsVoice {
    fun speak(text: String, utteranceId: String, onSignal: (TtsSignal) -> Unit)
    fun stop()
}

/**
 * Spec (A9): the last-resort [EngineSpeaker] ([Engine.Phone]). Speaks the event's own text only (never the
 * worktree name, whatever [withName] says; nothing else is added). Started, Done and Error map to the
 * [ClipEvent]s; an Error after the start is a part-way failure. Cancelling the collection before Done stops the
 * voice. Asked for another engine: Failed at once.
 */
class PhoneTtsSpeaker(private val voice: TtsVoice) : EngineSpeaker {
    override fun play(event: Event, withName: Boolean, engine: Engine): Flow<ClipEvent> {
        if (engine != Engine.Phone) return flowOf(ClipEvent.Failed(NOT_PHONE))
        return callbackFlow {
            var over = false
            voice.speak(event.text, event.id) { signal ->
                when (signal) {
                    TtsSignal.Started -> trySend(ClipEvent.Started)
                    TtsSignal.Done -> {
                        over = true
                        trySend(ClipEvent.Finished)
                        close()
                    }
                    is TtsSignal.Error -> {
                        over = true
                        trySend(ClipEvent.Failed(signal.reason))
                        close()
                    }
                }
            }
            awaitClose { if (!over) voice.stop() }
        }
    }

    companion object {
        const val NOT_PHONE = "not the phone's voice"
    }
}

/**
 * [TtsVoice] on Android's [TextToSpeech]: `USAGE_ASSISTANT` and `CONTENT_TYPE_SPEECH` (focus is the caller's,
 * through the playback [dev.nittala.voicegateway.audio.AudioFocusGate]). The engine starts on first use; a speak
 * before it is ready waits for it, and an engine that cannot start reports an Error. Only the latest utterance
 * reports. [shutdown] frees the engine (service end).
 */
class AndroidTtsVoice(private val context: Context) : TtsVoice {
    private var tts: TextToSpeech? = null
    private var ready: Boolean? = null
    private var pending: Triple<String, String, (TtsSignal) -> Unit>? = null
    private var current: Pair<String, (TtsSignal) -> Unit>? = null

    @Synchronized
    override fun speak(text: String, utteranceId: String, onSignal: (TtsSignal) -> Unit) {
        when (ready) {
            true -> start(text, utteranceId, onSignal)
            false -> onSignal(TtsSignal.Error(UNAVAILABLE))
            null -> {
                pending = Triple(text, utteranceId, onSignal)
                if (tts == null) tts = TextToSpeech(context, ::onInit)
            }
        }
    }

    @Synchronized
    override fun stop() {
        pending = null
        current = null
        tts?.stop()
    }

    @Synchronized
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = null
    }

    @Synchronized
    private fun onInit(status: Int) {
        val engine = tts ?: return
        ready = status == TextToSpeech.SUCCESS
        if (ready == true) {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine.setOnUtteranceProgressListener(Progress())
        }
        val waiting = pending ?: return
        pending = null
        if (ready == true) start(waiting.first, waiting.second, waiting.third) else waiting.third(TtsSignal.Error(UNAVAILABLE))
    }

    private fun start(text: String, utteranceId: String, onSignal: (TtsSignal) -> Unit) {
        current = utteranceId to onSignal
        val queued = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            current = null
            onSignal(TtsSignal.Error(UNAVAILABLE))
        }
    }

    @Synchronized
    private fun report(utteranceId: String?, signal: TtsSignal) {
        val (id, onSignal) = current ?: return
        if (id != utteranceId) return
        if (signal != TtsSignal.Started) current = null
        onSignal(signal)
    }

    private inner class Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = report(utteranceId, TtsSignal.Started)
        override fun onDone(utteranceId: String?) = report(utteranceId, TtsSignal.Done)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = report(utteranceId, TtsSignal.Error(STOPPED))

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = report(utteranceId, TtsSignal.Error(FAILED))
        override fun onError(utteranceId: String?, errorCode: Int) = report(utteranceId, TtsSignal.Error("$FAILED $errorCode"))
    }

    private companion object {
        const val UNAVAILABLE = "phone voice unavailable"
        const val FAILED = "phone voice failed"
        const val STOPPED = "phone voice stopped"
    }
}
