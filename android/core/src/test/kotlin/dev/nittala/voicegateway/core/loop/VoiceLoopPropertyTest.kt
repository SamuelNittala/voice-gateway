package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.api.Fixtures
import dev.nittala.voicegateway.core.api.RefusalReason
import dev.nittala.voicegateway.core.api.refusalOf
import dev.nittala.voicegateway.core.model.AskKind
import dev.nittala.voicegateway.core.model.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import dev.nittala.voicegateway.core.model.PlanAnswer
import dev.nittala.voicegateway.core.worktree.CardPhrase
import dev.nittala.voicegateway.core.worktree.CardSpeech
import dev.nittala.voicegateway.core.worktree.CardVerdict
import dev.nittala.voicegateway.core.worktree.WorktreeFlow
import org.junit.Test
import kotlin.random.Random

/**
 * Cross-cutting rules of [VoiceLoop.step], checked over every phase x every input and over seeded random walks:
 * - every phase x input has a defined transition; no phase is a dead end; every phase is reachable from Idle;
 * - the mic is on exactly in Listening, AwaitingAnswer and Capturing, and off whenever the loop speaks or plays;
 * - a phase's timers run exactly while in it (StartTimer on entry, CancelTimer on exit);
 * - the poll interval effect always matches [VoiceLoop.pollInterval];
 * - a prompt is sent only on a transcript, a Yes/No tap, a Send-now tap, a typed prompt or the one refusal retry;
 *   `voice = true` except for a typed prompt (and its Send-now and retry), always to the current worktree, never `/`
 *   or `!` text by voice; Held never sends on its own;
 * - AwaitingTap never opens the mic.
 */
class VoiceLoopPropertyTest {
    private val loop = VoiceLoop()

    /** The first-prompt hand-off switched on (not the default): its walk checks the one first prompt. */
    private val handoffOn = VoiceLoop(LoopConfig(handoffFirstPrompt = true))

    // ---- Exhaustiveness (a new subtype fails to compile here until it is added to the samples) ------------------

    private fun phaseName(p: Phase): String = when (p) {
        Phase.Idle -> "Idle"
        Phase.Listening -> "Listening"
        is Phase.AwaitingAnswer -> "AwaitingAnswer"
        is Phase.AwaitingTap -> "AwaitingTap"
        is Phase.Capturing -> "Capturing"
        is Phase.Transcribing -> "Transcribing"
        is Phase.Sending -> "Sending"
        is Phase.AgentWorking -> "AgentWorking"
        is Phase.Speaking -> "Speaking"
        is Phase.NeedsYou -> "NeedsYou"
        is Phase.Held -> "Held"
        is Phase.Paused -> "Paused"
        is Phase.Stopped -> "Stopped"
        is Phase.Error -> "Error"
        Phase.LoggedOut -> "LoggedOut"
        is Phase.NewWorktree -> "NewWorktree"
    }

    private fun inputName(i: LoopInput): String = when (i) {
        LoopInput.Tap -> "Tap"
        LoopInput.LongPress -> "LongPress"
        LoopInput.HeadsetPress -> "HeadsetPress"
        LoopInput.HeadsetLongPress -> "HeadsetLongPress"
        LoopInput.SpeechStart -> "SpeechStart"
        is LoopInput.SpeechEnd -> "SpeechEnd"
        is LoopInput.Transcribed -> "Transcribed"
        is LoopInput.TranscribeFailed -> "TranscribeFailed"
        is LoopInput.SendOk -> "SendOk"
        is LoopInput.SendRefused -> "SendRefused"
        is LoopInput.SendFailed -> "SendFailed"
        is LoopInput.View -> "View"
        is LoopInput.PlaybackDone -> "PlaybackDone"
        is LoopInput.PlaybackFailed -> "PlaybackFailed"
        LoopInput.FocusLost -> "FocusLost"
        LoopInput.NetworkLost -> "NetworkLost"
        LoopInput.NetworkBack -> "NetworkBack"
        is LoopInput.WorktreeChanged -> "WorktreeChanged"
        LoopInput.Unauthorized -> "Unauthorized"
        LoopInput.LoggedIn -> "LoggedIn"
        LoopInput.StopTap -> "StopTap"
        LoopInput.RestartTap -> "RestartTap"
        is LoopInput.KeyTap -> "KeyTap"
        is LoopInput.KeyRefused -> "KeyRefused"
        is LoopInput.ConfirmTap -> "ConfirmTap"
        LoopInput.SendNowTap -> "SendNowTap"
        LoopInput.DiscardTap -> "DiscardTap"
        is LoopInput.MuteChanged -> "MuteChanged"
        is LoopInput.ProgressChanged -> "ProgressChanged"
        is LoopInput.AppVisible -> "AppVisible"
        LoopInput.AppStopped -> "AppStopped"
        is LoopInput.TimerFired -> "TimerFired"
        is LoopInput.MicFailed -> "MicFailed"
        is LoopInput.TypedSend -> "TypedSend"
        LoopInput.NewWorktreeTap -> "NewWorktreeTap"
        is LoopInput.NewWorktreeTyped -> "NewWorktreeTyped"
        is LoopInput.Planned -> "Planned"
        is LoopInput.PlanRefused -> "PlanRefused"
        LoopInput.PlanFailed -> "PlanFailed"
        is LoopInput.CandidateTap -> "CandidateTap"
        is LoopInput.BaseTap -> "BaseTap"
        is LoopInput.BaseTyped -> "BaseTyped"
        LoopInput.CreateTap -> "CreateTap"
        LoopInput.OfflineCreateTap -> "OfflineCreateTap"
        LoopInput.CancelTap -> "CancelTap"
        is LoopInput.Created -> "Created"
        is LoopInput.CreateRefused -> "CreateRefused"
        LoopInput.CreateMaybeDone -> "CreateMaybeDone"
    }

