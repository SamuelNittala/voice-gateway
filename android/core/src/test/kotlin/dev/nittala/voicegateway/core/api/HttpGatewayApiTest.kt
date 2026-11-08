package dev.nittala.voicegateway.core.api

import dev.nittala.voicegateway.core.model.AskKind
import dev.nittala.voicegateway.core.model.PlanAnswer
import dev.nittala.voicegateway.core.model.PromptAnswer
import dev.nittala.voicegateway.core.model.TranscribeAnswer
import dev.nittala.voicegateway.core.speech.Engine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Spec (A4): `HttpGatewayApi` against MockWebServer as the fake gateway, answering with the real gateway's answers
 * (fixtures), and asserting the request rules gateway.py enforces.
 */
class HttpGatewayApiTest {
    private val server = MockWebServer()
    private val store = InMemoryCookieStore()
    private lateinit var api: HttpGatewayApi
    private lateinit var origin: String

    @Before
    fun start() {
        server.start()
        origin = server.url("/").toString().removeSuffix("/")
        api = HttpGatewayApi(server.url("/").toString(), store, Timeouts(readMillis = 500, callMillis = 5_000))
    }

    @After
    fun stop() = server.shutdown()

    private fun answer(name: String) = server.enqueue(Fixtures.load(name).response())

