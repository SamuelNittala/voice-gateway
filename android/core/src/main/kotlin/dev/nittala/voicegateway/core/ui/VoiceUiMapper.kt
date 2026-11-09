package dev.nittala.voicegateway.core.ui

import dev.nittala.voicegateway.core.config.BaseUrlRefusal
import dev.nittala.voicegateway.core.config.BaseUrlResult
import dev.nittala.voicegateway.core.config.BuildVariant
import dev.nittala.voicegateway.core.config.validateBaseUrl
import dev.nittala.voicegateway.core.loop.CapturePurpose
import dev.nittala.voicegateway.core.loop.ErrorKind
import dev.nittala.voicegateway.core.loop.HeldReason
import dev.nittala.voicegateway.core.loop.LoopState
import dev.nittala.voicegateway.core.loop.PauseReason
import dev.nittala.voicegateway.core.loop.Phase
import dev.nittala.voicegateway.core.model.SessionRow
import dev.nittala.voicegateway.core.model.SessionView
import dev.nittala.voicegateway.core.model.SessionsView
import dev.nittala.voicegateway.core.model.RepoRef
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.worktree.WorktreeFlow
import dev.nittala.voicegateway.core.worktree.WorktreeLines
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Spec (A11), pure: the voice view for a loop state.
 * - [loop]: the reducer's state (phase, worktree, last agent view, kept questions, mute, online).
 * - [sessions]: the last `GET /api/sessions`, for the picker; null before the first answer.
 * - [view]: the last `GET /api/session` of the current worktree, for the allowed keys and the typed prompt; ignored
 *   when it is for another worktree.
 * - [micLevel]: the capture level 0..1 (clamped); shown only while the mic is on.
 * - [zone]: the time zone dates on the plan card are shown in.
 *
 * Every phase maps to its own [VoiceMode]. Yes/No only in AwaitingTap; keys only in NeedsYou with a screen token;
 * Stop only while the agent works; Restart only in Stopped (not restarting); Held always shows its reason, and its
 * text when there is one. Output and event text are copied literally.
 */
fun voiceUiState(
    loop: LoopState,
    sessions: SessionsView? = null,
    view: SessionView? = null,
    micLevel: Float = 0f,
    settings: UiSettings = UiSettings(),
    zone: ZoneId = ZoneId.systemDefault(),
): VoiceUiState {
    val p = loop.phase
    val ownView = view?.takeIf { it.name == loop.worktree }
    val screen = screenOf(loop, p)
    val micOn = p == Phase.Listening || p is Phase.AwaitingAnswer || p is Phase.Capturing ||
        (p is Phase.NewWorktree && p.flow.listening)
    val working = p is Phase.AgentWorking ||
        (loop.agent.working && p !is Phase.Stopped && p != Phase.LoggedOut)
    return VoiceUiState(
        worktree = loop.worktree,
        mode = screen.mode,
        title = screen.title,
        detail = screen.detail,
        question = screen.question,
        micOn = micOn,
        level = if (micOn) micLevel.coerceIn(0f, 1f) else 0f,
        confirmButtons = p is Phase.AwaitingTap,
        keys = if (p is Phase.NeedsYou && p.screenToken != null) keysOf(ownView) else emptyList(),
        canStop = working,
        canRestart = p is Phase.Stopped && !p.restarting,
        held = (p as? Phase.Held)?.let { HeldUi(it.text, heldReason(it.reason, it.agentFree), it.text != null) },
        output = if (p is Phase.NeedsYou) p.output else loop.agent.output,
        speakingFrom = (p as? Phase.Speaking)?.event?.session?.takeIf { it != loop.worktree },
        orbTap = orbTapOf(p),
        openSheet = p is Phase.NeedsYou || p is Phase.Stopped,
        canType = ownView?.actions?.prompt == true && p != Phase.LoggedOut && p !is Phase.NewWorktree,
        muted = loop.muted,
        online = loop.online,
        worktrees = sessions?.let { worktreesOf(it, loop) } ?: emptyList(),
        engineInfo = engineInfo(settings),
        dictationInfo = "Dictation: Local Whisper on the gateway. Speech ends after " +
            "${formatSeconds(settings.endOfSpeechMs)} s of silence, detected on the phone.",
        announcement = listOfNotNull(screen.title, screen.question ?: screen.detail).joinToString(". "),
        newWorktree = flowPhaseOf(p)?.let { newWorktreeUi(it.flow, zone) },
        canStartNewWorktree = when (p) {
            Phase.Idle, Phase.Listening, is Phase.AwaitingAnswer, is Phase.Paused, is Phase.Held, is Phase.Error,
            is Phase.Stopped, is Phase.AgentWorking -> true
            else -> false
        },
        micNotice = micNoticeUi(loop),
    )
}