    private fun effectName(e: Effect): String = when (e) {
        is Effect.StartCapture -> "StartCapture"
        Effect.StopCapture -> "StopCapture"
        Effect.FinishCapture -> "FinishCapture"
        is Effect.Upload -> "Upload"
        is Effect.Send -> "Send"
        is Effect.Play -> "Play"
        Effect.StopPlayback -> "StopPlayback"
        is Effect.Ack -> "Ack"
        is Effect.Earcon -> "Earcon"
        is Effect.Say -> "Say"
        Effect.Vibrate -> "Vibrate"
        is Effect.Notify -> "Notify"
        is Effect.SetPoll -> "SetPoll"
        Effect.ShowSheet -> "ShowSheet"
        is Effect.SendKey -> "SendKey"
        is Effect.SendStop -> "SendStop"
        is Effect.SendRestart -> "SendRestart"
        is Effect.StartTimer -> "StartTimer"
        is Effect.CancelTimer -> "CancelTimer"
        Effect.EndService -> "EndService"
        is Effect.PlanWorktree -> "PlanWorktree"
        is Effect.ReplanBase -> "ReplanBase"
        is Effect.CreateWorktree -> "CreateWorktree"
    }

    private val agents = listOf(IDLE_AGENT, WORKING_AGENT, BLOCKED_AGENT, AgentView.Unknown, AgentView(state = "stopped"))

    private fun sampleInputs(): List<LoopInput> = listOf(
        LoopInput.Tap, LoopInput.LongPress, LoopInput.HeadsetPress, LoopInput.HeadsetLongPress, LoopInput.SpeechStart,
        LoopInput.SpeechEnd(GOOD), LoopInput.SpeechEnd(QUIET), LoopInput.Transcribed(WT, TEXT),
        LoopInput.Transcribed(WT, "/clear"), LoopInput.TranscribeFailed(WT, TranscribeFailure.Network),
        LoopInput.TranscribeFailed(WT, TranscribeFailure.Refused(refusal(RefusalReason.SpeechOff, 404))),
        LoopInput.SendOk(WT), LoopInput.SendRefused(WT, refusal(RefusalReason.Stopped)),
        LoopInput.SendRefused(WT, refusal(RefusalReason.PaneChanged)),
        LoopInput.SendRefused(WT, refusal(RefusalReason.NotSettled)), LoopInput.SendFailed(WT, true),
        LoopInput.View(view("idle")), LoopInput.View(view("working")), LoopInput.View(view("blocked", token = "t")),
        LoopInput.View(view("idle", state = "stopped")), LoopInput.View(view("idle", events = listOf(event("e1")))),
        LoopInput.View(view("idle", events = listOf(QUESTION))), LoopInput.View(view("idle", events = listOf(CONFIRM))),
        LoopInput.PlaybackDone("e1"), LoopInput.PlaybackFailed("e1"), LoopInput.FocusLost, LoopInput.NetworkLost,
        LoopInput.NetworkBack, LoopInput.WorktreeChanged(OTHER), LoopInput.Unauthorized, LoopInput.LoggedIn,
        LoopInput.StopTap, LoopInput.RestartTap, LoopInput.KeyTap("1"),
        LoopInput.KeyRefused(WT, refusal(RefusalReason.ScreenChanged, screenToken = "t2", output = "o")),
        LoopInput.ConfirmTap(true), LoopInput.ConfirmTap(false), LoopInput.SendNowTap, LoopInput.DiscardTap,
        LoopInput.MuteChanged(true), LoopInput.ProgressChanged(false), LoopInput.AppVisible(false), LoopInput.AppStopped,
        LoopInput.MicFailed(MicFailure.Silenced), LoopInput.MicFailed(MicFailure.Unavailable), LoopInput.TypedSend(TEXT),
    ) + flowInputs() + Timer.entries.map { LoopInput.TimerFired(it) }

    /** W6: every new-worktree input, with real gateway answers (fixtures). */
    private fun flowInputs(): List<LoopInput> = listOf(
        LoopInput.NewWorktreeTap, LoopInput.NewWorktreeTyped(QUERY), LoopInput.Planned(READY), LoopInput.Planned(ASK),
        LoopInput.Planned(NEEDS_BASE), LoopInput.PlanRefused(refusal(RefusalReason.PlanBusy)), LoopInput.PlanFailed,
        LoopInput.CandidateTap(ASK.candidates.first().id), LoopInput.BaseTap("develop"), LoopInput.BaseTyped("uat"),
        LoopInput.CreateTap, LoopInput.OfflineCreateTap, LoopInput.CancelTap, LoopInput.Created(CREATED),
        LoopInput.CreateRefused(refusal(RefusalReason.RepoChanged)), LoopInput.CreateMaybeDone,
    )

