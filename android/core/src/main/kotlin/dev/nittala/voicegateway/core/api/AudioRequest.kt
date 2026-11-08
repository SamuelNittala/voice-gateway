package dev.nittala.voicegateway.core.api

import dev.nittala.voicegateway.core.speech.Engine
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Spec (A9), pure: the `GET /api/audio` URL of one event in one voice, exactly as gateway.py accepts it:
 * `<base>/api/audio?id=<eventId>&with_name=0|1&engine=chatterbox|deepgram`, in that order, each once, and no other
 * parameter (the server ignores others, but the app never sends any). [base] is a parsed gateway base URL (no path,
 * query or fragment; [HttpGatewayApi.parseBase]); a query or path on it is dropped, never forwarded. The event id
 * is percent-encoded. [Engine.Phone] is the phone's own voice and is never sent: [IllegalArgumentException].
 */
fun audioUrl(base: HttpUrl, eventId: String, withName: Boolean, engine: Engine): HttpUrl {
    require(engine != Engine.Phone) { "The phone's voice is not a gateway engine." }
    return base.newBuilder()
        .encodedPath("/api/audio")
        .query(null)
        .fragment(null)
        .addQueryParameter("id", eventId)
        .addQueryParameter("with_name", if (withName) "1" else "0")
        .addQueryParameter("engine", engine.wire)
        .build()
}

/**
 * Spec (A9): the one OkHttp client for one gateway, with the same rules as [HttpGatewayApi]'s requests: the
 * `vg_login` cookie of [cookieStore] for this host only ([GatewayCookieJar]), `Origin` on every POST
 * ([OriginInterceptor]; a GET carries none), no `Tailscale-*` header ([HeaderScrubber], last before the network), no
 * redirects, no retry on connection failure, [timeouts]. The audio player (ExoPlayer's OkHttp data source) uses it so
 * the login cookie applies to `GET /api/audio`; nothing here ever repeats a request (the server remembers a failed
 * clip and answers each engine at most once per event).
 */
fun gatewayClient(base: HttpUrl, cookieStore: CookieStore, timeouts: Timeouts = Timeouts()): OkHttpClient =
    OkHttpClient.Builder()
        .cookieJar(GatewayCookieJar(base, cookieStore))
        .addInterceptor(OriginInterceptor(originOf(base)))
        .addNetworkInterceptor(HeaderScrubber())
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeouts.writeMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readMillis, TimeUnit.MILLISECONDS)
        .callTimeout(timeouts.callMillis, TimeUnit.MILLISECONDS)
        .build()
