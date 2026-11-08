package dev.nittala.voicegateway.core.api

import dev.nittala.voicegateway.core.model.CreateAnswer
import dev.nittala.voicegateway.core.model.GatewayJson
import dev.nittala.voicegateway.core.model.PlanAnswer
import dev.nittala.voicegateway.core.model.WorktreeSetup
import dev.nittala.voicegateway.core.model.planAnswerOf
import dev.nittala.voicegateway.core.model.OkAnswer
import dev.nittala.voicegateway.core.model.PromptAnswer
import dev.nittala.voicegateway.core.model.SessionView
import dev.nittala.voicegateway.core.model.SessionsView
import dev.nittala.voicegateway.core.model.TranscribeAnswer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Client timeouts in milliseconds. `callMillis` bounds a whole call; 0 means none. [planMillis] and [createMillis]
 * replace both the read and the call timeout for plan/re-plan (the interpreter, then `git ls-remote`: 90 s) and
 * create (the fetch, then `wt new`: 300 s), whose answers come only when the server is done.
 */
data class Timeouts(
    val connectMillis: Long = 10_000,
    val writeMillis: Long = 15_000,
    val readMillis: Long = 30_000,
    val callMillis: Long = 60_000,
    val planMillis: Long = 90_000,
    val createMillis: Long = 300_000,
)

/**
 * Spec (A4): [GatewayApi] on OkHttp, for one gateway.
 *
 * - [baseUrl] is a constructor argument, never hardcoded (production: the Tailscale Serve name such as
 *   `https://gateway.example.ts.net`; tests: the MockWebServer URL). It must be an absolute http(s) URL with no
 *   path, query, fragment or user info, else [IllegalArgumentException]. The https/ts.net rule for what the user
 *   types is the settings screen's (A7).
 * - Requests: `Origin` = [originOf] the base URL on every POST ([OriginInterceptor]); `Content-Type:
 *   application/json` (exactly) on JSON POSTs; the recording's own type and a Content-Length on transcribe; no
 *   `Tailscale-*` header ever ([HeaderScrubber], last before the network); only `vg_login`, only for this host
 *   ([GatewayCookieJar] over [cookieStore]).
 * - Transport: redirects are not followed (a 3xx is [ApiResult.Unexpected]); OkHttp's retry on connection failure is
 *   off; [timeouts] apply; nothing is logged (no bodies, passcodes, transcripts, cookies).
 * - [sharedClient]: the app's one client from [gatewayClient] (same base and [cookieStore]), so login, these calls and
 *   the audio player share one connection pool and one cookie jar. Calls go through a client derived from it that
 *   only adds the delivery listener (needed to tell [ApiResult.MaybeSent] apart); without it [gatewayClient] is built
 *   here with [timeouts].
 * - Answers: 2xx parses into the route's model ([ApiResult.Unexpected] if it does not); 401 is
 *   [ApiResult.LoggedOut] and clears [cookieStore], except on login, where it is a [ApiResult.Refused] wrong
 *   passcode; any other error is [ApiResult.Refused] via [refusalOf].
 * - New worktrees: plan and re-plan run with [Timeouts.planMillis], create with [Timeouts.createMillis] (read and
 *   call timeout both), on a client derived from the same one (same pool, cookies and delivery listener).
 * - Failures: a POST that failed after its body was written in full is [ApiResult.MaybeSent]; any failure before
 *   that, and any GET failure, is [ApiResult.NetworkFailure]. Nothing is ever retried here.
 */