    @Test
    fun theSamplesCoverEveryPhaseAndEveryInput() {
        assertEquals(16, samplePhases().map(::phaseName).toSet().size)
        assertEquals(48, sampleInputs().map(::inputName).toSet().size)
        val effects = listOf(
            Effect.StartCapture(false), Effect.StopCapture, Effect.FinishCapture, Effect.Upload(WT, GOOD),
            Effect.Send(WT, TEXT), Effect.Play(QUESTION, false), Effect.StopPlayback, Effect.Ack(listOf("e1")),
            Effect.Earcon(EarconKind.Sent), Effect.Say("x"), Effect.Vibrate, Effect.Notify("x"), Effect.SetPoll(null),
            Effect.ShowSheet, Effect.SendKey(WT, "1", null), Effect.SendStop(WT), Effect.SendRestart(WT),
            Effect.StartTimer(Timer.NoSpeech, 1), Effect.CancelTimer(Timer.NoSpeech), Effect.EndService,
            Effect.PlanWorktree(QUERY), Effect.ReplanBase("p", "uat"), Effect.CreateWorktree("p"),
        )
        assertEquals(23, effects.map(::effectName).toSet().size)
    }

    @Test
    fun everyPhaseTimesEveryInputYieldsADefinedTransition() {
        for (phase in samplePhases()) for (agent in agents) for (input in sampleInputs()) {
            val s = st(phase, agent)
            val t = try {
                loop.step(s, input)
            } catch (e: Throwable) {
                fail("${phaseName(phase)} x ${inputName(input)} threw $e"); return
            }
            assertTrue(t.state.worktree.isNotEmpty())
            t.effects.forEach { effectName(it) }
        }
    }

    @Test
    fun noPhaseIsADeadEnd() {
        for (phase in samplePhases()) {
            val exits = sampleInputs().filter { phaseName(loop.step(st(phase), it).phase) != phaseName(phase) }
            assertTrue("${phaseName(phase)} has no exit", exits.isNotEmpty())
        }
    }

    @Test
    fun everyPhaseIsReachableFromIdle() {
        val inputs = sampleInputs() + listOf(
            LoopInput.SpeechEnd(GOOD), LoopInput.Transcribed(WT, "yes"),
            LoopInput.PlaybackDone("q1"), LoopInput.PlaybackDone("c1"), LoopInput.WorktreeChanged(WT),
        )
        val seen = mutableMapOf<String, LoopState>()
        var frontier = listOf(st(Phase.Idle))
        repeat(10) {
            frontier = frontier.flatMap { s -> inputs.map { loop.step(s, it).state } }
                .filter { s ->
                    val speaking = (s.phase as? Phase.Speaking)?.event?.id
                    seen.putIfAbsent("${phaseName(s.phase)} $speaking ${s.agent.status} ${s.worktree}", s) == null
                }
        }
        assertEquals(samplePhases().map(::phaseName).toSet(), seen.values.map { phaseName(it.phase) }.toSet())
    }

    // ---- Random walks ---------------------------------------------------------------------------------------------

    private fun micOn(p: Phase) = p == Phase.Listening || p is Phase.AwaitingAnswer || p is Phase.Capturing ||
        (p is Phase.NewWorktree && (p.flow as? WorktreeFlow.Request)?.listening == true) ||
        (p is Phase.NewWorktree && (p.flow as? WorktreeFlow.Card)?.listening == true)

    /** Spec: the timers that run in each phase. */
    private fun expectedTimers(p: Phase): Set<Timer> = when (p) {
        Phase.Listening, is Phase.AwaitingAnswer -> setOf(Timer.NoSpeech)
        is Phase.AwaitingTap -> if (p.reminded) emptySet() else setOf(Timer.ConfirmReminder)
        is Phase.NeedsYou -> if (p.reminded) emptySet() else setOf(Timer.NeedsYouReminder)
        is Phase.Transcribing -> (if (p.retryPending) setOf(Timer.TranscribeRetry) else emptySet()) + cardExpiry(p.purpose)
        is Phase.Capturing -> cardExpiry(p.purpose)
        is Phase.Sending -> if (p.retryPending) setOf(Timer.SendRetry) else emptySet()
        is Phase.AgentWorking -> if (p.settledTimer) setOf(Timer.Settled) else emptySet()
        is Phase.Paused -> setOf(Timer.PauseLimit)
        is Phase.NewWorktree -> when (val f = p.flow) {
            is WorktreeFlow.Request -> if (f.listening) setOf(Timer.NoSpeech) else emptySet()
            is WorktreeFlow.Card -> (if (f.listening) setOf(Timer.NoSpeech) else emptySet()) +
                (if (f.expired) emptySet() else setOf(Timer.PlanExpiry))
            is WorktreeFlow.Handoff -> setOf(Timer.HandoffLimit)
            else -> emptySet()
        }
        else -> emptySet()
    }

