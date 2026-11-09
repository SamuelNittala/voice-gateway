package dev.nittala.voicegateway.core.model

import dev.nittala.voicegateway.core.api.Fixtures
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec (A4): the models read the gateway's real views (fixtures), ignore unknown fields, require the used ones. */
class ModelsTest {
    private inline fun <reified T> parse(name: String): T = GatewayJson.decodeFromString(Fixtures.load(name).bodyText)

    private fun onlyEvent(name: String): Event = parse<SessionView>(name).events.single()

    @Test
    fun sessionsView() {
        val view = parse<SessionsView>("sessions")
        assertEquals(listOf("wt-api"), view.attached.map { it.name })
        assertEquals(listOf("wt-scratch"), view.running.map { it.name })
        assertEquals(listOf("wt-old"), view.stopped.map { it.name })
        assertEquals("stopped", view.stopped.single().state)
        assertTrue(view.stopped.single().agents.isEmpty())
        val agent = view.attached.single().agents.single()
        assertEquals(AgentRow("builder", "working", true, "claude", ""), agent)
        assertNull(view.running.single().error)
        assertEquals(EventKind.Summary, view.events.single().kind)
        assertEquals(emptyList<String>(), view.waiting)
    }

    @Test
    fun voicesListsTheGatewayEnginesForTheEngineChain() {
        assertEquals(emptyList<String>(), parse<SessionView>("session_idle").voices)
        val body = Fixtures.load("session_idle").body.toMutableMap()
        body["voices"] = kotlinx.serialization.json.JsonArray(
            listOf(kotlinx.serialization.json.JsonPrimitive("chatterbox"), kotlinx.serialization.json.JsonPrimitive("deepgram")),
        )
        val view = GatewayJson.decodeFromString<SessionView>(kotlinx.serialization.json.JsonObject(body).toString())
        assertEquals(listOf("chatterbox", "deepgram"), view.voices)
    }

    @Test
    fun idleSession() {
        val view = parse<SessionView>("session_idle")
        assertEquals("wt-scratch", view.name)
        assertEquals("running", view.state)
        assertEquals("idle", view.orchestrator?.status)
        assertEquals(Actions(prompt = true, keys = emptyList(), stop = false, restart = false), view.actions)
        assertNull(view.screenToken)
        assertTrue(view.events.isEmpty())
    }

    @Test
    fun workingSession() {
        val view = parse<SessionView>("session_working")
        assertEquals("working", view.orchestrator?.status)
        assertTrue(view.actions.stop)
        assertFalse(view.actions.prompt)
        assertEquals("Reading files...\nRunning the tests", view.output)
    }

    @Test
    fun blockedSessionHasKeysAndAToken() {
        val view = parse<SessionView>("session_blocked")
        assertEquals("blocked", view.orchestrator?.status)
        assertEquals(listOf("1", "2", "up", "down", "enter", "esc"), view.actions.keys)
        assertEquals(32, view.screenToken?.length)
        assertTrue(view.output.contains("Do you want to make this edit?"))
    }

    @Test
    fun stoppedSessionHasNoOrchestrator() {
        val view = parse<SessionView>("session_stopped")
        assertEquals("stopped", view.state)
        assertNull(view.orchestrator)
        assertTrue(view.actions.restart)
        assertEquals("", view.output)
    }

    @Test
    fun eventsOfEachKind() {
        assertEquals(
            Event(onlyEvent("event_summary").id, EventKind.Summary, "wt-scratch", "scratch", "It is two."),
            onlyEvent("event_summary"),
        )
        assertEquals(EventKind.NeedsYou to "scratch needs you", onlyEvent("event_needs_you").let { it.kind to it.text })
        assertEquals(
            EventKind.CheckScreen to "scratch finished, check the screen",
            onlyEvent("event_check_screen").let { it.kind to it.text },
        )
    }

    @Test
    fun progressEvents() {
        val line = onlyEvent("event_progress")
        assertEquals(EventKind.Progress to "Reading the failing test", line.kind to line.text)
        assertEquals(AskKind.None, line.ask)
        assertEquals(EventKind.Progress, EventKind.of("progress"))
        assertEquals("working", parse<SessionView>("event_progress").orchestrator?.status)
        assertEquals(listOf("wt-scratch"), parse<SessionView>("event_progress").waiting)
        // The reply's view: the unheard progress line was dropped by the gateway.
        assertEquals(EventKind.Summary, onlyEvent("event_progress_then_summary").kind)
        assertTrue(onlyEvent("event_progress_then_summary").text.startsWith("Fixed the test."))
    }

