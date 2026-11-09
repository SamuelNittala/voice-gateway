package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.api.Fixtures
import dev.nittala.voicegateway.core.loop.Effect.Ack
import dev.nittala.voicegateway.core.loop.Effect.CancelTimer
import dev.nittala.voicegateway.core.loop.Effect.Earcon
import dev.nittala.voicegateway.core.loop.Effect.Play
import dev.nittala.voicegateway.core.loop.Effect.StartTimer
import dev.nittala.voicegateway.core.loop.Effect.StopPlayback
import dev.nittala.voicegateway.core.loop.LoopInput.PlaybackDone
import dev.nittala.voicegateway.core.loop.LoopInput.Tap
import dev.nittala.voicegateway.core.loop.LoopInput.TimerFired
import dev.nittala.voicegateway.core.loop.LoopInput.View
import dev.nittala.voicegateway.core.model.EventKind
import dev.nittala.voicegateway.core.model.GatewayJson
import dev.nittala.voicegateway.core.model.SessionView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress updates (TASKS.md "Progress updates"): the rows of the working cue and of `progress` events.
 * - A progress line plays only in AgentWorking, for the current worktree, with "Speak progress updates" on, not
 *   muted, and with no later event of that session queued. Otherwise it is acknowledged silently, never kept.
 * - After it the loop is back in AgentWorking: no mic, no question, no Listening.
 * - A newer event of the current worktree interrupts it; a tap interrupts it; the headset long-press stops the agent.
 * - The cue ([LoopConfig.workingCueMs]) runs exactly while in AgentWorking: an Earcon(Working) every period.
 */
class VoiceLoopProgressTest {
    private val loop = VoiceLoop()
    private val cue = VoiceLoop(LoopConfig(workingCueMs = 15_000))
    private val working = Phase.AgentWorking(Sent(TEXT))
    private val summary = event("s1", text = "Fixed the test.")
    private val cueStart = StartTimer(Timer.WorkingCue, 15_000)

    /** The effects without the poll interval (Speaking an ordinary event polls slower, as before). */
    private val Transition.noPoll: List<Effect> get() = effects.filterNot { it is Effect.SetPoll }

    private fun progressView(status: String = "working", events: List<dev.nittala.voicegateway.core.model.Event> = listOf(PROGRESS)) =
        View(view(status, events = events, waiting = true))

    // ---- Playing a progress line -----------------------------------------------------------------------------------

    @Test
    fun aProgressLineWhileWorkingIsSpoken() {
        val t = loop.step(st(working, WORKING_AGENT), progressView())
        assertEquals(Phase.Speaking(PROGRESS, working), t.phase)
        assertEquals(listOf(Play(PROGRESS, withName = false)), t.effects)
        assertTrue(t.captures.isEmpty())
        assertEquals("Reading the failing test", t.state.lastProgress)
    }

    @Test
    fun whenItEndsTheLoopIsBackInAgentWorkingWithTheMicOff() {
        for (over in listOf(PlaybackDone("p1"), LoopInput.PlaybackFailed("p1"))) {
            val t = loop.step(st(Phase.Speaking(PROGRESS, working), WORKING_AGENT), over)
            assertEquals("$over", working, t.phase)
            assertEquals("$over", listOf(Ack(listOf("p1"))), t.effects)
            assertTrue(t.state.questions.isEmpty())
            assertTrue("p1" in t.state.heard)
        }
    }

    @Test
    fun whenItEndsAfterTheAgentSettledTheNextViewStartsTheSettledTimer() {
        val t = loop.run(
            st(Phase.Speaking(PROGRESS, working), WORKING_AGENT),
            PlaybackDone("p1"), View(view("idle", events = emptyList())),
        )
        assertEquals(Phase.AgentWorking(Sent(TEXT), settledTimer = true), t.phase)
        assertTrue(t.captures.isEmpty())
    }

    @Test
    fun aTapInterruptsItAndTheMicStaysOff() {
        val t = loop.step(st(Phase.Speaking(PROGRESS, working), WORKING_AGENT), Tap)
        assertEquals(working, t.phase)
        assertEquals(listOf(StopPlayback, Ack(listOf("p1"))), t.effects)
    }

