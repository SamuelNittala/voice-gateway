package dev.nittala.voicegateway.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.nittala.voicegateway.core.api.audioUrl
import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.speech.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.HttpUrl

/** What the player reports, reduced to what [clipStep] needs. */
sealed interface PlayerSignal {
    /** `onIsPlayingChanged(true)`. */
    data object Playing : PlayerSignal

    /** `STATE_ENDED`. */
    data object Ended : PlayerSignal

    /** `onPlayerError`. */
    data class Error(val reason: String) : PlayerSignal
}

/**
 * Spec (A9), pure: the [ClipEvent] for a player [signal], given whether the clip has [started]. The first Playing
 * is Started (later ones, after a stall, are nothing); Ended after the start is Finished, before it is a failure
 * (an empty clip); an Error is Failed (before the start: fall back; after it: the clip ends part way).
 */
fun clipStep(started: Boolean, signal: PlayerSignal): ClipEvent? = when (signal) {
    PlayerSignal.Playing -> if (started) null else ClipEvent.Started
    PlayerSignal.Ended -> if (started) ClipEvent.Finished else ClipEvent.Failed(EMPTY_CLIP)
    is PlayerSignal.Error -> ClipEvent.Failed(signal.reason)
}

private const val EMPTY_CLIP = "ended before playing"

/**
 * Spec (A9): ExoPlayer never repeats a load. Every load error is fatal at once (no retry delay), with no fallback
 * selection: the gateway answers each engine once per event, remembers a failure (502 at once), and a resumed
 * request with a non-zero Range is 416.
 */
@OptIn(UnstableApi::class)
object NoRetryPolicy : LoadErrorHandlingPolicy {
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 0
}

/**
 * Spec (A9, plan section 4): an [EngineSpeaker] for the gateway's engines. Each clip is a new ExoPlayer on the main
 * thread playing `GET` [audioUrl] (`id`, `with_name`, `engine`, nothing else) through Media3's OkHttp data source over
 * [callFactory], which must be the app's shared gateway client
 * ([dev.nittala.voicegateway.core.api.gatewayClient]) so the login cookie and the Origin rule apply.
 *
 * - A progressive source with [NoRetryPolicy]: a 502/404/416 or a broken download is never asked again.
 * - Plays the chunked MP3 as it arrives: playback starts once [START_BUFFER_MS] of audio is in.
 * - Audio attributes `USAGE_ASSISTANT`, `CONTENT_TYPE_SPEECH`; ExoPlayer does NOT take audio focus itself (the
 *   chain holds the playback [dev.nittala.voicegateway.audio.AudioFocusGate] for every engine, the phone's too).
 *   Output routing (speaker, wired, Bluetooth) is the system's.
 * - Player callbacks go through [clipStep]; Finished or Failed ends the flow. Cancelling stops and releases the
 *   player at once. [Engine.Phone] is not this speaker's: Failed at once.
 */
@OptIn(UnstableApi::class)
class ExoSpeaker(
    private val context: Context,
    private val base: HttpUrl,
    callFactory: Call.Factory,
) : EngineSpeaker {
    private val mediaSources = ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(callFactory))
        .setLoadErrorHandlingPolicy(NoRetryPolicy)

    override fun play(event: Event, withName: Boolean, engine: Engine): Flow<ClipEvent> {
        if (engine == Engine.Phone) return flowOf(ClipEvent.Failed(NOT_GATEWAY))
        val url = audioUrl(base, event.id, withName, engine).toString()
        return callbackFlow {
            val player = ExoPlayer.Builder(context)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                            START_BUFFER_MS,
                            START_BUFFER_MS * 2,
                        )
                        .build(),
                )
                .setAudioAttributes(ATTRIBUTES, /* handleAudioFocus = */ false)
                .build()
            var started = false
            fun on(signal: PlayerSignal) {
                val clip = clipStep(started, signal) ?: return
                if (clip == ClipEvent.Started) started = true
                trySend(clip)
                if (clip != ClipEvent.Started) close()
            }
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) on(PlayerSignal.Playing)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) on(PlayerSignal.Ended)
                }

                override fun onPlayerError(error: PlaybackException) = on(PlayerSignal.Error(error.errorCodeName))
            })
            player.setMediaSource(mediaSources.createMediaSource(MediaItem.fromUri(url)))
            player.playWhenReady = true
            player.prepare()
            awaitClose {
                player.stop()
                player.release()
            }
        }.flowOn(Dispatchers.Main.immediate)
    }

    companion object {
        /** Audio buffered before the first sound: short, so a streamed clip starts with its first frames. */
        const val START_BUFFER_MS = 500
        const val NOT_GATEWAY = "not a gateway engine"

        private val ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(C.USAGE_ASSISTANT)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
    }
}
