package dev.nittala.voicegateway.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * Spec (A4): the JSON views of gateway.py that the app reads, as the server writes them (see TASKS.md "Wire format
 * for the Android client"). The test fixtures are produced from the real views by android/tools/make_fixtures.py.
 *
 * - Tolerant: any field the app does not use (`voice`, `local`, `other_url`, future fields) is ignored.
 * - Strict: a field the app depends on has no default, so a view without it fails to parse instead of being guessed.
 *   Nullable fields the server always writes (`orchestrator`, `screen_token`) are still required keys.
 * - Field names follow the wire (`screen_token` -> `screenToken`).
 */

/** The one [Json] used for every gateway body: unknown keys are ignored, nothing is coerced. */
val GatewayJson: Json = Json { ignoreUnknownKeys = true }

/**
 * What the app does with an event's `ask` field.
 * - absent -> [None] (an ordinary event: speak it, then listen as usual)
 * - `true` -> [Answer] (a spoken question: the mic reopens for the answer)
 * - `"confirm"`, or ANY other value (false, null, a number, another string) -> [Confirm] (tap-only: the mic stays
 *   off). Fail safe: a marker the app does not know never opens the mic.
 */
@Serializable(with = AskKindSerializer::class)
enum class AskKind { None, Answer, Confirm }

/** Pure: the [AskKind] of a present `ask` value, per [AskKind]'s rules. */
fun askKindOf(value: JsonElement): AskKind =
    if (value is JsonPrimitive && !value.isString && value.booleanOrNull == true) AskKind.Answer else AskKind.Confirm

/** Reads `ask` with [askKindOf]; writes the wire value back (`true`, `"confirm"`). Only used when the key exists. */
object AskKindSerializer : KSerializer<AskKind> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AskKind", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): AskKind =
        askKindOf((decoder as? JsonDecoder ?: error("JSON only")).decodeJsonElement())

    override fun serialize(encoder: Encoder, value: AskKind) {
        val wire = when (value) {
            AskKind.Answer -> JsonPrimitive(true)
            AskKind.Confirm -> JsonPrimitive("confirm")
            AskKind.None -> JsonNull
        }
        (encoder as? JsonEncoder ?: error("JSON only")).encodeJsonElement(wire)
    }
}

/**
 * An event's `kind`: `summary`, `needs-you`, `check-screen`, `progress`; any other value is [Unknown] and is not
 * dropped. [Progress] is a hint while the agent works (the agent's `PROGRESS[nonce]:` line): never final, never a
 * question, spoken only by the voice loop's progress rules (VoiceLoop).
 */
@Serializable(with = EventKindSerializer::class)
enum class EventKind(val wire: String) {
    Summary("summary"), NeedsYou("needs-you"), CheckScreen("check-screen"), Progress("progress"), Unknown("");

    companion object {
        /** Pure: the kind for a wire string; [Unknown] for anything not listed. */
        fun of(wire: String): EventKind = entries.firstOrNull { it != Unknown && it.wire == wire } ?: Unknown
    }
}

object EventKindSerializer : KSerializer<EventKind> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EventKind", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): EventKind = EventKind.of(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: EventKind) = encoder.encodeString(value.wire)
}

/**
 * The gateway's caps on progress lines (gateway.py `MAX_PROGRESS_CHARS`, `MAX_PROGRESS_EVENTS`,
 * `PROGRESS_MIN_GAP_SECONDS`), pinned against the server by ServerMirrorTest. The app trusts no longer text.
 */
object ProgressLine {
    const val MAX_CHARS = 100
    const val MAX_PER_SEND = 5
    const val MIN_GAP_SECONDS = 20
}

/** One unacknowledged announcement, in `events` of both views. `ask` only ever comes on a voice prompt's summary. */
@Serializable
data class Event(
    val id: String,
    val kind: EventKind,
    val session: String,
    val worktree: String,
    val text: String,
    val ask: AskKind = AskKind.None,
)

/** One agent row of a session. */
@Serializable
data class AgentRow(
    val name: String,
    val status: String,
    val orchestrator: Boolean,
    val kind: String = "",
    @SerialName("pane_label") val paneLabel: String = "",
)

/** One session in `GET /api/sessions`. `error` is set when Herdr could not be read for it. */
@Serializable
data class SessionRow(
    val name: String,
    val state: String,
    val agents: List<AgentRow> = emptyList(),
    val error: String? = null,
)

/** `GET /api/sessions`: sessions grouped by state, plus the announcements. */
@Serializable
data class SessionsView(
    val attached: List<SessionRow>,
    val running: List<SessionRow>,
    val stopped: List<SessionRow>,
    val events: List<Event>,
    val waiting: List<String>,
)

/** The orchestrator of a session detail. `status` is Herdr's (idle, done, working, blocked, unknown). */
@Serializable
data class OrchestratorInfo(
    val name: String,
    val kind: String,
    val status: String,
    @SerialName("pane_label") val paneLabel: String = "",
)

/** What may be done now. `keys` is empty unless blocked with a screen token. */
@Serializable
data class Actions(
    val prompt: Boolean,
    val keys: List<String>,
    val stop: Boolean,
    val restart: Boolean,
)

/**
 * `GET /api/session?name=`: one session's detail plus the announcements. [voices] lists the gateway engines that can
 * speak events now (`chatterbox`, `deepgram`), for `EngineChain.of`; absent reads as none (the phone's voice only).
 */
@Serializable
data class SessionView(
    val name: String,
    val state: String,
    val orchestrator: OrchestratorInfo?,
    val problem: String,
    val output: String,
    @SerialName("screen_token") val screenToken: String?,
    val draft: String,
    val actions: Actions,
    val events: List<Event>,
    val waiting: List<String>,
    val agents: List<AgentRow> = emptyList(),
    val voices: List<String> = emptyList(),
)

/** `POST /api/prompt` success. `draft` is `clear`, `present` or `unknown`. */
@Serializable
data class PromptAnswer(val ok: Boolean, val draft: String)

/** `{"ok": true}`: login, keys, stop, restart, announced. */
@Serializable
data class OkAnswer(val ok: Boolean)

/** `POST /api/transcribe` success. Empty text means Whisper heard nothing. */
@Serializable
data class TranscribeAnswer(val text: String)

/**
 * Any error body. Everything is optional: a request guard's body has only `error`, and a proxy in front of the
 * gateway may answer with something else entirely.
 */
@Serializable
data class ErrorBody(
    val error: String = "",
    val reason: String? = null,
    val status: String? = null,
    val output: String? = null,
    @SerialName("screen_token") val screenToken: String? = null,
    @SerialName("retry_after") val retryAfter: Int? = null,
    /** Only on `fetch_failed`: the downloaded-copy plan ([OfflinePlan]); read leniently by `refusalOf`. */
    val offline: kotlinx.serialization.json.JsonElement? = null,
)