    @Test
    fun whileALineIsSpokenThePollStaysFastSoANewerEventInterruptsIt() {
        val s = st(working, WORKING_AGENT)
        val t = loop.step(s, progressView())
        assertEquals(loop.pollInterval(s), loop.pollInterval(t.state))
        assertEquals(2_000L, loop.pollInterval(t.state))
    }

    @Test
    fun theHeadsetLongPressDuringItStopsTheAgent() {
        val s = st(Phase.Speaking(PROGRESS, working), WORKING_AGENT)
        assertEquals(Transition(s, listOf(Effect.SendStop(WT))), loop.step(s, LoopInput.HeadsetLongPress))
        // Any other event still goes Idle on a long-press, as before.
        assertEquals(Phase.Idle, loop.step(st(Phase.Speaking(summary, working)), LoopInput.HeadsetLongPress).phase)
    }

    @Test
    fun aSummaryInTheSameViewWinsAndTheProgressIsAcknowledgedSilently() {
        val t = loop.step(st(working, WORKING_AGENT), progressView("idle", listOf(PROGRESS, summary)))
        assertEquals(Phase.Speaking(summary, working), t.phase)
        assertEquals(listOf(Ack(listOf("p1")), Play(summary, withName = false)), t.noPoll)
        assertTrue("p1" in t.state.heard)
        assertTrue(t.state.queue.isEmpty())
    }

    @Test
    fun aSummaryArrivingMidLineInterruptsIt() {
        val t = loop.step(st(Phase.Speaking(PROGRESS, working), WORKING_AGENT), progressView("idle", listOf(PROGRESS, summary)))
        assertEquals(Phase.Speaking(summary, working), t.phase)
        assertEquals(listOf(StopPlayback, Ack(listOf("p1")), Play(summary, withName = false)), t.noPoll)
        // Then the summary's own rules: settle by the last view.
        val after = loop.step(t.state, PlaybackDone("s1"))
        assertEquals(Phase.Listening, after.phase)
    }

    @Test
    fun aNeedsYouArrivingMidLineInterruptsIt() {
        val needs = event("n1", kind = EventKind.NeedsYou, text = "scratch needs you")
        val t = loop.step(
            st(Phase.Speaking(PROGRESS, working), WORKING_AGENT),
            View(view("blocked", events = listOf(needs), token = "tok1", waiting = true)),
        )
        assertEquals(Phase.Speaking(needs, working), t.phase)
        assertEquals(StopPlayback, t.effects.first())
    }

    @Test
    fun anotherWorktreesEventDoesNotInterruptItAndPlaysAfter() {
        val other = event("o1", session = OTHER)
        val s = st(Phase.Speaking(PROGRESS, working), WORKING_AGENT)
        val t = loop.step(s, progressView("working", listOf(PROGRESS, other)))
        assertEquals(Phase.Speaking(PROGRESS, working), t.phase)
        assertTrue(t.effects.isEmpty())
        val next = loop.step(t.state, PlaybackDone("p1"))
        assertEquals(Phase.Speaking(other, working), next.phase)
        assertEquals(listOf(Ack(listOf("p1")), Play(other, withName = true)), next.noPoll)
    }

    @Test
    fun aNewerProgressLineMidLineIsDroppedNotQueued() {
        val newer = progress("p2", text = "Running the suite")
        val t = loop.step(st(Phase.Speaking(PROGRESS, working), WORKING_AGENT), progressView("working", listOf(PROGRESS, newer)))
        assertEquals(Phase.Speaking(PROGRESS, working), t.phase)
        assertEquals(listOf(Ack(listOf("p2"))), t.effects)
        assertTrue(t.state.queue.isEmpty())
    }

    // ---- Silent acknowledgement --------------------------------------------------------------------------------------

    @Test
    fun withSpokenProgressOffItIsAcknowledgedSilentlyButShown() {
        val s = st(working, WORKING_AGENT).copy(spokenProgress = false)
        val t = loop.step(s, progressView())
        assertEquals(working, t.phase)
        assertEquals(listOf(Ack(listOf("p1"))), t.effects)
        assertEquals("Reading the failing test", t.state.lastProgress)
    }

    @Test
    fun mutedItIsAcknowledgedWithNoEarcon() {
        val t = loop.step(st(working, WORKING_AGENT).copy(muted = true), progressView())
        assertEquals(working, t.phase)
        assertEquals(listOf(Ack(listOf("p1"))), t.effects)
    }