class HttpGatewayApi(
    baseUrl: String,
    private val cookieStore: CookieStore,
    private val timeouts: Timeouts = Timeouts(),
    sharedClient: OkHttpClient? = null,
) : GatewayApi {
    private val base: HttpUrl = parseBase(baseUrl)

    private val client: OkHttpClient = (sharedClient ?: gatewayClient(base, cookieStore, timeouts)).newBuilder()
        .eventListenerFactory { DeliveryListener }
        .build()

    override suspend fun login(passcode: String): ApiResult<Unit> =
        post("api/login", buildJsonObject { put("passcode", passcode) }, login = true) { decodeOk(it) }

    override suspend fun sessions(): ApiResult<SessionsView> =
        send(get(url("api/sessions"))) { GatewayJson.decodeFromString<SessionsView>(it) }

    override suspend fun session(name: String, earlier: Boolean): ApiResult<SessionView> {
        val url = url("api/session").newBuilder().addQueryParameter("name", name)
            .apply { if (earlier) addQueryParameter("earlier", "1") }.build()
        return send(get(url)) { GatewayJson.decodeFromString<SessionView>(it) }
    }

    override suspend fun prompt(session: String, text: String, voice: Boolean, confirmTap: Boolean): ApiResult<PromptAnswer> =
        post("api/prompt", buildJsonObject {
            put("session", session)
            put("text", text)
            put("voice", voice)
            if (confirmTap) put("confirm", "tap")
        }) { GatewayJson.decodeFromString<PromptAnswer>(it) }

    override suspend fun keys(session: String, key: String, screenToken: String?): ApiResult<Unit> =
        post("api/keys", buildJsonObject {
            put("session", session)
            put("key", key)
            put("screen_token", screenToken)
        }) { decodeOk(it) }

    override suspend fun stop(session: String): ApiResult<Unit> =
        post("api/stop", buildJsonObject { put("session", session) }) { decodeOk(it) }

    override suspend fun restart(session: String): ApiResult<Unit> =
        post("api/restart", buildJsonObject { put("session", session) }) { decodeOk(it) }

    override suspend fun announced(ids: List<String>): ApiResult<Unit> =
        post("api/announced", buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) }) { decodeOk(it) }

    override suspend fun transcribe(audio: ByteArray, contentType: String): ApiResult<TranscribeAnswer> {
        val request = postRequest(url("api/transcribe"), audio.toRequestBody(contentType.toMediaType()))
        return send(request) { GatewayJson.decodeFromString<TranscribeAnswer>(it) }
    }

    override suspend fun worktreeSetup(): ApiResult<WorktreeSetup> =
        send(get(url("api/worktree/setup"))) { GatewayJson.decodeFromString<WorktreeSetup>(it) }

    override suspend fun plan(query: String, repoId: String?): ApiResult<PlanAnswer> =
        post("api/worktree/plan", buildJsonObject {
            put("query", query)
            if (repoId != null) put("repo_id", repoId)
        }, client = planClient, decode = ::planAnswerOf)

    override suspend fun replan(planId: String, base: String): ApiResult<PlanAnswer> =
        post("api/worktree/plan", buildJsonObject {
            put("plan_id", planId)
            put("base", base)
        }, client = planClient, decode = ::planAnswerOf)

    override suspend fun create(planId: String): ApiResult<CreateAnswer> =
        post("api/worktree/create", buildJsonObject { put("plan_id", planId) }, client = createClient) {
            GatewayJson.decodeFromString<CreateAnswer>(it)
        }

    /** [client] with [millis] as both the read and the whole-call timeout (same pool, cookies and listener). */
    private fun longCall(millis: Long): OkHttpClient = client.newBuilder()
        .readTimeout(millis, TimeUnit.MILLISECONDS)
        .callTimeout(millis, TimeUnit.MILLISECONDS)
        .build()

    private val planClient: OkHttpClient by lazy { longCall(timeouts.planMillis) }
    private val createClient: OkHttpClient by lazy { longCall(timeouts.createMillis) }

    private fun url(path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()

    private fun get(url: HttpUrl): Request = Request.Builder().url(url).get().build()

    private fun postRequest(url: HttpUrl, body: RequestBody): Request =
        Request.Builder().url(url).post(body).tag(Delivery::class.java, Delivery()).build()

    private suspend fun <T> post(
        path: String,
        json: JsonObject,
        login: Boolean = false,
        client: OkHttpClient = this.client,
        decode: (String) -> T,
    ): ApiResult<T> {
        val body = json.toString().toByteArray(Charsets.UTF_8).toRequestBody(JSON)
        return send(postRequest(url(path), body), login, client, decode)
    }

    private fun decodeOk(body: String) {
        if (!GatewayJson.decodeFromString<OkAnswer>(body).ok) throw SerializationException("ok is false")
    }

    /** Runs one call, cancelling it if the coroutine is cancelled, and maps every outcome to an [ApiResult]. */
    private suspend fun <T> send(
        request: Request,
        login: Boolean = false,
        client: OkHttpClient = this.client,
        decode: (String) -> T,
    ): ApiResult<T> =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val written = request.tag(Delivery::class.java)?.written == true
                    continuation.resume(if (written) ApiResult.MaybeSent else ApiResult.NetworkFailure)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            resultOf(it.code, it.body.string(), it.header("Retry-After"), login, decode)
                        }
                    } catch (_: IOException) {
                        ApiResult.Unexpected(response.code)  // The status came, its body did not.
                    }
                    if (result == ApiResult.LoggedOut) cookieStore.put(null)
                    continuation.resume(result)
                }
            })
        }

    /** Set once a POST's body has been written in full, so a failure after it may have been delivered. */
    private class Delivery {
        @Volatile var written = false
    }

    private object DeliveryListener : EventListener() {
        override fun requestBodyEnd(call: Call, byteCount: Long) {
            call.request().tag(Delivery::class.java)?.written = true
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** The base URL, or [IllegalArgumentException]: absolute http(s), no path, query, fragment or user info. */
        fun parseBase(baseUrl: String): HttpUrl {
            val url = requireNotNull(baseUrl.toHttpUrlOrNull()) { "The gateway address is not an http(s) URL." }
            require(url.encodedPath == "/" && url.query == null && url.fragment == null) {
                "The gateway address must have no path, query or fragment."
            }
            require(url.username.isEmpty() && url.password.isEmpty()) { "The gateway address must have no user info." }
            return url
        }

        /**
         * Pure: the result for an answer. 2xx decodes ([ApiResult.Unexpected] if [decode] fails); 401 is
         * [ApiResult.LoggedOut] unless [login]; other 4xx/5xx are [ApiResult.Refused]; anything else (3xx) is
         * [ApiResult.Unexpected].
         */
        fun <T> resultOf(
            httpStatus: Int,
            body: String,
            retryAfter: String?,
            login: Boolean,
            decode: (String) -> T,
        ): ApiResult<T> = when {
            httpStatus in 200..299 -> try {
                ApiResult.Ok(decode(body))
            } catch (_: SerializationException) {
                ApiResult.Unexpected(httpStatus)
            } catch (_: IllegalArgumentException) {
                ApiResult.Unexpected(httpStatus)
            }
            httpStatus == 401 && !login -> ApiResult.LoggedOut
            httpStatus >= 400 -> ApiResult.Refused(refusalOf(httpStatus, body, retryAfter))
            else -> ApiResult.Unexpected(httpStatus)
        }
    }
}
