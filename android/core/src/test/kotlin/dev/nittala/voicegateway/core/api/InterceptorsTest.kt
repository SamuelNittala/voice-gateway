package dev.nittala.voicegateway.core.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Spec (A4): `originOf`, the Origin interceptor and the Tailscale-* scrubber. */
class InterceptorsTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    @Test
    fun originOfBaseUrls() {
        assertEquals("https://gateway.example.ts.net", originOf("https://gateway.example.ts.net/".toHttpUrl()))
        assertEquals("https://gateway.example.ts.net", originOf("https://Gateway.Example.TS.net:443".toHttpUrl()))
        assertEquals("https://gateway.example.ts.net:8443", originOf("https://gateway.example.ts.net:8443/".toHttpUrl()))
        assertEquals("http://127.0.0.1:8766", originOf("http://127.0.0.1:8766/".toHttpUrl()))
        assertEquals("http://10.0.2.2", originOf("http://10.0.2.2:80/".toHttpUrl()))
        assertEquals("http://[::1]:8766", originOf("http://[::1]:8766/".toHttpUrl()))
    }

    private fun client(vararg sneaky: Pair<String, String>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().apply { sneaky.forEach { (k, v) -> header(k, v) } }.build())
        }
        .addInterceptor(OriginInterceptor("https://gateway.example.ts.net"))
        .addNetworkInterceptor(HeaderScrubber())
        .build()

    @Test
    fun originIsSetOnPostAndReplacesAnyOther() {
        server.enqueue(MockResponse())
        val post = Request.Builder().url(server.url("/api/stop")).header("Origin", "https://evil.example.com")
            .post("{}".toByteArray().toRequestBody()).build()
        client().newCall(post).execute().close()
        val recorded = server.takeRequest()
        assertEquals(listOf("https://gateway.example.ts.net"), recorded.headers.values("Origin"))
    }

    @Test
    fun noOriginOnGet() {
        server.enqueue(MockResponse())
        client().newCall(Request.Builder().url(server.url("/api/sessions")).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Origin"))
    }

    @Test
    fun tailscaleHeadersNeverLeave() {
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        val headers = arrayOf("Tailscale-User-Login" to "owner@example.com", "tailscale-user-name" to "Owner",
            "TAILSCALE-HEADERS-INFO" to "x", "X-Other" to "kept")
        val c = client(*headers)
        c.newCall(Request.Builder().url(server.url("/a")).build()).execute().close()
        c.newCall(Request.Builder().url(server.url("/b")).post("{}".toByteArray().toRequestBody()).build())
            .execute().close()
        repeat(2) {
            val recorded = server.takeRequest()
            assertEquals(emptyList<String>(), recorded.headers.names().filter { it.lowercase().startsWith("tailscale-") })
            assertEquals("kept", recorded.getHeader("X-Other"))
        }
    }
}