    @Test
    fun anotherWorktreesProgressIsNeverSpokenOrShown() {
        val theirs = progress("p9", session = OTHER)
        for (v in listOf(view("working", events = listOf(theirs)), view("working", events = listOf(theirs), name = OTHER))) {
            val t = loop.step(st(working, WORKING_AGENT), View(v))
            assertEquals(working, t.phase)
            assertEquals(listOf(Ack(listOf("p9"))), t.effects)
            assertNull(t.state.lastProgress)
        }
    }

    @Test
    fun outsideAgentWorkingItIsAcknowledgedSilently() {
        val cases = listOf(
            st(Phase.Listening) to view("idle", events = listOf(PROGRESS)),
            st(Phase.AwaitingAnswer(QUESTION)) to view("idle", events = listOf(PROGRESS)),
            st(Phase.AwaitingTap(CONFIRM)) to view("idle", events = listOf(PROGRESS)),
            st(Phase.NeedsYou("tok1", "1. Yes"), BLOCKED_AGENT) to view("blocked", events = listOf(PROGRESS), token = "tok1"),
            st(Phase.Speaking(summary, working), WORKING_AGENT) to view("working", events = listOf(summary, PROGRESS)),
            st(Phase.Sending(WT, TEXT)) to view("idle", events = listOf(PROGRESS)),
            st(Phase.Idle) to view("idle", events = listOf(PROGRESS)),
        )
        for ((s, v) in cases) {
            val t = loop.step(s, View(v))
            assertTrue("${s.phase}", t.effects.none { it is Play })
            assertTrue("${s.phase}", Ack(listOf("p1")) in t.effects)
            assertTrue("${s.phase}", t.effects.none { it is Earcon })
            assertTrue("${s.phase}", "p1" in t.state.heard)
            assertTrue("${s.phase}", t.state.queue.none { it.kind == EventKind.Progress })
            assertEquals("${s.phase}", s.phase::class, t.phase::class)
        }
    }

    @Test
    fun turningSpokenProgressOffStopsALineMidSpeech() {
        val t = loop.step(st(Phase.Speaking(PROGRESS, working), WORKING_AGENT), LoopInput.ProgressChanged(false))
        assertEquals(working, t.phase)
        assertFalse(t.state.spokenProgress)
        assertEquals(listOf(StopPlayback, Ack(listOf("p1"))), t.effects)
    }

    @Test
    fun theSettingIsKeptAndChangesNothingElse() {
        assertTrue(LoopState(worktree = WT).spokenProgress)
        for (phase in samplePhases()) {
            val s = st(phase)
            val off = loop.step(s, LoopInput.ProgressChanged(false))
            if (phase is Phase.Speaking) continue
            assertEquals("$phase", Transition(s.copy(spokenProgress = false)), off)
            assertEquals("$phase", Transition(s), loop.step(s.copy(spokenProgress = false), LoopInput.ProgressChanged(true)))
        }
    }

    // ---- The subtitle's text -----------------------------------------------------------------------------------------

    @Test
    fun theLastLineIsForgottenWhenTheTurnEnds() {
        val s = st(Phase.AgentWorking(Sent(TEXT), settledTimer = true)).copy(lastProgress = "Reading")
        val t = loop.step(s, TimerFired(Timer.Settled))
        assertEquals(Phase.Listening, t.phase)
        assertNull(t.state.lastProgress)
        val switched = loop.step(st(working, WORKING_AGENT).copy(lastProgress = "Reading"), LoopInput.WorktreeChanged(OTHER))
        assertNull(switched.state.lastProgress)
        val kept = loop.step(st(working, WORKING_AGENT).copy(lastProgress = "Reading"), View(view("working")))
        assertEquals("Reading", kept.state.lastProgress)
    }

    // ---- The working cue ---------------------------------------------------------------------------------------------

    @Test
    fun theCueStartsWhenTheSendIsTaken() {
        val t = cue.step(st(Phase.Sending(WT, TEXT)), LoopInput.SendOk(WT))
        assertEquals(working, t.phase)
        assertEquals(listOf(cueStart), t.effects)
    }

