package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.api.RefusalReason
import dev.nittala.voicegateway.core.loop.Effect.Ack
import dev.nittala.voicegateway.core.loop.Effect.CancelTimer
import dev.nittala.voicegateway.core.loop.Effect.Earcon
import dev.nittala.voicegateway.core.loop.Effect.Play
import dev.nittala.voicegateway.core.loop.Effect.Say
import dev.nittala.voicegateway.core.loop.Effect.Send
import dev.nittala.voicegateway.core.loop.Effect.SetPoll
import dev.nittala.voicegateway.core.loop.Effect.StartCapture
import dev.nittala.voicegateway.core.loop.Effect.StartTimer
import dev.nittala.voicegateway.core.loop.Effect.StopCapture
import dev.nittala.voicegateway.core.loop.Effect.StopPlayback
import dev.nittala.voicegateway.core.loop.Effect.Upload
import dev.nittala.voicegateway.core.loop.LoopInput.ConfirmTap
import dev.nittala.voicegateway.core.loop.LoopInput.PlaybackDone
import dev.nittala.voicegateway.core.loop.LoopInput.SendRefused
import dev.nittala.voicegateway.core.loop.LoopInput.SpeechEnd
import dev.nittala.voicegateway.core.loop.LoopInput.Tap
import dev.nittala.voicegateway.core.loop.LoopInput.TimerFired
import dev.nittala.voicegateway.core.loop.LoopInput.Transcribed
import dev.nittala.voicegateway.core.loop.LoopInput.View
import dev.nittala.voicegateway.core.model.AskKind
import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.model.EventKind
import dev.nittala.voicegateway.core.model.GatewayJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transition table of plan section 7, one test per row. This file is the spec of [VoiceLoop.step].
 * Cross-cutting rules (mic, timers, polling, no auto-send) are checked over every phase and random walks in
 * VoiceLoopPropertyTest.
 */
class VoiceLoopTest {
    private val loop = VoiceLoop()
    private val transcribing = Phase.Transcribing(WT, GOOD)
    private val sending = Phase.Sending(WT, TEXT)
    private val working = Phase.AgentWorking(Sent(TEXT))

    // ---- Main path ----------------------------------------------------------------------------------------------

    @Test
    fun idleTapWithSettledAgentStartsListening() {
        val t = loop.step(st(Phase.Idle), Tap)
        assertEquals(Phase.Listening, t.phase)
        assertEquals(
            listOf(Earcon(EarconKind.Listen), StartCapture(answerMode = false), StartTimer(Timer.NoSpeech, 45_000)),
            t.effects,
        )
    }

    @Test
    fun idleTapWithWorkingAgentWaitsForTheAgentMicOff() {
        val t = loop.step(st(Phase.Idle, WORKING_AGENT), Tap)
        assertEquals(Phase.AgentWorking(null), t.phase)
        assertTrue(t.captures.isEmpty())
    }

    @Test
    fun idleTapBeforeAnyViewWaitsForTheFirstView() {
        val t = loop.step(st(Phase.Idle, AgentView.Unknown), Tap)
        assertEquals(Phase.AgentWorking(null), t.phase)
        assertEquals(Phase.Listening, loop.step(t.state, View(view("idle"))).phase)
    }

    @Test
    fun listeningSpeechStartCaptures() {
        val t = loop.step(st(Phase.Listening), LoopInput.SpeechStart)
        assertEquals(Phase.Capturing(WT, null), t.phase)
        assertEquals(listOf(CancelTimer(Timer.NoSpeech)), t.effects)
    }

    @Test
    fun speechEndPassingG1AndG2UploadsForTheUtterancesWorktree() {
        val t = loop.step(st(Phase.Capturing(WT)), SpeechEnd(GOOD))
        assertEquals(Phase.Transcribing(WT, GOOD), t.phase)
        assertEquals(listOf(StopCapture, Upload(WT, GOOD)), t.effects)
    }

    @Test
    fun transcribedTextIsSentAtOnceWithVoiceTrueAndNoConfirm() {
        val t = loop.step(st(transcribing), Transcribed(WT, TEXT))
        assertEquals(Phase.Sending(WT, TEXT), t.phase)
        assertEquals(listOf(Send(WT, TEXT, voice = true), Earcon(EarconKind.Sent), SetPoll(2_000)), t.effects)
    }

