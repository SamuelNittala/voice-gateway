package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.audio.AudioCapture
import dev.nittala.voicegateway.audio.AudioFocusGate
import dev.nittala.voicegateway.audio.CaptureEvent
import dev.nittala.voicegateway.audio.FocusEvent
import dev.nittala.voicegateway.audio.FocusResult
import dev.nittala.voicegateway.core.api.ApiResult
import dev.nittala.voicegateway.core.api.GatewayApi
import dev.nittala.voicegateway.core.api.HttpGatewayApi
import dev.nittala.voicegateway.core.loop.EarconKind
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
import dev.nittala.voicegateway.core.speech.DetectorConfig
import dev.nittala.voicegateway.core.speech.EndReason
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.speech.EngineChain
import dev.nittala.voicegateway.core.speech.Utterance
import dev.nittala.voicegateway.core.speech.UtteranceStats
import dev.nittala.voicegateway.core.speech.Wav
import dev.nittala.voicegateway.playback.PlaybackEvent
import dev.nittala.voicegateway.playback.Speaker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One recorded gateway answer (android/core/src/test/resources/fixtures, from the real gateway.py views). */
data class Fixture(val status: Int, val headers: Map<String, String>, val body: JsonObject)

object GatewayFixtures {
    fun load(name: String): Fixture {
        val text = requireNotNull(GatewayFixtures::class.java.getResource("/fixtures/$name.json")) { "no fixture $name" }
            .readText()
        val root = Json.parseToJsonElement(text).jsonObject
        return Fixture(
            status = root.getValue("status").jsonPrimitive.int,
            headers = root.getValue("headers").jsonObject.mapValues { it.value.jsonPrimitive.content },
            body = root.getValue("body").jsonObject,
        )
    }

    fun view(name: String): SessionView = GatewayJson.decodeFromString(SessionView.serializer(), load(name).body.toString())

    /** [name]'s view with the `ask` of its one event replaced by [ask] (a value the server might send one day). */
    fun viewWithAsk(name: String, ask: String): SessionView {
        val body = load(name).body
        val events = body.getValue("events").jsonArray.map {
            JsonObject(it.jsonObject + ("ask" to JsonPrimitive(ask)))
        }
        val changed = JsonObject(body + ("events" to kotlinx.serialization.json.JsonArray(events)))
        return GatewayJson.decodeFromString(SessionView.serializer(), changed.toString())
    }

    fun sessions(name: String = "sessions"): SessionsView =
        GatewayJson.decodeFromString(SessionsView.serializer(), load(name).body.toString())

    /** The answer the real client gives for [name] ([HttpGatewayApi.resultOf], the same mapping). */
    fun <T> result(name: String, login: Boolean = false, decode: (String) -> T): ApiResult<T> {
        val f = load(name)
        return HttpGatewayApi.resultOf(f.status, f.body.toString(), f.headers["Retry-After"], login, decode)
    }
}

/**
 * [GatewayApi] replaying the real fixtures, with the server's acknowledge rule (acknowledged events disappear from
 * later views). Records every call by route.
 */
class FakeGatewayApi : GatewayApi {
    var view: SessionView = GatewayFixtures.view("session_idle")
    var sessionsView: SessionsView = GatewayFixtures.sessions()

    /** Fixture names (or [MAYBE_SENT], [NOT_SENT]) answering the next prompts; empty -> `prompt_ok`. */
    val promptAnswers = ArrayDeque<String>()

    /** Texts the next transcriptions give (in the `transcribe_ok` shape); empty -> its own text. */
    val transcripts = ArrayDeque<String>()
    var loginAnswer = "login_ok"
    var offline = false
    var loggedOut = false

    val calls = mutableListOf<String>()
    val sessionNames = mutableListOf<String>()
    val prompts = mutableListOf<Triple<String, String, Boolean>>()
    /** The tap mark of each entry of [prompts], in the same order. */
    val confirmTaps = mutableListOf<Boolean>()
    val acked = mutableListOf<String>()
    val keys = mutableListOf<Pair<String, String?>>()
    var transcribes = 0
    var logins = 0

    fun show(fixture: String) {
        view = GatewayFixtures.view(fixture)
    }

    private fun <T> gate(route: String): ApiResult<T>? {
        calls += route
        return when {
            offline -> ApiResult.NetworkFailure
            loggedOut -> ApiResult.LoggedOut
            else -> null
        }
    }

    override suspend fun login(passcode: String): ApiResult<Unit> {
        logins++
        return gate("login") ?: GatewayFixtures.result(loginAnswer, login = true) {}
    }

