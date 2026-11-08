package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.api.Refusal
import dev.nittala.voicegateway.core.api.RefusalReason
import dev.nittala.voicegateway.core.loop.Effect.Earcon
import dev.nittala.voicegateway.core.loop.Effect.Say
import dev.nittala.voicegateway.core.loop.Effect.StartTimer
import dev.nittala.voicegateway.core.model.AskKind
import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.model.EventKind
import dev.nittala.voicegateway.core.model.SessionView
import dev.nittala.voicegateway.core.speech.EnergyGate
import dev.nittala.voicegateway.core.speech.TranscriptGuard
import dev.nittala.voicegateway.core.speech.TranscriptRejection
import dev.nittala.voicegateway.core.speech.TranscriptVerdict
import dev.nittala.voicegateway.core.speech.Utterance
import dev.nittala.voicegateway.core.speech.VoicedGate
import dev.nittala.voicegateway.core.worktree.FlowStep
import dev.nittala.voicegateway.core.worktree.WorktreeFlow
import dev.nittala.voicegateway.core.worktree.WorktreeFlowRules
import dev.nittala.voicegateway.core.worktree.WorktreeLines

/**
 * Spec (A6, plan section 7): the voice loop reducer. Pure: no clocks, threads, Android or network; timers are
 * [LoopInput.TimerFired] inputs and [Effect.StartTimer]/[Effect.CancelTimer] effects. The transition table is
 * VoiceLoopTest; the cross-cutting rules are VoiceLoopPropertyTest.
 *
 * [step] runs in three stages:
 * 1. the phase's own rule for the input;
 * 2. queued events are played if the new phase may speak (other worktrees' events only in Listening and
 *    AgentWorking, the current worktree's also in AwaitingAnswer, AwaitingTap and NeedsYou);
 * 3. rules that hold for every transition: leaving Speaking early stops and acknowledges the audio; the mic is
 *    stopped before the loop makes any sound and whenever it leaves Listening/AwaitingAnswer/Capturing; a phase's
 *    timers are cancelled when it is left; Say is dropped while muted; SetPoll follows [pollInterval].
 *
 * W6: [Phase.NewWorktree]'s own rules are [WorktreeFlowRules] (VoiceLoopWorktreeTest). Its captures go through
 * Capturing and Transcribing with a [CapturePurpose.Worktree], so the guards, the upload retries and every mic rule
 * above apply to them unchanged; their text goes back to the flow, never to the agent.
 */
class VoiceLoop(val config: LoopConfig = LoopConfig()) {
    private val flowRules = WorktreeFlowRules(config)

    fun step(state: LoopState, input: LoopInput): Transition = finish(state, input, drain(dispatch(state, input)))

    /** Pure: the poll interval for a state (plan section 5); null means no polling. */
    fun pollInterval(state: LoopState): Long? = when (state.phase) {
        Phase.LoggedOut -> null
        Phase.Idle -> if (state.visible) config.slowPollMs else null
        is Phase.Sending, is Phase.AgentWorking, is Phase.NeedsYou -> config.fastPollMs
        is Phase.NewWorktree -> if (state.phase.flow is WorktreeFlow.Handoff) config.fastPollMs else config.slowPollMs
        else -> config.slowPollMs
    }

    // ---- Stage 1: the phase's rule ----------------------------------------------------------------------------------

