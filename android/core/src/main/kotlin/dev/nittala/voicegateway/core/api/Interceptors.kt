package dev.nittala.voicegateway.core.api

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Spec (A4), pure: the origin of a base URL, `<scheme>://<host>[:port]`, lowercase, with the port only when it is
 * not the scheme's default and IPv6 hosts in brackets. `https://gateway.example.ts.net/` ->
 * `https://gateway.example.ts.net`; `http://127.0.0.1:8766/` -> `http://127.0.0.1:8766`. This is what
 * gateway.py's `origin_allowed` accepts.
 */
fun originOf(base: HttpUrl): String {
    val host = if (':' in base.host) "[${base.host}]" else base.host
    val port = if (base.port == HttpUrl.defaultPort(base.scheme)) "" else ":${base.port}"
    return "${base.scheme}://$host$port".lowercase()
}

/**
 * Spec (A4): sets `Origin: originOf(base)` on EVERY POST (login, prompt, keys, stop, restart, announced,
 * transcribe), replacing any Origin already there. Other methods pass unchanged.
 */
class OriginInterceptor(private val origin: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "POST") return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Origin", origin).build())
    }
}

/**
 * Spec (A4): removes every header whose name starts with `Tailscale-` (any case) from every request, as the last
 * step before the network. The gateway trusts `Tailscale-User-Login` from Tailscale Serve only; the app must never
 * send one itself.
 */
class HeaderScrubber : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val identity = request.headers.names().filter { it.startsWith("tailscale-", ignoreCase = true) }
        if (identity.isEmpty()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().apply { identity.forEach(::removeHeader) }.build())
    }
}
