package dev.nittala.voicegateway.core.api

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASKS.md "Orchestrator launch and new worktree from the app": every worktree refusal reason. */
val WORKTREE_REASONS: Set<String> = setOf(
    "bad_query", "bad_repo", "bad_plan", "bad_base", "repo_gone", "plan_gone", "worktrees_off", "interpreter_off",
    "no_repos", "plan_busy", "plan_expired", "create_busy", "repo_unlisted", "not_main_checkout", "branch_checked_out",
    "no_commit", "target_exists", "repo_changed", "branch_unusable", "base_unusable", "not_ready", "wt_missing",
    "wt_unrunnable", "git_failed", "fetch_failed", "wt_failed", "wt_timeout", "no_folder", "no_session_name",
)

/** Spec (A4): `refusalOf` reads every error body the real gateway writes (fixtures), and tolerates anything else. */
class RefusalsTest {
    private fun refusal(name: String): Refusal =
        Fixtures.load(name).let { refusalOf(it.status, it.bodyText, it.headers["Retry-After"]) }

    /** A refusal fixture's reason: its name without `refusal_` (and a variant's `_offline`). */
    private fun reasonOf(fixture: Fixture) = fixture.name.removePrefix("refusal_").removeSuffix("_offline")

    @Test
    fun everyS1ReasonHasAFixtureAndAnEntry() {
        val fromServer = Fixtures.all("refusal_").map(::reasonOf).toSet() - WORKTREE_REASONS
        val known = RefusalReason.entries.filter { it != RefusalReason.Unknown }.map { it.wire }.toSet() - WORKTREE_REASONS
        assertEquals(known, fromServer)
        assertEquals(27, fromServer.size)
    }

    @Test
    fun everyWorktreeReasonOfTheWireFormatHasAnEntry() {
        val known = RefusalReason.entries.map { it.wire }.toSet()
        assertEquals(29, WORKTREE_REASONS.size)
        assertEquals(emptySet<String>(), WORKTREE_REASONS - known)
        val fixtures = Fixtures.all("refusal_").map(::reasonOf).filter { it in WORKTREE_REASONS }.toSet()
        assertTrue(fixtures.toString(), fixtures.size >= 12)
    }