    private fun dispatch(s: LoopState, i: LoopInput): Transition {
        val p = s.phase
        if (p == Phase.LoggedOut) return if (i == LoopInput.LoggedIn) Transition(s.copy(phase = Phase.Idle)) else Transition(s)
        return when (i) {
            LoopInput.Unauthorized -> Transition(s.copy(phase = Phase.LoggedOut), listOf(Effect.EndService))
            LoopInput.LoggedIn -> Transition(s)
            LoopInput.AppStopped, LoopInput.LongPress -> Transition(keepQuestion(s).copy(phase = Phase.Idle))
            // On the plan card the headset button does nothing: the card's mic opens only after it was read aloud
            // or by a tap on the screen the user is looking at.
            LoopInput.HeadsetPress ->
                if (p is Phase.NewWorktree && p.flow is WorktreeFlow.Card) Transition(s) else dispatch(s, LoopInput.Tap)
            LoopInput.HeadsetLongPress ->
                dispatch(s, if (p is Phase.AgentWorking) LoopInput.StopTap else LoopInput.LongPress)
            LoopInput.StopTap -> Transition(s, listOf(Effect.SendStop(s.worktree)))
            is LoopInput.MuteChanged -> {
                val muted = s.copy(muted = i.muted)
                if (i.muted && p is Phase.Speaking) finishPlayback(muted, p, 0) else Transition(muted)
            }
            is LoopInput.AppVisible -> Transition(s.copy(visible = i.visible))
            LoopInput.NetworkLost -> Transition(s.copy(online = false))
            LoopInput.NetworkBack -> networkBack(s.copy(online = true))
            is LoopInput.View -> onView(s, i.view)
            is LoopInput.WorktreeChanged -> onSwitch(s, i.worktree)
            is LoopInput.TimerFired -> if (i.timer in runningTimers(p)) onTimer(s, i.timer) else Transition(s)
            LoopInput.Tap -> onTap(s)
            LoopInput.SpeechStart -> when {
                p == Phase.Listening -> Transition(s.copy(phase = Phase.Capturing(s.worktree)))
                p is Phase.AwaitingAnswer -> Transition(s.copy(phase = Phase.Capturing(s.worktree, p.question)))
                p is Phase.NewWorktree && p.flow.listening ->
                    Transition(s.copy(phase = Phase.Capturing(s.worktree, purpose = CapturePurpose.Worktree(p))))
                else -> Transition(s)
            }
            is LoopInput.SpeechEnd -> if (p is Phase.Capturing) onSpeechEnd(s, p, i.utterance) else Transition(s)
            is LoopInput.Transcribed ->
                if (p is Phase.Transcribing && p.worktree == i.worktree) onTranscribed(s, p, i.text) else Transition(s)
            is LoopInput.TranscribeFailed ->
                if (p is Phase.Transcribing && p.worktree == i.worktree && !p.waitingForNetwork && !p.retryPending) {
                    onTranscribeFailed(s, p, i.failure)
                } else Transition(s)
            is LoopInput.SendOk -> sending(p, i.session)?.let { sent(s, it, maybeSent = false) } ?: Transition(s)
            is LoopInput.SendRefused -> sending(p, i.session)?.let { onRefused(s, it, i.refusal) } ?: Transition(s)
            is LoopInput.SendFailed -> sending(p, i.session)?.let {
                if (i.maybeSent) sent(s, it, maybeSent = true)
                else held(s, it.text, HeldReason.NotSent, Lines.COULDNT_SEND, it.voice, it.confirmTap)
            } ?: Transition(s)
            is LoopInput.PlaybackDone -> playbackOver(s, i.eventId)
            is LoopInput.PlaybackFailed -> playbackOver(s, i.eventId)
            LoopInput.FocusLost -> when {
                flowOf(p) != null -> flowOf(p)!!.let { back -> applyFlow(s, back, flowRules.micLost(back.flow, null)) }
                pausesOnFocusLoss(p) -> enterPaused(s, PauseReason.FocusLost)
                else -> Transition(s)
            }
            LoopInput.RestartTap ->
                if (p is Phase.Stopped && !p.restarting) {
                    Transition(s.copy(phase = Phase.Stopped(restarting = true)), listOf(Effect.SendRestart(s.worktree)))
                } else Transition(s)
            is LoopInput.KeyTap ->
                if (p is Phase.NeedsYou) Transition(s, listOf(Effect.SendKey(s.worktree, i.key, p.screenToken)))
                else Transition(s)
            is LoopInput.KeyRefused ->
                if (p is Phase.NeedsYou && i.session == s.worktree) onKeyRefused(s, p, i.refusal) else Transition(s)
            LoopInput.NewWorktreeTap -> if (acceptsNewWorktree(p)) enterFlow(s) else Transition(s)
            is LoopInput.ConfirmTap ->
                if (p is Phase.AwaitingTap) send(s, if (i.yes) Lines.CONFIRM_YES else Lines.CONFIRM_NO, null, confirmTap = true)
                else Transition(s)
            LoopInput.SendNowTap ->
                if (p is Phase.Held && p.text != null) send(s, p.text, null, p.voice, p.confirmTap) else Transition(s)
            LoopInput.DiscardTap -> if (p is Phase.Held) resumeLoop(s) else Transition(s)
            is LoopInput.MicFailed -> when {
                !micOn(p) -> Transition(s)
                // The flow (request, typed text, card) stays with the mic off and shows the failure until a tap.
                flowOf(p) != null -> flowOf(p)!!.let { back ->
                    val line = if (i.failure == MicFailure.Silenced) Lines.MIC_SILENCED else Lines.MIC_UNAVAILABLE
                    val t = applyFlow(s, back, flowRules.micLost(back.flow, line))
                    Transition(t.state.copy(micProblem = i.failure), t.effects)
                }
                else -> onMicFailed(s, i.failure)
            }
            is LoopInput.TypedSend ->
                if (i.text.isNotBlank() && acceptsTyped(p)) send(s, i.text, null, voice = false) else Transition(s)
            is LoopInput.NewWorktreeTyped, is LoopInput.Planned, is LoopInput.PlanRefused, LoopInput.PlanFailed,
            is LoopInput.CandidateTap, is LoopInput.BaseTap, is LoopInput.BaseTyped, LoopInput.CreateTap,
            LoopInput.OfflineCreateTap, LoopInput.CancelTap, is LoopInput.Created, is LoopInput.CreateRefused,
            LoopInput.CreateMaybeDone ->
                if (p is Phase.NewWorktree) applyFlow(s, p, flowRules.step(p.flow, i, s.muted)) else Transition(s)
        }
    }

    private fun onTap(s: LoopState): Transition = when (val p = s.phase) {
        Phase.Idle, is Phase.Paused, is Phase.Error -> listOf(Earcon(EarconKind.Listen)).then(resumeLoop(s))
        Phase.Listening, is Phase.AwaitingAnswer -> enterPaused(s, PauseReason.User)
        is Phase.AwaitingTap -> Transition(s, listOf(Say(p.question.text)))
        is Phase.Capturing -> Transition(s, listOf(Effect.FinishCapture))
        is Phase.Speaking -> finishPlayback(s, p, config.interruptTailMs)
        is Phase.NewWorktree -> applyFlow(s, p, flowRules.step(p.flow, LoopInput.Tap, s.muted))
        else -> Transition(s)
    }

    private fun onMicFailed(s: LoopState, f: MicFailure): Transition = when (f) {
        MicFailure.Silenced -> listOf(Earcon(EarconKind.Error), Say(Lines.MIC_SILENCED))
            .then(enterPaused(s, PauseReason.MicSilenced))
        MicFailure.Unavailable -> listOf(Earcon(EarconKind.Error), Say(Lines.MIC_UNAVAILABLE))
            .then(enterPaused(s, PauseReason.MicUnavailable))
    }

    private fun onSpeechEnd(s: LoopState, p: Phase.Capturing, u: Utterance): Transition =
        if (u.pcm.isNotEmpty() && EnergyGate.passes(u, config.detector.frameSamples) && VoicedGate.passes(u.stats, config.detector)) {
            Transition(
                s.copy(phase = Phase.Transcribing(p.worktree, u, p.answering, purpose = p.purpose)),
                listOf(Effect.Upload(p.worktree, u)),
            )
        } else (p.purpose as? CapturePurpose.Worktree)?.let { flowMiss(s, it) } ?: reject(s, p.answering, null)