    @Test
    fun sendOkGoesToAgentWorking() {
        val t = loop.step(st(sending), LoopInput.SendOk(WT))
        assertEquals(Phase.AgentWorking(Sent(TEXT)), t.phase)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun summaryEventForThisWorktreeIsSpoken() {
        val e = event("e1")
        val t = loop.step(st(working, WORKING_AGENT), View(view("idle", events = listOf(e))))
        assertEquals(Phase.Speaking(e, working), t.phase)
        assertTrue(Play(e, withName = false) in t.effects)
    }

    @Test
    fun checkScreenEventIsSpokenLikeASummary() {
        val e = event("e2", kind = EventKind.CheckScreen, text = "wt-scratch finished, check the screen.")
        val t = loop.step(st(working, WORKING_AGENT), View(view("idle", events = listOf(e))))
        assertTrue(t.phase is Phase.Speaking)
    }

    @Test
    fun playbackDoneAcknowledgesAndListens() {
        val e = event("e1")
        val t = loop.step(st(Phase.Speaking(e, working)), PlaybackDone("e1"))
        assertEquals(Phase.Listening, t.phase)
        assertEquals(Ack(listOf("e1")), t.effects.first())
        assertTrue(StartCapture(false) in t.effects)
        assertTrue("e1" in t.state.heard)
    }

    @Test
    fun playbackFailedIsGivenUpAcknowledgedAndListens() {
        val e = event("e1")
        val t = loop.step(st(Phase.Speaking(e, working)), LoopInput.PlaybackFailed("e1"))
        assertEquals(Phase.Listening, t.phase)
        assertTrue(Ack(listOf("e1")) in t.effects)
    }

    @Test
    fun playbackResultForAnotherEventIsIgnored() {
        val s = st(Phase.Speaking(event("e1"), working))
        assertEquals(Transition(s), loop.step(s, PlaybackDone("zz")))
    }

    @Test
    fun anEventIsNotReplayedWhileItsAckIsInFlight() {
        val e = event("e1")
        val heard = loop.step(st(Phase.Speaking(e, working)), PlaybackDone("e1")).state
        val t = loop.step(heard, View(view("idle", events = listOf(e))))
        assertEquals(Phase.Listening, t.phase)
        assertTrue(t.effects.none { it is Play })
    }

    // ---- Guards ---------------------------------------------------------------------------------------------------

    @Test
    fun quietUtteranceFailsG1NothingUploaded() {
        val t = loop.step(st(Phase.Capturing(WT)), SpeechEnd(QUIET))
        assertEquals(Phase.Listening, t.phase)
        assertTrue(t.effects.none { it is Upload })
        assertEquals(listOf(Lines.DIDNT_CATCH), t.says)
        assertTrue(Earcon(EarconKind.Rejected) in t.effects)
        assertEquals(1, t.state.rejections)
    }

    @Test
    fun g1JudgesOnlyTheSpeechSoSilentPaddingDoesNotRejectQuietRealSpeech() {
        // 16 frames of silence, 30 voiced frames of quiet speech (-53 dBFS body, one -35 dBFS peak per frame, about
        // -52.5 RMS), 16 frames of silence: the whole buffer is under the G1 floor, the speech above it: uploaded.
        val fs = 512
        val speech = ShortArray(30 * fs) { if (it % fs == 0) 600 else if (it % 2 == 0) 73 else -73 }
        val pcm = ShortArray(16 * fs) + speech + ShortArray(16 * fs)
        val u = dev.nittala.voicegateway.core.speech.Utterance(
            pcm, dev.nittala.voicegateway.core.speech.UtteranceStats(16, 30, 30, 16, 32.0),
            dev.nittala.voicegateway.core.speech.EndReason.Silence,
        )
        val t = loop.step(st(Phase.Capturing(WT)), SpeechEnd(u))
        assertTrue(t.effects.any { it is Upload })
    }

    @Test
    fun sparseVoiceFailsG2NothingUploaded() {
        val t = loop.step(st(Phase.Capturing(WT)), SpeechEnd(SPARSE))
        assertEquals(Phase.Listening, t.phase)
        assertTrue(t.effects.none { it is Upload })
    }

    @Test
    fun transcriptGuardRejectionReturnsToListeningNothingSent() {
        for (text in listOf("thank you", "", "...", "yes", "привет мир", "go go go go")) {
            val t = loop.step(st(transcribing), Transcribed(WT, text))
            assertEquals(text, Phase.Listening, t.phase)
            assertTrue(text, t.sends.isEmpty())
            assertEquals(text, listOf(Lines.DIDNT_CATCH), t.says)
        }
    }

    @Test
    fun slashAndBangTextIsNeverSentByVoice() {
        for (text in listOf("/clear the context", "!rm -rf build", "  /compact now")) {
            val t = loop.step(st(transcribing), Transcribed(WT, text))
            assertTrue(text, t.sends.isEmpty())
            assertEquals(text, listOf(Lines.NO_COMMANDS), t.says)
        }
    }

    @Test
    fun thirdRejectionInARowPauses() {
        val t = loop.run(
            st(Phase.Capturing(WT)), SpeechEnd(QUIET),
            LoopInput.SpeechStart, SpeechEnd(QUIET),
            LoopInput.SpeechStart, SpeechEnd(QUIET),
        )
        assertEquals(Phase.Paused(PauseReason.Noise), t.phase)
        assertEquals(Lines.TOO_MUCH_NOISE, t.says.last())
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun aSendResetsTheRejectionCount() {
        val s = st(transcribing).copy(rejections = 2)
        assertEquals(0, loop.step(s, Transcribed(WT, TEXT)).state.rejections)
    }

    // ---- Agent not idle -------------------------------------------------------------------------------------------

    @Test
    fun viewShowingWorkingBeforeTheSendHoldsTheText() {
        val t = loop.run(st(transcribing), View(view("working")), Transcribed(WT, TEXT))
        assertEquals(Phase.Held(TEXT, HeldReason.AgentBusy), t.phase)
        assertEquals(listOf(Lines.BUSY_KEPT), t.says)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun notSettledRefusalHoldsTheText() {
        val t = loop.step(st(sending), SendRefused(WT, refusal(RefusalReason.NotSettled)))
        assertEquals(Phase.Held(TEXT, HeldReason.AgentBusy), t.phase)
        assertEquals(listOf(Lines.BUSY_KEPT), t.says)
    }

    @Test
    fun heldTextIsNeverSentAutomatically() {
        val held = st(Phase.Held(TEXT, HeldReason.AgentBusy), WORKING_AGENT)
        val t = loop.run(
            held, View(view("idle")), LoopInput.NetworkLost, LoopInput.NetworkBack, View(view("done")),
            *Timer.entries.map { TimerFired(it) }.toTypedArray(), Tap, LoopInput.HeadsetPress,
        )
        assertEquals(Phase.Held(TEXT, HeldReason.AgentBusy, agentFree = true), t.phase)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun heldSendNowSendsOnATap() {
        val t = loop.step(st(Phase.Held(TEXT, HeldReason.AgentBusy, agentFree = true)), LoopInput.SendNowTap)
        assertEquals(Phase.Sending(WT, TEXT), t.phase)
        assertEquals(listOf(Send(WT, TEXT, true)), t.sends)
    }

    @Test
    fun heldDiscardListensAgain() {
        val t = loop.step(st(Phase.Held(TEXT, HeldReason.AgentBusy)), LoopInput.DiscardTap)
        assertEquals(Phase.Listening, t.phase)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun heldWithoutTextHasNothingToSend() {
        val s = st(Phase.Held(null, HeldReason.CouldNotTranscribe))
        assertEquals(Transition(s), loop.step(s, LoopInput.SendNowTap))
    }

    // ---- MaybeSent ------------------------------------------------------------------------------------------------

    @Test
    fun maybeSentIsNeverResentAndWaitsForTheNextView() {
        val t = loop.step(st(sending), LoopInput.SendFailed(WT, maybeSent = true))
        assertEquals(Phase.AgentWorking(Sent(TEXT, maybeSent = true)), t.phase)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun herdrTimeoutMaybeSentIsTheSameAsMaybeSent() {
        val r = refusal(RefusalReason.HerdrTimeoutMaybeSent, status = 502)
        val t = loop.step(st(sending), SendRefused(WT, r))
        assertEquals(Phase.AgentWorking(Sent(TEXT, maybeSent = true)), t.phase)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun maybeSentThenWorkingMeansItWent() {
        val s = st(Phase.AgentWorking(Sent(TEXT, maybeSent = true)))
        assertEquals(Phase.AgentWorking(Sent(TEXT)), loop.step(s, View(view("working"))).phase)
    }

    @Test
    fun maybeSentStillSettledWithNoRecordAfter20sIsHeldNeverResent() {
        val s = st(Phase.AgentWorking(Sent(TEXT, maybeSent = true)))
        val armed = loop.step(s, View(view("idle")))
        assertEquals(Phase.AgentWorking(Sent(TEXT, maybeSent = true), settledTimer = true), armed.phase)
        assertTrue(StartTimer(Timer.Settled, 20_000) in armed.effects)
        val t = loop.step(armed.state, TimerFired(Timer.Settled))
        assertEquals(Phase.Held(TEXT, HeldReason.MaybeNotSent), t.phase)
        assertEquals(listOf(Lines.MAYBE_NOT_SENT), t.says)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun waitingRecordCancelsTheSettledTimer() {
        val s = st(Phase.AgentWorking(Sent(TEXT), settledTimer = true))
        val t = loop.step(s, View(view("idle", waiting = true)))
        assertEquals(Phase.AgentWorking(Sent(TEXT)), t.phase)
        assertTrue(CancelTimer(Timer.Settled) in t.effects)
    }

    @Test
    fun sendFailedWithNothingDeliveredHoldsTheText() {
        val t = loop.step(st(sending), LoopInput.SendFailed(WT, maybeSent = false))
        assertEquals(Phase.Held(TEXT, HeldReason.NotSent), t.phase)
        assertEquals(listOf(Lines.COULDNT_SEND), t.says)
        assertTrue(t.sends.isEmpty())
    }

    // ---- Refusals, one row per reason class -----------------------------------------------------------------------

    @Test
    fun draftPresentHolds() {
        val t = loop.step(st(sending), SendRefused(WT, refusal(RefusalReason.DraftPresent)))
        assertEquals(Phase.Held(TEXT, HeldReason.DraftAtMac), t.phase)
        assertEquals(listOf(Lines.DRAFT_AT_MAC), t.says)
    }

    @Test
    fun repeatWindowAndActionRunningRetryOnceAfter3sThenHold() {
        for (reason in listOf(RefusalReason.RepeatWindow, RefusalReason.ActionRunning)) {
            val first = loop.step(st(sending), SendRefused(WT, refusal(reason)))
            assertEquals(Phase.Sending(WT, TEXT, retried = true, retryPending = true), first.phase)
            assertEquals(listOf(StartTimer(Timer.SendRetry, 3_000)), first.effects)
            val retry = loop.step(first.state, TimerFired(Timer.SendRetry))
            assertEquals(listOf(Send(WT, TEXT, true)), retry.sends)
            val second = loop.step(retry.state, SendRefused(WT, refusal(reason)))
            assertEquals(Phase.Held(TEXT, HeldReason.RetryFailed), second.phase)
            assertTrue(second.sends.isEmpty())
        }
    }

    @Test
    fun paneChangedAndNoOrchestratorAreErrorsWithTheServersMessage() {
        val cases = mapOf(RefusalReason.PaneChanged to ErrorKind.PaneChanged, RefusalReason.NoOrchestrator to ErrorKind.NoOrchestrator)
        for ((reason, kind) in cases) {
            val t = loop.step(st(sending), SendRefused(WT, refusal(reason, message = "No agent pane.")))
            assertEquals(Phase.Error(kind, "No agent pane."), t.phase)
            assertEquals(listOf("No agent pane."), t.says)
        }
    }

    @Test
    fun stoppedRefusalGivesStopped() {
        val t = loop.step(st(sending), SendRefused(WT, refusal(RefusalReason.Stopped)))
        assertEquals(Phase.Stopped(), t.phase)
        assertEquals(listOf(Lines.stopped(WT)), t.says)
        assertTrue(Effect.ShowSheet in t.effects)
    }

    @Test
    fun voiceNoiseRefusalIsARejection() {
        val t = loop.step(st(sending), SendRefused(WT, refusal(RefusalReason.VoiceNoise, status = 422)))
        assertEquals(Phase.Listening, t.phase)
        assertEquals(1, t.state.rejections)
        assertEquals(listOf(Lines.DIDNT_CATCH), t.says)
    }

    @Test
    fun voiceCommandRefusalIsARejectionWithTheCommandLine() {
        val t = loop.step(st(sending), SendRefused(WT, refusal(RefusalReason.VoiceCommand, status = 400)))
        assertEquals(Phase.Listening, t.phase)
        assertEquals(listOf(Lines.NO_COMMANDS), t.says)
    }

    @Test
    fun everyOtherRefusalHoldsTheTextWithTheServersMessage() {
        val others = listOf(
            RefusalReason.BadText, RefusalReason.NotListed, RefusalReason.HerdrRefused, RefusalReason.HerdrFailed,
            RefusalReason.Unknown, null,
        )
        for (reason in others) {
            val t = loop.step(st(sending), SendRefused(WT, refusal(reason, status = 403, message = "No.")))
            assertEquals("$reason", Phase.Held(TEXT, HeldReason.Refused("No.")), t.phase)
            assertTrue(t.sends.isEmpty())
        }
    }

    @Test
    fun sendResultsForAnotherWorktreeAreIgnored() {
        val s = st(sending)
        for (input in listOf(
            LoopInput.SendOk(OTHER), LoopInput.SendFailed(OTHER, true), SendRefused(OTHER, refusal(RefusalReason.NotSettled)),
        )) assertEquals(Transition(s), loop.step(s, input))
    }

    // ---- Network --------------------------------------------------------------------------------------------------

    @Test
    fun networkLossWhileListeningOrCapturingKeepsRecording() {
        for (phase in listOf(Phase.Listening, Phase.Capturing(WT))) {
            val t = loop.step(st(phase), LoopInput.NetworkLost)
            assertEquals(phase, t.phase)
            assertTrue(t.effects.isEmpty())
            assertFalse(t.state.online)
        }
    }

    @Test
    fun transcribeNetworkFailureOfflineKeepsTheWavAndRetriesOnceOnNetworkBack() {
        val offline = st(transcribing).copy(online = false)
        val waiting = loop.step(offline, LoopInput.TranscribeFailed(WT, TranscribeFailure.Network))
        assertEquals(Phase.Transcribing(WT, GOOD, waitingForNetwork = true), waiting.phase)
        assertTrue(waiting.effects.isEmpty())
        val back = loop.step(waiting.state, LoopInput.NetworkBack)
        assertEquals(Phase.Transcribing(WT, GOOD, retried = true), back.phase)
        assertEquals(listOf(Upload(WT, GOOD)), back.effects)
        val again = loop.step(back.state, LoopInput.TranscribeFailed(WT, TranscribeFailure.Network))
        assertEquals(Phase.Held(null, HeldReason.CouldNotTranscribe), again.phase)
        assertTrue(Lines.COULDNT_TRANSCRIBE in again.says)
    }

    @Test
    fun transcribeNetworkFailureOnlineRetriesOnceAtOnce() {
        val t = loop.step(st(transcribing), LoopInput.TranscribeFailed(WT, TranscribeFailure.Network))
        assertEquals(Phase.Transcribing(WT, GOOD, retried = true), t.phase)
        assertEquals(listOf(Upload(WT, GOOD)), t.effects)
    }

    @Test
    fun transcribeBusyRetriesOnceAfter2s() {
        val busy = TranscribeFailure.Refused(refusal(RefusalReason.TranscribeBusy, status = 429, retryAfter = 2))
        val first = loop.step(st(transcribing), LoopInput.TranscribeFailed(WT, busy))
        assertEquals(Phase.Transcribing(WT, GOOD, retried = true, retryPending = true), first.phase)
        assertEquals(listOf(StartTimer(Timer.TranscribeRetry, 2_000)), first.effects)
        val retry = loop.step(first.state, TimerFired(Timer.TranscribeRetry))
        assertEquals(listOf(Upload(WT, GOOD)), retry.effects)
        val second = loop.step(retry.state, LoopInput.TranscribeFailed(WT, busy))
        assertEquals(Phase.Listening, second.phase)
        assertEquals(listOf(Lines.COULDNT_TRANSCRIBE), second.says)
    }

    @Test
    fun transcribeRateSaysHowLongToWait() {
        val rate = TranscribeFailure.Refused(refusal(RefusalReason.TranscribeRate, status = 429, retryAfter = 7))
        val t = loop.step(st(transcribing), LoopInput.TranscribeFailed(WT, rate))
        assertEquals(Phase.Listening, t.phase)
        assertEquals(listOf(Lines.waitSeconds(7)), t.says)
        assertEquals(0, t.state.rejections)
    }

    @Test
    fun speechOffIsAnError() {
        val off = TranscribeFailure.Refused(refusal(RefusalReason.SpeechOff, status = 404, message = "Local speech is not set up."))
        val t = loop.step(st(transcribing), LoopInput.TranscribeFailed(WT, off))
        assertEquals(Phase.Error(ErrorKind.SpeechOff, "Local speech is not set up."), t.phase)
    }

    @Test
    fun otherTranscribeRefusalsCountAsRejections() {
        for (reason in listOf(RefusalReason.AudioEmpty, RefusalReason.AudioTooLarge, RefusalReason.SpeechFailed, null)) {
            val f = TranscribeFailure.Refused(refusal(reason, status = 502))
            val t = loop.step(st(transcribing), LoopInput.TranscribeFailed(WT, f))
            assertEquals("$reason", Phase.Listening, t.phase)
            assertEquals(1, t.state.rejections)
            assertEquals(listOf(Lines.COULDNT_TRANSCRIBE), t.says)
        }
    }

    // ---- Session stopped ------------------------------------------------------------------------------------------

    @Test
    fun viewStoppedGivesStopped() {
        for (phase in listOf(Phase.Listening, working, Phase.AwaitingAnswer(QUESTION))) {
            val t = loop.step(st(phase), View(view("idle", state = "stopped")))
            assertEquals(Phase.Stopped(), t.phase)
            assertEquals(listOf(Lines.stopped(WT)), t.says)
        }
    }

    @Test
    fun restartThenRunningGoesIdle() {
        val stopped = st(Phase.Stopped(), AgentView(state = "stopped"))
        val r = loop.step(stopped, LoopInput.RestartTap)
        assertEquals(Phase.Stopped(restarting = true), r.phase)
        assertEquals(listOf(Effect.SendRestart(WT)), r.effects)
        assertEquals(Phase.Stopped(restarting = true), loop.step(r.state, View(view(null, state = "stopped"))).phase)
        assertEquals(Phase.Idle, loop.step(r.state, View(view("idle"))).phase)
    }

    // ---- 401 ------------------------------------------------------------------------------------------------------

    @Test
    fun unauthorizedLogsOutAndStopsTheService() {
        val t = loop.step(st(Phase.Listening), LoopInput.Unauthorized)
        assertEquals(Phase.LoggedOut, t.phase)
        assertEquals(StopCapture, t.effects.first())
        assertTrue(Effect.EndService in t.effects)
        assertTrue(SetPoll(null) in t.effects)
    }

    @Test
    fun loggedOutIgnoresEverythingButLoggedIn() {
        val s = st(Phase.LoggedOut)
        for (input in listOf(Tap, LoopInput.StopTap, View(view()), LoopInput.WorktreeChanged(OTHER), ConfirmTap(true))) {
            assertEquals(Transition(s), loop.step(s, input))
        }
        assertEquals(Phase.Idle, loop.step(s, LoopInput.LoggedIn).phase)
    }

    // ---- ask: true, the clarification loop --------------------------------------------------------------------------

    @Test
    fun askEventIsSpokenThenAwaitingAnswerWithTheMicOpenAndNoTap() {
        val t = loop.step(st(Phase.Speaking(QUESTION, working)), PlaybackDone("q1"))
        assertEquals(Phase.AwaitingAnswer(QUESTION), t.phase)
        assertEquals(
            listOf(Ack(listOf("q1")), Earcon(EarconKind.YourTurn), StartCapture(answerMode = true), StartTimer(Timer.NoSpeech, 20_000)),
            t.effects,
        )
        assertEquals(QUESTION, t.state.question)
    }

    @Test
    fun interruptedQuestionStillGoesToAwaitingAnswer() {
        val t = loop.step(st(Phase.Speaking(QUESTION, working)), Tap)
        assertEquals(Phase.AwaitingAnswer(QUESTION), t.phase)
        assertEquals(StopPlayback, t.effects.first())
        assertTrue(Ack(listOf("q1")) in t.effects)
        assertEquals(listOf(StartCapture(answerMode = true, delayMs = 300)), t.captures)
    }

    @Test
    fun firstSilenceAfterAQuestionResaysItByPhoneVoice() {
        val t = loop.step(st(Phase.AwaitingAnswer(QUESTION)), TimerFired(Timer.NoSpeech))
        assertEquals(Phase.AwaitingAnswer(QUESTION, reminded = true), t.phase)
        assertEquals(
            listOf(
                StopCapture, Say(Lines.WAITING_FOR_ANSWER), Say(QUESTION.text), StartCapture(answerMode = true),
                StartTimer(Timer.NoSpeech, 20_000),
            ),
            t.effects,
        )
    }

    @Test
    fun secondSilencePausesKeepingTheQuestionAndSendsNothing() {
        val t = loop.run(st(Phase.AwaitingAnswer(QUESTION)).copy(questions = mapOf(WT to QUESTION)), TimerFired(Timer.NoSpeech), TimerFired(Timer.NoSpeech))
        assertEquals(Phase.Paused(PauseReason.Question), t.phase)
        assertEquals(QUESTION, t.state.question)
        assertTrue(Effect.Notify(Lines.askedQuestion(WT)) in t.effects)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun tapInTheQuestionPauseResaysItAndAwaitsTheAnswer() {
        val s = st(Phase.Paused(PauseReason.Question)).copy(questions = mapOf(WT to QUESTION))
        val t = loop.step(s, Tap)
        assertEquals(Phase.AwaitingAnswer(QUESTION), t.phase)
        val sayAt = t.effects.indexOf(Say(QUESTION.text))
        val captureAt = t.effects.indexOf(StartCapture(answerMode = true))
        assertTrue(sayAt in 0 until captureAt)
    }

    @Test
    fun headsetPressInTheQuestionPauseDoesTheSame() {
        val s = st(Phase.Paused(PauseReason.Question)).copy(questions = mapOf(WT to QUESTION))
        assertEquals(Phase.AwaitingAnswer(QUESTION), loop.step(s, LoopInput.HeadsetPress).phase)
    }

    @Test
    fun answerIsCapturedInAnswerModeAndALoneYesIsSentUnchanged() {
        val t = loop.run(
            st(Phase.AwaitingAnswer(QUESTION)), LoopInput.SpeechStart, SpeechEnd(GOOD), Transcribed(WT, "yes"),
        )
        assertEquals(listOf(Send(WT, "yes", voice = true)), t.sends)
        assertEquals(Phase.Sending(WT, "yes", answering = QUESTION), t.phase)
    }

    @Test
    fun okayAsAnAnswerIsRejectedAndStaysAwaitingAnswer() {
        val t = loop.step(st(Phase.Transcribing(WT, GOOD, answering = QUESTION)), Transcribed(WT, "okay"))
        assertEquals(Phase.AwaitingAnswer(QUESTION), t.phase)
        assertEquals(listOf(Lines.DIDNT_CATCH_ANSWER), t.says)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun threeRejectedAnswersPauseKeepingTheQuestion() {
        val s = st(Phase.AwaitingAnswer(QUESTION)).copy(questions = mapOf(WT to QUESTION))
        val noisy = arrayOf(LoopInput.SpeechStart, SpeechEnd(QUIET))
        val t = loop.run(s, *noisy, *noisy, *noisy)
        assertEquals(Phase.Paused(PauseReason.Noise), t.phase)
        assertEquals(QUESTION, t.state.question)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun theAnswerClearsTheQuestionOnceSent() {
        val s = st(Phase.Sending(WT, "the api one", answering = QUESTION)).copy(questions = mapOf(WT to QUESTION))
        assertEquals(null, loop.step(s, LoopInput.SendOk(WT)).state.question)
    }

    @Test
    fun theReplyToAnAnswerCanBeAnotherQuestionTwoRounds() {
        val q2 = event("q2", ask = AskKind.Answer, text = "Which branch?")
        val t = loop.run(
            st(Phase.AwaitingAnswer(QUESTION)),
            LoopInput.SpeechStart, SpeechEnd(GOOD), Transcribed(WT, "the api one"), LoopInput.SendOk(WT),
            View(view("working")), View(view("idle", events = listOf(q2))), PlaybackDone("q2"),
            LoopInput.SpeechStart, SpeechEnd(GOOD), Transcribed(WT, "the main branch"),
        )
        assertEquals(listOf(Send(WT, "the api one", true), Send(WT, "the main branch", true)), t.sends)
        assertEquals(Phase.Sending(WT, "the main branch", answering = q2), t.phase)
        assertTrue(t.effects.none { it is Effect.ShowSheet })
    }

    @Test
    fun aNonQuestionReplyGoesBackToPlainListening() {
        val t = loop.run(st(working, WORKING_AGENT), View(view("idle", events = listOf(event("e9")))), PlaybackDone("e9"))
        assertEquals(Phase.Listening, t.phase)
        assertEquals(listOf(StartCapture(answerMode = false)), t.captures)
    }

    @Test
    fun aQuestionFromAnotherWorktreeIsSpokenWithItsNameAndDoesNotOpenAnswerMode() {
        val q = event("o1", ask = AskKind.Answer, session = OTHER, text = "Which file?")
        val speaking = loop.step(st(Phase.Listening), View(view("idle", events = listOf(q))))
        assertEquals(Phase.Speaking(q, Phase.Listening), speaking.phase)
        assertTrue(Play(q, withName = true) in speaking.effects)
        val t = loop.step(speaking.state, PlaybackDone("o1"))
        assertEquals(Phase.Listening, t.phase)
        assertEquals(listOf(StartCapture(answerMode = false)), t.captures)
        assertEquals(q, t.state.questions[OTHER])
    }

    @Test
    fun switchingToTheWorktreeOfAKeptQuestionResaysItAndAwaitsTheAnswer() {
        val q = event("o1", ask = AskKind.Answer, session = OTHER, text = "Which file?")
        val s = st(Phase.Listening).copy(questions = mapOf(OTHER to q))
        val t = loop.step(s, LoopInput.WorktreeChanged(OTHER))
        assertEquals(Phase.AwaitingAnswer(q), t.phase)
        assertEquals(OTHER, t.state.worktree)
        assertEquals(listOf(q.text), t.says)
    }

    @Test
    fun tapInAwaitingAnswerPausesKeepingTheQuestion() {
        val s = st(Phase.AwaitingAnswer(QUESTION)).copy(questions = mapOf(WT to QUESTION))
        val t = loop.step(s, Tap)
        assertEquals(Phase.Paused(PauseReason.User), t.phase)
        assertEquals(QUESTION, t.state.question)
    }

    @Test
    fun viewWorkingInAwaitingAnswerMeansSomeoneAnsweredElsewhere() {
        val s = st(Phase.AwaitingAnswer(QUESTION)).copy(questions = mapOf(WT to QUESTION))
        val t = loop.step(s, View(view("working")))
        assertEquals(Phase.AgentWorking(null), t.phase)
        assertEquals(null, t.state.question)
    }

    // ---- ask: "confirm", tap-only ----------------------------------------------------------------------------------

    @Test
    fun confirmEventIsSpokenThenAwaitingTapWithTheMicOff() {
        val t = loop.step(st(Phase.Speaking(CONFIRM, working)), PlaybackDone("c1"))
        assertEquals(Phase.AwaitingTap(CONFIRM), t.phase)
        assertEquals(
            listOf(Ack(listOf("c1")), Say(Lines.LOOK_TO_CONFIRM), Effect.Vibrate, StartTimer(Timer.ConfirmReminder, 60_000)),
            t.effects,
        )
    }

    @Test
    fun anUnknownAskValueIsTreatedAsConfirm() {
        val json = """{"id":"u1","kind":"summary","session":"$WT","worktree":"scratch","text":"Go?","ask":"later"}"""
        val e = GatewayJson.decodeFromString<Event>(json)
        val t = loop.run(st(working, WORKING_AGENT), View(view("idle", events = listOf(e))), PlaybackDone("u1"))
        assertEquals(Phase.AwaitingTap(e), t.phase)
        assertTrue(t.captures.isEmpty())
    }

    @Test
    fun interruptedConfirmationStillGoesToAwaitingTapMicOff() {
        val t = loop.step(st(Phase.Speaking(CONFIRM, working)), Tap)
        assertEquals(Phase.AwaitingTap(CONFIRM), t.phase)
        assertTrue(t.captures.isEmpty())
        assertTrue(StopPlayback in t.effects && Ack(listOf("c1")) in t.effects)
    }

    @Test
    fun speechInputsAreImpossibleInAwaitingTap() {
        val s = st(Phase.AwaitingTap(CONFIRM))
        for (input in listOf(LoopInput.SpeechStart, SpeechEnd(GOOD), Transcribed(WT, "yes, go ahead"))) {
            assertEquals(Transition(s), loop.step(s, input))
        }
    }

    @Test
    fun orbTapAndHeadsetButtonOnlyResayTheConfirmation() {
        val s = st(Phase.AwaitingTap(CONFIRM))
        for (input in listOf(Tap, LoopInput.HeadsetPress)) {
            val t = loop.step(s, input)
            assertEquals(Transition(s, listOf(Say(CONFIRM.text))), t)
        }
    }

    @Test
    fun yesTapSendsYesGoAheadAsAVoicePromptWithTheTapMark() {
        val t = loop.step(st(Phase.AwaitingTap(CONFIRM)), ConfirmTap(true))
        assertEquals(listOf(Send(WT, "yes, go ahead", voice = true, confirmTap = true)), t.sends)
        assertTrue(Earcon(EarconKind.Sent) in t.effects)
        assertEquals(Phase.Sending(WT, "yes, go ahead", confirmTap = true), t.phase)
        assertTrue(t.effects.none { it is StartCapture }) // The mic stays off.
    }

    @Test
    fun noTapSendsNoDoNotDoThatWithTheTapMark() {
        val t = loop.step(st(Phase.AwaitingTap(CONFIRM)), ConfirmTap(false))
        assertEquals(listOf(Send(WT, "no, do not do that", voice = true, confirmTap = true)), t.sends)
        assertEquals(Phase.Sending(WT, "no, do not do that", confirmTap = true), t.phase)
        assertTrue(t.effects.none { it is StartCapture })
    }

    @Test
    fun noOtherSendCarriesTheTapMark() {
        val sends = listOf(
            loop.run(st(transcribing), Transcribed(WT, TEXT)),
            loop.run(st(Phase.AwaitingAnswer(QUESTION)), LoopInput.SpeechStart, SpeechEnd(GOOD), Transcribed(WT, "yes")),
            loop.step(st(Phase.Listening), LoopInput.TypedSend("git status please")),
            loop.step(st(Phase.Held(TEXT, HeldReason.AgentBusy, agentFree = true)), LoopInput.SendNowTap),
        ).flatMap { it.sends }
        assertEquals(4, sends.size)
        assertTrue("$sends", sends.none { it.confirmTap })
        assertFalse(Send(WT, TEXT).confirmTap)
    }

    @Test
    fun theTapMarkSurvivesTheRetry() {
        val sent = loop.step(st(Phase.AwaitingTap(CONFIRM)), ConfirmTap(true))
        val first = loop.step(sent.state, SendRefused(WT, refusal(RefusalReason.RepeatWindow)))
        val retry = loop.step(first.state, TimerFired(Timer.SendRetry))
        assertEquals(listOf(Send(WT, "yes, go ahead", voice = true, confirmTap = true)), retry.sends)
    }

    @Test
    fun aHeldTapAnswerIsSentAgainWithTheTapMark() {
        val sent = loop.step(st(Phase.AwaitingTap(CONFIRM)), ConfirmTap(false))
        for (fail in listOf(
            SendRefused(WT, refusal(RefusalReason.NotSettled)), LoopInput.SendFailed(WT, maybeSent = false),
        )) {
            val held = loop.step(sent.state, fail)
            assertEquals(true, (held.phase as Phase.Held).confirmTap)
            assertEquals(
                listOf(Send(WT, "no, do not do that", voice = true, confirmTap = true)),
                loop.step(held.state, LoopInput.SendNowTap).sends,
            )
        }
        val maybe = loop.run(
            sent.state, LoopInput.SendFailed(WT, maybeSent = true), View(view("idle")), TimerFired(Timer.Settled),
        )
        assertEquals(true, (maybe.phase as Phase.Held).confirmTap)
    }

    @Test
    fun confirmReminderIsOnceAt60s() {
        val t = loop.step(st(Phase.AwaitingTap(CONFIRM)), TimerFired(Timer.ConfirmReminder))
        assertEquals(Phase.AwaitingTap(CONFIRM, reminded = true), t.phase)
        assertEquals(listOf(Say(Lines.LOOK_TO_CONFIRM), Effect.Vibrate), t.effects)
        assertEquals(Transition(t.state), loop.step(t.state, TimerFired(Timer.ConfirmReminder)))
    }

    @Test
    fun silenceInAwaitingTapNeverSends() {
        val t = loop.run(st(Phase.AwaitingTap(CONFIRM)), *Timer.entries.map { TimerFired(it) }.toTypedArray(), View(view("idle")))
        assertTrue(t.sends.isEmpty())
        assertTrue(t.phase is Phase.AwaitingTap)
    }

    @Test
    fun viewWorkingInAwaitingTapGoesToAgentWorkingAndRemovesTheButtons() {
        val s = st(Phase.AwaitingTap(CONFIRM)).copy(questions = mapOf(WT to CONFIRM))
        val t = loop.step(s, View(view("working")))
        assertEquals(Phase.AgentWorking(null), t.phase)
        assertEquals(null, t.state.question)
    }

    @Test
    fun longPressInAwaitingTapGoesIdleKeepingTheQuestion() {
        val s = st(Phase.AwaitingTap(CONFIRM)).copy(questions = mapOf(WT to CONFIRM))
        val t = loop.step(s, LoopInput.LongPress)
        assertEquals(Phase.Idle, t.phase)
        assertEquals(CONFIRM, t.state.question)
    }

    @Test
    fun worktreeChangeKeepsTheConfirmationForTheReturn() {
        val s = st(Phase.AwaitingTap(CONFIRM)).copy(questions = mapOf(WT to CONFIRM))
        val away = loop.step(s, LoopInput.WorktreeChanged(OTHER))
        assertEquals(CONFIRM, away.state.questions[WT])
        val back = loop.step(away.state, LoopInput.WorktreeChanged(WT))
        assertEquals(Phase.AwaitingTap(CONFIRM), back.phase)
        assertEquals(listOf(CONFIRM.text, Lines.LOOK_TO_CONFIRM), back.says)
        assertTrue(back.captures.isEmpty())
    }

    @Test
    fun aConfirmationFromAnotherWorktreeShowsNoButtonsHere() {
        val c = event("o2", ask = AskKind.Confirm, session = OTHER, text = "Force push?")
        val t = loop.run(st(Phase.Listening), View(view("idle", events = listOf(c))), PlaybackDone("o2"))
        assertEquals(Phase.Listening, t.phase)
        assertEquals(c, t.state.questions[OTHER])
    }

    @Test
    fun confirmTapOutsideAwaitingTapIsIgnored() {
        for (phase in listOf(Phase.Listening, Phase.AwaitingAnswer(QUESTION), working, Phase.Idle)) {
            val s = st(phase)
            assertEquals(Transition(s), loop.step(s, ConfirmTap(true)))
        }
    }

    // ---- NeedsYou -------------------------------------------------------------------------------------------------

    @Test
    fun needsYouEventIsSpokenThenTheLookAtThePhoneCueTapsOnly() {
        val e = event("n1", kind = EventKind.NeedsYou, text = "wt-scratch needs you.")
        val s = st(working, BLOCKED_AGENT)
        val t = loop.run(s, View(view("blocked", events = listOf(e), token = "tok1", output = "1. Yes")), PlaybackDone("n1"))
        assertEquals(Phase.NeedsYou("tok1", "1. Yes", sent = Sent(TEXT)), t.phase)
        assertEquals(listOf(Lines.LOOK_TO_ANSWER), t.says)
        assertTrue(Effect.Vibrate in t.effects && Effect.ShowSheet in t.effects)
        assertTrue(StartTimer(Timer.NeedsYouReminder, 60_000) in t.effects)
        assertTrue(t.captures.isEmpty()) // keys are taps only: the mic stays off while blocked
        assertEquals(Transition(t.state), loop.step(t.state, LoopInput.SpeechStart))
    }

    @Test
    fun blockedViewWithoutAnEventCuesAtOnce() {
        val t = loop.step(st(working, WORKING_AGENT), View(view("blocked", token = "tok1", output = "1. Yes")))
        assertEquals(Phase.NeedsYou("tok1", "1. Yes", sent = Sent(TEXT)), t.phase)
        assertEquals(listOf(Lines.LOOK_TO_ANSWER), t.says)
    }

    @Test
    fun needsYouCueRepeatsOnceAt60s() {
        val s = st(Phase.NeedsYou("tok1", "1. Yes"), BLOCKED_AGENT)
        val t = loop.step(s, TimerFired(Timer.NeedsYouReminder))
        assertEquals(Phase.NeedsYou("tok1", "1. Yes", reminded = true), t.phase)
        assertEquals(listOf(Say(Lines.LOOK_TO_ANSWER), Effect.Vibrate), t.effects)
        assertEquals(Transition(t.state), loop.step(t.state, TimerFired(Timer.NeedsYouReminder)))
    }

    @Test
    fun keyTapSendsTheKeyWithTheScreenToken() {
        val t = loop.step(st(Phase.NeedsYou("tok1", "1. Yes"), BLOCKED_AGENT), LoopInput.KeyTap("1"))
        assertEquals(listOf(Effect.SendKey(WT, "1", "tok1")), t.effects)
    }

    @Test
    fun screenChangedReplacesTheScreenAndAsksForTheTapAgain() {
        val r = refusal(RefusalReason.ScreenChanged, screenToken = "tok2", output = "2. No")
        val t = loop.step(st(Phase.NeedsYou("tok1", "1. Yes"), BLOCKED_AGENT), LoopInput.KeyRefused(WT, r))
        assertEquals(Phase.NeedsYou("tok2", "2. No"), t.phase)
        assertEquals(listOf(Lines.SCREEN_CHANGED), t.says)
        assertTrue(Effect.ShowSheet in t.effects)
    }

    @Test
    fun leavingBlockedGivesAgentWorkingThenTheFinalEventIsSpoken() {
        val s = st(Phase.NeedsYou("tok1", "1. Yes", sent = Sent(TEXT)), BLOCKED_AGENT)
        val t = loop.step(s, View(view("working")))
        assertEquals(Phase.AgentWorking(Sent(TEXT)), t.phase)
        val e = event("e5")
        assertEquals(Phase.Speaking(e, Phase.AgentWorking(Sent(TEXT))), loop.step(t.state, View(view("idle", events = listOf(e)))).phase)
    }

    @Test
    fun keyTapOutsideNeedsYouIsIgnored() {
        val s = st(Phase.Listening)
        assertEquals(Transition(s), loop.step(s, LoopInput.KeyTap("1")))
    }

    // ---- Tap semantics per phase ----------------------------------------------------------------------------------

    @Test
    fun tapInListeningPauses() = assertEquals(Phase.Paused(PauseReason.User), loop.step(st(Phase.Listening), Tap).phase)

    @Test
    fun tapInCapturingEndsTheUtteranceNow() {
        val t = loop.step(st(Phase.Capturing(WT)), Tap)
        assertEquals(Phase.Capturing(WT), t.phase)
        assertEquals(listOf(Effect.FinishCapture), t.effects)
    }

    @Test
    fun tapDoesNothingInTranscribingSendingAgentWorkingHeldStoppedNeedsYou() {
        for (phase in listOf(
            transcribing, sending, working, Phase.Held(TEXT, HeldReason.AgentBusy), Phase.Stopped(), Phase.NeedsYou("t", "o"),
        )) {
            val s = st(phase)
            assertEquals("$phase", Transition(s), loop.step(s, Tap))
        }
    }

    @Test
    fun tapWhileSpeakingInterruptsAcknowledgesAndListensAfterTheSpeakerTail() {
        val e = event("e1")
        val t = loop.step(st(Phase.Speaking(e, working)), Tap)
        assertEquals(Phase.Listening, t.phase)
        assertEquals(StopPlayback, t.effects.first())
        assertTrue(Ack(listOf("e1")) in t.effects)
        assertEquals(listOf(StartCapture(answerMode = false, delayMs = 300)), t.captures)
    }

    @Test
    fun tapInPausedOrErrorResumes() {
        for (phase in listOf(Phase.Paused(PauseReason.User), Phase.Error(ErrorKind.PaneChanged, "x"))) {
            val t = loop.step(st(phase).copy(rejections = 2), Tap)
            assertEquals(Phase.Listening, t.phase)
            assertEquals(0, t.state.rejections)
        }
    }

    // ---- Mute -----------------------------------------------------------------------------------------------------

    @Test
    fun mutedEventIsAcknowledgedWithAnEarconAndNoAudioThenListens() {
        val e = event("e1")
        val s = st(working, WORKING_AGENT).copy(muted = true)
        val t = loop.step(s, View(view("idle", events = listOf(e))))
        assertEquals(Phase.Listening, t.phase)
        assertTrue(Ack(listOf("e1")) in t.effects)
        assertTrue(t.effects.none { it is Play })
        assertTrue(t.effects.any { it is Earcon })
    }

    @Test
    fun mutingWhileSpeakingStopsTheAudioAndAcknowledges() {
        val t = loop.step(st(Phase.Speaking(event("e1"), working)), LoopInput.MuteChanged(true))
        assertEquals(StopPlayback, t.effects.first())
        assertTrue(Ack(listOf("e1")) in t.effects)
        assertEquals(Phase.Listening, t.phase)
    }

    @Test
    fun mutedDropsPhoneVoiceLines() {
        val s = st(Phase.Capturing(WT)).copy(muted = true)
        assertTrue(loop.step(s, SpeechEnd(QUIET)).says.isEmpty())
    }

    // ---- Stop -----------------------------------------------------------------------------------------------------

    @Test
    fun stopIsAlwaysAvailableAndOnlySendsStop() {
        for (phase in samplePhases().filter { it != Phase.LoggedOut }) {
            val s = st(phase)
            assertEquals("$phase", Transition(s, listOf(Effect.SendStop(WT))), loop.step(s, LoopInput.StopTap))
        }
    }

    @Test
    fun headsetLongPressStopsInAgentWorkingAndGoesIdleElsewhere() {
        assertEquals(listOf(Effect.SendStop(WT)), loop.step(st(working), LoopInput.HeadsetLongPress).effects)
        assertEquals(Phase.Idle, loop.step(st(Phase.Listening), LoopInput.HeadsetLongPress).phase)
    }

    @Test
    fun longPressAnywhereGoesIdle() {
        for (phase in samplePhases().filter { it != Phase.LoggedOut }) {
            assertEquals("$phase", Phase.Idle, loop.step(st(phase), LoopInput.LongPress).phase)
        }
    }

    // ---- Worktree switch (G8) -------------------------------------------------------------------------------------

    @Test
    fun switchWhileCapturingDiscardsTheUtterance() {
        val t = loop.run(st(Phase.Capturing(WT)), LoopInput.WorktreeChanged(OTHER), SpeechEnd(GOOD))
        assertEquals(StopCapture, t.effects.first())
        assertTrue(t.effects.none { it is Upload })
        assertEquals(OTHER, t.state.worktree)
        assertEquals(Phase.AgentWorking(null), t.phase)
        assertEquals(AgentView.Unknown, t.state.agent)
    }

    @Test
    fun aLateTranscriptForTheOldWorktreeIsNeverSent() {
        val t = loop.run(st(transcribing), LoopInput.WorktreeChanged(OTHER), View(view("idle", name = OTHER)), Transcribed(WT, TEXT))
        assertTrue(t.sends.isEmpty())
        assertEquals(Phase.Listening, t.phase)
    }

    @Test
    fun aTranscriptIsSentOnlyToTheWorktreeCurrentWhenSpeechStarted() {
        val s = st(Phase.Transcribing(OTHER, GOOD))
        assertTrue(loop.step(s, Transcribed(OTHER, TEXT)).sends.isEmpty())
        assertEquals(Transition(s), loop.step(s, Transcribed(WT, TEXT)))
    }

    @Test
    fun beforeTheNewWorktreesFirstViewATranscriptIsSentTheServerRechecks() {
        val s = st(Phase.Transcribing(WT, GOOD, answering = QUESTION), AgentView.Unknown)
        assertEquals(listOf(Send(WT, "yes", true)), loop.step(s, Transcribed(WT, "yes")).sends)
    }

    @Test
    fun switchingAwayFromHeldDropsTheHeldText() {
        val t = loop.step(st(Phase.Held(TEXT, HeldReason.AgentBusy)), LoopInput.WorktreeChanged(OTHER))
        assertEquals(Phase.AgentWorking(null), t.phase)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun switchingToTheSameWorktreeChangesNothing() {
        val s = st(Phase.Capturing(WT))
        assertEquals(Transition(s), loop.step(s, LoopInput.WorktreeChanged(WT)))
    }

    @Test
    fun viewsOfAnotherWorktreeDoNotChangeTheStatus() {
        val s = st(Phase.Listening)
        assertEquals(Transition(s), loop.step(s, View(view("working", name = OTHER))))
    }

    // ---- Headset, app paused or killed, focus ---------------------------------------------------------------------

    @Test
    fun headsetPressActsAsATap() {
        for (phase in listOf(Phase.Idle, Phase.Listening, Phase.Speaking(event("e1"), working), Phase.Capturing(WT))) {
            assertEquals("$phase", loop.step(st(phase), Tap), loop.step(st(phase), LoopInput.HeadsetPress))
        }
    }

    @Test
    fun appHiddenWhileIdleStopsPollingAndVisibleResumes() {
        val hidden = loop.step(st(Phase.Idle), LoopInput.AppVisible(false))
        assertEquals(listOf(SetPoll(null)), hidden.effects)
        assertEquals(listOf(SetPoll(5_000)), loop.step(hidden.state, LoopInput.AppVisible(true)).effects)
    }

    @Test
    fun appHiddenWhileTheLoopRunsChangesNothing() {
        val t = loop.step(st(Phase.Listening), LoopInput.AppVisible(false))
        assertEquals(Phase.Listening, t.phase)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun appStoppedEndsTheLoopReleasingMicAndAudio() {
        val c = loop.step(st(Phase.Capturing(WT)), LoopInput.AppStopped)
        assertEquals(Phase.Idle, c.phase)
        assertTrue(StopCapture in c.effects)
        val sp = loop.step(st(Phase.Speaking(event("e1"), working)), LoopInput.AppStopped)
        assertTrue(StopPlayback in sp.effects)
    }

    @Test
    fun focusLossPausesAndNeverResumesOnItsOwn() {
        val t = loop.run(st(Phase.Listening), LoopInput.FocusLost, View(view("idle")), View(view("idle", events = listOf(event("e1")))))
        assertEquals(Phase.Paused(PauseReason.FocusLost), t.phase)
        assertTrue(t.effects.none { it is Play || it is StartCapture })
    }

    // ---- Other rows -----------------------------------------------------------------------------------------------

    @Test
    fun noSpeechFor45sInListeningPausesWithAnEarcon() {
        val t = loop.step(st(Phase.Listening), TimerFired(Timer.NoSpeech))
        assertEquals(Phase.Paused(PauseReason.NoSpeech), t.phase)
        assertTrue(Earcon(EarconKind.Paused) in t.effects)
    }

    @Test
    fun pausedForTenMinutesEndsTheLoop() {
        val t = loop.step(st(Phase.Paused(PauseReason.User)), TimerFired(Timer.PauseLimit))
        assertEquals(Phase.Idle, t.phase)
        assertTrue(Effect.EndService in t.effects)
    }

    @Test
    fun noFinalEventAfter20sListensWithDoneCheckTheScreen() {
        val t = loop.run(st(working, WORKING_AGENT), View(view("idle")), TimerFired(Timer.Settled))
        assertEquals(Phase.Listening, t.phase)
        assertTrue(Lines.DONE_CHECK_SCREEN in t.says)
    }

    @Test
    fun listeningOnlyWhileTheAgentIsSettled() {
        assertEquals(Phase.AgentWorking(null), loop.step(st(Phase.Listening), View(view("working"))).phase)
    }

    @Test
    fun otherWorktreeEventsWaitDuringCapturingTranscribingAndAwaitingAnswer() {
        val o = event("o3", session = OTHER)
        for (phase in listOf(Phase.Capturing(WT), transcribing, Phase.AwaitingAnswer(QUESTION))) {
            val t = loop.step(st(phase), View(view("idle", events = listOf(o))))
            assertEquals("$phase", phase, t.phase)
            assertTrue(t.effects.none { it is Play })
            assertEquals(listOf(o), t.state.queue)
        }
    }

    @Test
    fun otherWorktreeEventIsSpokenWithItsNameBetweenTurnsAndTheLoopGoesOn() {
        val o = event("o3", session = OTHER)
        val t = loop.run(st(working, WORKING_AGENT), View(view("working", events = listOf(o))))
        assertEquals(Phase.Speaking(o, working), t.phase)
        assertTrue(Play(o, withName = true) in t.effects)
        assertEquals(working, loop.step(t.state, PlaybackDone("o3")).phase)
    }

    @Test
    fun pollingIsFastWhileWaitingForTheAgent() {
        assertEquals(2_000L, loop.pollInterval(st(sending)))
        assertEquals(2_000L, loop.pollInterval(st(working)))
        assertEquals(2_000L, loop.pollInterval(st(Phase.NeedsYou("t", "o"))))
        assertEquals(5_000L, loop.pollInterval(st(Phase.Listening)))
        assertEquals(5_000L, loop.pollInterval(st(Phase.Paused(PauseReason.User))))
        assertEquals(5_000L, loop.pollInterval(st(Phase.Idle)))
        assertEquals(null, loop.pollInterval(st(Phase.Idle).copy(visible = false)))
        assertEquals(null, loop.pollInterval(st(Phase.LoggedOut)))
    }

    @Test
    fun eventsServerNoLongerListsAreDroppedFromTheQueue() {
        val o = event("o3", session = OTHER)
        val queued = loop.step(st(Phase.Capturing(WT)), View(view("idle", events = listOf(o)))).state
        assertEquals(emptyList<Event>(), loop.step(queued, View(view("idle"))).state.queue)
    }

    // ---- A10: a pending question survives a new event ----------------------------------------------------------

    @Test
    fun aPlainEventWhileAwaitingAnAnswerIsSpokenThenTheQuestionIsAskedAgain() {
        val s = st(Phase.AwaitingAnswer(QUESTION)).copy(questions = mapOf(WT to QUESTION))
        val e = event("e7", text = "Build finished.")
        val speaking = loop.step(s, View(view("idle", events = listOf(e))))
        assertEquals(Phase.Speaking(e, Phase.AwaitingAnswer(QUESTION)), speaking.phase)
        val t = loop.step(speaking.state, PlaybackDone("e7"))
        assertEquals(Phase.AwaitingAnswer(QUESTION), t.phase)
        assertEquals(QUESTION, t.state.question)
        assertTrue(Ack(listOf("e7")) in t.effects)
        val sayAt = t.effects.indexOf(Say(QUESTION.text))
        val captureAt = t.effects.indexOf(StartCapture(answerMode = true))
        assertTrue(t.effects.toString(), sayAt in 0 until captureAt)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun aPlainEventWhileAwaitingATapNeverDropsTheConfirmation() {
        val s = st(Phase.AwaitingTap(CONFIRM)).copy(questions = mapOf(WT to CONFIRM))
        val t = loop.run(s, View(view("idle", events = listOf(event("e7")))), PlaybackDone("e7"))
        assertEquals(Phase.AwaitingTap(CONFIRM), t.phase)
        assertEquals(CONFIRM, t.state.question)
        assertTrue(t.says.toString(), CONFIRM.text in t.says)
        assertTrue(t.captures.isEmpty())
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun interruptingAPlainEventWhileAwaitingATapStillKeepsTheConfirmationMicOff() {
        val s = st(Phase.AwaitingTap(CONFIRM)).copy(questions = mapOf(WT to CONFIRM))
        val t = loop.run(s, View(view("idle", events = listOf(event("e7")))), Tap)
        assertEquals(Phase.AwaitingTap(CONFIRM), t.phase)
        assertTrue(Ack(listOf("e7")) in t.effects)
        assertTrue(t.captures.isEmpty())
    }

    @Test
    fun aConfirmationStaysTapOnlyWhenANewSpokenQuestionArrives() {
        val s = st(Phase.AwaitingTap(CONFIRM)).copy(questions = mapOf(WT to CONFIRM))
        val q2 = event("q2", ask = AskKind.Answer, text = "Which branch?")
        val t = loop.run(s, View(view("idle", events = listOf(q2))), PlaybackDone("q2"))
        val phase = t.phase as Phase.AwaitingTap
        assertEquals(q2.text, phase.question.text)
        assertEquals(AskKind.Confirm, t.state.question?.ask)
        assertTrue(t.captures.isEmpty())
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun aNewSpokenQuestionReplacesAnOlderSpokenQuestion() {
        val s = st(Phase.AwaitingAnswer(QUESTION)).copy(questions = mapOf(WT to QUESTION))
        val q2 = event("q2", ask = AskKind.Answer, text = "Which branch?")
        val t = loop.run(s, View(view("idle", events = listOf(q2))), PlaybackDone("q2"))
        assertEquals(Phase.AwaitingAnswer(q2), t.phase)
        assertEquals(q2, t.state.question)
    }

    @Test
    fun aNewConfirmationReplacesAnOlderSpokenQuestionWithTheMicOff() {
        val s = st(Phase.AwaitingAnswer(QUESTION)).copy(questions = mapOf(WT to QUESTION))
        val c2 = event("c2", ask = AskKind.Confirm, text = "Force push to main?")
        val t = loop.run(s, View(view("idle", events = listOf(c2))), PlaybackDone("c2"))
        assertEquals(Phase.AwaitingTap(c2), t.phase)
        assertEquals(c2, t.state.question)
        assertTrue(t.captures.isEmpty())
    }

    @Test
    fun aDialogWhileAConfirmationIsPendingComesBackToTheConfirmation() {
        val s = st(Phase.AwaitingTap(CONFIRM)).copy(questions = mapOf(WT to CONFIRM))
        val n = event("n1", kind = EventKind.NeedsYou, text = "scratch needs you")
        val blocked = loop.run(s, View(view("blocked", token = "t1", events = listOf(n))), PlaybackDone("n1"))
        assertTrue(blocked.phase is Phase.NeedsYou)
        assertEquals(CONFIRM, blocked.state.question)
        val t = loop.step(blocked.state, View(view("idle")))
        assertEquals(Phase.AwaitingTap(CONFIRM), t.phase)
        assertTrue(CONFIRM.text in t.says)
        assertTrue(t.captures.isEmpty())
    }

    // ---- A10: microphone problems reach the loop ---------------------------------------------------------------

    @Test
    fun micSilencedWhileListeningPausesAndSaysSoWithTheMicOff() {
        val t = loop.step(st(Phase.Listening), LoopInput.MicFailed(MicFailure.Silenced))
        assertEquals(Phase.Paused(PauseReason.MicSilenced), t.phase)
        assertEquals(listOf(Lines.MIC_SILENCED), t.says)
        assertTrue(t.effects.toString(), t.effects.indexOf(StopCapture) in 0 until t.effects.indexOf(Say(Lines.MIC_SILENCED)))
    }

    @Test
    fun micUnavailableWhileAwaitingAnAnswerPausesKeepingTheQuestion() {
        val s = st(Phase.AwaitingAnswer(QUESTION)).copy(questions = mapOf(WT to QUESTION))
        val t = loop.step(s, LoopInput.MicFailed(MicFailure.Unavailable))
        assertEquals(Phase.Paused(PauseReason.MicUnavailable), t.phase)
        assertEquals(QUESTION, t.state.question)
        assertEquals(listOf(Lines.MIC_UNAVAILABLE), t.says)
    }

    @Test
    fun micFailureWhileCapturingDropsTheUtteranceAndPauses() {
        val t = loop.step(st(Phase.Capturing(WT)), LoopInput.MicFailed(MicFailure.Silenced))
        assertEquals(Phase.Paused(PauseReason.MicSilenced), t.phase)
        assertTrue(t.effects.none { it is Upload })
    }

    @Test
    fun micFailureOutsideTheMicPhasesChangesNothing() {
        for (phase in listOf(Phase.Idle, Phase.AwaitingTap(CONFIRM), working, Phase.Speaking(event("e1"), working), transcribing)) {
            val s = st(phase)
            assertEquals("$phase", Transition(s), loop.step(s, LoopInput.MicFailed(MicFailure.Unavailable)))
        }
    }

    // ---- A10: typed prompts from the sheet ---------------------------------------------------------------------

    @Test
    fun typedTextIsSentWithVoiceFalseAndTheMicStops() {
        val t = loop.step(st(Phase.Listening), LoopInput.TypedSend("git status please"))
        assertEquals(Phase.Sending(WT, "git status please", voice = false), t.phase)
        assertEquals(listOf(Send(WT, "git status please", voice = false)), t.sends)
        assertTrue(t.effects.toString(), t.effects.indexOf(StopCapture) in 0 until t.effects.indexOf(Earcon(EarconKind.Sent)))
    }

    @Test
    fun typedTextFromIdleStartsTheTurnAndTheReplyIsSpoken() {
        val t = loop.run(
            st(Phase.Idle), LoopInput.TypedSend("run the tests"), LoopInput.SendOk(WT),
            View(view("working")), View(view("idle", events = listOf(event("e5")))),
        )
        assertEquals(listOf(Send(WT, "run the tests", voice = false)), t.sends)
        assertEquals(Phase.Speaking(event("e5"), Phase.AgentWorking(Sent("run the tests", voice = false))), t.phase)
    }

    @Test
    fun typedTextKeepsTodaysTypedRulesSoSlashIsAllowed() {
        val t = loop.step(st(Phase.Paused(PauseReason.User)), LoopInput.TypedSend("/compact"))
        assertEquals(listOf(Send(WT, "/compact", voice = false)), t.sends)
    }

    @Test
    fun typedTextIsIgnoredMidTurnAndWhenBlank() {
        val midTurn = listOf(
            Phase.Capturing(WT), transcribing, sending, working, Phase.Speaking(event("e1"), working),
            Phase.NeedsYou("t", "o"), Phase.Stopped(), Phase.LoggedOut,
        )
        for (phase in midTurn) assertEquals("$phase", Transition(st(phase)), loop.step(st(phase), LoopInput.TypedSend(TEXT)))
        for (blank in listOf("", "   ", "\n")) {
            assertEquals(Transition(st(Phase.Listening)), loop.step(st(Phase.Listening), LoopInput.TypedSend(blank)))
        }
    }

    @Test
    fun aRefusedTypedPromptIsHeldAndSendNowSendsItTypedAgain() {
        val t = loop.run(
            st(Phase.Sending(WT, "git status", voice = false)), SendRefused(WT, refusal(RefusalReason.NotSettled)),
        )
        assertEquals(Phase.Held("git status", HeldReason.AgentBusy, voice = false), t.phase)
        assertEquals(listOf(Send(WT, "git status", voice = false)), loop.step(t.state, LoopInput.SendNowTap).sends)
    }

    @Test
    fun theOneRetryOfATypedPromptStaysTyped() {
        val t = loop.run(
            st(Phase.Sending(WT, "git status", voice = false)), SendRefused(WT, refusal(RefusalReason.RepeatWindow)),
            TimerFired(Timer.SendRetry),
        )
        assertEquals(listOf(Send(WT, "git status", voice = false)), t.sends)
    }

    @Test
    fun aTypedReplyAnswersAPendingConfirmation() {
        val s = st(Phase.AwaitingTap(CONFIRM)).copy(questions = mapOf(WT to CONFIRM))
        val t = loop.run(s, LoopInput.TypedSend("no, keep the branch"), LoopInput.SendOk(WT))
        assertEquals(Phase.AgentWorking(Sent("no, keep the branch", voice = false)), t.phase)
        assertEquals(null, t.state.question)
    }
}

/** One instance of every phase (reused by the property tests). */
fun samplePhases(): List<Phase> = listOf(
    Phase.Idle,
    Phase.Listening,
    Phase.AwaitingAnswer(QUESTION),
    Phase.AwaitingTap(CONFIRM),
    Phase.Capturing(WT),
    Phase.Transcribing(WT, GOOD),
    Phase.Sending(WT, TEXT),
    Phase.AgentWorking(Sent(TEXT)),
    Phase.Speaking(event("e1"), Phase.Listening),
    Phase.NeedsYou("tok1", "1. Yes"),
    Phase.Held(TEXT, HeldReason.AgentBusy),
    Phase.Paused(PauseReason.User),
    Phase.Stopped(),
    Phase.Error(ErrorKind.PaneChanged, "x"),
    Phase.LoggedOut,
    Phase.NewWorktree(dev.nittala.voicegateway.core.worktree.WorktreeFlow.Request(listening = true)),
)