    override suspend fun sessions(): ApiResult<SessionsView> = gate("sessions") ?: ApiResult.Ok(sessionsView)

    override suspend fun session(name: String, earlier: Boolean): ApiResult<SessionView> {
        sessionNames += name
        return gate("session") ?: ApiResult.Ok(view.copy(name = name, events = view.events.filter { it.id !in acked }))
    }

    override suspend fun prompt(
        session: String, text: String, voice: Boolean, confirmTap: Boolean,
    ): ApiResult<PromptAnswer> {
        gate<PromptAnswer>("prompt")?.let { return it }
        prompts += Triple(session, text, voice)
        confirmTaps += confirmTap
        return when (val answer = promptAnswers.removeFirstOrNull() ?: "prompt_ok") {
            MAYBE_SENT -> ApiResult.MaybeSent
            NOT_SENT -> ApiResult.NetworkFailure
            else -> GatewayFixtures.result(answer) { GatewayJson.decodeFromString(PromptAnswer.serializer(), it) }
        }
    }

    override suspend fun keys(session: String, key: String, screenToken: String?): ApiResult<Unit> {
        gate<Unit>("keys")?.let { return it }
        keys += key to screenToken
        return GatewayFixtures.result("keys_ok") { GatewayJson.decodeFromString(OkAnswer.serializer(), it); Unit }
    }

    override suspend fun stop(session: String): ApiResult<Unit> =
        gate("stop") ?: GatewayFixtures.result("stop_ok") {}

    override suspend fun restart(session: String): ApiResult<Unit> =
        gate("restart") ?: GatewayFixtures.result("restart_ok") {}

    override suspend fun announced(ids: List<String>): ApiResult<Unit> {
        gate<Unit>("announced")?.let { return it }
        acked += ids
        return GatewayFixtures.result("announced_ok") {}
    }

    /** Fixture names answering the next plans and re-plans; empty -> `plan_new_ready`. */
    val planAnswers = ArrayDeque<String>()

    /** Fixture names (or [MAYBE_SENT], [NOT_SENT]) answering the next creates; empty -> `create_ok`. */
    val createAnswers = ArrayDeque<String>()
    val plans = mutableListOf<Pair<String, String?>>()
    val replans = mutableListOf<Pair<String, String>>()
    val creates = mutableListOf<String>()

    override suspend fun worktreeSetup(): ApiResult<WorktreeSetup> =
        gate("worktree_setup") ?: GatewayFixtures.result("worktree_setup") { GatewayJson.decodeFromString(WorktreeSetup.serializer(), it) }

    override suspend fun plan(query: String, repoId: String?): ApiResult<PlanAnswer> {
        gate<PlanAnswer>("plan")?.let { return it }
        plans += query to repoId
        return GatewayFixtures.result(planAnswers.removeFirstOrNull() ?: "plan_new_ready", decode = ::planAnswerOf)
    }

    override suspend fun replan(planId: String, base: String): ApiResult<PlanAnswer> {
        gate<PlanAnswer>("replan")?.let { return it }
        replans += planId to base
        return GatewayFixtures.result(planAnswers.removeFirstOrNull() ?: "plan_new_ready", decode = ::planAnswerOf)
    }

    override suspend fun create(planId: String): ApiResult<CreateAnswer> {
        gate<CreateAnswer>("create")?.let { return it }
        creates += planId
        return when (val answer = createAnswers.removeFirstOrNull() ?: "create_ok") {
            MAYBE_SENT -> ApiResult.MaybeSent
            NOT_SENT -> ApiResult.NetworkFailure
            else -> GatewayFixtures.result(answer) { GatewayJson.decodeFromString(CreateAnswer.serializer(), it) }
        }
    }

    override suspend fun transcribe(audio: ByteArray, contentType: String): ApiResult<TranscribeAnswer> {
        gate<TranscribeAnswer>("transcribe")?.let { return it }
        transcribes++
        val ok = GatewayFixtures.result("transcribe_ok") { GatewayJson.decodeFromString(TranscribeAnswer.serializer(), it) }
        val text = transcripts.removeFirstOrNull() ?: return ok
        return ApiResult.Ok(TranscribeAnswer(text))
    }

    companion object {
        const val MAYBE_SENT = "maybe-sent"
        const val NOT_SENT = "not-sent"
    }
}

/** A loud utterance passing G1 and G2 with a voiced time long enough for G3's word rate (3.2 s voiced). */
fun spokenUtterance(): Utterance {
    val pcm = ShortArray(16_000) { if (it % 2 == 0) 8_000 else -8_000 }
    return Utterance(pcm, UtteranceStats(0, 120, 100, 0, 32.0), EndReason.Silence)
}

