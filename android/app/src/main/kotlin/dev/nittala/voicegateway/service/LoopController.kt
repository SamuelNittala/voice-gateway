package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.audio.AudioCapture
import dev.nittala.voicegateway.audio.AudioFocusGate
import dev.nittala.voicegateway.audio.CaptureEvent
import dev.nittala.voicegateway.audio.FocusEvent
import dev.nittala.voicegateway.audio.FocusResult
import dev.nittala.voicegateway.core.api.ApiResult
import dev.nittala.voicegateway.core.api.GatewayApi
import dev.nittala.voicegateway.core.api.Refusal
import dev.nittala.voicegateway.core.api.RefusalReason
import dev.nittala.voicegateway.core.loop.Effect
import dev.nittala.voicegateway.core.loop.LoopConfig
import dev.nittala.voicegateway.core.loop.LoopInput
import dev.nittala.voicegateway.core.loop.LoopState
import dev.nittala.voicegateway.core.loop.MicFailure
import dev.nittala.voicegateway.core.loop.Phase
import dev.nittala.voicegateway.core.loop.Timer
import dev.nittala.voicegateway.core.loop.TranscribeFailure
import dev.nittala.voicegateway.core.loop.VoiceLoop
import dev.nittala.voicegateway.core.loop.WORKING_CUE_MS
import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.model.PlanAnswer
import dev.nittala.voicegateway.core.model.SessionView
import dev.nittala.voicegateway.core.model.SessionsView
import dev.nittala.voicegateway.core.speech.BudgetDecision
import dev.nittala.voicegateway.core.speech.DetectorConfig
import dev.nittala.voicegateway.core.speech.EndReason
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.speech.EngineChain
import dev.nittala.voicegateway.core.speech.RateBudget
import dev.nittala.voicegateway.core.speech.TranscriptGuard
import dev.nittala.voicegateway.core.speech.Utterance
import dev.nittala.voicegateway.core.speech.UtteranceStats
import dev.nittala.voicegateway.core.speech.Wav
import dev.nittala.voicegateway.core.ui.UiSettings
import dev.nittala.voicegateway.core.ui.VoiceUiState
import dev.nittala.voicegateway.core.ui.voiceUiState
import dev.nittala.voicegateway.core.worktree.WorktreeLines
import dev.nittala.voicegateway.playback.PlaybackEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** What the UI may change about the loop besides inputs. */
interface LoopControls : LoopInputs {
    /** The voice to try first (null: the gateway's default chain). */
    fun setSpeaker(engine: Engine?)

    /** The app became visible or hidden (polling of the sessions list, the loop's AppVisible). */
    fun setVisible(visible: Boolean)
}

/** The app's loop numbers: [LoopConfig]'s, with the working cue on ([WORKING_CUE_MS]). */
val APP_LOOP_CONFIG = LoopConfig(workingCueMs = WORKING_CUE_MS)

/**
 * Pure: the detector settings the controller starts capture with. The detector's own no-speech timeout is switched
 * off (set to [Int.MAX_VALUE]): the loop's NoSpeech timer (45 s listening, 20 s awaiting an answer) decides. The end
 * of speech is the user's setting.
 */
fun captureConfig(endOfSpeechMs: Int): DetectorConfig =
    DetectorConfig(endSilenceMs = endOfSpeechMs, noSpeechTimeoutMs = Int.MAX_VALUE)

