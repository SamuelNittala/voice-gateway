package dev.nittala.voicegateway.core.ui

import dev.nittala.voicegateway.core.api.Fixtures
import dev.nittala.voicegateway.core.api.refusalOf
import dev.nittala.voicegateway.core.loop.ASK
import dev.nittala.voicegateway.core.loop.CREATED
import dev.nittala.voicegateway.core.loop.CapturePurpose
import dev.nittala.voicegateway.core.loop.EXISTING
import dev.nittala.voicegateway.core.loop.ErrorKind
import dev.nittala.voicegateway.core.loop.GOOD
import dev.nittala.voicegateway.core.loop.HeldReason
import dev.nittala.voicegateway.core.loop.NEEDS_BASE
import dev.nittala.voicegateway.core.loop.PauseReason
import dev.nittala.voicegateway.core.loop.Phase
import dev.nittala.voicegateway.core.loop.QUERY
import dev.nittala.voicegateway.core.loop.QUESTION
import dev.nittala.voicegateway.core.loop.READY
import dev.nittala.voicegateway.core.loop.SESSION
import dev.nittala.voicegateway.core.loop.Sent
import dev.nittala.voicegateway.core.loop.TEXT
import dev.nittala.voicegateway.core.loop.WT
import dev.nittala.voicegateway.core.loop.createFixture
import dev.nittala.voicegateway.core.loop.event
import dev.nittala.voicegateway.core.loop.nw
import dev.nittala.voicegateway.core.loop.planFixture
import dev.nittala.voicegateway.core.loop.st
import dev.nittala.voicegateway.core.model.PlanAnswer
import dev.nittala.voicegateway.core.worktree.WorktreeFlow
import dev.nittala.voicegateway.core.worktree.WorktreeLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Spec (W6, plan section 5 test 8): the plan card and the flow's screen, from the real gateway answers. */
class VoiceUiMapperWorktreeTest {
    private fun ui(phase: Phase, muted: Boolean = false) =
        voiceUiState(st(phase).copy(muted = muted), zone = ZoneOffset.UTC)

    private fun card(flow: WorktreeFlow.Card): CardUi = (ui(nw(flow)).newWorktree as NewWorktreeUi.Card).card

    private fun cardOf(plan: PlanAnswer.Plan, listening: Boolean = false, expired: Boolean = false) =
        card(WorktreeFlow.Card(plan, QUERY, voice = true, listening = listening, expired = expired))

    @Test
    fun aReadyNewBranchCardShowsWhatTheWebPageShows() {
        val c = cardOf(READY, listening = true)
        assertEquals("web-app (code)", c.repo)
        assertEquals("Branch: fix/login-timeout", c.branch)
        assertEquals("New branch from origin/production-4.6.2", c.mode)
        assertEquals("${READY.base.sha} fix the session timeout; local copy: downloaded 2026-11-01 09:00", c.baseLine)
        assertNull(c.note)
        assertEquals("Fetched from origin just before creating.", c.fetchNote)
        assertEquals(listOf("main", "develop", "uat"), c.choices)
        assertEquals("production-4.6.2", c.currentChoice)
        assertTrue(c.otherBase)
        assertEquals("~/worktrees/web-app/fix-login-timeout", c.path)
        assertTrue(c.createEnabled)
        assertEquals("Create", c.createLabel)
        assertFalse(c.expired)
        assertEquals(300, c.expiresInSeconds)
        assertTrue(c.listening)
        assertEquals(WorktreeLines.SAY_CREATE_OR_CANCEL, c.hint)
        assertFalse(c.greyed)
    }

    @Test
    fun anExistingBranchCard() {
        val c = cardOf(EXISTING)
        assertEquals("Existing branch, checked out as it is (the base does not apply)", c.mode)
        assertEquals("${EXISTING.base.sha} on develop", c.baseLine)
        assertEquals(EXISTING.base.message, c.note)
        assertNull(c.fetchNote)
        assertTrue(c.choices.isEmpty())
        assertFalse(c.otherBase)
        assertTrue(c.createEnabled)
        assertNull(c.hint)
    }