    private fun onTranscribed(s: LoopState, p: Phase.Transcribing, text: String): Transition {
        val forFlow = p.purpose as? CapturePurpose.Worktree
        if (forFlow != null) {
            if (p.worktree != s.worktree) return flowMiss(s, forFlow)
            val answerMode = forFlow.back.flow is WorktreeFlow.Card
            val verdict = TranscriptGuard.judge(text, p.utterance.stats.voicedMs, answerMode, config.expectLatin)
            val heard = text.takeIf { verdict == TranscriptVerdict.Accepted }
            return applyFlow(s, forFlow.back, flowRules.heard(forFlow.back.flow, heard, s.muted))
        }
        if (p.worktree != s.worktree) return listenAgain(s, null, 0) // G8: never sent to another worktree
        val verdict = TranscriptGuard.judge(text, p.utterance.stats.voicedMs, p.answering != null, config.expectLatin)
        return when {
            verdict is TranscriptVerdict.Rejected && verdict.reason == TranscriptRejection.Command ->
                reject(s, p.answering, Lines.NO_COMMANDS)
            verdict is TranscriptVerdict.Rejected -> reject(s, p.answering, null)
            agentBusy(s.agent) -> held(s, text, HeldReason.AgentBusy, Lines.BUSY_KEPT)
            else -> send(s, text, p.answering)
        }
    }

    /** For the flow's captures, an outcome that ends the upload (not a retry, not an error) is a miss of the flow. */
    private fun onTranscribeFailed(s: LoopState, p: Phase.Transcribing, f: TranscribeFailure): Transition {
        val normal = transcribeFailed(s, p, f)
        val forFlow = p.purpose as? CapturePurpose.Worktree ?: return normal
        val after = normal.state.phase
        return if (after is Phase.Transcribing || after is Phase.Error) normal else flowMiss(s, forFlow)
    }

    private fun transcribeFailed(s: LoopState, p: Phase.Transcribing, f: TranscribeFailure): Transition = when (f) {
        TranscribeFailure.Network -> when {
            p.retried -> listOf(Earcon(EarconKind.Error))
                .then(held(s, null, HeldReason.CouldNotTranscribe, Lines.COULDNT_TRANSCRIBE))
            s.online -> Transition(s.copy(phase = p.copy(retried = true)), listOf(Effect.Upload(p.worktree, p.utterance)))
            else -> Transition(s.copy(phase = p.copy(waitingForNetwork = true)))
        }
        is TranscribeFailure.Refused -> when (f.refusal.reason) {
            RefusalReason.TranscribeBusy ->
                if (p.retried) reject(s, p.answering, Lines.COULDNT_TRANSCRIBE)
                else Transition(
                    s.copy(phase = p.copy(retried = true, retryPending = true)),
                    listOf(StartTimer(Timer.TranscribeRetry, config.transcribeBusyRetryMs)),
                )
            RefusalReason.TranscribeRate -> {
                val seconds = f.refusal.retryAfterSeconds ?: 60
                listOf(Earcon(EarconKind.Error), Say(Lines.waitSeconds(seconds))).then(listenAgain(s, p.answering, 0))
            }
            RefusalReason.SpeechOff -> enterError(s, ErrorKind.SpeechOff, f.refusal.message)
            else -> reject(s, p.answering, Lines.COULDNT_TRANSCRIBE)
        }
    }

    private fun networkBack(s: LoopState): Transition {
        val p = s.phase
        return if (p is Phase.Transcribing && p.waitingForNetwork) {
            Transition(
                s.copy(phase = p.copy(waitingForNetwork = false, retried = true)),
                listOf(Effect.Upload(p.worktree, p.utterance)),
            )
        } else Transition(s)
    }

    private fun sending(p: Phase, session: String): Phase.Sending? =
        (p as? Phase.Sending)?.takeIf { it.worktree == session && !it.retryPending }

    private fun sent(s: LoopState, p: Phase.Sending, maybeSent: Boolean): Transition = Transition(
        s.copy(phase = Phase.AgentWorking(Sent(p.text, maybeSent, p.voice, p.confirmTap)), questions = s.questions - p.worktree),
    )

    private fun onRefused(s: LoopState, p: Phase.Sending, r: Refusal): Transition = when (r.reason) {
        RefusalReason.NotSettled -> held(s, p.text, HeldReason.AgentBusy, Lines.BUSY_KEPT, p.voice, p.confirmTap)
        RefusalReason.DraftPresent -> held(s, p.text, HeldReason.DraftAtMac, Lines.DRAFT_AT_MAC, p.voice, p.confirmTap)
        RefusalReason.RepeatWindow, RefusalReason.ActionRunning ->
            if (p.retried) held(s, p.text, HeldReason.RetryFailed, Lines.COULDNT_SEND, p.voice, p.confirmTap)
            else Transition(
                s.copy(phase = p.copy(retried = true, retryPending = true)),
                listOf(StartTimer(Timer.SendRetry, config.sendRetryMs)),
            )
        RefusalReason.HerdrTimeoutMaybeSent -> sent(s, p, maybeSent = true)
        RefusalReason.PaneChanged -> enterError(s, ErrorKind.PaneChanged, r.message)
        RefusalReason.NoOrchestrator -> enterError(s, ErrorKind.NoOrchestrator, r.message)
        RefusalReason.Stopped -> enterStopped(s)
        RefusalReason.VoiceNoise -> reject(s, p.answering, null)
        RefusalReason.VoiceCommand -> reject(s, p.answering, Lines.NO_COMMANDS)
        else -> held(s, p.text, HeldReason.Refused(r.message), Lines.COULDNT_SEND, p.voice, p.confirmTap)
    }