    @Test
    fun theCueTicksSoftlyAndRunsAgain() {
        val s = st(working, WORKING_AGENT)
        val t = cue.step(s, TimerFired(Timer.WorkingCue))
        assertEquals(Transition(s, listOf(Earcon(EarconKind.Working), cueStart)), t)
        // Mute is voices only: the cue still plays.
        val muted = cue.step(s.copy(muted = true), TimerFired(Timer.WorkingCue))
        assertEquals(listOf(Earcon(EarconKind.Working), cueStart), muted.effects)
    }

    @Test
    fun viewsWhileWorkingKeepTheCadence() {
        val t = cue.step(st(working, WORKING_AGENT), View(view("working", waiting = true)))
        assertTrue(t.effects.none { it is StartTimer || it is CancelTimer })
    }

    @Test
    fun theSettledTimerStillEndsTheTurnWithTheCueOn() {
        val s = st(Phase.AgentWorking(Sent(TEXT), settledTimer = true))
        val t = cue.step(s, TimerFired(Timer.Settled))
        assertEquals(Phase.Listening, t.phase)
        assertEquals(listOf(Lines.DONE_CHECK_SCREEN), t.says)
        assertTrue(CancelTimer(Timer.WorkingCue) in t.effects)
        val ticked = cue.step(s, TimerFired(Timer.WorkingCue))
        assertEquals(s.phase, ticked.phase)
    }

    @Test
    fun theCueIsCancelledWhenTheLoopLeavesAgentWorking() {
        val s = st(working, WORKING_AGENT)
        val speaking = cue.step(s, View(view("idle", events = listOf(summary))))
        assertTrue(speaking.phase is Phase.Speaking)
        assertTrue(CancelTimer(Timer.WorkingCue) in speaking.effects)
        val needs = cue.step(s, View(view("blocked", token = "tok1")))
        assertTrue(needs.phase is Phase.NeedsYou)
        assertTrue(CancelTimer(Timer.WorkingCue) in needs.effects)
        val progressLine = cue.step(s, progressView())
        assertTrue(progressLine.phase is Phase.Speaking)
        assertTrue(CancelTimer(Timer.WorkingCue) in progressLine.effects)
        val idle = cue.step(s, LoopInput.LongPress)
        assertEquals(listOf(CancelTimer(Timer.WorkingCue)), idle.noPoll)
    }

    @Test
    fun theCueStartsAgainAfterAProgressLine() {
        val t = cue.step(st(Phase.Speaking(PROGRESS, working), WORKING_AGENT), PlaybackDone("p1"))
        assertEquals(working, t.phase)
        assertEquals(listOf(Ack(listOf("p1")), cueStart), t.effects)
    }

    @Test
    fun withoutTheSettingThereIsNoCue() {
        assertNull(LoopConfig().workingCueMs)
        val t = loop.step(st(Phase.Sending(WT, TEXT)), LoopInput.SendOk(WT))
        assertTrue(t.effects.isEmpty())
        val s = st(working, WORKING_AGENT)
        assertEquals(Transition(s), loop.step(s, TimerFired(Timer.WorkingCue)))
    }

    @Test
    fun theCueNeverFiresOutsideAgentWorking() {
        for (phase in samplePhases().filter { it !is Phase.AgentWorking }) {
            val s = st(phase)
            assertEquals("$phase", Transition(s), cue.step(s, TimerFired(Timer.WorkingCue)))
        }
    }

    // ---- The gateway's real views ------------------------------------------------------------------------------------

    private fun fixture(name: String): SessionView = GatewayJson.decodeFromString(Fixtures.load(name).bodyText)

    @Test
    fun theGatewaysProgressViewIsSpoken() {
        val v = fixture("event_progress")
        val t = cue.step(st(working, WORKING_AGENT), View(v))
        val line = v.events.single()
        assertEquals(EventKind.Progress, line.kind)
        assertEquals(Phase.Speaking(line, working), t.phase)
        assertTrue(Play(line, withName = false) in t.effects)
    }

    @Test
    fun theGatewaysSummaryAfterProgressEndsTheTurn() {
        val first = fixture("event_progress")
        val then = fixture("event_progress_then_summary")
        val t = cue.run(
            st(working, WORKING_AGENT), View(first), PlaybackDone(first.events.single().id), View(then),
            PlaybackDone(then.events.single().id),
        )
        assertEquals(Phase.Listening, t.phase)
        assertEquals(2, t.effects.filterIsInstance<Play>().size)
        assertEquals(EventKind.Summary, then.events.single().kind)
    }
}
