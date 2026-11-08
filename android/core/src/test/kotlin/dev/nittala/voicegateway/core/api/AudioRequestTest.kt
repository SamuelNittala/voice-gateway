package dev.nittala.voicegateway.core.api

import dev.nittala.voicegateway.core.speech.Engine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** Spec (A9): `audioUrl` (pure) and the shared client's `GET /api/audio` against MockWebServer as the gateway. */
class AudioRequestTest {
    private val base = "https://gateway.example.ts.net".toHttpUrl()

    @Test
    fun buildsExactlyTheThreeParametersInOrder() {
        assertEquals(
            "https://gateway.example.ts.net/api/audio?id=ev-1&with_name=0&engine=chatterbox",
            audioUrl(base, "ev-1", withName = false, engine = Engine.Chatterbox).toString(),
        )
        assertEquals(
            "https://gateway.example.ts.net/api/audio?id=ev-2&with_name=1&engine=deepgram",
            audioUrl(base, "ev-2", withName = true, engine = Engine.Deepgram).toString(),
        )
    }

    @Test
    fun onlyTheServersParametersEverGo() {
        val url = audioUrl(base, "ev-1", withName = true, engine = Engine.Deepgram)
        assertEquals(listOf("id", "with_name", "engine"), url.queryParameterNames.toList())
        listOf("id", "with_name", "engine").forEach { assertEquals(1, url.queryParameterValues(it).size) }
    }

    @Test
    fun theIdIsEncodedAndCannotAddParameters() {
        val url = audioUrl(base, "a&engine=x b", withName = false, engine = Engine.Chatterbox)
        assertEquals("a&engine=x b", url.queryParameter("id"))
        assertEquals(listOf("chatterbox"), url.queryParameterValues("engine"))
        assertEquals(3, url.querySize)
    }

    @Test
    fun aQueryOrPathOnTheBaseIsDropped() {
        val odd = "https://gateway.example.ts.net:8443/other?x=1".toHttpUrl()
        assertEquals(
            "https://gateway.example.ts.net:8443/api/audio?id=e&with_name=0&engine=deepgram",
            audioUrl(odd, "e", withName = false, engine = Engine.Deepgram).toString(),
        )
    }

    @Test
    fun thePhonesVoiceIsNeverAskedOfTheGateway() {
        assertThrows(IllegalArgumentException::class.java) { audioUrl(base, "e", false, Engine.Phone) }
    }

    // ---- The request ExoPlayer's OkHttp data source sends: a plain GET of the URL on the shared client ----------------

    private val server = MockWebServer()
    private val store = InMemoryCookieStore()

    @Before
    fun start() = server.start()

    @After
    fun shutdown() = server.shutdown()

    private fun gatewayBase() = server.url("/")

    /** What OkHttpDataSource does for a whole clip from position 0: GET, no Range, no body. */
    private fun fetch(url: okhttp3.HttpUrl): Pair<Int, ByteArray> =
        gatewayClient(gatewayBase(), store).newCall(Request.Builder().url(url).get().build()).execute()
            .use { it.code to it.body.bytes() }

    @Test
    fun theAudioGetCarriesTheLoginCookieAndNoOriginOrIdentity() {
        store.put(StoredCookie("secret-cookie", Long.MAX_VALUE))
        val mp3 = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x44, 0x00)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setChunkedBody(Buffer().write(mp3), 2))

        val (code, body) = fetch(audioUrl(gatewayBase(), "ev-1", withName = true, engine = Engine.Chatterbox))

        assertEquals(200, code)
        assertArrayEquals(mp3, body)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/audio?id=ev-1&with_name=1&engine=chatterbox", request.path)
        assertEquals("vg_login=secret-cookie", request.getHeader("Cookie"))
        assertNull(request.getHeader("Origin"))
        assertNull(request.getHeader("Range"))
        assertEquals(emptyList<String>(), request.headers.names().filter { it.startsWith("tailscale-", ignoreCase = true) })
    }

    @Test
    fun a502IsAnsweredOnceAndNeverRepeated() {
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(200).setBody("must never be fetched"))

        val (code, _) = fetch(audioUrl(gatewayBase(), "ev-1", withName = false, engine = Engine.Chatterbox))

        assertEquals(502, code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRedirectIsNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))

        val (code, _) = fetch(audioUrl(gatewayBase(), "ev-1", withName = false, engine = Engine.Deepgram))

        assertEquals(302, code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun postsThroughTheSharedClientStillCarryTheOrigin() {
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        val url = gatewayBase().newBuilder().addPathSegments("api/announced").build()
        gatewayClient(gatewayBase(), store).newCall(
            Request.Builder().url(url).post("{}".toRequestBody()).build(),
        ).execute().close()
        assertEquals(originOf(gatewayBase()), server.takeRequest().getHeader("Origin"))
    }
}