    private fun onKeyRefused(s: LoopState, p: Phase.NeedsYou, r: Refusal): Transition = when (r.reason) {
        RefusalReason.ScreenChanged -> Transition(
            s.copy(phase = p.copy(screenToken = r.screenToken, output = r.output ?: p.output)),
            listOf(Say(Lines.SCREEN_CHANGED), Effect.ShowSheet),
        )
        RefusalReason.NotBlocked ->
            p.handoff?.let { backToHandoff(s, it) } ?: Transition(s.copy(phase = Phase.AgentWorking(p.sent)))
        RefusalReason.Stopped -> enterStopped(s)
        else -> Transition(s, listOf(Earcon(EarconKind.Error)))
    }

    private fun onTimer(s: LoopState, timer: Timer): Transition = when (val p = s.phase) {
        Phase.Listening -> enterPaused(s, PauseReason.NoSpeech)
        is Phase.AwaitingAnswer ->
            if (!p.reminded) {
                listOf(Say(Lines.WAITING_FOR_ANSWER), Say(p.question.text))
                    .then(enterAwaitingAnswer(s, p.question, reminded = true, delayMs = 0))
            } else enterPaused(s, PauseReason.Question).let {
                Transition(it.state, it.effects + Effect.Notify(Lines.askedQuestion(s.worktree)))
            }
        is Phase.AwaitingTap ->
            Transition(s.copy(phase = p.copy(reminded = true)), listOf(Say(Lines.LOOK_TO_CONFIRM), Effect.Vibrate))
        is Phase.NeedsYou ->
            Transition(s.copy(phase = p.copy(reminded = true)), listOf(Say(Lines.LOOK_TO_ANSWER), Effect.Vibrate))
        is Phase.Sending ->
            Transition(s.copy(phase = p.copy(retryPending = false)), listOf(Effect.Send(p.worktree, p.text, p.voice, p.confirmTap)))
        is Phase.Transcribing -> if (timer == Timer.PlanExpiry) {
            Transition(s.copy(phase = p.copy(purpose = expiredBack(p.purpose, s))))
        } else {
            Transition(s.copy(phase = p.copy(retryPending = false)), listOf(Effect.Upload(p.worktree, p.utterance)))
        }
        is Phase.Capturing -> Transition(s.copy(phase = p.copy(purpose = expiredBack(p.purpose, s))))
        is Phase.AgentWorking -> {
            val sent = p.sent
            if (sent == null) Transition(s.copy(phase = p.copy(settledTimer = false)))
            else if (sent.maybeSent) held(s, sent.text, HeldReason.MaybeNotSent, Lines.MAYBE_NOT_SENT, sent.voice, sent.confirmTap)
            else listOf(Say(Lines.DONE_CHECK_SCREEN)).then(enterListening(s, 0))
        }
        is Phase.Paused -> Transition(s.copy(phase = Phase.Idle), listOf(Effect.EndService))
        is Phase.NewWorktree -> applyFlow(s, p, flowRules.step(p.flow, LoopInput.TimerFired(timer), s.muted))
        else -> Transition(s) // no timer runs in the other phases (dispatch only passes running timers)
    }

    // ---- W6: the new-worktree flow -----------------------------------------------------------------------------------

    private fun enterFlow(s: LoopState): Transition {
        val first = flowRules.enter()
        val wasLooping = when (s.phase) {
            Phase.Listening, is Phase.AwaitingAnswer, is Phase.AgentWorking, is Phase.Held -> true
            else -> false
        }
        return Transition(keepQuestion(s).copy(phase = Phase.NewWorktree(first.flow, wasLooping), rejections = 0), first.effects)
    }

    /** The flow phase a capture belongs to: the flow itself while it listens, or the one a capture comes back to. */
    private fun flowOf(p: Phase): Phase.NewWorktree? = when (p) {
        is Phase.NewWorktree -> p.takeIf { it.flow.listening }
        is Phase.Capturing -> (p.purpose as? CapturePurpose.Worktree)?.back
        is Phase.Transcribing -> (p.purpose as? CapturePurpose.Worktree)?.back
        else -> null
    }

    /** The card a capture comes back to, marked expired (its expiry fired while the reply was being heard). */
    private fun expiredBack(purpose: CapturePurpose, s: LoopState): CapturePurpose {
        val back = (purpose as? CapturePurpose.Worktree)?.back ?: return purpose
        val step = flowRules.step(back.flow, LoopInput.TimerFired(Timer.PlanExpiry), s.muted) as? FlowStep.Stay ?: return purpose
        return CapturePurpose.Worktree(back.copy(flow = step.flow))
    }

    private fun flowMiss(s: LoopState, purpose: CapturePurpose.Worktree): Transition =
        applyFlow(s, purpose.back, flowRules.heard(purpose.back.flow, null, s.muted))

    /** What one flow rule means for the loop. [p] is the flow phase it ran for. */
    private fun applyFlow(s: LoopState, p: Phase.NewWorktree, step: FlowStep?): Transition = when (step) {
        null -> Transition(s)
        is FlowStep.Stay -> Transition(s.copy(phase = p.copy(flow = step.flow)), step.effects)
        is FlowStep.Leave -> step.effects.then(if (p.wasLooping) resumeLoop(s) else Transition(s.copy(phase = Phase.Idle)))
        is FlowStep.Created -> {
            val moved = keepQuestion(s).copy(worktree = step.answer.session, agent = AgentView.Unknown, rejections = 0)
            if (config.handoffFirstPrompt) {
                val handoff = WorktreeFlow.Handoff(step.answer.session, step.query, step.voice, step.answer)
                Transition(
                    moved.copy(phase = p.copy(flow = handoff)),
                    step.effects + Say(WorktreeLines.CREATED_SENDING) + StartTimer(Timer.HandoffLimit, config.handoffLimitMs),
                )
            } else {
                // The default: on the new worktree, idle. Nothing is sent to it and nothing is kept to send.
                Transition(moved.copy(phase = Phase.Idle), step.effects + Say(WorktreeLines.CREATED))
            }
        }
        // G8: the first prompt goes to the created session only, and only while it is the current worktree.
        is FlowStep.SendFirst -> if (s.worktree == step.session) send(s, step.query, null, step.voice) else Transition(s)
        is FlowStep.Blocked -> {
            val t = enterNeedsYou(s, null)
            val needs = t.state.phase as Phase.NeedsYou
            Transition(t.state.copy(phase = needs.copy(handoff = p.copy(flow = step.handoff))), t.effects)
        }
        is FlowStep.Hold -> Transition(
            s.copy(phase = Phase.Held(step.text, if (step.timedOut) HeldReason.NewWorktreeNotReady else HeldReason.NewWorktreeRequest, voice = step.voice)),
            step.effects,
        )
    }