    /** W6: the card's expiry keeps running while its spoken reply is heard. */
    private fun cardExpiry(purpose: CapturePurpose): Set<Timer> {
        val card = (purpose as? CapturePurpose.Worktree)?.back?.flow as? WorktreeFlow.Card ?: return emptySet()
        return if (card.expired) emptySet() else setOf(Timer.PlanExpiry)
    }

    private val texts = listOf(TEXT, "yes", "okay", "/clear it", "!ls -la", "thank you", "the api one", "", "no")
    private val eventPool = listOf(
        event("e1"), event("e2", kind = EventKind.CheckScreen), QUESTION, CONFIRM,
        event("n1", kind = EventKind.NeedsYou), event("o1", session = OTHER),
        event("o2", ask = AskKind.Answer, session = OTHER), event("o3", ask = AskKind.Confirm, session = OTHER),
    )
    private val reasons = RefusalReason.entries + listOf<RefusalReason?>(null)

    private fun Random.input(s: LoopState): LoopInput {
        val wt = if (nextInt(10) == 0) OTHER else (s.phase as? Phase.Transcribing)?.worktree ?: s.worktree
        val speaking = (s.phase as? Phase.Speaking)?.event?.id ?: eventPool.random(this).id
        return when (nextInt(36)) {
            0, 1 -> LoopInput.Tap
            2 -> if (nextInt(4) == 0) LoopInput.LongPress else LoopInput.HeadsetPress
            3 -> if (nextInt(4) == 0) LoopInput.HeadsetLongPress else LoopInput.SpeechStart
            4, 5 -> LoopInput.SpeechStart
            6, 7 -> LoopInput.SpeechEnd(listOf(GOOD, GOOD, QUIET, SPARSE).random(this))
            8, 9 -> LoopInput.Transcribed(wt, texts.random(this))
            10 -> LoopInput.TranscribeFailed(
                wt,
                if (nextBoolean()) TranscribeFailure.Network
                else TranscribeFailure.Refused(refusal(reasons.random(this), 429, retryAfter = nextInt(1, 9))),
            )
            11 -> LoopInput.SendOk(wt)
            12 -> LoopInput.SendRefused(wt, refusal(reasons.random(this)))
            13 -> LoopInput.SendFailed(wt, nextBoolean())
            14, 15, 16, 17 -> LoopInput.View(
                view(
                    status = listOf("idle", "done", "working", "blocked", null).random(this),
                    state = if (nextInt(12) == 0) "stopped" else "running",
                    events = eventPool.filter { nextInt(6) == 0 },
                    waiting = nextInt(4) == 0,
                    token = "tok${nextInt(3)}",
                    name = if (nextInt(8) == 0) OTHER else s.worktree,
                ),
            )
            18, 19 -> if (nextInt(5) == 0) LoopInput.PlaybackFailed(speaking) else LoopInput.PlaybackDone(speaking)
            20 -> if (nextInt(4) == 0) LoopInput.FocusLost else LoopInput.NetworkBack
            21 -> if (nextBoolean()) LoopInput.NetworkLost else LoopInput.NetworkBack
            22 -> LoopInput.WorktreeChanged(listOf(WT, OTHER).random(this))
            23 -> if (nextInt(6) == 0) LoopInput.Unauthorized else LoopInput.LoggedIn
            24 -> if (nextBoolean()) LoopInput.StopTap else LoopInput.RestartTap
            25 -> if (nextBoolean()) LoopInput.KeyTap("1")
            else LoopInput.KeyRefused(s.worktree, refusal(reasons.random(this), screenToken = "tokx", output = "o"))
            26 -> LoopInput.ConfirmTap(nextBoolean())
            27 -> if (nextBoolean()) LoopInput.SendNowTap else LoopInput.DiscardTap
            28 -> LoopInput.MuteChanged(nextInt(3) == 0)
            29 -> if (nextInt(6) == 0) LoopInput.AppStopped else LoopInput.AppVisible(nextBoolean())
            30 -> LoopInput.MicFailed(MicFailure.entries.random(this))
            31 -> LoopInput.TypedSend(texts.random(this))
            else -> LoopInput.TimerFired(Timer.entries.random(this))
        }
    }

    private fun mayStartASend(before: LoopState, input: LoopInput): Boolean = when (input) {
        is LoopInput.Transcribed -> before.phase is Phase.Transcribing
        is LoopInput.ConfirmTap -> before.phase is Phase.AwaitingTap
        LoopInput.SendNowTap -> before.phase is Phase.Held
        is LoopInput.TypedSend -> input.text.isNotBlank() && typedAllowed(before.phase)
        is LoopInput.TimerFired -> input.timer == Timer.SendRetry && (before.phase as? Phase.Sending)?.retryPending == true
        is LoopInput.View -> (before.phase as? Phase.NewWorktree)?.flow is WorktreeFlow.Handoff
        else -> false
    }

    private fun typedAllowed(p: Phase) = p == Phase.Idle || p == Phase.Listening || p is Phase.AwaitingAnswer ||
        p is Phase.AwaitingTap || p is Phase.Paused || p is Phase.Held || p is Phase.Error

