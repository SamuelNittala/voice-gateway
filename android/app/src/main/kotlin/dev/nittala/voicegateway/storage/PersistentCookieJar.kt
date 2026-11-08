package dev.nittala.voicegateway.storage

import dev.nittala.voicegateway.core.api.CookieStore
import dev.nittala.voicegateway.core.api.StoredCookie

/**
 * Spec (A7): the persistent store behind the gateway's cookie jar (`GatewayCookieJar` in :core, which already keeps
 * only `vg_login` and only for the one host). The cookie and its expiry live in one [SecretStore] entry named
 * [ENTRY] as `<expiresAtMillis>|<value>`.
 * - [put] with null deletes the entry: `HttpGatewayApi` does that on every 401, and logout does it too.
 * - [get] returns null when the entry is absent, unreadable (see [SecretStore]) or malformed (expiry not a number,
 *   value empty or with characters a cookie value cannot hold).
 */
class PersistentCookieStore(private val secrets: SecretStore) : CookieStore {
    override fun get(): StoredCookie? = secrets.get(ENTRY)?.let(::parse)

    override fun put(cookie: StoredCookie?) {
        secrets.put(ENTRY, cookie?.let { "${it.expiresAtMillis}$SEPARATOR${it.value}" })
    }

    companion object {
        const val ENTRY = "vg_login"
        private const val SEPARATOR = '|'

        /** RFC 6265 cookie-octet: visible ASCII except `"`, `,`, `;` and `\`. */
        private fun isCookieOctet(c: Char): Boolean = c.code in 0x21..0x7e && c !in "\",;\\"

        private fun parse(raw: String): StoredCookie? {
            val expiry = raw.substringBefore(SEPARATOR, "")
            val value = raw.substringAfter(SEPARATOR, "")
            if (expiry.isEmpty() || !expiry.all { it in '0'..'9' } || value.isEmpty() || !value.all(::isCookieOctet)) {
                return null
            }
            return expiry.toLongOrNull()?.let { StoredCookie(value, it) }
        }
    }
}