    /** The dialog is gone: back to the hand-off, counting this view, with its time limit started again. */
    private fun backToHandoff(s: LoopState, handoff: Phase.NewWorktree): Transition {
        val flow = handoff.flow as? WorktreeFlow.Handoff ?: return Transition(s.copy(phase = handoff))
        val t = applyFlow(s, handoff, flowRules.onAgent(flow.copy(settledPolls = 0), s.agent))
        return if (t.state.phase is Phase.NewWorktree) {
            Transition(t.state, t.effects + StartTimer(Timer.HandoffLimit, config.handoffLimitMs))
        } else t
    }

    // ---- Views and worktree switches -------------------------------------------------------------------------------

    private fun onView(s: LoopState, v: SessionView): Transition {
        val ids = v.events.map { it.id }.toSet()
        val heard = s.heard.intersect(ids)
        val kept = s.queue.filter { it.id in ids }
        val known = kept.map { it.id }.toSet() + heard + listOfNotNull((s.phase as? Phase.Speaking)?.event?.id)
        val fresh = v.events.filter { it.id !in known }.distinctBy { it.id }
        val agent = if (v.name != s.worktree) s.agent else AgentView(
            state = v.state,
            status = v.orchestrator?.status,
            waiting = s.worktree in v.waiting,
            screenToken = v.screenToken,
            output = v.output,
        )
        val updated = s.copy(agent = agent, heard = heard, queue = kept + fresh)
        // Events first (stage 2 plays them); status rules apply once nothing is waiting to be spoken.
        return if (v.name != s.worktree || playable(updated) != null) Transition(updated) else statusRules(updated)
    }

    private fun statusRules(s: LoopState): Transition {
        val a = s.agent
        return when (val p = s.phase) {
            Phase.Listening -> when {
                a.stopped -> enterStopped(s)
                a.blocked -> enterNeedsYou(s, null)
                a.working -> Transition(s.copy(phase = Phase.AgentWorking(null)))
                else -> Transition(s)
            }
            is Phase.AwaitingAnswer, is Phase.AwaitingTap -> when {
                a.stopped -> enterStopped(s.copy(questions = s.questions - s.worktree))
                a.blocked -> enterNeedsYou(s, null)
                a.working -> Transition(s.copy(phase = Phase.AgentWorking(null), questions = s.questions - s.worktree))
                else -> Transition(s)
            }
            is Phase.AgentWorking -> agentWorkingRules(s, p)
            is Phase.NeedsYou -> when {
                a.stopped -> enterStopped(s)
                a.blocked -> Transition(s.copy(phase = p.copy(screenToken = a.screenToken, output = a.output)))
                p.handoff != null && (a.working || a.settled) -> backToHandoff(s, p.handoff)
                a.working || a.settled -> agentWorkingRules(s, Phase.AgentWorking(p.sent))
                else -> Transition(s)
            }
            is Phase.Held -> Transition(s.copy(phase = p.copy(agentFree = a.settled)))
            is Phase.NewWorktree -> {
                val flow = p.flow
                if (flow is WorktreeFlow.Handoff) applyFlow(s, p, flowRules.onAgent(flow, a)) else Transition(s)
            }
            is Phase.Stopped -> if (!a.stopped && a.state != null) Transition(s.copy(phase = Phase.Idle)) else Transition(s)
            else -> Transition(s)
        }
    }

    private fun agentWorkingRules(s: LoopState, p: Phase.AgentWorking): Transition {
        val a = s.agent
        return when {
            a.stopped -> enterStopped(s)
            a.blocked -> enterNeedsYou(s, p.sent)
            a.working || a.waiting ->
                Transition(s.copy(phase = Phase.AgentWorking(p.sent?.copy(maybeSent = false), settledTimer = false)))
            a.settled -> when {
                p.sent == null -> s.question?.let { resayQuestion(s, it) } ?: enterListening(s, 0)
                p.settledTimer -> Transition(s.copy(phase = p))
                else -> Transition(
                    s.copy(phase = p.copy(settledTimer = true)),
                    listOf(StartTimer(Timer.Settled, config.settledGraceMs)),
                )
            }
            else -> Transition(s.copy(phase = p))
        }
    }

    /** G8: the old worktree's utterance, prompt and held text are dropped; its open question is kept for later. */
    private fun onSwitch(s: LoopState, worktree: String): Transition {
        if (worktree == s.worktree) return Transition(s)
        val moved = keepQuestion(s).copy(worktree = worktree, agent = AgentView.Unknown, rejections = 0)
        return when (s.phase) {
            Phase.Idle, is Phase.Paused -> Transition(moved)
            else -> moved.question?.let { resayQuestion(moved, it) }
                ?: Transition(moved.copy(phase = Phase.AgentWorking(null)))
        }
    }

    // ---- Playback ---------------------------------------------------------------------------------------------------

    private fun playbackOver(s: LoopState, id: String): Transition {
        val p = s.phase
        return if (p is Phase.Speaking && p.event.id == id) finishPlayback(s, p, 0) else Transition(s)
    }