/** The flow shown for [p]: the flow itself, or the one a capture or upload belongs to. */
private fun flowPhaseOf(p: Phase): Phase.NewWorktree? = when (p) {
    is Phase.NewWorktree -> p
    is Phase.Capturing -> (p.purpose as? CapturePurpose.Worktree)?.back
    is Phase.Transcribing -> (p.purpose as? CapturePurpose.Worktree)?.back
    else -> null
}

/** Pure (W6): the flow's screen part, the same content as the web page's plan card. */
fun newWorktreeUi(flow: WorktreeFlow, zone: ZoneId): NewWorktreeUi = when (flow) {
    is WorktreeFlow.Request -> NewWorktreeUi.Request(flow.listening)
    is WorktreeFlow.Planning ->
        NewWorktreeUi.Planning(flow.query, flow.previous?.let { cardUi(it, zone).copy(greyed = true, createEnabled = false, listening = false, hint = null) })
    is WorktreeFlow.Choosing -> NewWorktreeUi.Choosing(
        flow.ask.reason?.let { "${flow.ask.question} ($it)" } ?: flow.ask.question,
        flow.ask.candidates.map { CandidateUi(it.id, repoLabel(it)) },
    )
    is WorktreeFlow.Card -> NewWorktreeUi.Card(cardUi(flow, zone))
    is WorktreeFlow.Creating ->
        NewWorktreeUi.Creating(flow.card?.let { cardUi(it, zone).copy(createEnabled = false, listening = false, hint = null) })
    is WorktreeFlow.Failed -> NewWorktreeUi.Failed(
        message = flow.message,
        offlineLabel = flow.offline?.let { o ->
            "Create from the downloaded copy (dated ${dateText(o.fetchedAt ?: o.committedAt, zone) ?: o.sha})"
        },
        canPlanAgain = flow.query != null,
        openPicker = flow.maybeCreated,
    )
    is WorktreeFlow.Handoff -> NewWorktreeUi.Handoff(
        session = flow.session,
        request = flow.query,
        started = flow.created.base?.let { b ->
            val how = when {
                !b.fetched -> "the downloaded copy, not fetched"
                b.moved -> "fetched just now; the plan showed ${b.plannedSha}"
                else -> "fetched just now"
            }
            "Started from ${b.ref} at ${b.sha} ${b.subject} ($how)."
        },
        launch = flow.created.launch?.let { l ->
            "Orchestrator: ${l.model}, effort ${l.effort}, playbook ${yesNo(l.playbook)}, settings ${yesNo(l.settings)}, trust ${l.trust}"
        },
    )
}

