package dev.nittala.voicegateway.playback

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nittala.voicegateway.audio.AudioFocusGate
import dev.nittala.voicegateway.audio.FocusEvent
import dev.nittala.voicegateway.audio.FocusResult
import dev.nittala.voicegateway.core.api.InMemoryCookieStore
import dev.nittala.voicegateway.core.api.StoredCookie
import dev.nittala.voicegateway.core.api.gatewayClient
import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.model.EventKind
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.speech.EngineChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Spec (A9), on a device or emulator: the real ExoPlayer over the shared gateway client against an on-device
 * MockWebServer as the gateway, streaming short MP3 tones (androidTest assets, made with ffmpeg: 440 Hz, 24 kHz
 * mono, 32 kb/s, no Xing header, as the gateway streams them) as chunked responses.
 */
@RunWith(AndroidJUnit4::class)
class ExoSpeakerDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val server = MockWebServer()
    private val store = InMemoryCookieStore()
    private lateinit var exo: ExoSpeaker

    private val event = Event(id = "ev-1", kind = EventKind.Summary, session = "wt-api", worktree = "wt-api", text = "Tests pass.")

    @Before
    fun start() {
        server.start()
        store.put(StoredCookie("device-test-cookie", Long.MAX_VALUE))
        val base = server.url("/")
        exo = ExoSpeaker(context, base, gatewayClient(base, store))
    }

    @After
    fun shutdown() = server.shutdown()

    private fun mp3(name: String) = Buffer().write(assets.open(name).use { it.readBytes() })

    private fun audio(name: String) =
        MockResponse().setHeader("Content-Type", "audio/mpeg").setHeader("Cache-Control", "no-store")
            .setChunkedBody(mp3(name), 1024)

    private fun clip(engine: Engine): List<ClipEvent> =
        runBlocking { withTimeout(20_000) { exo.play(event, withName = false, engine = engine).toList() } }

    @Test
    fun playsAChunkedMp3AndReportsStartedThenFinished() {
        server.enqueue(audio("tone_1s.mp3"))

        assertEquals(listOf(ClipEvent.Started, ClipEvent.Finished), clip(Engine.Chatterbox))

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/audio?id=ev-1&with_name=0&engine=chatterbox", request.path)
        assertEquals("vg_login=device-test-cookie", request.getHeader("Cookie"))
        assertEquals(null, request.getHeader("Range"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun a502FailsBeforeStartingAndIsNeverRepeated() {
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(audio("tone_1s.mp3")) // must never be fetched

        val out = clip(Engine.Deepgram)

        assertEquals(1, out.size)
        assertEquals(true, out.single() is ClipEvent.Failed)
        assertEquals(1, server.requestCount)
    }

    // ---- The chain over the real player ------------------------------------------------------------------------------

    private class NoFocus : AudioFocusGate {
        override val events: Flow<FocusEvent> = emptyFlow()
        override fun acquire() = FocusResult.Granted
        override fun release() = Unit
    }

    /** The phone's voice, recorded instead of spoken. */
    private class SilentPhone : EngineSpeaker {
        var asked = 0
        override fun play(event: Event, withName: Boolean, engine: Engine): Flow<ClipEvent> {
            asked++
            return flowOf(ClipEvent.Started, ClipEvent.Finished)
        }
    }

    private fun chainPlay(phone: SilentPhone): List<PlaybackEvent> {
        val speaker = ChainSpeaker(mapOf(Engine.Chatterbox to exo, Engine.Deepgram to exo, Engine.Phone to phone), NoFocus())
        val chain = EngineChain.of(listOf("chatterbox", "deepgram"))
        return runBlocking { withTimeout(30_000) { speaker.play(event, withName = false, engineChain = chain).toList() } }
    }

    @Test
    fun chatterbox502FallsBackToDeepgram() {
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(audio("tone_1s.mp3"))
        val phone = SilentPhone()

        val out = chainPlay(phone)

        assertEquals(PlaybackEvent.FellBack(Engine.Chatterbox, Engine.Deepgram, "Chatterbox unavailable, used Deepgram"), out[1])
        assertEquals(listOf(PlaybackEvent.Started(Engine.Deepgram), PlaybackEvent.Finished), out.drop(2))
        assertEquals("/api/audio?id=ev-1&with_name=0&engine=chatterbox", server.takeRequest().path)
        assertEquals("/api/audio?id=ev-1&with_name=0&engine=deepgram", server.takeRequest().path)
        assertEquals(2, server.requestCount)
        assertEquals(0, phone.asked)
    }

    @Test
    fun aDownloadCutPartWayEndsTheClipWithoutASecondVoice() {
        // Delivered at about real time so playback starts, then the connection drops half way, as the gateway does
        // when the engine fails after its first byte.
        server.enqueue(
            audio("tone_3s.mp3").throttleBody(2_048, 500, TimeUnit.MILLISECONDS)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        server.enqueue(audio("tone_1s.mp3")) // must never be fetched
        val phone = SilentPhone()

        val out = chainPlay(phone)

        assertEquals(PlaybackEvent.Started(Engine.Chatterbox), out.first())
        assertEquals(true, out.last() is PlaybackEvent.Failed && (out.last() as PlaybackEvent.Failed).engine == Engine.Chatterbox)
        assertEquals(2, out.size)
        assertEquals(1, server.requestCount) // no Deepgram, no resumed Range request
        assertEquals(0, phone.asked)
    }
}
