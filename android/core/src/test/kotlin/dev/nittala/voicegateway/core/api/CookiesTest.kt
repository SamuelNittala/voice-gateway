package dev.nittala.voicegateway.core.api

import okhttp3.Cookie
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec (A4): the jar keeps only `vg_login`, only for the one gateway host, and drops it when expired. */
class CookiesTest {
    private val base = "https://gateway.example.ts.net/".toHttpUrl()
    private val now = 1_000_000L
    private val store = InMemoryCookieStore()
    private val jar = GatewayCookieJar(base, store) { now }

    private fun cookies(url: String, vararg setCookie: String): List<Cookie> =
        Cookie.parseAll(url.toHttpUrl(), headersOf(*setCookie.flatMap { listOf("Set-Cookie", it) }.toTypedArray()))

    @Test
    fun keepsOnlyTheLoginCookieFromTheGateway() {
        val url = "https://gateway.example.ts.net/api/login"
        val loginSetCookie = Fixtures.load("login_ok").headers.getValue("Set-Cookie")
        jar.saveFromResponse(url.toHttpUrl(), cookies(url, "tracking=1; Path=/", loginSetCookie))
        assertEquals("fixture-cookie-0001", store.get()?.value)
        val sent = jar.loadForRequest("https://gateway.example.ts.net/api/sessions".toHttpUrl())
        assertEquals(listOf("vg_login" to "fixture-cookie-0001"), sent.map { it.name to it.value })
    }

    @Test
    fun ignoresOtherHosts() {
        val other = "https://other.example.ts.net/api/login"
        jar.saveFromResponse(other.toHttpUrl(), cookies(other, "vg_login=stolen; Path=/"))
        assertNull(store.get())
    }

    @Test
    fun sendsOnlyToTheGateway() {
        store.put(StoredCookie("secret", now + 60_000))
        for (url in listOf("https://other.example.ts.net/", "http://gateway.example.ts.net/",
            "https://gateway.example.ts.net:8443/", "https://evil.gateway.example.ts.net/")) {
            assertTrue(url, jar.loadForRequest(url.toHttpUrl()).isEmpty())
        }
        assertEquals(1, jar.loadForRequest("https://gateway.example.ts.net/api/prompt".toHttpUrl()).size)
    }

    @Test
    fun expiredCookieIsForgotten() {
        store.put(StoredCookie("old", now - 1))
        assertTrue(jar.loadForRequest(base).isEmpty())
        assertNull(store.get())
    }

    @Test
    fun maxAgeZeroForgetsTheStoredCookie() {
        store.put(StoredCookie("old", Long.MAX_VALUE))
        val url = "https://gateway.example.ts.net/api/login"
        jar.saveFromResponse(url.toHttpUrl(), cookies(url, "vg_login=gone; Path=/; Max-Age=0"))
        assertNull(store.get())
    }
}