/**
 * Spec (A10, plan section 7): the impure shell around the pure [VoiceLoop.step].
 *
 * Inputs: [send] (taps, headset, typed text, worktree changes), capture events, listening-focus events, playback
 * results, API answers, the [Poller]'s views and 401s, connectivity changes and fired timers all become [LoopInput]s,
 * processed one at a time in arrival order on [scope]. For each, the loop's new state is published on [state] and
 * its effects run in list order:
 * - `Say` (phone voice) and `Earcon` are awaited, so a Say finishes before the next effect (the mic is never opened
 *   over the phone's own voice); `StartCapture` waits its `delayMs` first.
 * - `StartCapture`: take the listening focus (refused -> FocusLost), then start capture with [captureConfig]. The
 *   capture's own NoSpeechPause is ignored. `StopCapture`: stop capture and RELEASE the listening focus. `Play` also
 *   releases the listening focus before playing, so the playback focus request never reads as a loss.
 * - `Play` runs in the background (a tap can interrupt it): Finished -> PlaybackDone, a terminal Failed ->
 *   PlaybackFailed, an Interrupted the controller did not ask for (focus taken by a call) -> FocusLost.
 *   `StopPlayback` stops it; its Interrupted is then expected and dropped.
 * - `Upload`, `Send`, `Ack`, `SendKey`, `SendStop`, `SendRestart` call the gateway in the background and feed the
 *   answer back (Transcribed / TranscribeFailed, SendOk / SendRefused / SendFailed, KeyRefused). A 401 anywhere is
 *   Unauthorized. A prompt is never retried here: no answer is SendFailed(maybeSent = true), a request that failed
 *   before it went out is SendFailed(maybeSent = false), a 2xx it cannot read counts as maybe sent.
 *   Uploads respect the app's RateBudget (10 a minute, one at a time, Retry-After honoured).
 * - `Ack` is the only acknowledgement (`POST /api/announced`), so an event is acknowledged only after it was played,
 *   interrupted or given up. A failed ack is retried after the next good poll.
 * - W6: `PlanWorktree` / `ReplanBase` call the gateway's plan and feed back Planned / PlanRefused / PlanFailed;
 *   `CreateWorktree` calls create once and feeds back Created / CreateRefused / CreateMaybeDone (no answer is never
 *   retried); a 401 is Unauthorized. Their debug lines are codes and counts only ([worktreeDebugLine]).
 * - `StartTimer` / `CancelTimer`: virtual-time friendly timers; a fired timer that was cancelled or restarted since
 *   is dropped. `SetPoll` sets the poller. `Notify` updates [notice]. `Vibrate` -> [Haptics]. `EndService` ->
 *   [ServiceControl.stop]. `ShowSheet` needs nothing: the screen opens the sheet from the state.
 * - The service is started ([ServiceControl.start]) when the loop leaves Idle or LoggedOut; [running] is true while it
 *   is in any other phase (the service holds its wake lock only then).
 * - Microphone silenced or broken -> MicFailed; network lost/back -> NetworkLost/NetworkBack.
 * - Nothing is logged, except through [debug] (DEBUG builds only; null in release): one line per capture result and
 *   per transcribe answer, numbers and reason codes only ([captureDebugLine], [transcriptDebugLine]), also published
 *   on [debugLine] for the screen. Never transcript text, prompts, passcodes, cookies or event text.
 */