    @Test
    fun askOnEvents() {
        assertEquals(AskKind.None, onlyEvent("event_summary").ask)
        assertEquals(AskKind.None, onlyEvent("event_voice_summary").ask)
        assertEquals(AskKind.Answer to "Did you mean wt-api or wt-web?", onlyEvent("event_ask_true").let { it.ask to it.text })
        assertEquals(AskKind.Confirm to "Delete branch wt-old?", onlyEvent("event_ask_confirm").let { it.ask to it.text })
    }

    @Test
    fun anyOtherAskValueIsConfirm() {
        assertEquals(AskKind.Answer, askKindOf(JsonPrimitive(true)))
        assertEquals(AskKind.Confirm, askKindOf(JsonPrimitive("confirm")))
        for (other in listOf(JsonPrimitive(false), JsonNull, JsonPrimitive("maybe"), JsonPrimitive(1),
            JsonPrimitive("true"), JsonArray(emptyList()), JsonObject(emptyMap()))) {
            assertEquals(other.toString(), AskKind.Confirm, askKindOf(other))
        }
        val event = withEventField("ask", JsonPrimitive("later"))
        assertEquals(AskKind.Confirm, GatewayJson.decodeFromString<SessionView>(event.toString()).events.single().ask)
        val nullAsk = withEventField("ask", JsonNull)
        assertEquals(AskKind.Confirm, GatewayJson.decodeFromString<SessionView>(nullAsk.toString()).events.single().ask)
    }

    @Test
    fun unknownEventKindIsKept() {
        val body = withEventField("kind", JsonPrimitive("something-new"))
        assertEquals(EventKind.Unknown, GatewayJson.decodeFromString<SessionView>(body.toString()).events.single().kind)
        assertEquals(EventKind.NeedsYou, EventKind.of("needs-you"))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val body = Fixtures.load("event_ask_true").body
        val extra = JsonObject(mapOf("brand_new" to JsonObject(mapOf("x" to JsonPrimitive(1)))))
        val events = JsonArray(body.getValue("events").jsonArray.map { JsonObject(it.jsonObject + extra) })
        val orchestrator = JsonObject(body.getValue("orchestrator").jsonObject + extra)
        val wider = JsonObject(body + extra + mapOf("events" to events, "orchestrator" to orchestrator))
        val view = GatewayJson.decodeFromString<SessionView>(wider.toString())
        assertEquals(AskKind.Answer, view.events.single().ask)
        val sessions = JsonObject(Fixtures.load("sessions").body + extra)
        assertEquals(1, GatewayJson.decodeFromString<SessionsView>(sessions.toString()).running.size)
    }

    @Test
    fun usedFieldsAreRequired() {
        val idle = Fixtures.load("session_idle").body
        for (field in listOf("actions", "events", "waiting", "orchestrator", "screen_token", "output", "state")) {
            val without = JsonObject(idle - field)
            assertThrows(field, SerializationException::class.java) {
                GatewayJson.decodeFromString<SessionView>(without.toString())
            }
        }
        val event = Fixtures.load("event_summary").body
        val noText = JsonObject(event + ("events" to JsonArray(event.getValue("events").jsonArray.map {
            JsonObject(it.jsonObject - "text")
        })))
        assertThrows(SerializationException::class.java) { GatewayJson.decodeFromString<SessionView>(noText.toString()) }
        val sessions = JsonObject(Fixtures.load("sessions").body - "stopped")
        assertThrows(SerializationException::class.java) { GatewayJson.decodeFromString<SessionsView>(sessions.toString()) }
    }

    @Test
    fun answers() {
        assertEquals(PromptAnswer(ok = true, draft = "unknown"), parse<PromptAnswer>("prompt_ok"))
        for (name in listOf("login_ok", "keys_ok", "stop_ok", "restart_ok", "announced_ok")) {
            assertEquals(name, OkAnswer(true), parse<OkAnswer>(name))
        }
        assertEquals(TranscribeAnswer("run the tests in the api worktree"), parse<TranscribeAnswer>("transcribe_ok"))
    }

    private fun withEventField(name: String, value: kotlinx.serialization.json.JsonElement): JsonObject {
        val body = Fixtures.load("event_summary").body
        val events = JsonArray(body.getValue("events").jsonArray.map { JsonObject(it.jsonObject + (name to value)) })
        return JsonObject(body + ("events" to events))
    }
}
