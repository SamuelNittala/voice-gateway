package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.audio.CaptureEvent
import dev.nittala.voicegateway.audio.CaptureFailure
import dev.nittala.voicegateway.audio.CaptureRejection
import dev.nittala.voicegateway.audio.CaptureStats
import dev.nittala.voicegateway.core.loop.EarconKind
import dev.nittala.voicegateway.core.loop.HeldReason
import dev.nittala.voicegateway.core.loop.Lines
import dev.nittala.voicegateway.core.loop.LoopConfig
import dev.nittala.voicegateway.core.loop.LoopInput
import dev.nittala.voicegateway.core.loop.LoopState
import dev.nittala.voicegateway.core.loop.PauseReason
import dev.nittala.voicegateway.core.loop.Phase
import dev.nittala.voicegateway.core.loop.VoiceLoop
import dev.nittala.voicegateway.core.ui.MicTexts
import dev.nittala.voicegateway.core.worktree.WorktreeFlow
import dev.nittala.voicegateway.core.worktree.WorktreeLines
import dev.nittala.voicegateway.core.model.AskKind
import dev.nittala.voicegateway.core.speech.Engine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

const val WT = "wt-scratch"
private const val FIRST = "run the tests in the api worktree"

/**
 * Spec (A10, plan "A10" and section 7): whole turns through the real [LoopController], [Poller] and `VoiceLoop`, with a
 * [FakeGatewayApi] replaying the real gateway answers, a fake microphone, speaker and phone voice, and virtual time.
 * The only tap in a hands-free turn is the one that starts the loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopControllerScenarioTest {
    private class Harness(val test: TestScope, debugOn: Boolean = false, loop: VoiceLoop = VoiceLoop()) {
        val journal = mutableListOf<String>()
        val debugLines = mutableListOf<String>()
        val api = FakeGatewayApi()
        val speaker = ScenarioSpeaker(journal)
        val capture = ScenarioCapture(journal)
        val focus = ScenarioFocus(journal)
        val voice = ScenarioVoice(journal)
        val earcons = ScenarioEarcons(journal)
        var vibrations = 0
        val service = ScenarioService()
        val network = ScenarioNetwork()
        val controller = LoopController(
            initial = LoopState(WT),
            links = { GatewayLink(api, speaker) },
            capture = capture,
            listenFocus = focus,
            voice = voice,
            earcons = earcons,
            haptics = { vibrations++ },
            service = service,
            network = network,
            scope = test.backgroundScope,
            clock = { test.testScheduler.currentTime },
            loop = loop,
            debug = if (debugOn) { line -> debugLines += line } else null,
        )

        val phase: Phase get() = controller.state.value.phase

        fun advance(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }

        fun send(input: LoopInput) {
            controller.send(input)
            advance(1)
        }

        /** Starts the controller and taps once with an idle agent: the loop listens. */
        fun startListening() {
            controller.start()
            advance(1)
            send(LoopInput.Tap)
            advance(3_000)
            assertEquals(Phase.Listening, phase)
        }

        /** Speaks [text] (what Whisper will give) and lets it go out. */
        fun say(text: String) {
            api.transcripts += text
            assertTrue("the mic is off", capture.speak())
            advance(1_000)
        }

        /** The agent works, then finishes with [fixture]'s event; the event plays through. */
        fun agentReplies(fixture: String) {
            api.show("session_working")
            advance(2_500)
            api.show(fixture)
            advance(2_500)
            advance(speaker.playMs + 2_000)
        }

        val prompts get() = api.prompts.map { it.second }
    }

    private fun scenario(block: Harness.() -> Unit) = runTest { Harness(this).block() }

    private fun debugScenario(block: Harness.() -> Unit) = runTest { Harness(this, debugOn = true).block() }

    /** The first-prompt hand-off switched on (not the default). */
    private fun handoffScenario(block: Harness.() -> Unit) =
        runTest { Harness(this, loop = VoiceLoop(LoopConfig(handoffFirstPrompt = true))).block() }

    // ---- A normal turn ----------------------------------------------------------------------------------------------

    @Test
    fun aNormalTurnIsSentSpokenAcknowledgedAndListensAgainWithOneTap() = scenario {
        startListening()
        assertEquals(1, service.starts)
        assertTrue(controller.running.value)
        say(FIRST)
        assertEquals(listOf(Triple(WT, FIRST, true)), api.prompts)
        assertTrue(EarconKind.Sent in earcons.kinds)

        api.show("session_working")
        advance(2_500)
        assertTrue(phase is Phase.AgentWorking)
        api.show("event_summary")
        advance(2_500)
        val event = api.view.events.single()
        assertEquals(Phase.Speaking(event, (phase as Phase.Speaking).resume), phase)
        assertEquals(listOf(event.id), speaker.played)
        assertTrue("acknowledged before it was heard", api.acked.isEmpty())

        advance(speaker.playMs + 1_000)
        assertEquals(listOf(event.id), api.acked)
        assertEquals(Phase.Listening, phase)
        assertEquals(2, capture.configs.size)
        assertEquals(1, api.prompts.size)
    }

    @Test
    fun theAcknowledgementComesOnlyAfterTheEventWasGivenUp() = scenario {
        startListening()
        say(FIRST)
        speaker.failEverything = true
        agentReplies("event_summary")
        assertEquals(listOf("000000000000000b"), api.acked)
        assertEquals(Phase.Listening, phase)
    }

    // ---- ask: true, the question turn ------------------------------------------------------------------------------

    @Test
    fun aQuestionIsSpokenTheMicReopensWithNoTapAndTheAnswerIsTheSecondOfExactlyTwoPrompts() = scenario {
        startListening()
        say(FIRST)
        agentReplies("event_ask_true")
        val question = api.acked.single()
        assertEquals("0000000000000014", question)
        val awaiting = phase as Phase.AwaitingAnswer
        assertEquals("Did you mean wt-api or wt-web?", awaiting.question.text)
        assertEquals(2, capture.configs.size)
        assertTrue(capture.running)
        assertTrue(EarconKind.YourTurn in earcons.kinds)

        say("the api one")
        agentReplies("event_summary")
        assertEquals(listOf(FIRST, "the api one"), prompts)
        assertTrue(api.prompts.all { it.third })
        assertEquals(Phase.Listening, phase)
    }

    @Test
    fun silenceAfterAQuestionResaysItOnceThenPausesAndNothingIsSent() = scenario {
        startListening()
        say(FIRST)
        agentReplies("event_ask_true")
        assertTrue(phase is Phase.AwaitingAnswer)
        advance(20_000)
        assertTrue(Lines.WAITING_FOR_ANSWER in voice.lines)
        assertTrue("Did you mean wt-api or wt-web?" in voice.lines)
        assertTrue(phase is Phase.AwaitingAnswer)
        advance(22_000)
        assertEquals(Phase.Paused(PauseReason.Question), phase)
        assertEquals(AskKind.Answer, controller.state.value.question?.ask)
        assertEquals(1, api.prompts.size)
    }

    @Test
    fun aLoneYesIsSentAsExactlyYes() = scenario {
        startListening()
        say(FIRST)
        agentReplies("event_ask_true")
        say("yes")
        assertEquals(Triple(WT, "yes", true), api.prompts.last())
    }

    @Test
    fun okayAsTheAnswerIsRejectedAndTheMicStaysOpenForTheAnswer() = scenario {
        startListening()
        say(FIRST)
        agentReplies("event_ask_true")
        say("okay")
        assertEquals(1, api.prompts.size)
        assertTrue(Lines.DIDNT_CATCH_ANSWER in voice.lines)
        assertTrue(phase is Phase.AwaitingAnswer)
        assertTrue(capture.running)
    }

    @Test
    fun theAnswersReplyCanItselfBeAQuestionTwoRounds() = scenario {
        startListening()
        say(FIRST)
        agentReplies("event_ask_true")
        say("the api one")
        api.acked.clear()
        agentReplies("event_ask_true")
        assertTrue(phase is Phase.AwaitingAnswer)
        say("the main branch")
        assertEquals(listOf(FIRST, "the api one", "the main branch"), prompts)
    }

    // ---- ask: "confirm", tap only ----------------------------------------------------------------------------------

    @Test
    fun aConfirmationKeepsTheMicOffUntilYesIsTappedThenSendsExactlyOnePrompt() = scenario {
        startListening()
        say(FIRST)
        agentReplies("event_ask_true")
        say("the api one")
        agentReplies("event_ask_confirm")
        assertEquals(listOf("0000000000000014", "0000000000000017"), api.acked)
        assertEquals("Delete branch wt-old?", (phase as Phase.AwaitingTap).question.text)
        assertTrue(Lines.LOOK_TO_CONFIRM in voice.lines)
        assertEquals(1, vibrations)
        val starts = capture.configs.size
        assertFalse("the mic is on", capture.running)
        api.transcripts += "yes, go ahead"
        assertFalse(capture.speak())

        advance(5 * 60_000)
        assertEquals(starts, capture.configs.size)
        assertEquals(2, api.prompts.size)
        assertEquals(2, voice.lines.count { it == Lines.LOOK_TO_CONFIRM })
        assertEquals(2, vibrations)

        send(LoopInput.ConfirmTap(true))
        advance(500)
        assertEquals(Triple(WT, "yes, go ahead", true), api.prompts.last())
        assertEquals(3, api.prompts.size)
        assertEquals(listOf(false, false, true), api.confirmTaps) // Only the tap carries the tap mark.
    }

    @Test
    fun noSendsNoDoNotDoThat() = scenario {
        startListening()
        say(FIRST)
        agentReplies("event_ask_confirm")
        send(LoopInput.ConfirmTap(false))
        advance(500)
        assertEquals(Triple(WT, "no, do not do that", true), api.prompts.last())
        assertEquals(listOf(false, true), api.confirmTaps)
    }

    @Test
    fun theHeadsetButtonOnlyResaysAConfirmation() = scenario {
        startListening()
        say(FIRST)
        agentReplies("event_ask_confirm")
        send(LoopInput.HeadsetPress)
        advance(2_000)
        assertEquals(1, api.prompts.size)
        assertTrue(phase is Phase.AwaitingTap)
        assertEquals("said again by the phone voice", 1, voice.lines.count { it == "Delete branch wt-old?" })
        assertFalse(capture.running)
    }

    @Test
    fun anUnknownAskValueIsTapOnly() = scenario {
        startListening()
        say(FIRST)
        api.show("session_working")
        advance(2_500)
        api.view = GatewayFixtures.viewWithAsk("event_ask_confirm", "later")
        advance(2_500 + speaker.playMs + 2_000)
        assertTrue(phase is Phase.AwaitingTap)
        assertFalse(capture.running)
        advance(60_000)
        assertEquals(1, api.prompts.size)
    }

    // ---- Refusals and failures -------------------------------------------------------------------------------------

    @Test
    fun aRefusedPromptIsHeldAndNeverResentByItself() = scenario {
        startListening()
        api.promptAnswers += "refusal_not_settled"
        say(FIRST)
        assertEquals(Phase.Held(FIRST, HeldReason.AgentBusy), (phase as Phase.Held).copy(agentFree = false))
        assertTrue(Lines.BUSY_KEPT in voice.lines)
        advance(5 * 60_000)
        assertEquals(1, api.prompts.size)
        assertTrue((phase as Phase.Held).agentFree)
        send(LoopInput.SendNowTap)
        advance(500)
        assertEquals(listOf(FIRST, FIRST), prompts)
    }

    @Test
    fun maybeSentIsNeverResent() = scenario {
        startListening()
        api.promptAnswers += FakeGatewayApi.MAYBE_SENT
        say(FIRST)
        assertTrue(phase is Phase.AgentWorking)
        advance(25_000)
        assertEquals(HeldReason.MaybeNotSent, (phase as Phase.Held).reason)
        advance(5 * 60_000)
        assertEquals(1, api.prompts.size)
    }

    @Test
    fun herdrTimeoutMaybeSentIsNeverResentEither() = scenario {
        startListening()
        api.promptAnswers += "refusal_herdr_timeout_maybe_sent"
        say(FIRST)
        advance(5 * 60_000)
        assertEquals(1, api.prompts.size)
    }

    @Test
    fun a401StopsEverything() = scenario {
        startListening()
        api.loggedOut = true
        advance(6_000)
        assertEquals(Phase.LoggedOut, phase)
        assertFalse(controller.running.value)
        assertTrue(service.stops >= 1)
        assertFalse(capture.running)
        assertFalse(focus.held)
        val calls = api.calls.size
        advance(60_000)
        assertEquals("still polling after a 401", calls, api.calls.size)
    }

    @Test
    fun networkLossKeepsTheRecordingAndRecoversWithOneReupload() = scenario {
        startListening()
        api.offline = true
        network.state.value = false
        advance(10)
        api.transcripts += FIRST
        assertTrue(capture.speak())
        advance(1_000)
        assertTrue((phase as Phase.Transcribing).waitingForNetwork)
        val polls = api.calls.count { it == "session" }
        advance(30_000)
        assertTrue("polls did not back off", api.calls.count { it == "session" } - polls <= 5)
        assertTrue(api.prompts.isEmpty())

        api.offline = false
        network.state.value = true
        advance(1_000)
        assertEquals(1, api.transcribes)
        assertEquals(listOf(FIRST), prompts)
    }

    // ---- Taps, switches ----------------------------------------------------------------------------------------------

    @Test
    fun tapToInterruptStopsTheAudioAcknowledgesAndListensAfterTheTail() = scenario {
        startListening()
        say(FIRST)
        api.show("session_working")
        advance(2_500)
        api.show("event_summary")
        advance(2_500)
        assertTrue(phase is Phase.Speaking)
        send(LoopInput.Tap)
        advance(10)
        assertEquals(listOf("000000000000000b"), api.acked)
        advance(500)
        assertEquals(Phase.Listening, phase)
        assertTrue(capture.running)
        assertFalse("an asked-for stop is not a focus loss", phase is Phase.Paused)
    }

    @Test
    fun aWorktreeSwitchDropsHeldText() = scenario {
        startListening()
        api.promptAnswers += "refusal_not_settled"
        say(FIRST)
        assertTrue(phase is Phase.Held)
        send(LoopInput.WorktreeChanged("wt-api"))
        advance(6_000)
        assertFalse(phase is Phase.Held)
        assertEquals("wt-api", controller.state.value.worktree)
        assertEquals("wt-api", api.sessionNames.last())
        advance(5 * 60_000)
        assertEquals(1, api.prompts.size)
    }

    @Test
    fun aTypedPromptGoesOutWithVoiceFalse() = scenario {
        startListening()
        send(LoopInput.TypedSend("git status please"))
        advance(500)
        assertEquals(Triple(WT, "git status please", false), api.prompts.single())
    }

    // ---- Carry-forward rules -------------------------------------------------------------------------------------------

    @Test
    fun theDetectorsOwnNoSpeechTimeoutIsOffAndTheLoopTimerDecides() = scenario {
        startListening()
        assertEquals(Int.MAX_VALUE, capture.configs.last().noSpeechTimeoutMs)
        assertEquals(Int.MAX_VALUE, captureConfig(1_500).noSpeechTimeoutMs)
        assertEquals(1_500, captureConfig(1_500).endSilenceMs)
        capture.hear(CaptureEvent.NoSpeechPause)
        advance(100)
        assertEquals(Phase.Listening, phase)
        advance(45_000)
        assertEquals(Phase.Paused(PauseReason.NoSpeech), phase)
    }

    @Test
    fun theListeningFocusIsReleasedBeforeEveryPlay() = scenario {
        startListening()
        say(FIRST)
        agentReplies("event_summary")
        val play = journal.indexOf("play 000000000000000b")
        val release = journal.subList(0, play).lastIndexOf("focus release")
        val acquire = journal.subList(0, play).lastIndexOf("focus acquire")
        assertTrue(journal.toString(), release > acquire)
        assertEquals(Phase.Listening, phase)
    }

    @Test
    fun losingTheListeningFocusPauses() = scenario {
        startListening()
        focus.lose(permanent = false)
        advance(500)
        assertEquals(Phase.Paused(PauseReason.FocusLost), phase)
        assertFalse(capture.running)
    }

    @Test
    fun aCallTakingTheAudioMidPlayPauses() = scenario {
        startListening()
        say(FIRST)
        api.show("session_working")
        advance(2_500)
        api.show("event_summary")
        advance(2_500)
        speaker.loseFocus()
        advance(500)
        assertEquals(Phase.Paused(PauseReason.FocusLost), phase)
        assertEquals(listOf("000000000000000b"), api.acked)
    }

    @Test
    fun aSilencedOrBrokenMicPausesAndSaysSo() = scenario {
        startListening()
        capture.hear(CaptureEvent.MicSilenced)
        advance(1_000)
        assertEquals(Phase.Paused(PauseReason.MicSilenced), phase)
        assertTrue(Lines.MIC_SILENCED in voice.lines)
        send(LoopInput.Tap)
        advance(1_000)
        capture.hear(CaptureEvent.Failed(CaptureFailure.NoPermission))
        advance(1_000)
        assertEquals(Phase.Paused(PauseReason.MicUnavailable), phase)
    }

    @Test
    fun hiddenAndIdleStopsPollingAndTheSessionsListIsPolledOnlyWhileVisible() = scenario {
        controller.start()
        controller.setVisible(true)
        advance(11_000)
        assertTrue(api.calls.count { it == "sessions" } >= 2)
        assertTrue(api.calls.count { it == "session" } >= 2)
        controller.setVisible(false)
        advance(100)
        val calls = api.calls.size
        advance(60_000)
        assertEquals(calls, api.calls.size)
    }

    @Test
    fun theSpeakerChoiceShapesTheEngineChain() = scenario {
        startListening()
        controller.setSpeaker(Engine.Phone)
        say(FIRST)
        agentReplies("event_summary")
        assertEquals(listOf(Engine.Phone), speaker.chains.single().order)
    }

    @Test
    fun tenMinutesPausedEndsTheService() = scenario {
        startListening()
        send(LoopInput.Tap)
        assertTrue(phase is Phase.Paused)
        advance(10 * 60_000 + 1_000)
        assertEquals(Phase.Idle, phase)
        assertTrue(service.stops >= 1)
        assertFalse(controller.running.value)
    }

    // ---- DEBUG diagnostics -----------------------------------------------------------------------------------------

    @Test
    fun debugLinesShowEveryCaptureAndTranscriptResultAsNumbersOnly() = debugScenario {
        startListening()
        val stats = CaptureStats(120, 0.97f, -38.0, -47.5, -31.0, 2_400, 1_900, 0.79)
        assertTrue(capture.hear(CaptureEvent.SpeechStarted(0.4f)))
        advance(1)
        assertTrue(capture.hear(CaptureEvent.Rejected(CaptureRejection.TooQuiet, stats)))
        advance(3_000)
        assertTrue(Lines.DIDNT_CATCH in voice.lines)
        assertTrue(debugLines.toString(), debugLines.any { it == captureDebugLine("TooQuiet", stats) })
        assertEquals(debugLines.last(), controller.debugLine.value)

        say(FIRST)
        val tx = debugLines.last { it.startsWith("tx ") }
        assertEquals("tx http=200 reason=- chars=${FIRST.length} words=7 guard=ok", tx)
        for (word in FIRST.split(" ").filter { it.length >= 3 }) {
            assertTrue("'$word' leaked", debugLines.none { it.contains(word) })
        }
    }

    @Test
    fun withoutADebugSinkThereIsNoDebugLine() = scenario {
        startListening()
        assertTrue(capture.hear(CaptureEvent.SpeechStarted(0.4f)))
        advance(1)
        assertTrue(capture.hear(CaptureEvent.Rejected(CaptureRejection.TooQuiet, CaptureStats(1, 0f, -90.0, -90.0, -90.0, 0, 0, 0.0))))
        advance(3_000)
        say(FIRST)
        assertEquals(null, controller.debugLine.value)
    }

    // ---- W6: a new worktree from the phone ----------------------------------------------------------------------------

    private val request = "a worktree in web app for the login timeout"
    private val newSession = "wt-web-app-fix-login-timeout"

    private fun Harness.newWorktreeFlow(): dev.nittala.voicegateway.core.worktree.WorktreeFlow? =
        (phase as? Phase.NewWorktree)?.flow

    @Test
    fun aSpokenRequestIsPlannedReadConfirmedByVoiceCreatedAndSentOnceAsTheFirstPrompt() = handoffScenario {
        controller.start()
        advance(1)
        send(LoopInput.NewWorktreeTap)
        advance(1_000)
        assertTrue(capture.running)
        say(request)
        assertEquals(listOf(request to null), api.plans)
        advance(2_000)
        val card = newWorktreeFlow() as dev.nittala.voicegateway.core.worktree.WorktreeFlow.Card
        val reading = journal.indexOfLast { it.startsWith("say New branch fix/login-timeout in web-app") }
        assertTrue(journal.toString(), reading >= 0 && journal.indexOf("capture start") < reading)
        assertTrue(journal.toString(), journal.subList(reading, journal.size).contains("capture start"))
        assertTrue(card.listening)
        assertTrue(api.prompts.isEmpty())

        say("Create it.")
        assertEquals(listOf(card.plan.planId), api.creates)
        assertEquals(newSession, controller.state.value.worktree)
        assertTrue("one settled view is not enough", api.prompts.isEmpty())
        advance(4_000)
        assertEquals(listOf(Triple(newSession, request, true)), api.prompts)
        assertTrue(api.sessionNames.contains(newSession))

        agentReplies("event_summary")
        assertEquals(1, api.prompts.size)
        assertEquals(1, api.creates.size)
        assertTrue(speaker.played.isNotEmpty())
    }

    @Test
    fun aFailedFetchOffersTheDownloadedCopyWhichCreatesByTap() = scenario {
        controller.start()
        advance(1)
        send(LoopInput.NewWorktreeTap)
        send(LoopInput.NewWorktreeTyped(request))
        advance(1_000)
        assertTrue(newWorktreeFlow() is dev.nittala.voicegateway.core.worktree.WorktreeFlow.Card)
        assertFalse("a typed card does not open the mic", capture.running)
        api.createAnswers += "refusal_fetch_failed_offline"
        send(LoopInput.CreateTap)
        advance(1_000)
        val failed = controller.ui.value.newWorktree as dev.nittala.voicegateway.core.ui.NewWorktreeUi.Failed
        assertTrue(failed.offlineLabel!!.startsWith("Create from the downloaded copy"))
        assertEquals(1, api.creates.size)
        send(LoopInput.OfflineCreateTap)
        advance(1_000)
        val offline = GatewayFixtures.load("refusal_fetch_failed_offline").body["offline"].toString()
        assertEquals(2, api.creates.size)
        assertTrue(offline.contains(api.creates[1]))
        assertEquals(newSession, controller.state.value.worktree)
    }

    @Test
    fun anExpiredPlanIsPlannedAgainNeverCreated() = scenario {
        controller.start()
        advance(1)
        send(LoopInput.NewWorktreeTap)
        send(LoopInput.NewWorktreeTyped(request))
        advance(301_000)
        val card = controller.ui.value.newWorktree as dev.nittala.voicegateway.core.ui.NewWorktreeUi.Card
        assertEquals("Plan again", card.card.createLabel)
        send(LoopInput.CreateTap)
        advance(1_000)
        assertEquals(2, api.plans.size)
        assertTrue(api.creates.isEmpty())
    }

    @Test
    fun aTrustDialogDuringTheHandoffIsAnsweredByTapThenTheRequestGoesOnce() = handoffScenario {
        controller.start()
        advance(1)
        send(LoopInput.NewWorktreeTap)
        send(LoopInput.NewWorktreeTyped(request))
        advance(1_000)
        api.show("session_blocked")
        send(LoopInput.CreateTap)
        advance(3_000)
        val needs = phase as Phase.NeedsYou
        assertTrue(needs.handoff != null)
        assertTrue(api.prompts.isEmpty())
        send(LoopInput.KeyTap("1"))
        assertEquals("1", api.keys.single().first)
        api.show("session_idle")
        advance(10_000)
        assertEquals(listOf(Triple(newSession, request, false)), api.prompts)
    }

    @Test
    fun aCreateWithNoAnswerIsNeverRetriedAndTheListIsOpened() = scenario {
        controller.start()
        advance(1)
        send(LoopInput.NewWorktreeTap)
        send(LoopInput.NewWorktreeTyped(request))
        advance(1_000)
        api.createAnswers += FakeGatewayApi.MAYBE_SENT
        send(LoopInput.CreateTap)
        advance(5_000)
        send(LoopInput.CreateTap)
        send(LoopInput.OfflineCreateTap)
        advance(5_000)
        assertEquals(1, api.creates.size)
        val failed = controller.ui.value.newWorktree as dev.nittala.voicegateway.core.ui.NewWorktreeUi.Failed
        assertTrue(failed.openPicker)
        assertTrue(api.prompts.isEmpty())
    }

    @Test
    fun theDebugLinesOfTheFlowHoldCodesAndCountsOnly() = debugScenario {
        controller.start()
        advance(1)
        send(LoopInput.NewWorktreeTap)
        send(LoopInput.NewWorktreeTyped(request))
        advance(1_000)
        send(LoopInput.CreateTap)
        advance(10_000)
        val flow = debugLines.filter { it.startsWith("wt ") }
        assertEquals(listOf("wt plan http=200 kind=plan mode=new ready=true choices=3",
            "wt create http=200 listed=true base=new moved=false launch=no"), flow)
        for (line in debugLines) {
            for (word in listOf("login", "web-app", "worktree in", "fix/", newSession)) assertFalse(line, line.contains(word))
        }
    }

    // ---- Default after Create: nothing is sent to the new worktree ------------------------------------------------

    @Test
    fun byDefaultACreatedWorktreeGetsNoPromptAndTheLoopIsIdleOnIt() = scenario {
        controller.start()
        advance(1)
        send(LoopInput.NewWorktreeTap)
        advance(1_000)
        say(request)
        advance(2_000)
        assertTrue(newWorktreeFlow() is WorktreeFlow.Card)
        say("Create it.")
        assertEquals(1, api.creates.size)
        advance(60_000) // many idle views of the new session, well past the old two-view wait
        assertEquals(0, api.prompts.size)
        assertEquals(Phase.Idle, phase)
        assertEquals(newSession, controller.state.value.worktree)
        assertTrue(WorktreeLines.CREATED in voice.lines)
        assertEquals(null, controller.ui.value.held)
        assertEquals(dev.nittala.voicegateway.core.ui.VoiceMode.Idle, controller.ui.value.mode)
        assertEquals(newSession, controller.ui.value.worktree)
        assertFalse(capture.running)
    }

    // ---- The mic fails (no permission) in the new-worktree flow --------------------------------------------------

    @Test
    fun noMicPermissionKeepsTheNewWorktreePanelWithOneMessageAndATapRetries() = scenario {
        controller.start()
        advance(1)
        send(LoopInput.NewWorktreeTap)
        advance(1_000)
        assertTrue(capture.hear(CaptureEvent.Failed(CaptureFailure.NoPermission)))
        advance(2_000)
        assertEquals(WorktreeFlow.Request(listening = false), newWorktreeFlow())
        assertTrue(controller.ui.value.newWorktree != null)
        assertEquals(MicTexts.NOT_AVAILABLE, controller.ui.value.micNotice?.message)
        assertEquals(listOf(Lines.MIC_UNAVAILABLE), voice.lines.filter { it == Lines.MIC_UNAVAILABLE })
        assertFalse(capture.running)
        advance(30_000) // no retry loop: the mic stays off until a tap
        assertFalse(capture.running)
        assertEquals(1, voice.lines.count { it == Lines.MIC_UNAVAILABLE })

        send(LoopInput.Tap) // one tap, one try
        advance(1_000)
        assertTrue(capture.running)
        assertEquals(null, controller.ui.value.micNotice)
        assertTrue(capture.hear(CaptureEvent.Failed(CaptureFailure.NoPermission)))
        advance(2_000)
        assertEquals(2, voice.lines.count { it == Lines.MIC_UNAVAILABLE })
        assertTrue(newWorktreeFlow() is WorktreeFlow.Request)
        send(LoopInput.NewWorktreeTyped(request)) // the typed request still works
        advance(1_000)
        assertEquals(listOf(request to null), api.plans)
    }
}