    /** The event is over (done, given up, interrupted or muted): acknowledge it, then where the loop goes next. */
    private fun finishPlayback(s: LoopState, p: Phase.Speaking, delayMs: Long): Transition {
        val e = p.event
        val heard = s.copy(heard = s.heard + e.id, queue = s.queue.filterNot { it.id == e.id })
        val next = when {
            e.session != s.worktree -> {
                val kept = if (e.ask == AskKind.None) heard else heard.copy(questions = heard.questions + (e.session to e))
                reenter(kept, p.resume, delayMs)
            }
            e.kind == EventKind.NeedsYou ->
                if (p.resume is Phase.NeedsYou) reenter(heard, p.resume, delayMs) else enterNeedsYou(heard, sentOf(p.resume))
            pendingQuestion(p.resume) != null -> keepPending(heard, pendingQuestion(p.resume)!!, e, delayMs)
            e.ask == AskKind.Answer ->
                listOf(Earcon(EarconKind.YourTurn)).then(enterAwaitingAnswer(heard, e, reminded = false, delayMs = delayMs))
            e.ask == AskKind.Confirm -> enterAwaitingTap(heard, e)
            else -> settle(heard.copy(questions = heard.questions - s.worktree), delayMs, null)
        }
        return listOf<Effect>(Effect.Ack(listOf(e.id))).then(next)
    }

    /**
     * An event of this worktree played while a question waited for its answer ([pending]). The question is never
     * dropped by it: a plain event is followed by the question again. A newer question replaces it (it is what the
     * agent waits on now), but if either is a tap-only confirmation the result stays tap-only, so a pending
     * confirmation never turns into an open mic.
     */
    private fun keepPending(s: LoopState, pending: Event, e: Event, delayMs: Long): Transition = when {
        e.ask == AskKind.None -> resayQuestion(s, pending)
        pending.ask == AskKind.Confirm || e.ask == AskKind.Confirm -> enterAwaitingTap(s, e.copy(ask = AskKind.Confirm))
        else -> listOf(Earcon(EarconKind.YourTurn)).then(enterAwaitingAnswer(s, e, reminded = false, delayMs = delayMs))
    }

    private fun pendingQuestion(p: Phase): Event? = (p as? Phase.AwaitingAnswer)?.question ?: (p as? Phase.AwaitingTap)?.question

    /** Back to the phase an event of another worktree interrupted. */
    private fun reenter(s: LoopState, phase: Phase, delayMs: Long): Transition = when (phase) {
        Phase.Listening -> settle(s, delayMs, null)
        is Phase.AgentWorking -> Transition(s.copy(phase = phase.copy(settledTimer = false)))
        is Phase.NeedsYou -> Transition(
            s.copy(phase = phase),
            if (phase.reminded) emptyList() else listOf(StartTimer(Timer.NeedsYouReminder, config.needsYouReminderMs)),
        )
        is Phase.AwaitingAnswer -> enterAwaitingAnswer(s, phase.question, phase.reminded, delayMs)
        is Phase.AwaitingTap -> Transition(
            s.copy(phase = phase),
            if (phase.reminded) emptyList() else listOf(StartTimer(Timer.ConfirmReminder, config.confirmReminderMs)),
        )
        else -> Transition(s.copy(phase = phase))
    }

    private fun playable(s: LoopState): Event? = when (s.phase) {
        Phase.Listening, is Phase.AgentWorking -> s.queue.firstOrNull()
        is Phase.AwaitingAnswer, is Phase.AwaitingTap, is Phase.NeedsYou -> s.queue.firstOrNull { it.session == s.worktree }
        else -> null
    }

    // ---- Stage 2: play queued events ---------------------------------------------------------------------------------

    private fun drain(t: Transition): Transition {
        var current = t
        repeat(t.state.queue.size) {
            val event = playable(current.state) ?: return current
            val s = current.state
            val resume = (s.phase as? Phase.AgentWorking)?.copy(settledTimer = false) ?: s.phase
            val speaking = Phase.Speaking(event, resume)
            current = if (!s.muted) {
                return Transition(
                    s.copy(phase = speaking, queue = s.queue.filterNot { it.id == event.id }),
                    current.effects + Effect.Play(event, withName = event.session != s.worktree),
                )
            } else {
                val over = finishPlayback(s.copy(phase = speaking), speaking, 0)
                Transition(over.state, current.effects + Earcon(EarconKind.Listen) + over.effects)
            }
        }
        return current
    }

    // ---- Entering phases ----------------------------------------------------------------------------------------------

    /** Where the loop goes when it may listen: by the last view (Listening only when the agent is settled). */
    private fun settle(s: LoopState, delayMs: Long, sent: Sent?): Transition {
        val a = s.agent
        return when {
            a.stopped -> enterStopped(s)
            a.blocked -> enterNeedsYou(s, sent)
            a.settled -> enterListening(s, delayMs)
            else -> Transition(s.copy(phase = Phase.AgentWorking(sent)))
        }
    }

    /** Start or resume the loop: a kept question is said again and answered; otherwise by the last view. */
    private fun resumeLoop(s: LoopState): Transition {
        val fresh = s.copy(rejections = 0)
        return fresh.question?.let { resayQuestion(fresh, it) } ?: settle(fresh, 0, null)
    }

    private fun resayQuestion(s: LoopState, q: Event): Transition =
        listOf(Say(q.text)).then(
            if (q.ask == AskKind.Answer) enterAwaitingAnswer(s, q, reminded = false, delayMs = 0) else enterAwaitingTap(s, q),
        )

    /** Back to listening after a rejection or a dropped utterance; answer mode survives. */
    private fun listenAgain(s: LoopState, answering: Event?, delayMs: Long): Transition =
        if (answering != null) enterAwaitingAnswer(s, answering, reminded = false, delayMs = delayMs) else settle(s, delayMs, null)

    private fun reject(s: LoopState, answering: Event?, line: String?): Transition {
        val count = s.rejections + 1
        if (count >= config.maxRejections) {
            return listOf(Earcon(EarconKind.Rejected), Say(Lines.TOO_MUCH_NOISE))
                .then(enterPaused(s.copy(rejections = 0), PauseReason.Noise))
        }
        val say = line ?: if (answering != null) Lines.DIDNT_CATCH_ANSWER else Lines.DIDNT_CATCH
        return listOf(Earcon(EarconKind.Rejected), Say(say)).then(listenAgain(s.copy(rejections = count), answering, 0))
    }

