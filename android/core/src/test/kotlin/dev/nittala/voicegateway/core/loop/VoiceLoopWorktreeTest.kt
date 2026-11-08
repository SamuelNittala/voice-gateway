package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.api.Fixtures
import dev.nittala.voicegateway.core.api.RefusalReason
import dev.nittala.voicegateway.core.api.refusalOf
import dev.nittala.voicegateway.core.loop.Effect.CreateWorktree
import dev.nittala.voicegateway.core.loop.Effect.Earcon
import dev.nittala.voicegateway.core.loop.Effect.PlanWorktree
import dev.nittala.voicegateway.core.loop.Effect.ReplanBase
import dev.nittala.voicegateway.core.loop.Effect.Say
import dev.nittala.voicegateway.core.loop.Effect.Send
import dev.nittala.voicegateway.core.loop.Effect.SetPoll
import dev.nittala.voicegateway.core.loop.Effect.StartCapture
import dev.nittala.voicegateway.core.loop.Effect.StartTimer
import dev.nittala.voicegateway.core.loop.Effect.StopCapture
import dev.nittala.voicegateway.core.loop.LoopInput.CancelTap
import dev.nittala.voicegateway.core.loop.LoopInput.CreateTap
import dev.nittala.voicegateway.core.loop.LoopInput.NewWorktreeTap
import dev.nittala.voicegateway.core.loop.LoopInput.SpeechEnd
import dev.nittala.voicegateway.core.loop.LoopInput.SpeechStart
import dev.nittala.voicegateway.core.loop.LoopInput.TimerFired
import dev.nittala.voicegateway.core.loop.LoopInput.Transcribed
import dev.nittala.voicegateway.core.loop.LoopInput.View
import dev.nittala.voicegateway.core.model.CreateAnswer
import dev.nittala.voicegateway.core.model.GatewayJson
import dev.nittala.voicegateway.core.model.PlanAnswer
import dev.nittala.voicegateway.core.model.planAnswerOf
import dev.nittala.voicegateway.core.worktree.CardSpeech
import dev.nittala.voicegateway.core.worktree.WorktreeFlow
import dev.nittala.voicegateway.core.worktree.WorktreeLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

fun planFixture(name: String): PlanAnswer = planAnswerOf(Fixtures.load(name).bodyText)

fun createFixture(name: String): CreateAnswer = GatewayJson.decodeFromString(Fixtures.load(name).bodyText)

val READY = planFixture("plan_new_ready") as PlanAnswer.Plan
val EXISTING = planFixture("plan_existing") as PlanAnswer.Plan
val NEEDS_BASE = planFixture("plan_needs_base") as PlanAnswer.Plan
val ASK = planFixture("plan_ask_candidates") as PlanAnswer.Ask
val CREATED = createFixture("create_ok")
val SESSION = CREATED.session
const val QUERY = "a worktree in web app from production 4.6.2 for the login timeout"

fun nw(flow: WorktreeFlow, wasLooping: Boolean = false) = Phase.NewWorktree(flow, wasLooping)

/** A request is a long sentence: 4.8 s voiced keeps it under G3's word rate. */
val LONG = utterance(voicedFrames = 150, spanFrames = 160)

/**
 * W6: the new-worktree rows of the transition table (plan section 5 test 6). Every row of VoiceLoopTest stays as it
 * is; these are added. The cross-cutting rules (mic, create, send, silence) are in VoiceLoopPropertyTest.
 */
class VoiceLoopWorktreeTest {
    private val loop = VoiceLoop()

    /** The first-prompt hand-off switched on (no longer the default: see VoiceLoopNoHandoffTest). */
    private val handoffOn = VoiceLoop(LoopConfig(handoffFirstPrompt = true))
    private val request = nw(WorktreeFlow.Request(listening = true))
    private val planning = nw(WorktreeFlow.Planning(QUERY, voice = true))
    private val card = nw(WorktreeFlow.Card(READY, QUERY, voice = true, listening = true))
    private val creating = nw(WorktreeFlow.Creating(READY.planId, QUERY, voice = true))
    private val handoff = nw(WorktreeFlow.Handoff(SESSION, QUERY, voice = true, created = CREATED))

    private val Transition.plans get() = effects.filterIsInstance<PlanWorktree>()
    private val Transition.creates get() = effects.filterIsInstance<CreateWorktree>()