    @Test
    fun createIsDisabledWhenNotReadyOrExpired() {
        val needs = cardOf(NEEDS_BASE)
        assertEquals("New branch: pick a base", needs.mode)
        assertNull(needs.baseLine)
        assertTrue(needs.note!!.startsWith("Pick a base"))
        assertNull(needs.fetchNote)
        assertNull(needs.currentChoice)
        assertFalse(needs.createEnabled)
        val expired = cardOf(READY, expired = true)
        assertFalse(expired.createEnabled)
        assertTrue(expired.expired)
        assertEquals("Plan again", expired.createLabel)
    }

    @Test
    fun aBaseOnOriginOnlyAndHowItWasChosen() {
        val onOrigin = planFixture("plan_on_origin") as PlanAnswer.Plan
        assertEquals("${onOrigin.base.sha} on origin, not downloaded here yet", cardOf(onOrigin).baseLine)
        val remembered = READY.copy(base = READY.base.copy(how = "remembered"))
        assertEquals("New branch from origin/production-4.6.2 (last used here)", cardOf(remembered).mode)
        val head = READY.copy(base = READY.base.copy(how = "origin-head", fetchedAt = null))
        assertEquals("New branch from origin/production-4.6.2 (origin/HEAD)", cardOf(head).mode)
        assertTrue(cardOf(head).baseLine!!.endsWith("local copy: commit of 2026-11-01 09:00"))
    }

    @Test
    fun theFlowsScreens() {
        assertEquals(NewWorktreeUi.Request(listening = true), ui(nw(WorktreeFlow.Request(true))).newWorktree)
        val planning = ui(nw(WorktreeFlow.Planning(QUERY, true, previous = WorktreeFlow.Card(READY, QUERY, true))))
        val p = planning.newWorktree as NewWorktreeUi.Planning
        assertEquals(QUERY, p.query)
        assertTrue(p.previous!!.greyed)
        assertFalse(p.previous!!.createEnabled)
        val choosing = ui(nw(WorktreeFlow.Choosing(QUERY, true, ASK))).newWorktree as NewWorktreeUi.Choosing
        assertEquals("Which repo did you mean?", choosing.question)
        assertEquals(listOf(CandidateUi("85dfc701aea8", "web-app (code)"), CandidateUi("c4cfe063a1bc", "notes (code/group)")), choosing.candidates)
        val lost = planFixture("plan_not_understood") as PlanAnswer.Ask
        val lostUi = ui(nw(WorktreeFlow.Choosing(QUERY, true, lost))).newWorktree as NewWorktreeUi.Choosing
        assertEquals("${lost.question} (${lost.reason})", lostUi.question)
        val creating = ui(nw(WorktreeFlow.Creating(READY.planId, QUERY, true, WorktreeFlow.Card(READY, QUERY, true))))
        assertEquals("web-app (code)", (creating.newWorktree as NewWorktreeUi.Creating).card!!.repo)
        assertFalse((creating.newWorktree as NewWorktreeUi.Creating).card!!.createEnabled)
    }

    @Test
    fun aFailedFetchOffersTheDownloadedCopyWithItsDate() {
        val r = Fixtures.load("refusal_fetch_failed_offline").let { refusalOf(it.status, it.bodyText) }
        val f = ui(nw(WorktreeFlow.Failed(r.message, QUERY, true, r.offline))).newWorktree as NewWorktreeUi.Failed
        assertEquals(r.message, f.message)
        assertEquals("Create from the downloaded copy (dated 2026-11-01 09:00)", f.offlineLabel)
        assertTrue(f.canPlanAgain)
        assertFalse(f.openPicker)
        val maybe = ui(nw(WorktreeFlow.Failed(WorktreeLines.NOT_SURE_CREATED, null, true, maybeCreated = true)))
        val m = maybe.newWorktree as NewWorktreeUi.Failed
        assertNull(m.offlineLabel)
        assertFalse(m.canPlanAgain)
        assertTrue(m.openPicker)
    }