    /** [confirmTap] only for the Yes or No of AwaitingTap (and that text's retry or Send now). */
    private fun send(
        s: LoopState, text: String, answering: Event?, voice: Boolean = true, confirmTap: Boolean = false,
    ): Transition = Transition(
        s.copy(phase = Phase.Sending(s.worktree, text, answering, voice = voice, confirmTap = confirmTap), rejections = 0),
        listOf(Effect.Send(s.worktree, text, voice = voice, confirmTap = confirmTap), Earcon(EarconKind.Sent)),
    )

    private fun held(
        s: LoopState, text: String?, reason: HeldReason, line: String, voice: Boolean = true, confirmTap: Boolean = false,
    ): Transition = Transition(s.copy(phase = Phase.Held(text, reason, voice = voice, confirmTap = confirmTap)), listOf(Say(line)))

    private fun enterListening(s: LoopState, delayMs: Long): Transition = Transition(
        s.copy(phase = Phase.Listening),
        listOf(Effect.StartCapture(answerMode = false, delayMs = delayMs), StartTimer(Timer.NoSpeech, config.listenSilenceMs)),
    )

    private fun enterAwaitingAnswer(s: LoopState, q: Event, reminded: Boolean, delayMs: Long): Transition = Transition(
        s.copy(phase = Phase.AwaitingAnswer(q, reminded), questions = s.questions + (s.worktree to q)),
        listOf(Effect.StartCapture(answerMode = true, delayMs = delayMs), StartTimer(Timer.NoSpeech, config.answerSilenceMs)),
    )

    /** Tap-only: never a StartCapture here. */
    private fun enterAwaitingTap(s: LoopState, q: Event): Transition = Transition(
        s.copy(phase = Phase.AwaitingTap(q), questions = s.questions + (s.worktree to q)),
        listOf(Say(Lines.LOOK_TO_CONFIRM), Effect.Vibrate, StartTimer(Timer.ConfirmReminder, config.confirmReminderMs)),
    )

    private fun enterNeedsYou(s: LoopState, sent: Sent?): Transition = Transition(
        s.copy(phase = Phase.NeedsYou(s.agent.screenToken, s.agent.output, reminded = false, sent = sent)),
        listOf(
            Say(Lines.LOOK_TO_ANSWER), Effect.Vibrate, Effect.ShowSheet,
            StartTimer(Timer.NeedsYouReminder, config.needsYouReminderMs),
        ),
    )

    private fun enterPaused(s: LoopState, why: PauseReason): Transition = Transition(
        keepQuestion(s).copy(phase = Phase.Paused(why)),
        listOf(Earcon(EarconKind.Paused), StartTimer(Timer.PauseLimit, config.pauseLimitMs)),
    )

    private fun enterStopped(s: LoopState): Transition =
        Transition(s.copy(phase = Phase.Stopped()), listOf(Say(Lines.stopped(s.worktree)), Effect.ShowSheet))

    private fun enterError(s: LoopState, kind: ErrorKind, message: String): Transition = Transition(
        s.copy(phase = Phase.Error(kind, message)),
        listOfNotNull(Earcon(EarconKind.Error), message.takeIf { it.isNotBlank() }?.let { Say(it) }),
    )

    // ---- Stage 3: rules for every transition ---------------------------------------------------------------------------

    private fun finish(old: LoopState, input: LoopInput, t: Transition): Transition {
        var state = t.state
        var effects = t.effects
        val before = old.phase
        val after = state.phase

        if (before is Phase.Speaking && !(after is Phase.Speaking && after.event.id == before.event.id)) {
            val over = (input as? LoopInput.PlaybackDone)?.eventId ?: (input as? LoopInput.PlaybackFailed)?.eventId
            if (over != before.event.id) {
                val id = before.event.id
                val acked = effects.any { it is Effect.Ack && id in it.ids }
                if (!acked) state = state.copy(heard = state.heard + id, queue = state.queue.filterNot { it.id == id })
                effects = listOf(Effect.StopPlayback) + (if (acked) emptyList() else listOf(Effect.Ack(listOf(id)))) + effects
            }
        }
        if (state.muted) effects = effects.filterNot { it is Say }

        // Timers: those of the old phase that the new one does not keep are cancelled first; one started by a phase
        // passed through within this step (a muted event played in between) is cancelled at the end.
        val fired = (input as? LoopInput.TimerFired)?.timer
        val runningBefore = runningTimers(before) - setOfNotNull(fired)
        val runningAtEnd = effects.fold(runningBefore) { set, e ->
            when (e) {
                is StartTimer -> set + e.timer
                is Effect.CancelTimer -> set - e.timer
                else -> set
            }
        }
        val startedHere = effects.filterIsInstance<StartTimer>().map { it.timer }.toSet()
        val cancelFirst = (runningBefore - startedHere - runningTimers(after)).map { Effect.CancelTimer(it) }
        val cancelLast = (runningAtEnd - runningTimers(after) - (runningBefore - startedHere)).map { Effect.CancelTimer(it) }

        val (withMic, micLeftOn) = micRule(micOn(before), effects)
        effects = when {
            !micLeftOn || micOn(after) -> cancelFirst + withMic
            withMic.none { it is Effect.StartCapture } -> listOf(Effect.StopCapture) + cancelFirst + withMic
            else -> cancelFirst + withMic + Effect.StopCapture
        } + cancelLast
        effects = compact(effects)

        // The flow's mic message goes when the mic opens again or the flow is no longer on a Request or Card.
        if (state.micProblem != null && (effects.any { it is Effect.StartCapture } || !quietFlow(after))) {
            state = state.copy(micProblem = null)
        }

        val poll = pollInterval(state)
        if (poll != pollInterval(old)) effects = effects + Effect.SetPoll(poll)
        return Transition(state, effects)
    }

