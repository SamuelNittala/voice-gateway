package dev.nittala.voicegateway.playback

import dev.nittala.voicegateway.audio.AudioFocusGate
import dev.nittala.voicegateway.audio.FocusEvent
import dev.nittala.voicegateway.audio.FocusResult
import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.speech.EngineChain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spec (A9, plan section 4; mirrors the page's `playAudio`/`startAudio`): the [Speaker] that walks an [EngineChain]
 * (Chatterbox, then Deepgram, then the phone's voice) over one [EngineSpeaker] per engine in [voices].
 *
 * - Audio focus: [focus] is acquired before the first engine and released when the play ends, however it ends. A
 *   refused focus (a call) asks no engine and ends with a terminal Failed of the chain's first engine. A focus loss
 *   while playing stops the audio: Interrupted.
 * - Each engine: [PlaybackLimits.startMs] to start; failing before the first sound (refused, 502, 404, load error,
 *   no player in [voices], or too slow) gives `Failed(engine)`, then `FellBack(engine, next, note)` and the next
 *   engine, via [EngineChain.failed], so no engine is asked twice for one event. A too-slow engine's collection is
 *   cancelled, so a late start after giving up is stopped and never reported.
 * - Once started: `Started(engine)`, then `Finished` at the end, or at [PlaybackLimits.playMs] (stopped). A clip
 *   that fails part way gives a terminal `Failed(engine)`: no second voice.
 * - The chain exhausted: the last Failed is terminal (the loop gives up and acknowledges).
 */
class ChainSpeaker(
    private val voices: Map<Engine, EngineSpeaker>,
    private val focus: AudioFocusGate,
    private val limits: PlaybackLimits = PlaybackLimits(),
) : Speaker {
    /** Bumped by every stop, interrupt and mute; a play ends when it moves past the value it started with. */
    private val halts = MutableStateFlow(0L)

    @Volatile private var mutedFlag = false

    override var muted: Boolean
        get() = mutedFlag
        set(value) {
            mutedFlag = value
            if (value) halt()
        }

    override fun interrupt() = halt()

    override fun stop() = halt()

    private fun halt() = halts.update { it + 1 }

    override fun play(event: Event, withName: Boolean, engineChain: EngineChain): Flow<PlaybackEvent> = channelFlow {
        if (muted) {
            send(PlaybackEvent.Interrupted)
            return@channelFlow
        }
        val mark = halts.value
        if (focus.acquire() == FocusResult.Denied) {
            send(PlaybackEvent.Failed(engineChain.current ?: Engine.Phone, NO_FOCUS))
            return@channelFlow
        }
        try {
            val chain = launch { walk(channel, event, withName, engineChain) }
            val halted = async {
                merge(
                    halts.filter { it > mark }.map { },
                    focus.events.filterIsInstance<FocusEvent.Lost>().map { },
                ).first()
            }
            val stopped = select { chain.onJoin { false }; halted.onAwait { true } }
            if (stopped) {
                chain.cancelAndJoin()
                send(PlaybackEvent.Interrupted)
            } else {
                halted.cancel()
            }
        } finally {
            focus.release()
        }
    }

    /** Tries the chain's engines in order until one plays or none is left. */
    private suspend fun walk(out: SendChannel<PlaybackEvent>, event: Event, withName: Boolean, start: EngineChain) {
        var chain = start
        while (true) {
            val engine = chain.current ?: return
            when (val outcome = attempt(out, voices[engine], event, withName, engine)) {
                Outcome.Done -> return out.send(PlaybackEvent.Finished)
                is Outcome.Broke -> return out.send(PlaybackEvent.Failed(engine, outcome.reason))
                is Outcome.NotStarted -> {
                    out.send(PlaybackEvent.Failed(engine, outcome.reason))
                    chain = chain.failed(engine)
                    val next = chain.current ?: return
                    out.send(PlaybackEvent.FellBack(engine, next, chain.note()))
                }
            }
        }
    }

    /** One engine's try under the start and play limits; its collection is cancelled when the try ends. */
    private suspend fun attempt(
        out: SendChannel<PlaybackEvent>,
        voice: EngineSpeaker?,
        event: Event,
        withName: Boolean,
        engine: Engine,
    ): Outcome {
        voice ?: return Outcome.NotStarted(NO_PLAYER)
        return coroutineScope {
            val clip = Channel<ClipEvent>(Channel.UNLIMITED)
            val playing = launch {
                try {
                    voice.play(event, withName, engine).collect { clip.send(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    clip.send(ClipEvent.Failed(e.javaClass.simpleName))
                } finally {
                    clip.close()
                }
            }
            try {
                val first = withTimeoutOrNull(limits.startMs) {
                    clip.receiveCatching().getOrNull() ?: ClipEvent.Failed(ENDED)
                }
                when (first) {
                    null -> Outcome.NotStarted(NOT_STARTED)
                    is ClipEvent.Failed -> Outcome.NotStarted(first.reason)
                    ClipEvent.Finished -> {
                        out.send(PlaybackEvent.Started(engine))
                        Outcome.Done
                    }
                    ClipEvent.Started -> {
                        out.send(PlaybackEvent.Started(engine))
                        val end = withTimeoutOrNull(limits.playMs) {
                            clip.receiveCatching().getOrNull() ?: ClipEvent.Finished
                        }
                        if (end is ClipEvent.Failed) Outcome.Broke(end.reason) else Outcome.Done
                    }
                }
            } finally {
                playing.cancel()
            }
        }
    }

    private sealed interface Outcome {
        data object Done : Outcome
        data class NotStarted(val reason: String) : Outcome
        data class Broke(val reason: String) : Outcome
    }

    companion object {
        const val NOT_STARTED = "not started in time"
        const val NO_FOCUS = "no audio focus"
        const val NO_PLAYER = "no player"
        const val ENDED = "ended before playing"
    }
}