    /** Only a typed prompt (the TypedSend itself, its Send-now from Held, its one retry) goes out with voice false. */
    private fun typed(before: LoopState, input: LoopInput): Boolean = when (input) {
        is LoopInput.TypedSend -> true
        LoopInput.SendNowTap -> (before.phase as? Phase.Held)?.voice == false
        is LoopInput.TimerFired -> (before.phase as? Phase.Sending)?.voice == false
        is LoopInput.View -> ((before.phase as? Phase.NewWorktree)?.flow as? WorktreeFlow.Handoff)?.voice == false
        else -> false
    }

    @Test
    fun randomWalksKeepEveryCrossCuttingRule() {
        val random = Random(20261108)
        var steps = 0
        var sends = 0
        repeat(400) { walk ->
            var s = LoopState(worktree = WT)
            var mic = false
            var timers = emptySet<Timer>()
            var poll = loop.pollInterval(s)
            repeat(200) {
                val input = random.input(s)
                val t = loop.step(s, input)
                val where = "walk $walk: ${phaseName(s.phase)} x $input -> ${phaseName(t.phase)} ${t.effects}"
                if (input is LoopInput.TimerFired) timers = timers - input.timer
                for (e in t.effects) {
                    when (e) {
                        is Effect.StartCapture -> mic = true
                        Effect.StopCapture -> mic = false
                        is Effect.Say, is Effect.Play -> assertFalse("audio with the mic on: $where", mic)
                        is Effect.StartTimer -> timers = timers + e.timer
                        is Effect.CancelTimer -> timers = timers - e.timer
                        is Effect.SetPoll -> poll = e.intervalMs
                        is Effect.Send -> {
                            sends++
                            assertTrue("unexpected send: $where", mayStartASend(s, input))
                            assertEquals(where, !typed(s, input), e.voice)
                            assertEquals(where, s.worktree, e.session)
                            if (e.voice) {
                                assertFalse(where, e.text.trimStart().startsWith("/") || e.text.trimStart().startsWith("!"))
                            }
                        }
                        else -> Unit
                    }
                }
                assertEquals("mic: $where", micOn(t.phase), mic)
                assertEquals("timers: $where", expectedTimers(t.phase), timers)
                assertEquals("poll: $where", loop.pollInterval(t.state), poll)
                assertTrue(where, t.state.rejections < loop.config.maxRejections)
                val fromUserOrMic = input is LoopInput.SpeechEnd || input is LoopInput.Transcribed ||
                    input == LoopInput.SpeechStart || input == LoopInput.Tap || input == LoopInput.HeadsetPress
                if (t.phase is Phase.AwaitingTap || (s.phase is Phase.AwaitingTap && fromUserOrMic)) {
                    assertTrue("mic opened in AwaitingTap: $where", t.effects.none { it is Effect.StartCapture })
                }
                if (s.phase is Phase.Held && input != LoopInput.SendNowTap && input !is LoopInput.TypedSend) assertTrue(where, t.effects.none { it is Effect.Send })
                if (t.phase is Phase.Speaking) assertFalse(where, (t.phase as Phase.Speaking).resume is Phase.Speaking)
                if (s.phase is Phase.LoggedOut && input != LoopInput.LoggedIn) assertEquals(where, Transition(s), t)
                s = t.state
                steps++
            }
        }
        assertTrue("walks too short to mean anything: $steps steps, $sends sends", sends > 100)
    }

    @Test
    fun randomWalksNeverResendAfterMaybeSent() {
        val random = Random(7)
        repeat(300) {
            var s = LoopState(worktree = WT, phase = Phase.Sending(WT, TEXT), agent = IDLE_AGENT)
            s = loop.step(s, LoopInput.SendFailed(WT, maybeSent = true)).state
            repeat(100) {
                val input = random.input(s)
                if (input is LoopInput.Transcribed || input is LoopInput.ConfirmTap || input == LoopInput.SendNowTap ||
                    input is LoopInput.TypedSend
                ) return@repeat
                val t = loop.step(s, input)
                assertTrue("resent after maybeSent: $input", t.effects.none { it is Effect.Send })
                s = t.state
            }
        }
    }

    // ---- Progress updates: the working cue and progress lines -------------------------------------------------------

    private val withCue = VoiceLoop(LoopConfig(workingCueMs = WORKING_CUE_MS))
    private val progressPool = listOf(
        progress("p1"), progress("p2", text = "Running the suite"), progress("p3", session = OTHER),
        event("e1"), QUESTION, CONFIRM, event("n1", kind = EventKind.NeedsYou), event("o1", session = OTHER),
    )

    /** The usual inputs, plus views carrying progress lines and the setting being turned on and off. */
    private fun Random.progressInput(s: LoopState): LoopInput {
        val speaking = (s.phase as? Phase.Speaking)?.event?.id
        return when (nextInt(10)) {
            0, 1 -> LoopInput.View(
                view(
                    status = listOf("working", "working", "idle", "blocked").random(this),
                    events = progressPool.filter { nextInt(4) == 0 },
                    waiting = nextBoolean(),
                    token = "tok${nextInt(2)}",
                    name = if (nextInt(8) == 0) OTHER else s.worktree,
                ),
            )
            2 -> LoopInput.ProgressChanged(nextInt(3) != 0)
            3 -> if (speaking != null) LoopInput.PlaybackDone(speaking) else LoopInput.TimerFired(Timer.WorkingCue)
            else -> input(s)
        }
    }

