package dev.nittala.voicegateway.playback

import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.speech.EngineChain
import kotlinx.coroutines.flow.Flow

/** What happened to one event's playback, in order (plan section 4). */
sealed interface PlaybackEvent {
    /** [engine]'s audio is playing (the first sound is out). */
    data class Started(val engine: Engine) : PlaybackEvent

    /** The clip played to its end (or hit the play limit). Terminal: the loop's `PlaybackDone`. */
    data object Finished : PlaybackEvent

    /**
     * [engine] failed. Followed by [FellBack] when another engine is tried; otherwise terminal: every engine failed,
     * or a clip failed part way (it ends there, no second voice). Terminal [Failed] is the loop's `PlaybackFailed`.
     */
    data class Failed(val engine: Engine, val reason: String) : PlaybackEvent

    /** Moving on from [from] to [to]; [note] is the screen's line, "Chatterbox unavailable, used Deepgram". */
    data class FellBack(val from: Engine, val to: Engine, val note: String) : PlaybackEvent

    /** Stopped from outside (tap, [Speaker.stop], mute, audio focus lost). Terminal. The loop already knows. */
    data object Interrupted : PlaybackEvent
}

/**
 * Spec (A9): what the loop controller (A10) drives for `Effect.Play` and `Effect.StopPlayback`.
 *
 * - [play] returns a cold flow that plays [event] when collected: the chain's engines in order, each at most once,
 *   until one plays. It completes after exactly one terminal event ([PlaybackEvent.Finished], a terminal
 *   [PlaybackEvent.Failed] or [PlaybackEvent.Interrupted]). Cancelling the collection stops the audio at once. One
 *   play at a time. [withName] is the gateway's `with_name` (an event of another worktree).
 * - [interrupt] (tap) and [stop] stop the audio at once; the running [play] ends with
 *   [PlaybackEvent.Interrupted]. Neither calls the gateway (the loop acknowledges the event). No-op when idle.
 * - [muted]: while true nothing is played; setting it stops the running [play] (Interrupted) and a [play] while
 *   muted ends with Interrupted at once, asking no engine.
 */
interface Speaker {
    fun play(event: Event, withName: Boolean, engineChain: EngineChain): Flow<PlaybackEvent>
    fun interrupt()
    fun stop()
    var muted: Boolean
}

/** One engine's attempt at one clip. */
sealed interface ClipEvent {
    data object Started : ClipEvent

    data object Finished : ClipEvent

    data class Failed(val reason: String) : ClipEvent
}

/**
 * Spec (A9): ONE voice playing ONE clip ([ExoSpeaker] for the gateway's engines, [PhoneTtsSpeaker] for the phone).
 * [play] is a cold flow: [ClipEvent.Started] once audio is out, then [ClipEvent.Finished] or [ClipEvent.Failed]; or
 * [ClipEvent.Failed] alone if it never started. Cancelling the collection stops the audio and frees the player.
 * Never retries anything. No timers of its own: [ChainSpeaker] owns the limits.
 */
interface EngineSpeaker {
    fun play(event: Event, withName: Boolean, engine: Engine): Flow<ClipEvent>
}

/**
 * The limits of one event (plan section 4, as the page's START_LIMIT_MS and PLAY_LIMIT_MS): [startMs] per engine
 * from asking to the first sound (it also bounds the first byte: the gateway gives up at 10 s and answers 502);
 * [playMs] from the first sound to the end, so the next event is never held up longer.
 */
data class PlaybackLimits(val startMs: Long = 15_000, val playMs: Long = 60_000)