    /** A view of the new session. */
    private fun newView(status: String? = "idle") = view(status, name = SESSION)

    private fun refused(name: String) = Fixtures.load(name).let { refusalOf(it.status, it.bodyText) }

    // ---- Entry ------------------------------------------------------------------------------------------------------

    @Test
    fun entryIsAcceptedBetweenTurnsAndOpensTheMicForTheRequest() {
        val accepted = mapOf(
            Phase.Idle to false, Phase.Listening to true, Phase.AwaitingAnswer(QUESTION) to true,
            Phase.Paused(PauseReason.User) to false, Phase.Held(TEXT, HeldReason.AgentBusy) to true,
            Phase.Error(ErrorKind.PaneChanged, "x") to false, Phase.Stopped() to false,
            Phase.AgentWorking(Sent(TEXT)) to true,
        )
        for ((phase, looping) in accepted) {
            val t = loop.step(st(phase), NewWorktreeTap)
            assertEquals("$phase", nw(WorktreeFlow.Request(listening = true), looping), t.phase)
            val listen = t.effects.indexOf(Earcon(EarconKind.Listen))
            val capture = t.effects.indexOf(StartCapture(answerMode = false))
            assertTrue("$phase ${t.effects}", listen in 0 until capture)
            assertTrue("$phase", StartTimer(Timer.NoSpeech, 20_000) in t.effects)
            assertTrue(t.sends.isEmpty())
        }
    }

    @Test
    fun entryIsIgnoredMidTurnAndWhileADialogOrConfirmationWaits() {
        val ignored = listOf(
            Phase.Capturing(WT), Phase.Transcribing(WT, GOOD), Phase.Sending(WT, TEXT),
            Phase.Speaking(event("e1"), Phase.Listening), Phase.NeedsYou("t", "o"), Phase.AwaitingTap(CONFIRM),
            Phase.LoggedOut, request, card,
        )
        for (phase in ignored) assertEquals("$phase", Transition(st(phase)), loop.step(st(phase), NewWorktreeTap))
    }

    @Test
    fun anOpenQuestionStaysKeptForItsWorktreeAndComesBackOnCancel() {
        val s = st(Phase.AwaitingAnswer(QUESTION)).copy(questions = mapOf(WT to QUESTION))
        val entered = loop.step(s, NewWorktreeTap)
        assertEquals(QUESTION, entered.state.questions[WT])
        val back = loop.step(entered.state, CancelTap)
        assertEquals(Phase.AwaitingAnswer(QUESTION), back.phase)
        assertTrue(QUESTION.text in back.says)
    }

    @Test
    fun cancelGoesBackToIdleWhenTheLoopWasOffAndResumesItWhenItRan() {
        val off = loop.step(st(request), CancelTap)
        assertEquals(Phase.Idle, off.phase)
        assertEquals(listOf(WorktreeLines.CANCELLED), off.says)
        assertTrue(off.effects.toString(), off.effects.indexOf(StopCapture) in 0 until off.effects.indexOf(Say(WorktreeLines.CANCELLED)))
        assertEquals(Phase.Listening, loop.step(st(nw(WorktreeFlow.Request(true), wasLooping = true)), CancelTap).phase)
    }

    // ---- Request ----------------------------------------------------------------------------------------------------

