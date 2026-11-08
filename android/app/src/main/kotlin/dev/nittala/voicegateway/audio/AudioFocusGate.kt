package dev.nittala.voicegateway.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** The answer to [AudioFocusGate.acquire]. */
enum class FocusResult { Granted, Denied }

/** A focus change while the gate holds focus. */
sealed interface FocusEvent {
    /**
     * Another app took focus. [permanent] is `AUDIOFOCUS_LOSS` (focus is gone for good; the gate no longer holds
     * it); otherwise `AUDIOFOCUS_LOSS_TRANSIENT` (an incoming call rings with this one). The loop pauses on either
     * and never resumes by itself (plan section 4: "it never resumes on its own after a call").
     */
    data class Lost(val permanent: Boolean) : FocusEvent

    /** `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`: a short sound elsewhere; recording is unaffected, keep listening. */
    data object Ducked : FocusEvent

    /** `AUDIOFOCUS_GAIN` after a transient loss. Informational: the loop does not resume on it. */
    data object Regained : FocusEvent
}

/**
 * Spec (A8): audio focus while the mic is open (plan section 4: "While listening the app holds
 * AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE so music pauses"). Playback focus is ExoPlayer's own (A9), not this gate's.
 *
 * - [acquire] requests focus once; while held, further calls return [FocusResult.Granted] without a new request.
 *   A refused or delayed request is [FocusResult.Denied] (the loop must not listen over a call).
 * - [release] abandons focus; idempotent; changes reported after it are dropped.
 * - [events] reports [FocusEvent]s while held (single consumer, buffered).
 */
interface AudioFocusGate {
    val events: Flow<FocusEvent>
    fun acquire(): FocusResult
    fun release()
}

/** The system side of focus, so [SystemAudioFocusGate]'s rules run on the JVM. Returns `AUDIOFOCUS_REQUEST_*`. */
interface FocusRequester {
    fun request(onChange: (Int) -> Unit): Int
    fun abandon()
}

/** Pure: the [FocusEvent] of an `AudioManager.AUDIOFOCUS_*` change, or null for a value of no interest. */
fun focusEventOf(change: Int): FocusEvent? = when (change) {
    AudioManager.AUDIOFOCUS_LOSS -> FocusEvent.Lost(permanent = true)
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusEvent.Lost(permanent = false)
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusEvent.Ducked
    AudioManager.AUDIOFOCUS_GAIN -> FocusEvent.Regained
    else -> null
}

/**
 * [AudioFocusGate] over a [FocusRequester]. On a permanent loss it abandons its request (Android's advice: the
 * focus will not come back) and counts as not holding, so the next [acquire] asks again.
 */
class SystemAudioFocusGate(private val requester: FocusRequester) : AudioFocusGate {
    private val channel = Channel<FocusEvent>(Channel.UNLIMITED)
    override val events: Flow<FocusEvent> = channel.receiveAsFlow()

    private var held = false

    /** Bumped on every request and release, so a change for an older request is dropped. */
    private var generation = 0

    @Synchronized
    override fun acquire(): FocusResult {
        if (held) return FocusResult.Granted
        val mine = ++generation
        val answer = requester.request { change -> onChange(mine, change) }
        if (answer == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            held = true
            return FocusResult.Granted
        }
        if (answer == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) requester.abandon()
        return FocusResult.Denied
    }

    @Synchronized
    override fun release() {
        generation++
        if (!held) return
        held = false
        requester.abandon()
    }

    @Synchronized
    private fun onChange(requestGeneration: Int, change: Int) {
        if (!held || requestGeneration != generation) return
        val event = focusEventOf(change) ?: return
        if (event is FocusEvent.Lost && event.permanent) {
            held = false
            generation++
            requester.abandon()
        }
        channel.trySend(event)
    }
}

/**
 * [FocusRequester] on [AudioManager]: `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` with `USAGE_ASSISTANT` and
 * `CONTENT_TYPE_SPEECH` (a voice assistant's listening turn), no delayed gain (a late grant would open the mic
 * after the user has moved on). Changes arrive on the main thread.
 */
class AudioManagerFocusRequester(private val audioManager: AudioManager) : FocusRequester {
    private var current: AudioFocusRequest? = null

    @Synchronized
    override fun request(onChange: (Int) -> Unit): Int {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
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