    @Test
    fun randomWalksKeepTheProgressRules() {
        val random = Random(20261109)
        var played = 0
        var cues = 0
        repeat(400) { walk ->
            var s = LoopState(worktree = WT)
            var mic = false
            var timers = emptySet<Timer>()
            repeat(200) {
                val input = random.progressInput(s)
                val t = withCue.step(s, input)
                val where = "walk $walk: ${phaseName(s.phase)} x $input -> ${phaseName(t.phase)} ${t.effects}"
                if (input is LoopInput.TimerFired) timers = timers - input.timer
                for (e in t.effects) {
                    when (e) {
                        is Effect.StartCapture -> mic = true
                        Effect.StopCapture -> mic = false
                        is Effect.Say, is Effect.Play -> assertFalse("audio with the mic on: $where", mic)
                        is Effect.StartTimer -> timers = timers + e.timer
                        is Effect.CancelTimer -> timers = timers - e.timer
                        else -> Unit
                    }
                    if (e is Effect.Play && e.event.kind == EventKind.Progress) {
                        played++
                        val now = t.phase as? Phase.Speaking
                        assertEquals("progress played outside AgentWorking: $where", e.event, now?.event)
                        assertTrue("progress played outside AgentWorking: $where", now!!.resume is Phase.AgentWorking)
                        assertEquals("another worktree's progress: $where", t.state.worktree, e.event.session)
                        assertTrue(where, t.state.spokenProgress && !t.state.muted)
                    }
                    if (e == Effect.Earcon(EarconKind.Working)) {
                        cues++
                        assertTrue("cue outside AgentWorking: $where", s.phase is Phase.AgentWorking && t.phase is Phase.AgentWorking)
                    }
                }
                assertEquals("mic: $where", micOn(t.phase), mic)
                val cue = if (t.phase is Phase.AgentWorking) setOf(Timer.WorkingCue) else emptySet()
                assertEquals("timers: $where", expectedTimers(t.phase) + cue, timers)
                assertTrue("a progress line became a question: $where", t.state.questions.values.none { it.kind == EventKind.Progress })
                assertTrue("progress queued for later: $where", t.state.queue.none { it.kind == EventKind.Progress && !(t.phase is Phase.AgentWorking) })
                val p = s.phase
                if (p is Phase.Speaking && p.event.kind == EventKind.Progress && input == LoopInput.PlaybackDone(p.event.id) &&
                    s.queue.isEmpty()
                ) {
                    assertTrue("a progress line opened the mic: $where", t.effects.none { it is Effect.StartCapture })
                    assertTrue(where, t.phase is Phase.AgentWorking)
                }
                val keeps = t.phase is Phase.AgentWorking || (t.phase as? Phase.Speaking)?.resume is Phase.AgentWorking
                if (!keeps) assertEquals("subtitle kept out of AgentWorking: $where", null, t.state.lastProgress)
                s = t.state
            }
        }
        assertTrue("walks too short to mean anything: $played lines, $cues cues", played > 50 && cues > 50)
    }

    // ---- W6: the new-worktree flow --------------------------------------------------------------------------------

    private val flowTexts = listOf(QUERY, "create it", "Yes, create it.", "cancel", "create it in the other one", "yes",
        "", "thank you", "/clear", "stop")

    /** Inputs biased towards the flow: the old inputs, every flow input, and transcripts of flow-like text. */
    private fun Random.flowInput(s: LoopState): LoopInput {
        val p = s.phase
        val wt = (p as? Phase.Transcribing)?.worktree ?: s.worktree
        val flow = (p as? Phase.NewWorktree)?.flow
        if (nextBoolean()) {
            // Half the time, an input that moves the flow on from where it is.
            when (flow) {
                is WorktreeFlow.Request -> return if (nextBoolean()) LoopInput.NewWorktreeTyped(QUERY) else LoopInput.SpeechStart
                is WorktreeFlow.Planning -> return LoopInput.Planned(listOf(READY, READY, NEEDS_BASE, EXISTING, ASK).random(this))
                is WorktreeFlow.Choosing -> return LoopInput.CandidateTap(ASK.candidates.random(this).id)
                is WorktreeFlow.Card -> return listOf(LoopInput.CreateTap, LoopInput.SpeechStart, LoopInput.BaseTap("uat")).random(this)
                is WorktreeFlow.Creating -> return listOf(
                    LoopInput.Created(CREATED), LoopInput.Created(CREATED), LoopInput.CreateMaybeDone,
                    LoopInput.CreateRefused(Fixtures.load("refusal_fetch_failed_offline").let { refusalOf(it.status, it.bodyText) }),
                ).random(this)
                is WorktreeFlow.Failed -> return listOf(LoopInput.OfflineCreateTap, LoopInput.CreateTap).random(this)
                else -> Unit
            }
        }
        return when (nextInt(14)) {
            0, 1 -> input(s)
            2 -> LoopInput.NewWorktreeTap
            3 -> LoopInput.SpeechStart
            4 -> LoopInput.SpeechEnd(listOf(GOOD, LONG, QUIET).random(this))
            5 -> LoopInput.Transcribed(wt, flowTexts.random(this))
            6 -> flowInputs().random(this)
            7 -> LoopInput.View(
                view(
                    status = listOf("idle", "done", "working", "blocked", null).random(this),
                    name = if (nextInt(6) == 0) OTHER else s.worktree, token = "tok${nextInt(2)}",
                ),
            )
            8 -> LoopInput.TimerFired(listOf(Timer.NoSpeech, Timer.PlanExpiry, Timer.HandoffLimit).random(this))
            9 -> LoopInput.KeyTap("1")
            10 -> LoopInput.CreateRefused(
                if (nextBoolean()) Fixtures.load("refusal_fetch_failed_offline").let { refusalOf(it.status, it.bodyText) }
                else refusal(RefusalReason.WtFailed, 502),
            )
            11 -> LoopInput.Tap
            12 -> LoopInput.MuteChanged(nextInt(4) == 0)
            else -> LoopInput.SendOk(s.worktree)
        }
    }