class LoopController(
    initial: LoopState,
    private val links: GatewayLinks,
    private val capture: AudioCapture,
    private val listenFocus: AudioFocusGate,
    private val voice: PhoneVoice,
    private val earcons: Earcons,
    private val haptics: Haptics,
    private val service: ServiceControl,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val loop: VoiceLoop = VoiceLoop(),
    private val endOfSpeechMs: () -> Int = { DetectorConfig().endSilenceMs },
    private val debug: ((String) -> Unit)? = null,
) : LoopControls {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<LoopState> = _state

    private val _ui = MutableStateFlow(voiceUiState(initial))
    val ui: StateFlow<VoiceUiState> = _ui

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    private val _running = MutableStateFlow(isRunning(initial.phase))
    val running: StateFlow<Boolean> = _running

    private val _debugLine = MutableStateFlow<String?>(null)

    /**
     * The last result line, then the last listening-progress line under it, so progress never hides why the last
     * utterance was refused (DEBUG builds only: always null without [debug]).
     */
    val debugLine: StateFlow<String?> = _debugLine
    private var lastResultLine: String? = null
    private var lastProgressLine: String? = null

    /** Inputs and fired timers, in arrival order. A timer carries its start number so a stale fire is dropped. */
    private sealed interface Message {
        data class Input(val input: LoopInput) : Message
        data class Fired(val timer: Timer, val generation: Long) : Message
    }

    private val messages = Channel<Message>(Channel.UNLIMITED)
    private val poller = Poller({ links.current()?.api }, scope, PollListener())

    private var sessions: SessionsView? = null
    private var lastView: SessionView? = null
    private var micLevel = 0f
    private val _speaker = MutableStateFlow<Engine?>(null)

    /** The voice chosen in Settings (null: the gateway's default chain). */
    val speakerChoice: StateFlow<Engine?> = _speaker
    private val speaker: Engine? get() = _speaker.value
    private var engineNote = ""
    private var budget = RateBudget()
    private val timers = mutableMapOf<Timer, Pair<Long, Job>>()
    private var timerGeneration = 0L
    private var playJob: Job? = null
    private var stopAsked = false
    private val pendingAcks = linkedSetOf<String>()

    /** Starts listening to capture, focus, network and the poller. Call once. */
    fun start() {
        scope.launch { for (m in messages) process(m) }
        scope.launch { capture.events.collect(::onCapture) }
        scope.launch {
            listenFocus.events.collect { if (it is FocusEvent.Lost) send(LoopInput.FocusLost) }
        }
        scope.launch {
            network.online.distinctUntilChanged().drop(1).collect {
                send(if (it) LoopInput.NetworkBack else LoopInput.NetworkLost)
            }
        }
        val s = _state.value
        poller.setWorktree(s.worktree)
        poller.setInterval(loop.pollInterval(s))
        poller.setSessionsWanted(s.visible && s.phase != Phase.LoggedOut)
    }

    override fun send(input: LoopInput) {
        messages.trySend(Message.Input(input))
    }

    override fun setSpeaker(engine: Engine?) {
        _speaker.value = engine
        publishUi()
    }

    override fun setVisible(visible: Boolean) = send(LoopInput.AppVisible(visible))

    // ---- The loop -----------------------------------------------------------------------------------------------

    private suspend fun process(m: Message) {
        val input = when (m) {
            is Message.Input -> m.input
            is Message.Fired -> {
                if (timers[m.timer]?.first != m.generation) return
                timers.remove(m.timer)
                LoopInput.TimerFired(m.timer)
            }
        }
        if (input is LoopInput.MuteChanged) {
            if (input.muted) stopAsked = true // The loop stops the audio itself; that stop is not a focus loss.
            links.current()?.speaker?.muted = input.muted
        }
        val before = _state.value
        val t = loop.step(before, input)
        _state.value = t.state
        afterStep(before, t.state)
        for (effect in t.effects) run(effect)
    }

    private fun afterStep(before: LoopState, after: LoopState) {
        if (before.worktree != after.worktree) {
            lastView = null
            // The new interval first, so the step's SetPoll (run after its spoken lines) does not poll a second time.
            poller.setInterval(loop.pollInterval(after))
            poller.setWorktree(after.worktree)
        }
        if (before.phase::class != after.phase::class) _notice.value = null
        val wasRunning = isRunning(before.phase)
        val nowRunning = isRunning(after.phase)
        if (!wasRunning && nowRunning) service.start()
        _running.value = nowRunning
        poller.setSessionsWanted(after.visible && after.phase != Phase.LoggedOut)
        if (after.phase == Phase.LoggedOut) {
            poller.setInterval(null)
            stopEverything()
        }
        publishUi()
    }

    private fun publishUi() {
        _ui.value = voiceUiState(
            _state.value, sessions, lastView, micLevel,
            UiSettings(speaker = speaker, engineNote = engineNote, endOfSpeechMs = endOfSpeechMs()),
        )
    }

    /** Logged out: nothing may keep running (mic, audio, timers). */
    private fun stopEverything() {
        capture.stop()
        listenFocus.release()
        stopAsked = true
        links.current()?.speaker?.stop()
        playJob?.cancel()
        timers.values.forEach { it.second.cancel() }
        timers.clear()
    }

    private suspend fun run(effect: Effect) {
        when (effect) {
            is Effect.StartCapture -> startCapture(effect)
            Effect.StopCapture -> {
                capture.stop()
                listenFocus.release()
                micLevel = 0f
            }
            Effect.FinishCapture -> capture.finishUtterance()
            is Effect.Upload -> scope.launch { upload(effect.worktree, effect.utterance) }
            is Effect.Send -> scope.launch { prompt(effect) }
            is Effect.Play -> play(effect.event, effect.withName)
            Effect.StopPlayback -> {
                stopAsked = true
                links.current()?.speaker?.stop()
                playJob?.cancel()
                playJob = null
            }
            is Effect.Ack -> scope.launch { acknowledge(effect.ids) }
            is Effect.Earcon -> earcons.play(effect.kind)
            is Effect.Say -> voice.say(effect.text)
            Effect.Vibrate -> haptics.vibrate()
            is Effect.Notify -> _notice.value = effect.text
            is Effect.SetPoll -> poller.setInterval(effect.intervalMs)
            Effect.ShowSheet -> Unit
            is Effect.SendKey -> scope.launch { key(effect) }
            is Effect.SendStop -> scope.launch { simple { it.stop(effect.session) } }
            is Effect.SendRestart -> scope.launch { simple { it.restart(effect.session) } }
            is Effect.StartTimer -> startTimer(effect.timer, effect.ms)
            is Effect.CancelTimer -> timers.remove(effect.timer)?.second?.cancel()
            Effect.EndService -> service.stop()
            is Effect.PlanWorktree -> scope.launch { plan { it.plan(effect.query, effect.repoId) } }
            is Effect.ReplanBase -> scope.launch { plan { it.replan(effect.planId, effect.base) } }
            is Effect.CreateWorktree -> scope.launch { create(effect.planId) }
        }
    }

    private suspend fun startCapture(effect: Effect.StartCapture) {
        if (effect.delayMs > 0) delay(effect.delayMs)
        if (listenFocus.acquire() == FocusResult.Denied) {
            send(LoopInput.FocusLost)
            return
        }
        capture.start(captureConfig(endOfSpeechMs()))
    }

    private fun startTimer(timer: Timer, ms: Long) {
        timers.remove(timer)?.second?.cancel()
        val generation = ++timerGeneration
        val job = scope.launch {
            delay(ms)
            messages.trySend(Message.Fired(timer, generation))
        }
        timers[timer] = generation to job
    }

    // ---- Capture -------------------------------------------------------------------------------------------------

    private fun onCapture(event: CaptureEvent) {
        if (debug != null) debugCapture(event)
        when (event) {
            is CaptureEvent.SpeechStarted -> {
                micLevel = event.level
                send(LoopInput.SpeechStart)
            }
            is CaptureEvent.UtteranceReady -> send(LoopInput.SpeechEnd(event.utterance))
            // Failed G1/G2 on the phone: an empty utterance, which the loop rejects like any gate failure.
            is CaptureEvent.Rejected -> send(LoopInput.SpeechEnd(REJECTED))
            CaptureEvent.NoSpeechPause -> Unit // The loop's own NoSpeech timer decides (carry-forward rule).
            is CaptureEvent.ListenProgress -> Unit // Diagnostics only.
            CaptureEvent.MicSilenced -> send(LoopInput.MicFailed(MicFailure.Silenced))
            is CaptureEvent.Failed -> send(LoopInput.MicFailed(MicFailure.Unavailable))
        }
    }

    private fun debugCapture(event: CaptureEvent) {
        val line = when (event) {
            is CaptureEvent.SpeechStarted -> "cap SpeechStarted"
            is CaptureEvent.UtteranceReady -> event.stats?.let { captureDebugLine("Ready", it) } ?: "cap Ready"
            is CaptureEvent.Rejected -> event.stats?.let { captureDebugLine(event.reason.name, it) } ?: "cap ${event.reason.name}"
            is CaptureEvent.ListenProgress -> captureDebugLine("Listening", event.stats)
            CaptureEvent.NoSpeechPause -> "cap NoSpeechPause"
            CaptureEvent.MicSilenced -> "cap MicSilenced"
            is CaptureEvent.Failed -> "cap Failed ${event.reason.name}"
        }
        emitDebug(line, progress = event is CaptureEvent.ListenProgress)
    }

    private fun emitDebug(line: String, progress: Boolean = false) {
        val sink = debug ?: return
        sink(line)
        if (progress) lastProgressLine = line else {
            lastResultLine = line
            lastProgressLine = null
        }
        _debugLine.value = listOfNotNull(lastResultLine, lastProgressLine).joinToString("\n")
    }

    // ---- Playback ------------------------------------------------------------------------------------------------

    private fun play(event: Event, withName: Boolean) {
        listenFocus.release() // Before the playback focus request, which would otherwise read as a loss.
        val link = links.current() ?: return send(LoopInput.PlaybackFailed(event.id))
        val chain = EngineChain.of(lastView?.voices.orEmpty(), speaker)
        stopAsked = false
        playJob?.cancel()
        playJob = scope.launch {
            var last: PlaybackEvent? = null
            link.speaker.play(event, withName, chain).collect { p ->
                last = p
                when (p) {
                    is PlaybackEvent.FellBack -> {
                        engineNote = p.note
                        publishUi()
                    }
                    PlaybackEvent.Finished -> send(LoopInput.PlaybackDone(event.id))
                    PlaybackEvent.Interrupted -> if (!stopAsked) send(LoopInput.FocusLost)
                    is PlaybackEvent.Started, is PlaybackEvent.Failed -> Unit
                }
            }
            // A Failed is terminal only when it is the flow's last event (every engine failed, or a clip broke).
            if (last is PlaybackEvent.Failed) send(LoopInput.PlaybackFailed(event.id))
        }
    }

    // ---- Gateway calls -----------------------------------------------------------------------------------------------

    private suspend fun upload(worktree: String, utterance: Utterance) {
        val api = links.current()?.api ?: return send(LoopInput.TranscribeFailed(worktree, TranscribeFailure.Network))
        val decision = budget.check(clock())
        if (decision != BudgetDecision.Go) emitDebug("tx not sent: rate budget ${decision::class.simpleName}")
        when (decision) {
            BudgetDecision.InFlight -> return send(LoopInput.TranscribeFailed(worktree, TranscribeFailure.Network))
            is BudgetDecision.WaitUntil -> {
                val seconds = ((decision.atMs - clock() + 999) / 1_000).toInt()
                val refusal = Refusal(429, RefusalReason.TranscribeRate, "", retryAfterSeconds = seconds)
                return send(LoopInput.TranscribeFailed(worktree, TranscribeFailure.Refused(refusal)))
            }
            BudgetDecision.Go -> budget = budget.started(clock())
        }
        val result = api.transcribe(Wav.encode(utterance.pcm))
        budget = budget.finished()
        if (debug != null) {
            val answering = (_state.value.phase as? Phase.Transcribing)?.answering != null
            val guard = (result as? ApiResult.Ok)?.let {
                TranscriptGuard.judge(it.value.text, utterance.stats.voicedMs, answering, loop.config.expectLatin)
            }
            emitDebug(transcriptDebugLine(result, guard))
        }
        send(
            when (result) {
                is ApiResult.Ok -> LoopInput.Transcribed(worktree, result.value.text)
                ApiResult.LoggedOut -> LoopInput.Unauthorized
                is ApiResult.Refused -> {
                    val r = result.refusal
                    if (r.httpStatus == 429) budget = budget.retryAfter(clock(), r.retryAfterSeconds ?: 0)
                    LoopInput.TranscribeFailed(worktree, TranscribeFailure.Refused(r))
                }
                else -> LoopInput.TranscribeFailed(worktree, TranscribeFailure.Network)
            },
        )
    }

    private suspend fun prompt(effect: Effect.Send) {
        val api = links.current()?.api ?: return send(LoopInput.SendFailed(effect.session, maybeSent = false))
        send(
            when (val result = api.prompt(effect.session, effect.text, effect.voice, effect.confirmTap)) {
                is ApiResult.Ok -> LoopInput.SendOk(effect.session)
                ApiResult.LoggedOut -> LoopInput.Unauthorized
                is ApiResult.Refused -> LoopInput.SendRefused(effect.session, result.refusal)
                ApiResult.NetworkFailure -> LoopInput.SendFailed(effect.session, maybeSent = false)
                ApiResult.MaybeSent, is ApiResult.Unexpected -> LoopInput.SendFailed(effect.session, maybeSent = true)
            },
        )
    }

    /** A plan or re-plan: its answer goes back to the loop; nothing is retried here. */
    private suspend fun plan(call: suspend (GatewayApi) -> ApiResult<PlanAnswer>) {
        val api = links.current()?.api ?: return send(LoopInput.PlanFailed)
        val result = call(api)
        emitDebug(worktreeDebugLine("plan", result))
        send(
            when (result) {
                is ApiResult.Ok -> LoopInput.Planned(result.value)
                ApiResult.LoggedOut -> LoopInput.Unauthorized
                is ApiResult.Refused -> LoopInput.PlanRefused(result.refusal)
                else -> LoopInput.PlanFailed
            },
        )
    }

    /**
     * A create, never retried: no answer (or one it cannot read) may still have created it -> CreateMaybeDone; a
     * request that never went out created nothing -> a refusal with no reason that says so (Plan again).
     */
    private suspend fun create(planId: String) {
        val api = links.current()?.api ?: return send(LoopInput.CreateRefused(Refusal(0, null, WorktreeLines.COULDNT_REACH)))
        val result = api.create(planId)
        emitDebug(worktreeDebugLine("create", result))
        send(
            when (result) {
                is ApiResult.Ok -> LoopInput.Created(result.value)
                ApiResult.LoggedOut -> LoopInput.Unauthorized
                is ApiResult.Refused -> LoopInput.CreateRefused(result.refusal)
                ApiResult.NetworkFailure -> LoopInput.CreateRefused(Refusal(0, null, WorktreeLines.COULDNT_REACH))
                ApiResult.MaybeSent, is ApiResult.Unexpected -> LoopInput.CreateMaybeDone
            },
        )
    }

    private suspend fun acknowledge(ids: List<String>) {
        val api = links.current()?.api
        val result = api?.announced(ids)
        when (result) {
            is ApiResult.Ok -> pendingAcks.removeAll(ids.toSet())
            ApiResult.LoggedOut -> send(LoopInput.Unauthorized)
            else -> pendingAcks.addAll(ids)
        }
    }

    private suspend fun key(effect: Effect.SendKey) {
        val api = links.current()?.api ?: return
        when (val result = api.keys(effect.session, effect.key, effect.screenToken)) {
            ApiResult.LoggedOut -> send(LoopInput.Unauthorized)
            is ApiResult.Refused -> send(LoopInput.KeyRefused(effect.session, result.refusal))
            else -> Unit
        }
    }

    private suspend fun simple(call: suspend (dev.nittala.voicegateway.core.api.GatewayApi) -> ApiResult<Unit>) {
        val api = links.current()?.api ?: return
        if (call(api) == ApiResult.LoggedOut) send(LoopInput.Unauthorized)
    }

    // ---- Poller ----------------------------------------------------------------------------------------------------

    private inner class PollListener : Poller.Listener {
        override fun onView(view: SessionView) {
            if (view.name == _state.value.worktree) lastView = view
            send(LoopInput.View(view))
            if (pendingAcks.isNotEmpty()) {
                val ids = pendingAcks.toList()
                pendingAcks.clear()
                scope.launch { acknowledge(ids) }
            }
        }

        override fun onSessions(view: SessionsView) {
            sessions = view
            val s = _state.value
            if (s.worktree.isBlank()) {
                (view.attached + view.running).firstOrNull()?.let { send(LoopInput.WorktreeChanged(it.name)) }
            }
            publishUi()
        }

        override fun onLoggedOut() = send(LoopInput.Unauthorized)
    }

    private companion object {
        val REJECTED = Utterance(ShortArray(0), UtteranceStats(0, 0, 0, 0, 32.0), EndReason.Forced)

        fun isRunning(p: Phase) = p != Phase.Idle && p != Phase.LoggedOut
    }
}
