package dev.nittala.voicegateway.core.model

import dev.nittala.voicegateway.core.api.Fixtures
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec (W6, plan section 5 test 1): the new-worktree models read the gateway's real views (fixtures). */
class WorktreeModelsTest {
    private fun plan(name: String): PlanAnswer = planAnswerOf(Fixtures.load(name).bodyText)

    private fun create(name: String): CreateAnswer = GatewayJson.decodeFromString(Fixtures.load(name).bodyText)

    private val webApp = RepoRef("85dfc701aea8", "web-app", "code")

    @Test
    fun everyPlanAndCreateFixtureParses() {
        val plans = Fixtures.all("plan_")
        assertEquals(7, plans.size)
        plans.forEach { planAnswerOf(it.bodyText) }
        val creates = Fixtures.all("create_ok")
        assertEquals(5, creates.size)
        creates.forEach { GatewayJson.decodeFromString<CreateAnswer>(it.bodyText) }
    }

    @Test
    fun aReadyNewBranchWithADownloadedBase() {
        val p = plan("plan_new_ready") as PlanAnswer.Plan
        assertEquals(32, p.planId.length)
        assertEquals(webApp, p.repo)
        assertEquals("fix/login-timeout" to "new", p.branch to p.mode)
        assertTrue(p.ready)
        assertFalse(p.existing)
        assertEquals("~/worktrees/web-app/fix-login-timeout", p.path)
        assertEquals(300, p.expiresIn)
        val b = p.base
        assertTrue(b.applies)
        assertEquals("production-4.6.2" to "origin/production-4.6.2", b.name to b.ref)
        assertEquals("named", b.how)
        assertEquals(7, b.sha?.length)
        assertEquals("fix the session timeout", b.subject)
        assertEquals(1793523600L, b.committedAt)
        assertEquals(1793523600L, b.fetchedAt)
        assertTrue(b.local)
        assertEquals("production 4.6.2", b.said)
        assertEquals("", b.message)
        assertEquals(listOf("main", "develop", "uat"), b.choices)
    }

    @Test
    fun anExistingBranchHasOnlyTheBaseKeysTheServerSends() {
        val p = plan("plan_existing") as PlanAnswer.Plan
        assertTrue(p.existing)
        assertTrue(p.ready)
        assertFalse(p.base.applies)
        assertEquals("fix/login-timeout", p.base.ref)
        assertEquals("on develop", p.base.subject)
        assertTrue(p.base.message.contains("does not apply"))
        assertNull(p.base.name)
        assertTrue(p.base.choices.isEmpty())
        assertTrue(p.base.local)
    }

    @Test
    fun aPlanThatNeedsABaseAndOneWithCloseMatches() {
        val needs = plan("plan_needs_base") as PlanAnswer.Plan
        assertFalse(needs.ready)
        assertNull(needs.base.name)
        assertNull(needs.base.ref)
        assertNull(needs.base.sha)
        assertTrue(needs.base.message.startsWith("Pick a base"))
        assertEquals(setOf("develop", "uat", "main"), needs.base.choices.toSet())
        val close = plan("plan_close_matches") as PlanAnswer.Plan
        assertFalse(close.ready)
        assertEquals(
            listOf("production-4.6.0", "production-4.6.1", "production-4.6.2", "production-4.6.3"),
            close.base.choices.take(4).sorted(),
        )
    }

    @Test
    fun aBaseOnlyOnOriginIsNotLocalAndHasNoSubject() {
        val p = plan("plan_on_origin") as PlanAnswer.Plan
        assertTrue(p.ready)
        assertFalse(p.base.local)
        assertNull(p.base.subject)
        assertNull(p.base.fetchedAt)
        assertEquals("production-4.6.4", p.base.name)
    }

    @Test
    fun askAnswers() {
        val ask = plan("plan_ask_candidates") as PlanAnswer.Ask
        assertTrue(ask.understood)
        assertEquals("Which repo did you mean?", ask.question)
        assertEquals(listOf("web-app", "notes"), ask.candidates.map { it.name })
        assertNull(ask.reason)
        val lost = plan("plan_not_understood") as PlanAnswer.Ask
        assertFalse(lost.understood)
        assertEquals("the answer was not strict JSON", lost.reason)
        assertEquals(2, lost.candidates.size)
    }

    @Test
    fun aPlanBodyWithNoOrAnUnknownKindIsRefused() {
        val body = Fixtures.load("plan_new_ready").body
        for (kind in listOf(null, JsonPrimitive("brand_new"), JsonPrimitive(5), JsonNull)) {
            val changed = JsonObject(if (kind == null) body - "kind" else body + ("kind" to kind))
            assertThrows("$kind", SerializationException::class.java) { planAnswerOf(changed.toString()) }
        }
        assertThrows(SerializationException::class.java) { planAnswerOf("[1]") }
        assertThrows(SerializationException::class.java) { planAnswerOf("<html>") }
        assertThrows(SerializationException::class.java) { planAnswerOf(JsonObject(body - "plan_id").toString()) }
    }

    @Test
    fun unknownKeysAndValuesAreKept() {
        val body = Fixtures.load("plan_new_ready").body
        val changed = JsonObject(body + ("mode" to JsonPrimitive("someday")) + ("brand_new" to JsonPrimitive(1)))
        val p = planAnswerOf(changed.toString()) as PlanAnswer.Plan
        assertEquals("someday", p.mode)
        assertFalse(p.existing)
    }

    @Test
    fun createAnswers() {
        val ok = create("create_ok")
        assertEquals("wt-web-app-fix-login-timeout", ok.session)
        assertTrue(ok.ok && ok.listed)
        assertEquals("~/worktrees/web-app/fix-login-timeout", ok.path)
        assertEquals(CreatedBase("origin/production-4.6.2", ok.base!!.sha, "fix the session timeout", true, false, ok.base!!.sha), ok.base)
        assertNull(ok.launch)
        val moved = create("create_ok_moved").base!!
        assertTrue(moved.moved)
        assertEquals("hotfix after the plan", moved.subject)
        assertTrue(moved.sha != moved.plannedSha)
        assertNull(create("create_ok_existing").base)
        assertEquals(Launch("claude-opus-5-5", "high", true, false, "written"), create("create_ok_launch").launch)
        assertNull(create("create_ok_no_launch").launch)
    }

    @Test
    fun aMissingLaunchKeyIsNullButAMissingBaseKeyIsNot() {
        val body = Fixtures.load("create_ok").body
        assertNull(GatewayJson.decodeFromString<CreateAnswer>(JsonObject(body - "launch").toString()).launch)
        assertThrows(SerializationException::class.java) {
            GatewayJson.decodeFromString<CreateAnswer>(JsonObject(body - "base").toString())
        }
    }

    @Test
    fun setupViews() {
        val on = GatewayJson.decodeFromString<WorktreeSetup>(Fixtures.load("worktree_setup").bodyText)
        assertEquals(WorktreeSetup(true, true, 2, ""), on)
        val off = GatewayJson.decodeFromString<WorktreeSetup>(Fixtures.load("worktree_setup_off").bodyText)
        assertFalse(off.enabled)
        assertTrue(off.message.contains("VOICE_GATEWAY_REPO_ROOTS"))
    }
}
