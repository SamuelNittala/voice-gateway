package dev.nittala.voicegateway.core.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** The gateway's login cookie value and when it expires (epoch millis). */
data class StoredCookie(val value: String, val expiresAtMillis: Long)

/**
 * Spec (A4): where the one login cookie lives. The in-memory store is here; the encrypted, persistent one is A7.
 * [put] with null forgets it.
 */
interface CookieStore {
    fun get(): StoredCookie?
    fun put(cookie: StoredCookie?)
}

/** Lost when the process ends. Thread-safe. */
class InMemoryCookieStore : CookieStore {
    @Volatile private var cookie: StoredCookie? = null
    override fun get(): StoredCookie? = cookie
    override fun put(cookie: StoredCookie?) {
        this.cookie = cookie
    }
}

/**
 * Spec (A4): an OkHttp [CookieJar] for exactly one gateway.
 * - Saves only `vg_login`, only from a response of the base URL's scheme, host and port; every other cookie and
 *   every other host is ignored. A `vg_login` already expired (Max-Age 0) forgets the stored one.
 * - Sends `vg_login` only to the base URL's scheme, host and port, and only while it has not expired by [now]; an
 *   expired one is forgotten.
 */
class GatewayCookieJar(
    private val base: HttpUrl,
    private val store: CookieStore,
    private val now: () -> Long = System::currentTimeMillis,
) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!isGateway(url)) return
        val login = cookies.lastOrNull { it.name == COOKIE_NAME } ?: return
        store.put(if (login.expiresAt <= now()) null else StoredCookie(login.value, login.expiresAt))
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!isGateway(url)) return emptyList()
        val stored = store.get() ?: return emptyList()
        if (stored.expiresAtMillis <= now()) {
            store.put(null)
            return emptyList()
        }
        return listOf(Cookie.Builder().name(COOKIE_NAME).value(stored.value).hostOnlyDomain(base.host).path("/").build())
    }

    private fun isGateway(url: HttpUrl): Boolean =
        url.scheme == base.scheme && url.host == base.host && url.port == base.port

    companion object {
        const val COOKIE_NAME = "vg_login"
    }
}