    @Test
    fun randomWalksThroughTheNewWorktreeFlowKeepItsRules() {
        val random = Random(20261109)
        var creates = 0
        var firstPrompts = 0
        var cards = 0
        repeat(500) { walk ->
            var s = LoopState(worktree = WT, agent = IDLE_AGENT)
            var mic = false
            var timers = emptySet<Timer>()
            val readAloud = mutableSetOf<String>()
            val createdPlans = mutableListOf<String>()
            var createdSession: String? = null
            var flowSends = 0
            repeat(150) {
                val input = random.flowInput(s)
                val t = handoffOn.step(s, input)
                val where = "walk $walk: ${phaseName(s.phase)} ${s.phase} x $input -> ${t.phase} ${t.effects}"
                if (input is LoopInput.TimerFired) timers = timers - input.timer
                if (input is LoopInput.Created && s.phase is Phase.NewWorktree) createdSession = input.answer.session
                // The fake gateway replays fixtures, so the server's fresh id for each new plan is the same string here.
                val flowBefore = (s.phase as? Phase.NewWorktree)?.flow
                if (input is LoopInput.Planned && flowBefore is WorktreeFlow.Planning) {
                    (input.answer as? PlanAnswer.Plan)?.let { createdPlans -= it.planId }
                }
                if (input is LoopInput.CreateRefused && flowBefore is WorktreeFlow.Creating) {
                    input.refusal.offline?.let { createdPlans -= it.planId }
                }
                val beforeFlow = (s.phase as? Phase.NewWorktree)?.flow
                val capturedFor = ((s.phase as? Phase.Transcribing)?.purpose as? CapturePurpose.Worktree)?.back?.flow
                for (e in t.effects) {
                    when (e) {
                        is Effect.StartCapture -> {
                            mic = true
                            val card = (t.phase as? Phase.NewWorktree)?.flow as? WorktreeFlow.Card
                            if (card != null) {
                                assertTrue("card mic when not ready or expired: $where", card.plan.ready && !card.expired)
                                // Read aloud first, or opened by a tap on the card the user is looking at.
                                if (input == LoopInput.Tap) readAloud += card.plan.planId
                                assertTrue("card mic before it was read: $where", card.plan.planId in readAloud)
                            }
                        }
                        Effect.StopCapture -> mic = false
                        is Effect.Say -> {
                            assertFalse("audio with the mic on: $where", mic)
                            listOf(READY, NEEDS_BASE, EXISTING).firstOrNull { CardSpeech.of(it) == e.text }?.let { readAloud += it.planId }
                        }
                        is Effect.Play -> assertFalse("audio with the mic on: $where", mic)
                        is Effect.StartTimer -> timers = timers + e.timer
                        is Effect.CancelTimer -> timers = timers - e.timer
                        is Effect.CreateWorktree -> {
                            creates++
                            val byPhrase = input is LoopInput.Transcribed && capturedFor is WorktreeFlow.Card &&
                                CardPhrase.judge(input.text) == CardVerdict.Create
                            assertTrue("create without a tap or the phrase: $where",
                                input == LoopInput.CreateTap || input == LoopInput.OfflineCreateTap || byPhrase)
                            assertFalse("a plan created twice: $where", e.planId in createdPlans)
                            createdPlans += e.planId
                        }
                        is Effect.PlanWorktree -> {
                            val heard = input is LoopInput.Transcribed && input.text.isNotBlank() &&
                                capturedFor is WorktreeFlow.Request
                            assertTrue("planned on silence or without a request: $where",
                                heard || input is LoopInput.NewWorktreeTyped || input is LoopInput.CandidateTap ||
                                    input == LoopInput.CreateTap)
                        }
                        is Effect.ReplanBase ->
                            assertTrue(where, input is LoopInput.BaseTap || input is LoopInput.BaseTyped)
                        is Effect.Send -> if (beforeFlow is WorktreeFlow.Handoff) {
                            firstPrompts++
                            flowSends++
                            assertEquals("first prompt elsewhere: $where", createdSession, e.session)
                            assertEquals(where, beforeFlow.query, e.text)
                            assertTrue("first prompt sent twice: $where", flowSends <= 1)
                        }
                        else -> Unit
                    }
                }
                if (t.phase is Phase.NewWorktree && (t.phase as Phase.NewWorktree).flow is WorktreeFlow.Card) cards++
                assertEquals("mic: $where", micOn(t.phase), mic)
                assertEquals("timers: $where", expectedTimers(t.phase), timers)
                assertMicProblemShownOnlyOnAQuietRequestOrCard(t.state, where)
                if (input is LoopInput.Created && s.phase is Phase.NewWorktree) flowSends = 0
                s = t.state
            }
        }
        assertTrue("walks never created: $creates", creates > 50)
        assertTrue("walks never sent a first prompt: $firstPrompts", firstPrompts > 20)
        assertTrue("walks never showed a card: $cards", cards > 100)
    }