private fun cardUi(f: WorktreeFlow.Card, zone: ZoneId): CardUi {
    val plan = f.plan
    val base = plan.base
    val newBranch = !plan.existing
    val baseLine = when {
        plan.existing -> listOfNotNull(base.sha, base.subject).joinToString(" ").ifEmpty { null }
        base.ref == null -> null
        !base.local -> "${base.sha.orEmpty()} on origin, not downloaded here yet".trim()
        else -> {
            val date = base.fetchedAt?.let { dateText(it, zone)?.let { d -> "downloaded $d" } }
                ?: base.committedAt?.let { dateText(it, zone)?.let { d -> "commit of $d" } }
            listOfNotNull(base.sha, base.subject).joinToString(" ") + (date?.let { "; local copy: $it" } ?: "")
        }
    }
    return CardUi(
        repo = repoLabel(plan.repo),
        branch = "Branch: ${plan.branch}",
        mode = when {
            plan.existing -> "Existing branch, checked out as it is (the base does not apply)"
            base.ref != null -> "New branch from ${base.ref}${baseHow(base.how)}"
            else -> "New branch: pick a base"
        },
        baseLine = baseLine,
        note = base.message.ifBlank { null },
        fetchNote = if (newBranch && base.ref != null) "Fetched from origin just before creating." else null,
        choices = if (newBranch) base.choices else emptyList(),
        currentChoice = if (newBranch) base.name else null,
        otherBase = newBranch,
        path = plan.path,
        createEnabled = plan.ready && !f.expired,
        createLabel = if (f.expired) "Plan again" else "Create",
        expired = f.expired,
        expiresInSeconds = plan.expiresIn,
        listening = f.listening,
        hint = if (f.listening) WorktreeLines.SAY_CREATE_OR_CANCEL else null,
    )
}

private fun repoLabel(r: RepoRef) = "${r.name} (${r.where})"

private fun baseHow(how: String?) = when (how) {
    "remembered" -> " (last used here)"
    "origin-head" -> " (origin/HEAD)"
    else -> ""
}

private fun yesNo(b: Boolean) = if (b) "yes" else "no"

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

private fun dateText(epochSeconds: Long?, zone: ZoneId): String? =
    epochSeconds?.takeIf { it > 0 }?.let { DATE.format(Instant.ofEpochSecond(it).atZone(zone)) }

/** Pure: a plain-language sentence for why a base URL was refused, saying what to type instead. */
fun baseUrlMessage(reason: BaseUrlRefusal): String = when (reason) {
    BaseUrlRefusal.EMPTY_OR_TOO_LONG -> "Enter the gateway address (at most 300 characters)."
    BaseUrlRefusal.WHITESPACE -> "Remove the spaces or line breaks from the address."
    BaseUrlRefusal.BACKSLASH -> "Use forward slashes only: the address has a backslash (\\)."
    BaseUrlRefusal.NOT_ASCII -> "Use plain letters, digits, dots and hyphens only; the address has other characters."
    BaseUrlRefusal.BAD_SCHEME -> "Start the address with https://"
    BaseUrlRefusal.CLEARTEXT ->
        "Use https:// with your Tailscale name. Plain http:// is only allowed in debug builds, to a tailnet IP."
    BaseUrlRefusal.USERINFO -> "Remove the name and @ before the host; the passcode is asked for separately."
    BaseUrlRefusal.BAD_HOST -> "The host name is not valid. Use the Mac's name, like mac.tail1234.ts.net"
    BaseUrlRefusal.BAD_PORT -> "The port after : must be a number from 1 to 65535, or leave it out."
    BaseUrlRefusal.HOST_NOT_ALLOWED -> "Use your Mac's Tailscale address, ending in .ts.net"
    BaseUrlRefusal.PATH -> "Enter only the address, with nothing after the host (no /path)."
    BaseUrlRefusal.QUERY -> "Remove the ? and everything after it."
    BaseUrlRefusal.FRAGMENT -> "Remove the # and everything after it."
}

/** Pure: null when [raw] is an acceptable base URL for [variant], else [baseUrlMessage] of the refusal. */
fun baseUrlProblem(raw: String, variant: BuildVariant): String? =
    (validateBaseUrl(raw, variant) as? BaseUrlResult.Refused)?.let { baseUrlMessage(it.reason) }

private data class Screen(val mode: VoiceMode, val title: String, val detail: String? = null, val question: String? = null)