    @Test
    fun everyWorktreeReasonGatewayPyWritesIsAnEntry() {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !java.io.File(dir, "gateway.py").isFile) dir = dir.parentFile
        val source = java.io.File(requireNotNull(dir) { "gateway.py not found" }, "gateway.py").readText()
        val worktrees = source.substringAfter("class NewWorktrees:").substringBefore("\nclass ")
        val create = source.substringAfter("def _wt_for_create(").substringBefore("\n    def ")
        val written = Regex("""reason(?:=|", )"([a-z_]+)"""").findAll(worktrees + create).map { it.groupValues[1] }.toSet() +
            Regex(""""(wt_[a-z_]+)"""").findAll(create).map { it.groupValues[1] }
        assertEquals(WORKTREE_REASONS, written)
    }

    @Test
    fun everyRefusalFixtureMapsToItsReasonStatusAndMessage() {
        for (fixture in Fixtures.all("refusal_")) {
            val refusal = refusalOf(fixture.status, fixture.bodyText, fixture.headers["Retry-After"])
            assertEquals(fixture.name, reasonOf(fixture), refusal.reason?.wire)
            assertEquals(fixture.name, fixture.status, refusal.httpStatus)
            assertEquals(fixture.name, fixture.body.getValue("error").jsonPrimitive.content, refusal.message)
        }
    }

    @Test
    fun statusesByReasonMatchTheWireFormat() {
        val expected = mapOf(
            400 to setOf("bad_text", "voice_command", "audio_empty"),
            404 to setOf("not_listed", "speech_off"),
            409 to setOf("stopped", "no_orchestrator", "pane_changed", "not_settled", "not_blocked", "not_working",
                "draft_present", "screen_changed", "repeat_window", "action_running", "herdr_refused",
                "already_running", "restart_refused"),
            413 to setOf("audio_too_large"),
            415 to setOf("bad_audio_type"),
            422 to setOf("voice_noise"),
            429 to setOf("transcribe_busy", "transcribe_rate"),
            502 to setOf("herdr_timeout_maybe_sent", "herdr_failed", "speech_failed", "restart_failed"),
        )
        val actual = Fixtures.all("refusal_").filter { reasonOf(it) !in WORKTREE_REASONS }
            .groupBy({ it.status }, ::reasonOf).mapValues { it.value.toSet() }
        assertEquals(expected, actual)
    }

    @Test
    fun worktreeStatusesMatchTheWireFormat() {
        val byStatus = mapOf(
            400 to setOf("bad_query", "bad_repo", "bad_plan", "bad_base"),
            404 to setOf("repo_gone", "plan_gone"),
            409 to setOf("worktrees_off", "interpreter_off", "no_repos", "plan_busy", "plan_expired", "create_busy",
                "repo_unlisted", "not_main_checkout", "branch_checked_out", "no_commit", "target_exists",
                "repo_changed", "branch_unusable", "base_unusable", "not_ready", "wt_missing", "wt_unrunnable"),
            502 to setOf("git_failed", "fetch_failed", "wt_failed", "wt_timeout", "no_folder", "no_session_name"),
        )
        assertEquals(WORKTREE_REASONS, byStatus.values.flatten().toSet())
        for (fixture in Fixtures.all("refusal_").filter { reasonOf(it) in WORKTREE_REASONS }) {
            val status = byStatus.entries.single { reasonOf(fixture) in it.value }.key
            assertEquals(fixture.name, status, fixture.status)
        }
    }

    @Test
    fun offlineIsReadOnlyWithFetchFailed() {
        val offline = refusal("refusal_fetch_failed_offline")
        assertEquals(RefusalReason.FetchFailed, offline.reason)
        val plan = offline.offline!!
        assertEquals(32, plan.planId.length)
        assertEquals("origin/production-4.6.1", plan.ref)
        assertEquals("on production-4.6.1", plan.subject)
        assertEquals(7, plan.sha.length)
        assertNull(plan.fetchedAt)
        assertEquals(1793523600L, plan.committedAt)
        assertNull(refusal("refusal_fetch_failed").offline)
        val body = Fixtures.load("refusal_fetch_failed_offline").body
        val otherReason = kotlinx.serialization.json.JsonObject(body + ("reason" to kotlinx.serialization.json.JsonPrimitive("wt_failed")))
        assertNull(refusalOf(502, otherReason.toString()).offline)
        val broken = kotlinx.serialization.json.JsonObject(body + ("offline" to kotlinx.serialization.json.JsonPrimitive("x")))
        val tolerated = refusalOf(502, broken.toString())
        assertEquals(RefusalReason.FetchFailed, tolerated.reason)
        assertNull(tolerated.offline)
        assertEquals(body.getValue("error").jsonPrimitive.content, tolerated.message)
    }

    @Test
    fun extraFields() {
        assertEquals("working", refusal("refusal_not_settled").agentStatus)
        assertEquals("idle", refusal("refusal_not_blocked").agentStatus)
        assertEquals("idle", refusal("refusal_not_working").agentStatus)
        val changed = refusal("refusal_screen_changed")
        assertEquals(32, changed.screenToken?.length)
        assertEquals("Do you want to make this edit?\n❯ 1. Yes\n  2. No\n", changed.output)
        assertEquals(2, refusal("refusal_transcribe_busy").retryAfterSeconds)
        val rate = Fixtures.load("refusal_transcribe_rate")
        assertEquals(rate.headers.getValue("Retry-After").toInt(), refusal("refusal_transcribe_rate").retryAfterSeconds)
        assertNull(refusal("refusal_bad_text").agentStatus)
        assertNull(refusal("refusal_bad_text").retryAfterSeconds)
        assertNull(refusal("refusal_bad_text").screenToken)
    }

    @Test
    fun guardBodiesHaveNoReason() {
        val guards = Fixtures.all("guard_")
        assertEquals(setOf(400, 401, 403, 411, 413, 415), guards.map { it.status }.toSet())
        for (fixture in guards) {
            val refusal = refusalOf(fixture.status, fixture.bodyText)
            assertNull(fixture.name, refusal.reason)
            assertEquals(fixture.status, refusal.httpStatus)
        }
        assertEquals("body too large", refusal("guard_413_body").message)
    }

    @Test
    fun loginAnswers() {
        assertEquals(Refusal(401, null, "Wrong passcode."), refusal("login_wrong"))
        val locked = refusal("login_locked")
        assertEquals(429, locked.httpStatus)
        assertNull(locked.reason)
        assertEquals(31, locked.retryAfterSeconds)
    }

    @Test
    fun anythingElseIsToleratedWithoutAReason() {
        assertEquals(Refusal(502, null, ""), refusalOf(502, "<html>Bad gateway</html>"))
        assertEquals(Refusal(502, null, ""), refusalOf(502, ""))
        assertEquals(Refusal(500, null, ""), refusalOf(500, "[1, 2]"))
        assertEquals(RefusalReason.Unknown, refusalOf(409, """{"error":"x","reason":"brand_new","more":1}""").reason)
        assertEquals(7, refusalOf(429, "{}", "7").retryAfterSeconds)
        assertNull(refusalOf(429, "{}", "Wed, 21 Oct 2026 07:28:00 GMT").retryAfterSeconds)
        assertEquals(3, refusalOf(429, """{"retry_after":3}""", "9").retryAfterSeconds)
    }

    @Test
    fun reasonOfWire() {
        assertNull(RefusalReason.of(null))
        assertEquals(RefusalReason.HerdrTimeoutMaybeSent, RefusalReason.of("herdr_timeout_maybe_sent"))
        assertEquals(RefusalReason.Unknown, RefusalReason.of(""))
        assertEquals(RefusalReason.Unknown, RefusalReason.of("BAD_TEXT"))
    }
}