    @Test
    fun aSpokenRequestGoesThroughTheGuardsAndIsPlannedNotSent() {
        val captured = loop.step(st(request), SpeechStart)
        assertEquals(Phase.Capturing(WT, purpose = CapturePurpose.Worktree(request)), captured.phase)
        val uploading = loop.step(captured.state, SpeechEnd(LONG))
        assertEquals(Phase.Transcribing(WT, LONG, purpose = CapturePurpose.Worktree(request)), uploading.phase)
        val t = loop.step(uploading.state, Transcribed(WT, QUERY))
        assertEquals(nw(WorktreeFlow.Planning(QUERY, voice = true)), t.phase)
        assertEquals(listOf(PlanWorktree(QUERY, null)), t.plans)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun aRejectedRequestIsAMissAndTwoMissesTurnTheMicOff() {
        val first = loop.run(st(request), SpeechStart, SpeechEnd(GOOD), Transcribed(WT, "thank you"))
        assertEquals(nw(WorktreeFlow.Request(listening = true, misses = 1)), first.phase)
        assertTrue(Lines.DIDNT_CATCH in first.says)
        assertEquals(0, first.state.rejections)
        val second = loop.run(first.state, SpeechStart, SpeechEnd(QUIET))
        assertEquals(nw(WorktreeFlow.Request(listening = false, misses = 2)), second.phase)
        assertTrue(second.captures.isEmpty())
        assertTrue((first.effects + second.effects).none { it is PlanWorktree || it is Send })
    }

    @Test
    fun aSlashCommandRequestIsNeverPlanned() {
        val t = loop.run(st(request), SpeechStart, SpeechEnd(GOOD), Transcribed(WT, "/clear the worktree now"))
        assertTrue(t.plans.isEmpty())
    }

    @Test
    fun silenceTurnsTheMicOffAndPlansNothing() {
        val t = loop.step(st(request), TimerFired(Timer.NoSpeech))
        assertEquals(nw(WorktreeFlow.Request(listening = false)), t.phase)
        assertEquals(StopCapture, t.effects.first())
        assertTrue(t.plans.isEmpty())
    }

    @Test
    fun aTypedRequestIsPlannedWithVoiceFalseAndBlankIsIgnored() {
        val t = loop.step(st(request), LoopInput.NewWorktreeTyped("  a worktree in notes for the readme  "))
        assertEquals(nw(WorktreeFlow.Planning("a worktree in notes for the readme", voice = false)), t.phase)
        assertEquals(listOf(PlanWorktree("a worktree in notes for the readme", null)), t.plans)
        assertEquals(StopCapture, t.effects.first())
        assertEquals(Transition(st(request)), loop.step(st(request), LoopInput.NewWorktreeTyped("   ")))
    }

    @Test
    fun theOrbTogglesTheRequestMic() {
        val off = loop.step(st(request), LoopInput.Tap)
        assertEquals(nw(WorktreeFlow.Request(listening = false)), off.phase)
        val on = loop.step(off.state, LoopInput.Tap)
        assertEquals(request, on.phase)
        assertEquals(1, on.captures.size)
    }

    // ---- Planning and choosing --------------------------------------------------------------------------------------

    @Test
    fun anAskIsSaidThenTheReposAreTapsOnly() {
        val t = loop.step(st(planning), LoopInput.Planned(ASK))
        assertEquals(nw(WorktreeFlow.Choosing(QUERY, voice = true, ask = ASK)), t.phase)
        assertEquals(listOf(ASK.question, WorktreeLines.pickRepo(listOf("web-app", "notes"))), t.says)
        assertTrue(t.captures.isEmpty())
        val tapped = loop.step(t.state, LoopInput.CandidateTap("85dfc701aea8"))
        assertEquals(nw(WorktreeFlow.Planning(QUERY, voice = true, repoId = "85dfc701aea8")), tapped.phase)
        assertEquals(listOf(PlanWorktree(QUERY, "85dfc701aea8")), tapped.plans)
        assertEquals(Transition(t.state), loop.step(t.state, LoopInput.CandidateTap("not-listed")))
    }

    @Test
    fun aNotUnderstoodAskDoesNotReadOutNames() {
        val lost = planFixture("plan_not_understood") as PlanAnswer.Ask
        val t = loop.step(st(planning), LoopInput.Planned(lost))
        assertEquals(listOf(lost.question, WorktreeLines.pickRepo(emptyList())), t.says)
    }

    @Test
    fun planRefusalsAndFailuresAreSaidAndOfferPlanAgain() {
        val busy = refusalOf(409, """{"error": "Another plan is still being worked out.", "reason": "plan_busy"}""")
        val r = loop.step(st(planning), LoopInput.PlanRefused(busy))
        assertEquals(nw(WorktreeFlow.Failed("Another plan is still being worked out.", QUERY, voice = true)), r.phase)
        assertEquals(listOf(busy.message), r.says)
        val f = loop.step(st(planning), LoopInput.PlanFailed)
        assertEquals(nw(WorktreeFlow.Failed(WorktreeLines.COULDNT_REACH, QUERY, voice = true)), f.phase)
        val again = loop.step(f.state, CreateTap)
        assertEquals(listOf(PlanWorktree(QUERY, null)), again.plans)
    }

    // ---- The card -------------------------------------------------------------------------------------------------

    @Test
    fun aReadyCardIsReadAloudAndOnlyThenTheMicOpens() {
        val t = loop.step(st(planning), LoopInput.Planned(READY))
        assertEquals(card, t.phase)
        val say = t.effects.indexOf(Say(CardSpeech.of(READY)))
        val capture = t.effects.indexOf(StartCapture(answerMode = true))
        assertTrue(t.effects.toString(), say in 0 until capture)
        assertTrue(StartTimer(Timer.PlanExpiry, 300_000) in t.effects)
        assertTrue(StartTimer(Timer.NoSpeech, 20_000) in t.effects)
    }

    @Test
    fun aCardThatIsNotReadyIsReadButTheMicStaysOff() {
        val t = loop.step(st(planning), LoopInput.Planned(NEEDS_BASE))
        assertEquals(nw(WorktreeFlow.Card(NEEDS_BASE, QUERY, voice = true)), t.phase)
        assertEquals(listOf(CardSpeech.of(NEEDS_BASE)), t.says)
        assertTrue(t.captures.isEmpty())
    }

    @Test
    fun mutedOrTypedTheCardIsNotReadSoTheMicStaysOff() {
        val muted = loop.step(st(planning).copy(muted = true), LoopInput.Planned(READY))
        assertEquals(nw(WorktreeFlow.Card(READY, QUERY, voice = true)), muted.phase)
        assertTrue(muted.captures.isEmpty())
        val typed = loop.step(st(nw(WorktreeFlow.Planning(QUERY, voice = false))), LoopInput.Planned(READY))
        assertEquals(nw(WorktreeFlow.Card(READY, QUERY, voice = false)), typed.phase)
        assertTrue(typed.says.isEmpty())
        assertTrue(typed.captures.isEmpty())
    }

    @Test
    fun theWholePhraseCreateItCreates() {
        val t = loop.run(st(card), SpeechStart, SpeechEnd(GOOD), Transcribed(WT, "Yes, create it."))
        val shown = (card.flow as WorktreeFlow.Card).copy(listening = false)
        assertEquals(nw(WorktreeFlow.Creating(READY.planId, QUERY, voice = true, card = shown)), t.phase)
        assertEquals(listOf(CreateWorktree(READY.planId)), t.creates)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun theWholePhraseCancelCancels() {
        val t = loop.run(st(card), SpeechStart, SpeechEnd(GOOD), Transcribed(WT, "Cancel."))
        assertEquals(Phase.Idle, t.phase)
        assertTrue(t.creates.isEmpty())
        assertTrue(WorktreeLines.CANCELLED in t.says)
    }

    @Test
    fun aNearMissIsAskedAgainAndTwoMissesTurnTheMicOffNeverCreating() {
        val first = loop.run(st(card), SpeechStart, SpeechEnd(GOOD), Transcribed(WT, "create it in the other one"))
        assertEquals(nw(WorktreeFlow.Card(READY, QUERY, voice = true, listening = true, misses = 1)), first.phase)
        assertTrue(WorktreeLines.SAY_CREATE_OR_CANCEL in first.says)
        val second = loop.run(first.state, SpeechStart, SpeechEnd(GOOD), Transcribed(WT, "yes"))
        assertEquals(nw(WorktreeFlow.Card(READY, QUERY, voice = true, listening = false, misses = 2)), second.phase)
        assertTrue(second.captures.isEmpty())
        assertTrue((first.effects + second.effects).none { it is CreateWorktree })
    }

    @Test
    fun silenceOnTheCardTurnsTheMicOffAndCreatesNothing() {
        val t = loop.step(st(card), TimerFired(Timer.NoSpeech))
        assertEquals(nw(WorktreeFlow.Card(READY, QUERY, voice = true)), t.phase)
        assertEquals(StopCapture, t.effects.first())
        assertTrue(t.creates.isEmpty())
    }

    @Test
    fun aCreateTapCreatesOnlyWhenReady() {
        val t = loop.step(st(card), CreateTap)
        assertEquals(listOf(CreateWorktree(READY.planId)), t.creates)
        assertEquals(StopCapture, t.effects.first())
        val notReady = st(nw(WorktreeFlow.Card(NEEDS_BASE, QUERY, voice = true)))
        assertEquals(Transition(notReady), loop.step(notReady, CreateTap))
    }

    @Test
    fun aBaseTapOrTypedBasePlansAgainWithTheOldCardGreyed() {
        val shown = WorktreeFlow.Card(NEEDS_BASE, QUERY, voice = true)
        val t = loop.step(st(nw(shown)), LoopInput.BaseTap("develop"))
        assertEquals(nw(WorktreeFlow.Planning(QUERY, voice = true, previous = shown)), t.phase)
        assertEquals(listOf(ReplanBase(NEEDS_BASE.planId, "develop")), t.effects.filterIsInstance<ReplanBase>())
        val typed = loop.step(st(nw(shown)), LoopInput.BaseTyped("  production 4.6.1 "))
        assertEquals(listOf(ReplanBase(NEEDS_BASE.planId, "production 4.6.1")), typed.effects.filterIsInstance<ReplanBase>())
        assertEquals(Transition(st(nw(shown))), loop.step(st(nw(shown)), LoopInput.BaseTyped("  ")))
        val existing = st(nw(WorktreeFlow.Card(EXISTING, QUERY, voice = true)))
        assertEquals(Transition(existing), loop.step(existing, LoopInput.BaseTap("uat")))
    }

    @Test
    fun anExpiredCardTurnsCreateIntoPlanAgain() {
        val expired = loop.step(st(card), TimerFired(Timer.PlanExpiry))
        assertEquals(nw(WorktreeFlow.Card(READY, QUERY, voice = true, expired = true)), expired.phase)
        assertEquals(listOf(WorktreeLines.PLAN_EXPIRED), expired.says)
        assertTrue(expired.effects.indexOf(StopCapture) in 0 until expired.effects.indexOf(Say(WorktreeLines.PLAN_EXPIRED)))
        val again = loop.step(expired.state, CreateTap)
        assertEquals(nw(WorktreeFlow.Planning(QUERY, voice = true)), again.phase)
        assertEquals(listOf(PlanWorktree(QUERY, null)), again.plans)
        assertTrue(again.creates.isEmpty())
        assertEquals(Transition(expired.state), loop.step(expired.state, LoopInput.BaseTap("main")))
    }

    @Test
    fun theMicLostOnTheCardTurnsItOffAndKeepsTheCard() {
        for (input in listOf(LoopInput.FocusLost, LoopInput.MicFailed(MicFailure.Silenced))) {
            val t = loop.step(st(card), input)
            assertEquals("$input", nw(WorktreeFlow.Card(READY, QUERY, voice = true)), t.phase)
        }
        val capturing = st(Phase.Capturing(WT, purpose = CapturePurpose.Worktree(card)))
        assertEquals(nw(WorktreeFlow.Card(READY, QUERY, voice = true)), loop.step(capturing, LoopInput.FocusLost).phase)
    }

    @Test
    fun theCardExpiresWhileItsReplyIsHeardAndThenCreatesNothing() {
        val t = loop.run(st(card), SpeechStart, SpeechEnd(GOOD), TimerFired(Timer.PlanExpiry), Transcribed(WT, "create it"))
        assertEquals(nw(WorktreeFlow.Card(READY, QUERY, voice = true, expired = true)), t.phase)
        assertTrue(t.creates.isEmpty())
        assertTrue(WorktreeLines.PLAN_EXPIRED in t.says)
    }

    @Test
    fun staleResultsAreIgnored() {
        for ((phase, input) in listOf(
            card to LoopInput.Planned(READY), request to LoopInput.Planned(ASK), card to LoopInput.Created(CREATED),
            planning to LoopInput.Created(CREATED), card to LoopInput.CreateMaybeDone, request to LoopInput.PlanFailed,
        )) assertEquals("$phase $input", Transition(st(phase)), loop.step(st(phase), input))
    }

    // ---- Create ---------------------------------------------------------------------------------------------------

    @Test
    fun createdSwitchesToTheNewSessionAndWaitsForTheOrchestrator() {
        val t = handoffOn.step(st(creating), LoopInput.Created(CREATED))
        assertEquals(SESSION, t.state.worktree)
        assertEquals(AgentView.Unknown, t.state.agent)
        assertEquals(handoff, t.phase)
        assertTrue(StartTimer(Timer.HandoffLimit, 120_000) in t.effects)
        assertTrue(SetPoll(2_000) in t.effects)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun twoSettledViewsInARowSendTheRequestOnceToTheNewSession() {
        val created = handoffOn.step(st(creating), LoopInput.Created(CREATED)).state
        val one = handoffOn.step(created, View(newView("idle")))
        assertEquals(nw(WorktreeFlow.Handoff(SESSION, QUERY, true, CREATED, settledPolls = 1)), one.phase)
        assertTrue(one.sends.isEmpty())
        val two = loop.step(one.state, View(newView("done")))
        assertEquals(listOf(Send(SESSION, QUERY, voice = true)), two.sends)
        assertEquals(Phase.Sending(SESSION, QUERY), two.phase)
        val after = loop.run(two.state, LoopInput.SendOk(SESSION), View(newView("working")), View(newView("idle")))
        assertTrue(after.sends.isEmpty())
    }

    @Test
    fun aTypedRequestIsSentTyped() {
        val s = st(nw(WorktreeFlow.Handoff(SESSION, QUERY, false, CREATED, settledPolls = 1)), worktree = SESSION)
        assertEquals(listOf(Send(SESSION, QUERY, voice = false)), loop.step(s, View(newView("idle"))).sends)
    }

    @Test
    fun aWorkingOrMissingOrchestratorStartsTheCountAgain() {
        val s = st(nw(WorktreeFlow.Handoff(SESSION, QUERY, true, CREATED, settledPolls = 1)), worktree = SESSION)
        for (status in listOf("working", null, "unknown")) {
            val t = loop.step(s, View(newView(status)))
            assertEquals("$status", nw(WorktreeFlow.Handoff(SESSION, QUERY, true, CREATED, settledPolls = 0)), t.phase)
        }
        val stopped = loop.step(s, View(view("idle", state = "stopped", name = SESSION)))
        assertTrue(stopped.sends.isEmpty())
        assertTrue(stopped.phase is Phase.NewWorktree)
    }

    @Test
    fun aDialogDuringTheHandoffIsNeedsYouAndThenTheHandoffGoesOn() {
        val s = st(handoff, AgentView.Unknown, worktree = SESSION)
        val blocked = loop.step(s, View(view("blocked", token = "tok9", name = SESSION)))
        val needs = blocked.phase as Phase.NeedsYou
        assertEquals(handoff, needs.handoff)
        assertEquals("tok9", needs.screenToken)
        assertTrue(Lines.LOOK_TO_ANSWER in blocked.says)
        assertTrue(CancelTimerOf(Timer.HandoffLimit) in blocked.effects)
        assertEquals(listOf(Effect.SendKey(SESSION, "1", "tok9")), loop.step(blocked.state, LoopInput.KeyTap("1")).effects)
        val back = loop.step(blocked.state, View(newView("idle")))
        assertEquals(nw(WorktreeFlow.Handoff(SESSION, QUERY, true, CREATED, settledPolls = 1)), back.phase)
        assertTrue(StartTimer(Timer.HandoffLimit, 120_000) in back.effects)
        assertEquals(listOf(Send(SESSION, QUERY, true)), loop.step(back.state, View(newView("idle"))).sends)
    }

    @Test
    fun noReadyOrchestratorIn120sKeepsTheRequestNeverSendingIt() {
        val s = st(handoff, worktree = SESSION)
        val t = loop.step(s, TimerFired(Timer.HandoffLimit))
        assertEquals(Phase.Held(QUERY, HeldReason.NewWorktreeNotReady), t.phase)
        assertEquals(listOf(WorktreeLines.NOT_READY_KEPT), t.says)
        val later = loop.run(t.state, View(newView("idle")), View(newView("idle")), *Timer.entries.map { TimerFired(it) }.toTypedArray())
        assertTrue(later.sends.isEmpty())
        assertEquals(listOf(Send(SESSION, QUERY, true)), loop.step(later.state, LoopInput.SendNowTap).sends)
    }

    @Test
    fun withTheHandoffSwitchedOffCreatedSendsAndKeepsNothing() {
        val off = VoiceLoop(LoopConfig(handoffFirstPrompt = false))
        val t = off.step(st(creating), LoopInput.Created(CREATED))
        assertEquals(SESSION, t.state.worktree)
        assertEquals(Phase.Idle, t.phase)
        assertTrue(off.run(t.state, View(newView("idle")), View(newView("idle"))).sends.isEmpty())
    }

    @Test
    fun cancelDuringTheHandoffKeepsTheRequestUnsent() {
        val t = loop.step(st(handoff, worktree = SESSION), CancelTap)
        assertEquals(Phase.Held(QUERY, HeldReason.NewWorktreeRequest), t.phase)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun switchingAwayDuringTheHandoffNeverSendsTheRequestElsewhere() {
        val s = st(handoff, worktree = SESSION)
        val t = loop.run(s, LoopInput.WorktreeChanged(OTHER), View(view("idle", name = OTHER)), View(view("idle", name = OTHER)))
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun aPendingQuestionOfTheOldWorktreeSurvivesTheCreate() {
        val s = st(creating).copy(questions = mapOf(WT to QUESTION))
        val t = loop.step(s, LoopInput.Created(CREATED))
        assertEquals(QUESTION, t.state.questions[WT])
    }

    @Test
    fun aCreateWithNoAnswerIsNeverRetried() {
        val t = loop.step(st(creating), LoopInput.CreateMaybeDone)
        assertEquals(nw(WorktreeFlow.Failed(WorktreeLines.NOT_SURE_CREATED, null, voice = true, maybeCreated = true)), t.phase)
        assertEquals(listOf(WorktreeLines.NOT_SURE_CREATED), t.says)
        val taps = loop.run(t.state, CreateTap, LoopInput.OfflineCreateTap)
        assertTrue((t.effects + taps.effects).none { it is CreateWorktree || it is PlanWorktree })
    }

    @Test
    fun aFailedFetchOffersTheDownloadedCopyByTapOnly() {
        val r = refused("refusal_fetch_failed_offline")
        val t = loop.step(st(creating), LoopInput.CreateRefused(r))
        val failed = (t.phase as Phase.NewWorktree).flow as WorktreeFlow.Failed
        assertEquals(r.offline, failed.offline)
        assertEquals(listOf(r.message), t.says)
        assertTrue(t.creates.isEmpty())
        val offline = loop.step(t.state, LoopInput.OfflineCreateTap)
        assertEquals(listOf(CreateWorktree(r.offline!!.planId)), offline.creates)
        assertEquals(nw(WorktreeFlow.Creating(r.offline!!.planId, QUERY, voice = true)), offline.phase)
    }

    @Test
    fun otherCreateRefusalsOfferPlanAgainButNoDownloadedCopy() {
        for (name in listOf("refusal_fetch_failed", "refusal_repo_changed", "refusal_wt_failed", "refusal_plan_expired")) {
            val t = loop.step(st(creating), LoopInput.CreateRefused(refused(name)))
            val failed = (t.phase as Phase.NewWorktree).flow as WorktreeFlow.Failed
            assertEquals(name, null, failed.offline)
            assertEquals(name, QUERY, failed.query)
            assertEquals(name, Transition(t.state), loop.step(t.state, LoopInput.OfflineCreateTap))
            assertEquals(name, listOf(PlanWorktree(QUERY, null)), loop.step(t.state, CreateTap).plans)
        }
    }

    @Test
    fun cancelLeavesEveryStateButCreating() {
        for (flow in listOf(
            WorktreeFlow.Request(false), WorktreeFlow.Planning(QUERY, true), WorktreeFlow.Choosing(QUERY, true, ASK),
            WorktreeFlow.Card(READY, QUERY, true), WorktreeFlow.Failed("x", QUERY, true),
        )) assertEquals("$flow", Phase.Idle, loop.step(st(nw(flow)), CancelTap).phase)
        assertEquals(Transition(st(creating)), loop.step(st(creating), CancelTap))
    }

    @Test
    fun theHandoffPollsFastAndTheRestOfTheFlowSlow() {
        assertEquals(2_000L, loop.pollInterval(st(handoff)))
        assertEquals(5_000L, loop.pollInterval(st(card)))
        assertEquals(5_000L, loop.pollInterval(st(creating)))
    }

    @Test
    fun aLongPressEndsTheFlow() {
        assertEquals(Phase.Idle, loop.step(st(card), LoopInput.LongPress).phase)
    }

    @Test
    fun aRefusedPlanWithAReasonIsKeptAsAReason() {
        val r = refused("refusal_worktrees_off")
        assertEquals(RefusalReason.WorktreesOff, r.reason)
        val t = loop.step(st(planning), LoopInput.PlanRefused(r))
        assertEquals(r.message, ((t.phase as Phase.NewWorktree).flow as WorktreeFlow.Failed).message)
    }

    @Suppress("TestFunctionName")
    private fun CancelTimerOf(timer: Timer) = Effect.CancelTimer(timer)
}