    // ---- Microphone failures (permission gate fix) ---------------------------------------------------------------

    /** The loop's mic message exists only on a Request or Card of the flow with the mic off. */
    private fun assertMicProblemShownOnlyOnAQuietRequestOrCard(s: LoopState, where: String) {
        if (s.micProblem == null) return
        val flow = (s.phase as? Phase.NewWorktree)?.flow
        assertTrue("mic problem outside the flow: $where", flow is WorktreeFlow.Request || flow is WorktreeFlow.Card)
        assertFalse("mic problem with the mic on: $where", flow!!.listening)
    }

    /** The flow a phase shows: the flow itself, or the one a capture or upload belongs to. */
    private fun shownFlow(p: Phase): WorktreeFlow? = when (p) {
        is Phase.NewWorktree -> p.flow
        is Phase.Capturing -> (p.purpose as? CapturePurpose.Worktree)?.back?.flow
        is Phase.Transcribing -> (p.purpose as? CapturePurpose.Worktree)?.back?.flow
        else -> null
    }

    @Test
    fun aMicFailureIsSaidOnceAndNeverRepeatsOrDropsTheFlow() {
        val flowPhases = listOf(
            Phase.NewWorktree(WorktreeFlow.Request(listening = true)),
            Phase.NewWorktree(WorktreeFlow.Request(listening = false)),
            Phase.NewWorktree(WorktreeFlow.Card(READY, QUERY, voice = true, listening = true)),
            Phase.NewWorktree(WorktreeFlow.Card(READY, QUERY, voice = false)),
            Phase.Capturing(WT, purpose = CapturePurpose.Worktree(Phase.NewWorktree(WorktreeFlow.Request(listening = true)))),
            Phase.Transcribing(WT, GOOD, purpose = CapturePurpose.Worktree(Phase.NewWorktree(WorktreeFlow.Request(true)))),
        )
        for (phase in samplePhases() + flowPhases) for (agent in agents) for (failure in MicFailure.entries) {
            val s = st(phase, agent)
            val first = loop.step(s, LoopInput.MicFailed(failure))
            val where = "${phaseName(phase)} $phase $agent x $failure -> ${first.phase} ${first.effects}"
            assertTrue("more than one message: $where", first.effects.count { it is Effect.Say } <= 1)
            assertTrue("mic reopened by a failure: $where", first.effects.none { it is Effect.StartCapture })
            assertEquals("a second failure is not silent: $where", Transition(first.state), loop.step(first.state, LoopInput.MicFailed(failure)))
            val before = shownFlow(phase)
            if (before != null) {
                val after = shownFlow(first.phase)
                assertTrue("flow dropped: $where", after != null && after::class == before::class)
            }
            assertMicProblemShownOnlyOnAQuietRequestOrCard(first.state, where)
        }
    }

    @Test
    fun byDefaultACreateNeverSendsAnythingToTheNewWorktree() {
        val random = Random(20261110)
        var creates = 0
        repeat(500) { walk ->
            var s = LoopState(worktree = WT, agent = IDLE_AGENT)
            repeat(150) {
                val input = random.flowInput(s)
                val t = loop.step(s, input)
                val where = "walk $walk: ${phaseName(s.phase)} ${s.phase} x $input -> ${t.phase} ${t.effects}"
                if (input is LoopInput.Created && (s.phase as? Phase.NewWorktree)?.flow is WorktreeFlow.Creating) {
                    creates++
                    assertEquals(where, Phase.Idle, t.phase)
                    assertEquals(where, input.answer.session, t.state.worktree)
                }
                assertFalse("hand-off by default: $where", shownFlow(t.phase) is WorktreeFlow.Handoff)
                assertFalse("request kept by default: $where",
                    (t.phase as? Phase.Held)?.reason == HeldReason.NewWorktreeRequest)
                if (input is LoopInput.Created || input is LoopInput.View) {
                    assertTrue("sent without the user: $where", t.effects.none { it is Effect.Send })
                }
                assertMicProblemShownOnlyOnAQuietRequestOrCard(t.state, where)
                s = t.state
            }
        }
        assertTrue("walks never created: $creates", creates > 50)
    }
}