private fun screenOf(loop: LoopState, p: Phase): Screen = when (p) {
    Phase.Idle -> Screen(VoiceMode.Idle, "Tap to start", "The loop is off.")
    Phase.Listening -> Screen(VoiceMode.Listening, "Listening", "Speak your request.")
    is Phase.AwaitingAnswer -> Screen(VoiceMode.AwaitingAnswer, "Your answer", "The agent asked:", p.question.text)
    is Phase.AwaitingTap ->
        Screen(VoiceMode.AwaitingTap, "Confirm on the phone", "The mic is off. Tap Yes or No.", p.question.text)
    is Phase.Capturing ->
        Screen(VoiceMode.Capturing, if (p.answering != null) "Hearing your answer" else "Hearing you", null, p.answering?.text)
    is Phase.Transcribing -> Screen(
        VoiceMode.Transcribing, "Transcribing",
        if (p.waitingForNetwork) "Waiting for the network." else null, p.answering?.text,
    )
    is Phase.Sending -> Screen(VoiceMode.Sending, "Sending", p.text)
    is Phase.AgentWorking -> Screen(
        VoiceMode.Working, "Agent working",
        when {
            p.sent?.maybeSent == true -> "May not have been sent; check the output."
            loop.lastProgress != null -> loop.lastProgress
            p.sent != null -> p.sent.text
            else -> null
        },
    )
    is Phase.Speaking -> Screen(VoiceMode.Speaking, "Speaking", p.event.text)
    is Phase.NeedsYou ->
        Screen(VoiceMode.NeedsYou, "Look at the phone", "The agent needs you to choose. Use the keys in the sheet.")
    is Phase.Held -> Screen(VoiceMode.Held, "Message kept", heldReason(p.reason, p.agentFree))
    is Phase.Paused -> Screen(VoiceMode.Paused, "Paused", pauseReason(p.why), loop.question?.text)
    is Phase.Stopped -> Screen(
        VoiceMode.Stopped,
        if (p.restarting) "Restarting" else "Worktree stopped",
        if (p.restarting) "Waiting for ${loop.worktree} to run." else "${loop.worktree} is stopped. Restart it from the sheet.",
    )
    is Phase.Error -> Screen(VoiceMode.Error, "Problem", "${errorText(p.kind)} ${p.message}".trim())
    Phase.LoggedOut -> Screen(VoiceMode.LoggedOut, "Logged out", "Log in again to continue.")
    is Phase.NewWorktree -> when (val f = p.flow) {
        is WorktreeFlow.Request -> Screen(
            VoiceMode.NewWorktree, "New worktree",
            if (f.listening) "Say which repo, and what the worktree is for." else "Type which repo, and what the worktree is for, or tap to speak.",
        )
        is WorktreeFlow.Planning -> Screen(VoiceMode.NewWorktree, "Working it out", "Planning the worktree. Nothing is created yet.")
        is WorktreeFlow.Choosing -> Screen(VoiceMode.NewWorktree, "Pick a repo", null, f.ask.question)
        is WorktreeFlow.Card -> Screen(
            VoiceMode.NewWorktree, "Check the plan",
            when {
                f.expired -> "The plan expired. Plan again."
                f.listening -> "Say create it, or cancel. Or tap."
                f.plan.ready -> "Tap Create, or Cancel."
                else -> "Pick a base branch."
            },
        )
        is WorktreeFlow.Creating -> Screen(VoiceMode.NewWorktree, "Creating the worktree", "This can take a few minutes.")
        is WorktreeFlow.Failed -> Screen(VoiceMode.NewWorktree, "Not created", f.message)
        is WorktreeFlow.Handoff -> Screen(
            VoiceMode.NewWorktree, "Waiting for the new orchestrator",
            "Your request goes to ${f.session} once it is ready.",
        )
    }
}