    @Test
    fun theHandoffSaysWhereItStartedAndWhatWtLaunched() {
        val h = ui(nw(WorktreeFlow.Handoff(SESSION, QUERY, true, CREATED))).newWorktree as NewWorktreeUi.Handoff
        assertEquals(SESSION, h.session)
        assertEquals(QUERY, h.request)
        assertEquals("Started from origin/production-4.6.2 at ${CREATED.base!!.sha} fix the session timeout (fetched just now).", h.started)
        assertNull(h.launch)
        val moved = createFixture("create_ok_moved")
        val mh = ui(nw(WorktreeFlow.Handoff(SESSION, QUERY, true, moved))).newWorktree as NewWorktreeUi.Handoff
        assertTrue(mh.started!!.endsWith("(fetched just now; the plan showed ${moved.base!!.plannedSha})."))
        val launched = createFixture("create_ok_launch")
        val lh = ui(nw(WorktreeFlow.Handoff(SESSION, QUERY, true, launched))).newWorktree as NewWorktreeUi.Handoff
        assertEquals("Orchestrator: claude-opus-5-5, effort high, playbook yes, settings no, trust written", lh.launch)
        val existing = ui(nw(WorktreeFlow.Handoff(SESSION, QUERY, true, createFixture("create_ok_existing"))))
        assertNull((existing.newWorktree as NewWorktreeUi.Handoff).started)
    }

    @Test
    fun theCardStaysOnScreenWhileItsReplyIsHeardAndTheMicShows() {
        val back = nw(WorktreeFlow.Card(READY, QUERY, true, listening = true))
        val capturing = ui(Phase.Capturing(WT, purpose = CapturePurpose.Worktree(back)))
        assertEquals(VoiceMode.Capturing, capturing.mode)
        assertTrue(capturing.newWorktree is NewWorktreeUi.Card)
        assertTrue(capturing.micOn)
        val transcribing = ui(Phase.Transcribing(WT, GOOD, purpose = CapturePurpose.Worktree(back)))
        assertTrue(transcribing.newWorktree is NewWorktreeUi.Card)
        assertTrue(ui(nw(WorktreeFlow.Request(true))).micOn)
        assertFalse(ui(nw(WorktreeFlow.Request(false))).micOn)
        assertTrue(ui(back).micOn)
    }

    @Test
    fun newWorktreeIsOfferedExactlyWhereTheLoopTakesIt() {
        val offered = listOf(
            Phase.Idle, Phase.Listening, Phase.AwaitingAnswer(QUESTION), Phase.Paused(PauseReason.User),
            Phase.Held(TEXT, HeldReason.AgentBusy), Phase.Error(ErrorKind.PaneChanged, "x"), Phase.Stopped(),
            Phase.AgentWorking(Sent(TEXT)),
        )
        val not = listOf(
            Phase.Capturing(WT), Phase.Transcribing(WT, GOOD), Phase.Sending(WT, TEXT),
            Phase.Speaking(event("e1"), Phase.Listening), Phase.NeedsYou("t", "o"), Phase.LoggedOut,
            nw(WorktreeFlow.Request(true)),
        )
        offered.forEach { assertTrue("$it", ui(it).canStartNewWorktree) }
        not.forEach { assertFalse("$it", ui(it).canStartNewWorktree) }
        offered.filterNot { it is Phase.NewWorktree }.forEach { assertNull("$it", ui(it).newWorktree) }
        assertFalse(ui(nw(WorktreeFlow.Request(true))).canType)
    }

    @Test
    fun theFlowHasTitlesAndNoPlanIdIsEverShown() {
        val flows = listOf(
            WorktreeFlow.Request(true), WorktreeFlow.Planning(QUERY, true), WorktreeFlow.Choosing(QUERY, true, ASK),
            WorktreeFlow.Card(READY, QUERY, true), WorktreeFlow.Creating(READY.planId, QUERY, true),
            WorktreeFlow.Failed("x", QUERY, true), WorktreeFlow.Handoff(SESSION, QUERY, true, CREATED),
        )
        for (flow in flows) {
            val s = ui(nw(flow))
            assertEquals("$flow", VoiceMode.NewWorktree, s.mode)
            assertTrue("$flow", s.title.isNotBlank())
            assertFalse("$flow", s.toString().contains(READY.planId))
        }
    }
}