    private fun taken(): RecordedRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "no request" }

    private fun jsonBody(request: RecordedRequest) = Json.parseToJsonElement(request.body.readUtf8())

    /** Every POST the app makes, each answered with its real success fixture. */
    private val posts: List<Pair<String, suspend () -> ApiResult<*>>> = listOf(
        "login_ok" to { api.login("correct horse") },
        "prompt_ok" to { api.prompt("wt-scratch", "run the tests", voice = true) },
        "keys_ok" to { api.keys("wt-scratch", "1", "abc") },
        "stop_ok" to { api.stop("wt-scratch") },
        "restart_ok" to { api.restart("wt-scratch") },
        "announced_ok" to { api.announced(listOf("a1")) },
        "transcribe_ok" to { api.transcribe(byteArrayOf(1, 2, 3)) },
        "plan_new_ready" to { api.plan("a worktree in web app for the login fix") },
        "plan_existing" to { api.replan("0".repeat(32), "uat") },
        "create_ok" to { api.create("0".repeat(32)) },
    )

    private fun assertRequestRules(request: RecordedRequest) {
        val names = request.headers.names()
        assertTrue(request.path!!, names.none { it.lowercase().startsWith("tailscale-") })
        assertNull("no chunking", request.getHeader("Transfer-Encoding"))
        if (request.method == "POST") assertEquals(request.path, listOf(origin), request.headers.values("Origin"))
    }

    @Test
    fun originOnEveryPostAndNoTailscaleHeaderEver() = runBlocking {
        for ((fixture, call) in posts) {
            answer(fixture)
            assertTrue(fixture, call() is ApiResult.Ok)
            assertRequestRules(taken())
        }
        answer("sessions")
        api.sessions()
        answer("session_idle")
        api.session("wt-scratch")
        repeat(2) {
            val get = taken()
            assertEquals("GET", get.method)
            assertRequestRules(get)
            assertNull(get.getHeader("Origin"))
        }
    }

    @Test
    fun jsonPostsHaveExactlyTheJsonContentType() = runBlocking {
        for ((fixture, call) in posts.filter { it.first != "transcribe_ok" }) {
            answer(fixture)
            call()
            assertEquals(fixture, "application/json", taken().getHeader("Content-Type"))
        }
    }

    @Test
    fun loginBodyAndCookieRoundTrip() = runBlocking {
        answer("login_ok")
        assertEquals(ApiResult.Ok(Unit), api.login("correct horse"))
        val request = taken()
        assertEquals("POST" to "/api/login", request.method to request.path)
        assertEquals(JsonObject(mapOf("passcode" to JsonPrimitive("correct horse"))), jsonBody(request))
        assertNull(request.getHeader("Cookie"))
        assertEquals("fixture-cookie-0001", store.get()?.value)

        answer("sessions")
        api.sessions()
        assertEquals("vg_login=fixture-cookie-0001", taken().getHeader("Cookie"))
        answer("prompt_ok")
        api.prompt("wt-scratch", "hi", voice = false)
        assertEquals("vg_login=fixture-cookie-0001", taken().getHeader("Cookie"))
    }

    @Test
    fun otherCookiesAreNotKept() = runBlocking {
        server.enqueue(Fixtures.load("sessions").response().addHeader("Set-Cookie", "tracking=1; Path=/"))
        api.sessions()
        taken()
        assertNull(store.get())
        answer("sessions")
        api.sessions()
        assertNull(taken().getHeader("Cookie"))
    }

    @Test
    fun sessionsAndSessionViews() = runBlocking {
        answer("sessions")
        val list = api.sessions() as ApiResult.Ok
        assertEquals(listOf("wt-scratch"), list.value.running.map { it.name })
        assertEquals("/api/sessions", taken().path)

        answer("event_ask_true")
        val view = api.session("wt-scratch") as ApiResult.Ok
        assertEquals(AskKind.Answer, view.value.events.single().ask)
        assertEquals("/api/session?name=wt-scratch", taken().path)

        answer("session_idle")
        api.session("wt-scratch", earlier = true)
        assertEquals("/api/session?name=wt-scratch&earlier=1", taken().path)
    }

    @Test
    fun promptBodyCarriesVoice() = runBlocking {
        answer("prompt_ok")
        assertEquals(ApiResult.Ok(PromptAnswer(true, "unknown")), api.prompt("wt-scratch", "run the tests", voice = true))
        val voice = taken()
        assertEquals("/api/prompt", voice.path)
        assertEquals(
            JsonObject(mapOf("session" to JsonPrimitive("wt-scratch"), "text" to JsonPrimitive("run the tests"),
                "voice" to JsonPrimitive(true))),
            jsonBody(voice),
        )
        answer("prompt_ok")
        api.prompt("wt-scratch", "typed", voice = false)
        assertEquals(JsonPrimitive(false), (jsonBody(taken()) as JsonObject)["voice"])
    }

    @Test
    fun promptBodyCarriesTheTapMarkOnlyForAConfirmTap() = runBlocking {
        answer("prompt_ok_tap")
        assertEquals(
            ApiResult.Ok(PromptAnswer(true, "unknown")),
            api.prompt("wt-scratch", "yes, go ahead", voice = true, confirmTap = true),
        )
        assertEquals(
            JsonObject(mapOf("session" to JsonPrimitive("wt-scratch"), "text" to JsonPrimitive("yes, go ahead"),
                "voice" to JsonPrimitive(true), "confirm" to JsonPrimitive("tap"))),
            jsonBody(taken()),
        )
        answer("prompt_ok")
        api.prompt("wt-scratch", "run the tests", voice = true, confirmTap = false)
        assertNull((jsonBody(taken()) as JsonObject)["confirm"])
        answer("prompt_ok")
        api.prompt("wt-scratch", "typed", voice = false)
        assertNull((jsonBody(taken()) as JsonObject)["confirm"])
    }

    @Test
    fun aBadTapMarkIsTheServersBadText() = runBlocking {
        answer("prompt_bad_confirm")
        val refusal = (api.prompt("wt-scratch", "yes", voice = true, confirmTap = true) as ApiResult.Refused).refusal
        assertEquals(RefusalReason.BadText, refusal.reason)
    }

    @Test
    fun keysStopRestartAnnouncedBodies() = runBlocking {
        answer("keys_ok")
        assertEquals(ApiResult.Ok(Unit), api.keys("wt-scratch", "enter", "tok"))
        assertEquals(
            JsonObject(mapOf("session" to JsonPrimitive("wt-scratch"), "key" to JsonPrimitive("enter"),
                "screen_token" to JsonPrimitive("tok"))),
            jsonBody(taken()),
        )
        answer("keys_ok")
        api.keys("wt-scratch", "1", null)
        assertEquals(JsonNull, (jsonBody(taken()) as JsonObject)["screen_token"])
        answer("stop_ok")
        api.stop("wt-scratch")
        taken().let {
            assertEquals("/api/stop", it.path)
            assertEquals(JsonObject(mapOf("session" to JsonPrimitive("wt-scratch"))), jsonBody(it))
        }
        answer("restart_ok")
        api.restart("wt-scratch")
        assertEquals("/api/restart", taken().path)
        answer("announced_ok")
        api.announced(listOf("a1", "b2"))
        taken().let {
            assertEquals("/api/announced", it.path)
            assertEquals(JsonObject(mapOf("ids" to JsonArray(listOf(JsonPrimitive("a1"), JsonPrimitive("b2"))))), jsonBody(it))
        }
    }

    @Test
    fun transcribeSendsRawAudioWithLengthAndType() = runBlocking {
        val wav = ByteArray(5000) { (it % 251).toByte() }
        answer("transcribe_ok")
        assertEquals(ApiResult.Ok(TranscribeAnswer("run the tests in the api worktree")), api.transcribe(wav))
        val request = taken()
        assertEquals("/api/transcribe", request.path)
        assertEquals("audio/wav", request.getHeader("Content-Type"))
        assertEquals("5000", request.getHeader("Content-Length"))
        assertNull(request.getHeader("Transfer-Encoding"))
        assertTrue(request.chunkSizes.isEmpty())
        assertArrayEquals(wav, request.body.readByteArray())
        assertRequestRules(request)
    }

    @Test
    fun unauthorizedIsLoggedOutAndClearsTheCookie() = runBlocking {
        store.put(StoredCookie("dead", Long.MAX_VALUE))
        answer("guard_401_logged_out")
        assertEquals(ApiResult.LoggedOut, api.session("wt-scratch"))
        assertNull(store.get())
        answer("guard_401_logged_out_post")
        assertEquals(ApiResult.LoggedOut, api.prompt("wt-scratch", "hi", voice = true))
        answer("guard_401_logged_out_post")
        assertEquals(ApiResult.LoggedOut, api.transcribe(byteArrayOf(1)))
    }

    @Test
    fun wrongPasscodeIsARefusalNotLoggedOut() = runBlocking {
        answer("login_wrong")
        assertEquals(ApiResult.Refused(Refusal(401, null, "Wrong passcode.")), api.login("nope"))
        answer("login_locked")
        val locked = api.login("nope") as ApiResult.Refused
        assertEquals(429 to 31, locked.refusal.httpStatus to locked.refusal.retryAfterSeconds)
    }

    @Test
    fun everyRefusalReasonComesThroughItsRoute() = runBlocking {
        val transcribe = setOf("speech_off", "bad_audio_type", "audio_empty", "audio_too_large", "transcribe_busy",
            "transcribe_rate", "speech_failed")
        val restart = setOf("already_running", "restart_refused", "restart_failed")
        val create = setOf("bad_plan", "plan_gone", "plan_expired", "create_busy", "repo_changed", "not_ready",
            "fetch_failed", "wt_failed")
        for (fixture in Fixtures.all("refusal_")) {
            val reason = fixture.name.removePrefix("refusal_").removeSuffix("_offline")
            server.enqueue(fixture.response())
            val result = when (reason) {
                in transcribe -> api.transcribe(byteArrayOf(1, 2))
                in restart -> api.restart("wt-scratch")
                in create -> api.create("0".repeat(32))
                "bad_base" -> api.replan("0".repeat(32), " ")
                in WORKTREE_REASONS -> api.plan("a worktree")
                "not_blocked", "screen_changed" -> api.keys("wt-scratch", "1", "tok")
                "not_working" -> api.stop("wt-scratch")
                else -> api.prompt("wt-scratch", "hello", voice = true)
            }
            val refusal = (result as? ApiResult.Refused)?.refusal
            assertEquals(reason, reason, refusal?.reason?.wire)
            assertEquals(reason, fixture.status, refusal?.httpStatus)
            taken()
        }
    }

    // ---- W6: new worktrees -----------------------------------------------------------------------------------------

    @Test
    fun worktreeSetupIsAGet() = runBlocking {
        answer("worktree_setup")
        val setup = api.worktreeSetup() as ApiResult.Ok
        assertEquals(2, setup.value.repos)
        val request = taken()
        assertEquals("GET" to "/api/worktree/setup", request.method to request.path)
        assertRequestRules(request)
    }

    @Test
    fun planBodiesCarryTheRepoIdOnlyWhenGiven() = runBlocking {
        answer("plan_new_ready")
        val plan = api.plan("a worktree in web app for the login fix") as ApiResult.Ok
        assertTrue(plan.value is PlanAnswer.Plan)
        taken().let {
            assertEquals("POST" to "/api/worktree/plan", it.method to it.path)
            assertEquals(JsonObject(mapOf("query" to JsonPrimitive("a worktree in web app for the login fix"))), jsonBody(it))
        }
        answer("plan_ask_candidates")
        assertTrue((api.plan("the login fix", repoId = "85dfc701aea8") as ApiResult.Ok).value is PlanAnswer.Ask)
        assertEquals(
            JsonObject(mapOf("query" to JsonPrimitive("the login fix"), "repo_id" to JsonPrimitive("85dfc701aea8"))),
            jsonBody(taken()),
        )
    }

    @Test
    fun replanAndCreateBodies() = runBlocking {
        val id = "0".repeat(31) + "a"
        answer("plan_new_ready")
        api.replan(id, "production 4.6.2")
        taken().let {
            assertEquals("/api/worktree/plan", it.path)
            assertEquals(JsonObject(mapOf("plan_id" to JsonPrimitive(id), "base" to JsonPrimitive("production 4.6.2"))), jsonBody(it))
        }
        answer("create_ok_launch")
        val created = api.create(id) as ApiResult.Ok
        assertEquals("wt-web-app-fix-login-timeout", created.value.session)
        assertEquals("claude-opus-5-5", created.value.launch?.model)
        taken().let {
            assertEquals("/api/worktree/create", it.path)
            assertEquals(JsonObject(mapOf("plan_id" to JsonPrimitive(id))), jsonBody(it))
        }
    }

    @Test
    fun planAndCreateWaitLongerThanTheReadTimeout() = runBlocking {
        assertEquals(90_000L, Timeouts().planMillis)
        assertEquals(300_000L, Timeouts().createMillis)
        val slow = HttpGatewayApi(
            server.url("/").toString(), store,
            Timeouts(readMillis = 300, callMillis = 600, planMillis = 5_000, createMillis = 5_000),
        )
        server.enqueue(Fixtures.load("create_ok").response().setHeadersDelay(1_200, TimeUnit.MILLISECONDS))
        assertTrue(slow.create("0".repeat(32)) is ApiResult.Ok)
        server.enqueue(Fixtures.load("plan_new_ready").response().setHeadersDelay(1_200, TimeUnit.MILLISECONDS))
        assertTrue(slow.plan("x y") is ApiResult.Ok)
        server.enqueue(Fixtures.load("session_idle").response().setHeadersDelay(1_200, TimeUnit.MILLISECONDS))
        assertEquals(ApiResult.NetworkFailure, slow.session("wt-scratch"))
    }

    @Test
    fun aCutCreateIsMaybeSentAndNeverRetried() = runBlocking {
        val short = HttpGatewayApi(server.url("/").toString(), store, Timeouts(readMillis = 300, createMillis = 800))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        answer("create_ok")
        assertEquals(ApiResult.MaybeSent, short.create("0".repeat(32)))
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun worktreeCallsLogOutOn401() = runBlocking {
        store.put(StoredCookie("dead", Long.MAX_VALUE))
        answer("guard_401_logged_out_post")
        assertEquals(ApiResult.LoggedOut, api.plan("x y"))
        assertNull(store.get())
        answer("guard_401_logged_out_post")
        assertEquals(ApiResult.LoggedOut, api.create("0".repeat(32)))
        answer("guard_401_logged_out")
        assertEquals(ApiResult.LoggedOut, api.worktreeSetup())
    }

    @Test
    fun theDownloadedCopyComesWithAFailedFetch() = runBlocking {
        answer("refusal_fetch_failed_offline")
        val refusal = (api.create("0".repeat(32)) as ApiResult.Refused).refusal
        assertEquals(RefusalReason.FetchFailed to 502, refusal.reason to refusal.httpStatus)
        assertEquals("origin/production-4.6.1", refusal.offline?.ref)
    }

    @Test
    fun aPlanBodyOfAnUnknownKindIsUnexpected() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"kind": "later", "x": 1}"""))
        assertEquals(ApiResult.Unexpected(200), api.plan("x y"))
    }

    @Test
    fun refusalExtrasSurvive() = runBlocking {
        answer("refusal_screen_changed")
        val changed = (api.keys("wt-scratch", "1", "old") as ApiResult.Refused).refusal
        assertEquals(RefusalReason.ScreenChanged, changed.reason)
        assertEquals(32, changed.screenToken?.length)
        answer("refusal_transcribe_busy")
        assertEquals(2, (api.transcribe(byteArrayOf(1)) as ApiResult.Refused).refusal.retryAfterSeconds)
        answer("refusal_not_settled")
        assertEquals("working", (api.prompt("wt-scratch", "x", true) as ApiResult.Refused).refusal.agentStatus)
    }

    @Test
    fun guardRefusalsGoByStatus() = runBlocking {
        for (fixture in Fixtures.all("guard_").filter { it.status != 401 }) {
            server.enqueue(fixture.response())
            val refusal = (api.prompt("wt-scratch", "hi", voice = true) as ApiResult.Refused).refusal
            assertNull(fixture.name, refusal.reason)
            assertEquals(fixture.name, fixture.status, refusal.httpStatus)
            taken()
        }
    }

    @Test
    fun postWithNoAnswerIsMaybeSentAndNeverRetried() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        answer("prompt_ok")
        assertEquals(ApiResult.MaybeSent, api.prompt("wt-scratch", "run the tests", voice = true))
        assertEquals("/api/prompt", taken().path)
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun transcribeWithNoAnswerIsMaybeSentAndNeverRetried() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        answer("transcribe_ok")
        assertEquals(ApiResult.MaybeSent, api.transcribe(ByteArray(100)))
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun postCutAfterTheRequestIsMaybeSent() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        answer("stop_ok")
        assertEquals(ApiResult.MaybeSent, api.stop("wt-scratch"))
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aSharedClientCarriesEveryCallAndItsCookieJarServesTheAudioToo() = runBlocking {
        val seen = mutableListOf<String>()
        val base = HttpGatewayApi.parseBase(server.url("/").toString())
        val shared = gatewayClient(base, store, Timeouts(readMillis = 500, callMillis = 5_000)).newBuilder()
            .addInterceptor { chain -> seen += chain.request().url.encodedPath; chain.proceed(chain.request()) }
            .build()
        val api = HttpGatewayApi(server.url("/").toString(), store, sharedClient = shared)
        answer("login_ok")
        assertTrue(api.login("correct horse") is ApiResult.Ok)
        taken()
        answer("session_idle")
        assertTrue(api.session("wt-scratch") is ApiResult.Ok)
        taken()
        assertEquals(listOf("/api/login", "/api/session"), seen)
        server.enqueue(MockResponse().setBody("mp3"))
        shared.newCall(Request.Builder().url(audioUrl(base, "e1", false, Engine.Chatterbox)).build()).execute().close()
        assertTrue(taken().getHeader("Cookie").orEmpty().startsWith("vg_login="))
    }

    @Test
    fun aSharedClientStillTellsMaybeSentApart() = runBlocking {
        val base = HttpGatewayApi.parseBase(server.url("/").toString())
        val shared = gatewayClient(base, store, Timeouts(readMillis = 500, callMillis = 5_000))
        val api = HttpGatewayApi(server.url("/").toString(), store, sharedClient = shared)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(ApiResult.MaybeSent, api.prompt("wt-scratch", "run the tests", voice = true))
        server.shutdown()
        assertEquals(ApiResult.NetworkFailure, api.prompt("wt-scratch", "run the tests", voice = true))
    }

    @Test
    fun getWithNoAnswerIsANetworkFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(ApiResult.NetworkFailure, api.session("wt-scratch"))
    }

    @Test
    fun postThatCannotConnectIsANetworkFailureNotMaybeSent() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val closed = HttpGatewayApi("http://127.0.0.1:$port", store, Timeouts(connectMillis = 1_000))
        assertEquals(ApiResult.NetworkFailure, closed.prompt("wt-scratch", "hi", voice = true))
    }

    @Test
    fun redirectsAreNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(303).addHeader("Location", "/login"))
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/api/prompt2"))
        answer("session_idle")
        assertEquals(ApiResult.Unexpected(303), api.session("wt-scratch"))
        assertEquals(ApiResult.Unexpected(307), api.prompt("wt-scratch", "hi", voice = true))
        Thread.sleep(200)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun unknownFieldsAreIgnoredAndBadBodiesAreUnexpected() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok": true, "draft": "clear", "brand_new": [1, {"x": 2}]}"""))
        assertEquals(ApiResult.Ok(PromptAnswer(true, "clear")), api.prompt("wt-scratch", "hi", voice = false))
        server.enqueue(MockResponse().setBody("""{"draft": "clear"}"""))
        assertEquals(ApiResult.Unexpected(200), api.prompt("wt-scratch", "hi", voice = false))
        server.enqueue(MockResponse().setBody("<html>"))
        assertEquals(ApiResult.Unexpected(200), api.sessions())
    }

    @Test
    fun baseUrlIsValidated() {
        for (bad in listOf("https://gateway.example.ts.net/app", "https://gateway.example.ts.net/?x=1",
            "https://u:p@gateway.example.ts.net", "https://gateway.example.ts.net/#f", "not a url",
            "ftp://gateway.example.ts.net")) {
            assertThrows(bad, IllegalArgumentException::class.java) { HttpGatewayApi(bad, store) }
        }
        HttpGatewayApi("https://gateway.example.ts.net", store)
        HttpGatewayApi("https://gateway.example.ts.net/", store)
    }
}