private fun orbTapOf(p: Phase): OrbTap = when (p) {
    is Phase.NewWorktree -> when (val f = p.flow) {
        is WorktreeFlow.Request -> OrbTap.Tap
        is WorktreeFlow.Card -> if (f.listening || (f.plan.ready && !f.expired)) OrbTap.Tap else OrbTap.None
        else -> OrbTap.None
    }
    Phase.Idle, is Phase.Paused, is Phase.Error -> OrbTap.Start
    is Phase.Speaking -> OrbTap.Interrupt
    Phase.Listening, is Phase.AwaitingAnswer, is Phase.Capturing, is Phase.AwaitingTap -> OrbTap.Tap
    else -> OrbTap.None
}

private fun keysOf(view: SessionView?): List<String> {
    val allowed = view?.actions?.keys ?: return VoiceUiState.ALLOWED_KEYS
    return VoiceUiState.ALLOWED_KEYS.filter { it in allowed }
}

private fun heldReason(reason: HeldReason, agentFree: Boolean): String = when (reason) {
    HeldReason.AgentBusy ->
        if (agentFree) "The agent is free: send now?" else "The agent is busy. Your message was kept, not sent."
    HeldReason.DraftAtMac -> "There is unsent text at the Mac. Your message was kept, not sent."
    HeldReason.RetryFailed -> "Couldn't send after a retry. Your message was kept."
    HeldReason.NotSent -> "Couldn't send; nothing was delivered. Your message was kept."
    HeldReason.MaybeNotSent -> "May not have been sent; check the output before sending again."
    HeldReason.CouldNotTranscribe -> "Couldn't transcribe what you said. Nothing was sent."
    is HeldReason.Refused -> "The gateway refused it: ${reason.message}"
    HeldReason.NewWorktreeNotReady ->
        "The new orchestrator was not ready within 2 minutes. Your request was kept, not sent."
    HeldReason.NewWorktreeRequest -> "Your request for the new worktree was kept, not sent. Send it now?"
}

private fun pauseReason(why: PauseReason): String = when (why) {
    PauseReason.User -> "You paused. Tap to continue."
    PauseReason.NoSpeech -> "No speech heard. Tap to continue."
    PauseReason.Question -> "The agent is waiting for your answer. Tap to answer."
    PauseReason.Noise -> "Too much noise. Tap to try again."
    PauseReason.FocusLost -> "Another app took the audio (a call?). Tap to continue."
    PauseReason.MicSilenced -> "Android silenced the microphone (a call, another app or the privacy switch). Tap to try again."
    PauseReason.MicUnavailable -> "The microphone could not be opened. Check the permission, then tap to try again."
}

private fun errorText(kind: ErrorKind): String = when (kind) {
    ErrorKind.PaneChanged -> "The agent's pane changed."
    ErrorKind.NoOrchestrator -> "No agent found in this worktree."
    ErrorKind.SpeechOff -> "Transcription is off on the gateway."
}

private fun worktreesOf(v: SessionsView, loop: LoopState): List<WorktreeItem> {
    fun item(row: SessionRow, group: WorktreeGroup) = WorktreeItem(
        name = row.name,
        group = group,
        agentStatus = row.agents.firstOrNull { it.orchestrator }?.status,
        problem = row.error,
        current = row.name == loop.worktree,
        openQuestion = row.name in loop.questions,
        waiting = row.name in v.waiting,
    )
    return v.attached.map { item(it, WorktreeGroup.Attached) } +
        v.running.map { item(it, WorktreeGroup.Running) } +
        v.stopped.map { item(it, WorktreeGroup.Stopped) }
}

private fun engineInfo(s: UiSettings): String {
    val chosen = when (s.speaker) {
        null -> "gateway default (Chatterbox, then Deepgram, then the phone)"
        Engine.Phone -> "the phone's voice"
        else -> s.speaker.label
    }
    return if (s.engineNote.isEmpty()) "Voice: $chosen." else "Voice: $chosen. ${s.engineNote}."
}

private fun formatSeconds(ms: Int): String =
    if (ms % 1000 == 0) "${ms / 1000}" else "%.1f".format(Locale.ROOT, ms / 1000.0)