/** [AudioCapture] for scenarios: records each start's config; [speak] only works while a session runs. */
class ScenarioCapture(private val journal: MutableList<String>) : AudioCapture {
    private val channel = Channel<CaptureEvent>(Channel.UNLIMITED)
    override val events: Flow<CaptureEvent> = channel.receiveAsFlow()
    val configs = mutableListOf<DetectorConfig>()
    var running = false
        private set
    var finishes = 0

    override fun start(config: DetectorConfig): Boolean {
        if (running) return false
        running = true
        configs += config
        journal += "capture start"
        return true
    }

    override fun finishUtterance() {
        finishes++
    }

    override fun stop() {
        if (running) journal += "capture stop"
        running = false
    }

    /** The user speaks: speech start, then a finished utterance. False (nothing happens) when the mic is off. */
    fun speak(utterance: Utterance = spokenUtterance()): Boolean {
        if (!running) return false
        channel.trySend(CaptureEvent.SpeechStarted(0.8f))
        channel.trySend(CaptureEvent.UtteranceReady(Wav.encode(utterance.pcm), 0, utterance.stats.voicedMs, utterance))
        running = false
        return true
    }

    /** Any capture event, only while a session runs. */
    fun hear(event: CaptureEvent): Boolean {
        if (!running) return false
        channel.trySend(event)
        if (event.endsSession) running = false
        return true
    }
}

/** The listening [AudioFocusGate] for scenarios: journals acquire/release; [lose] reports a loss while held. */
class ScenarioFocus(private val journal: MutableList<String>, var grant: Boolean = true) : AudioFocusGate {
    private val channel = Channel<FocusEvent>(Channel.UNLIMITED)
    override val events: Flow<FocusEvent> = channel.receiveAsFlow()
    var held = false
        private set

    override fun acquire(): FocusResult {
        if (held) return FocusResult.Granted
        if (!grant) return FocusResult.Denied
        held = true
        journal += "focus acquire"
        return FocusResult.Granted
    }

    override fun release() {
        if (held) journal += "focus release"
        held = false
    }

    fun lose(permanent: Boolean) {
        if (held) channel.trySend(FocusEvent.Lost(permanent))
    }
}

/** [Speaker] for scenarios: every play lasts [playMs] unless stopped; journals each play. */
class ScenarioSpeaker(private val journal: MutableList<String>, var playMs: Long = 3_000) : Speaker {
    val played = mutableListOf<String>()
    val chains = mutableListOf<EngineChain>()
    var failEverything = false
    private val halts = MutableStateFlow(0L)
    override var muted: Boolean = false
        set(value) {
            field = value
            if (value) halt()
        }

    override fun play(event: dev.nittala.voicegateway.core.model.Event, withName: Boolean, engineChain: EngineChain) =
        channelFlow {
            if (muted) {
                send(PlaybackEvent.Interrupted)
                return@channelFlow
            }
            val mark = halts.value
            journal += "play ${event.id}"
            played += event.id
            chains += engineChain
            if (failEverything) {
                send(PlaybackEvent.Failed(Engine.Phone, "no voice"))
                return@channelFlow
            }
            send(PlaybackEvent.Started(engineChain.current ?: Engine.Phone))
            val halted = withTimeoutOrNull(playMs) { halts.first { it > mark } } != null
            send(if (halted) PlaybackEvent.Interrupted else PlaybackEvent.Finished)
        }

    override fun interrupt() = halt()

    override fun stop() = halt()

    /** Another app took the audio focus mid-play (not asked by the loop). */
    fun loseFocus() = halt()

    private fun halt() = halts.update { it + 1 }
}

/** Phone voice that takes [sayMs] per line and journals it. */
class ScenarioVoice(private val journal: MutableList<String>, private val sayMs: Long = 500) : PhoneVoice {
    val lines = mutableListOf<String>()
    override suspend fun say(text: String) {
        journal += "say $text"
        lines += text
        delay(sayMs)
    }
}

class ScenarioEarcons(private val journal: MutableList<String>) : Earcons {
    val kinds = mutableListOf<EarconKind>()
    override suspend fun play(kind: EarconKind) {
        journal += "earcon $kind"
        kinds += kind
        delay(100)
    }
}

class ScenarioService : ServiceControl {
    var starts = 0
    var stops = 0
    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }
}

class ScenarioNetwork : NetworkMonitor {
    val state = MutableStateFlow(true)
    override val online: Flow<Boolean> = state
}