    /**
     * Follows the mic through [effects] from [micStartsOn]: a StopCapture goes in before any sound (Say, Play,
     * Earcon) made with the mic on, and before a StartCapture that restarts it. Returns the effects and whether the
     * mic is on at the end.
     */
    private fun micRule(micStartsOn: Boolean, effects: List<Effect>): Pair<List<Effect>, Boolean> {
        var mic = micStartsOn
        val out = mutableListOf<Effect>()
        for (e in effects) {
            val sound = e is Say || e is Effect.Play || e is Earcon
            if (mic && (sound || e is Effect.StartCapture)) { out += Effect.StopCapture; mic = false }
            if (e is Effect.StartCapture) mic = true
            if (e is Effect.StopCapture) mic = false
            out += e
        }
        return out to mic
    }

    /**
     * Drops what a phase passed through within one step started and the step then undid: a StartCapture whose next
     * mic effect is a StopCapture (both go: the mic never opens), and a StartTimer cancelled later in the same list
     * (the start goes, the cancel stays, so a timer that ran before is still cancelled).
     */
    private fun compact(effects: List<Effect>): List<Effect> {
        val drop = mutableSetOf<Int>()
        effects.forEachIndexed { i, e ->
            if (e is Effect.StartCapture) {
                val next = (i + 1 until effects.size).firstOrNull { effects[it].isMicEffect() }
                if (next != null && effects[next] == Effect.StopCapture) { drop += i; drop += next }
            }
            if (e is StartTimer && (i + 1 until effects.size).any { effects[it] == Effect.CancelTimer(e.timer) }) drop += i
        }
        return effects.filterIndexed { i, _ -> i !in drop }
    }

    private fun Effect.isMicEffect() =
        this is Effect.StartCapture || this == Effect.StopCapture || this == Effect.FinishCapture

    // ---- Small pure helpers ------------------------------------------------------------------------------------------

    private fun micOn(p: Phase) = p == Phase.Listening || p is Phase.AwaitingAnswer || p is Phase.Capturing ||
        (p is Phase.NewWorktree && p.flow.listening)

    /** A Request or Card of the flow with the mic off: where a mic failure stays shown. */
    private fun quietFlow(p: Phase) =
        p is Phase.NewWorktree && (p.flow is WorktreeFlow.Request || p.flow is WorktreeFlow.Card) && !p.flow.listening

    /** The timers that run while in a phase. */
    private fun runningTimers(p: Phase): Set<Timer> = when (p) {
        Phase.Listening, is Phase.AwaitingAnswer -> setOf(Timer.NoSpeech)
        is Phase.AwaitingTap -> if (p.reminded) emptySet() else setOf(Timer.ConfirmReminder)
        is Phase.NeedsYou -> if (p.reminded) emptySet() else setOf(Timer.NeedsYouReminder)
        is Phase.Transcribing -> (if (p.retryPending) setOf(Timer.TranscribeRetry) else emptySet()) + backTimers(p.purpose)
        is Phase.Capturing -> backTimers(p.purpose)
        is Phase.Sending -> if (p.retryPending) setOf(Timer.SendRetry) else emptySet()
        is Phase.AgentWorking -> if (p.settledTimer) setOf(Timer.Settled) else emptySet()
        is Phase.Paused -> setOf(Timer.PauseLimit)
        is Phase.NewWorktree -> flowRules.runningTimers(p.flow)
        else -> emptySet()
    }

    /** The flow's timers that keep running while one of its captures is heard (the card's expiry), never NoSpeech. */
    private fun backTimers(purpose: CapturePurpose): Set<Timer> =
        (purpose as? CapturePurpose.Worktree)?.let { flowRules.runningTimers(it.back.flow) - Timer.NoSpeech } ?: emptySet()

    /** "New worktree" is taken between turns only, never mid-turn or over a dialog or a confirmation. */
    private fun acceptsNewWorktree(p: Phase) = when (p) {
        Phase.Idle, Phase.Listening, is Phase.AwaitingAnswer, is Phase.Paused, is Phase.Held, is Phase.Error,
        is Phase.Stopped, is Phase.AgentWorking -> true
        else -> false
    }

    /** A typed prompt goes out only between turns, never over a capture, an upload, a send or the agent's turn. */
    private fun acceptsTyped(p: Phase) = when (p) {
        Phase.Idle, Phase.Listening, is Phase.AwaitingAnswer, is Phase.AwaitingTap, is Phase.Paused, is Phase.Held,
        is Phase.Error -> true
        else -> false
    }

    private fun pausesOnFocusLoss(p: Phase) = when (p) {
        Phase.Listening, is Phase.AwaitingAnswer, is Phase.AwaitingTap, is Phase.Capturing, is Phase.Transcribing,
        is Phase.Sending, is Phase.AgentWorking, is Phase.Speaking, is Phase.NeedsYou -> true
        else -> false
    }

    /** Only a view that positively shows the agent busy holds a transcript; the server re-checks before typing. */
    private fun agentBusy(a: AgentView) = a.working || a.blocked || a.stopped

    private fun sentOf(p: Phase): Sent? = (p as? Phase.AgentWorking)?.sent ?: (p as? Phase.NeedsYou)?.sent

    /** The current worktree's open question, from the phase, saved into [LoopState.questions]. */
    private fun keepQuestion(s: LoopState): LoopState {
        val q = when (val p = s.phase) {
            is Phase.AwaitingAnswer -> p.question
            is Phase.AwaitingTap -> p.question
            is Phase.Capturing -> p.answering
            is Phase.Transcribing -> p.answering
            else -> null
        }
        return if (q == null) s else s.copy(questions = s.questions + (s.worktree to q))
    }

    /** These effects, then [next]'s, ending in [next]'s state. */
    private fun List<Effect>.then(next: Transition): Transition = Transition(next.state, this + next.effects)
}
