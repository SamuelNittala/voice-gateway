package dev.nittala.voicegateway.core.worktree

import dev.nittala.voicegateway.core.api.RefusalReason
import dev.nittala.voicegateway.core.loop.AgentView
import dev.nittala.voicegateway.core.loop.EarconKind
import dev.nittala.voicegateway.core.loop.Effect
import dev.nittala.voicegateway.core.loop.Lines
import dev.nittala.voicegateway.core.loop.LoopConfig
import dev.nittala.voicegateway.core.loop.LoopInput
import dev.nittala.voicegateway.core.loop.Timer
import dev.nittala.voicegateway.core.model.CreateAnswer
import dev.nittala.voicegateway.core.model.OfflinePlan
import dev.nittala.voicegateway.core.model.PlanAnswer

/*
 * Spec (W6, plan section 5): the "new worktree" flow inside the voice loop, as one loop phase
 * (`Phase.NewWorktree(flow)`) whose rules live here, pure. The loop stays the only owner of the mic: the flow only
 * says, through [WorktreeFlow.listening] and its effects, when the mic is on; captures go through the loop's own
 * Capturing and Transcribing phases (G1-G4 and the upload retries) and come back here as [WorktreeFlowRules.heard].
 *
 * Nothing is ever planned or created on silence or on a near miss: a plan needs a transcript that passed G3/G4 or a
 * typed request, a create needs a tap on Create, a tap on the downloaded-copy button, or the whole phrase
 * "create it" ([CardPhrase]) after the card was read aloud. Candidates and bases are taps only.
 */

/** The flow's states, immutable. [listening]: the mic is on in this state (only [Request] and [Card]). */
sealed interface WorktreeFlow {
    val listening: Boolean get() = false

    /**
     * Waiting for the request ("a worktree in <repo> [from <base>] for <task>"). Spoken with the mic on, or typed in
     * the box. [misses] counts utterances that did not pass the guards; after [WorktreeFlowRules.MAX_MISSES] the mic
     * goes off and the box stays.
     */
    data class Request(override val listening: Boolean, val misses: Int = 0) : WorktreeFlow

    /**
     * `POST /api/worktree/plan` in flight for [query] ([voice]: it was spoken). [repoId] after a candidate tap;
     * [previous] is the card shown greyed out while a base re-plan runs.
     */
    data class Planning(
        val query: String,
        val voice: Boolean,
        val repoId: String? = null,
        val previous: Card? = null,
    ) : WorktreeFlow

    /** The gateway asked which repo ([ask]): the question is said, then the repos are taps only. */
    data class Choosing(val query: String, val voice: Boolean, val ask: PlanAnswer.Ask) : WorktreeFlow

    /**
     * The plan card. Read aloud once when it appears (spoken requests only); only then, and only when [plan] is
     * ready, not [expired] and the phone is not muted, does the mic open ([listening]) for "create it" or "cancel".
     * [misses] replies that were neither; after [WorktreeFlowRules.MAX_MISSES] the mic goes off and the card waits for
     * a tap. [expired] after `expires_in`: Create becomes "Plan again".
     */
    data class Card(
        val plan: PlanAnswer.Plan,
        val query: String,
        val voice: Boolean,
        override val listening: Boolean = false,
        val misses: Int = 0,
        val expired: Boolean = false,
    ) : WorktreeFlow

    /** `POST /api/worktree/create` of [planId] in flight; mic off. [card] stays on screen (null for the offline plan). */
    data class Creating(val planId: String, val query: String, val voice: Boolean, val card: Card? = null) : WorktreeFlow

    /**
     * A plan or create that did not work. [message] is said and shown. [query] non-null allows "Plan again";
     * [offline] offers "Create from the downloaded copy" (tap only, it knowingly uses an old commit); [maybeCreated]
     * when the create got no answer: never retried, the worktree list is opened.
     */
    data class Failed(
        val message: String,
        val query: String?,
        val voice: Boolean,
        val offline: OfflinePlan? = null,
        val maybeCreated: Boolean = false,
    ) : WorktreeFlow

    /**
     * Created and switched to [session]: waiting for two settled views in a row of its orchestrator, then [query] is
     * sent ONCE, to [session] only, through the normal prompt path. [settledPolls] counts settled views in a row.
     */
    data class Handoff(
        val session: String,
        val query: String,
        val voice: Boolean,
        val created: CreateAnswer,
        val settledPolls: Int = 0,
    ) : WorktreeFlow
}

/** What the loop does after one flow rule. */
sealed interface FlowStep {
    val effects: List<Effect>

    /** Stay in the flow, now [flow]. */
    data class Stay(val flow: WorktreeFlow, override val effects: List<Effect> = emptyList()) : FlowStep

    /** The flow is over (cancelled): back to the loop. */
    data class Leave(override val effects: List<Effect> = emptyList()) : FlowStep

    /** Created: switch the loop to [answer]'s session, then Idle there (default) or the hand-off (`handoffFirstPrompt`). */
    data class Created(
        val answer: CreateAnswer,
        val query: String,
        val voice: Boolean,
        override val effects: List<Effect> = emptyList(),
    ) : FlowStep

    /** The hand-off is due: send [query] to [session] (the loop checks it is the current worktree). */
    data class SendFirst(val session: String, val query: String, val voice: Boolean) : FlowStep {
        override val effects: List<Effect> get() = emptyList()
    }

    /** The new orchestrator shows a dialog: NeedsYou, keeping [handoff] to come back to. */
    data class Blocked(val handoff: WorktreeFlow.Handoff) : FlowStep {
        override val effects: List<Effect> get() = emptyList()
    }

    /** Keep [text] in Held, never sent by itself ([timedOut]: 120 s without a ready orchestrator). */
    data class Hold(
        val text: String,
        val voice: Boolean,
        val timedOut: Boolean,
        override val effects: List<Effect> = emptyList(),
    ) : FlowStep
}

/** Every phone-voice line of the flow. */
object WorktreeLines {
    const val SAY_CREATE_OR_CANCEL = "Say create it, or cancel"
    const val TAP_TO_CREATE = "Tap Create, or Cancel"
    const val TYPE_OR_TAP = "Type the request, or tap to speak"
    const val PICK_REPO = "Pick a repo on the screen"
    const val PLAN_EXPIRED = "The plan expired. Tap Plan again"
    const val CANCELLED = "Cancelled, nothing was created"
    const val COULDNT_REACH = "Couldn't reach the gateway, nothing was created"
    const val NOT_SURE_CREATED = "Not sure it was created, check the list"
    const val NOT_READY_KEPT = "The new orchestrator is not ready, I kept your request"
    /** The default after Create: switched to the new worktree, nothing is sent to it. */
    const val CREATED = "Worktree created"
    const val CREATED_SENDING ="Worktree created. I will send your request when it is ready"

    /** "Pick a repo on the screen: a, b or c." with up to three names when the request was understood. */
    fun pickRepo(names: List<String>): String {
        val shown = names.take(3)
        return when (shown.size) {
            0 -> PICK_REPO
            1 -> "$PICK_REPO: ${shown[0]}"
            else -> "$PICK_REPO: ${shown.dropLast(1).joinToString(", ")} or ${shown.last()}"
        }
    }
}

/**
 * Spec (W6): the flow's rules, pure. Called by `VoiceLoop` for `Phase.NewWorktree`; `null` means "this input means
 * nothing here" (the loop then applies its own rules for every phase, or changes nothing).
 *
 * Transitions (the table is VoiceLoopWorktreeTest):
 * - [enter]: Request with the mic on (Listen earcon, capture, NoSpeech after [LoopConfig.answerSilenceMs]).
 * - Request: typed text -> Planning (voice false) + `PlanWorktree`; Tap toggles the mic; NoSpeech -> mic off; Cancel.
 * - Planning: `Planned(Plan)` -> Card; `Planned(Ask)` -> Choosing (question and "Pick a repo" said for a spoken
 *   request); `PlanRefused` / `PlanFailed` -> Failed with the server's message (or [WorktreeLines.COULDNT_REACH]).
 * - Choosing: `CandidateTap(id)` of a listed candidate -> Planning with that repo; typed text -> Planning; Cancel.
 * - Card: CreateTap -> Creating + `CreateWorktree(planId)` when ready and not expired, else (expired) Planning again
 *   with the query ("Plan again"); `BaseTap` / `BaseTyped` (new branch, not expired, not blank) -> Planning with the
 *   old card greyed + `ReplanBase`; Tap toggles the mic (ready, not expired); NoSpeech -> mic off; `PlanExpiry` ->
 *   expired, mic off; Cancel.
 * - Creating: `Created` -> [FlowStep.Created]; `CreateRefused` -> Failed (with `offline` only for `fetch_failed`);
 *   `CreateMaybeDone` -> Failed(maybeCreated); Cancel is ignored (the create is already running).
 * - Failed: OfflineCreateTap (offline offered) -> Creating(offline plan); CreateTap (query kept) -> Planning again;
 *   typed text -> Planning; Cancel.
 * - Handoff: [onAgent] counts settled views; `HandoffLimit` -> Hold(timedOut); Cancel -> Hold (kept, never sent).
 */
class WorktreeFlowRules(private val config: LoopConfig = LoopConfig()) {
    /** The flow's first state and effects (from a `NewWorktreeTap`). */
    fun enter(): FlowStep.Stay = FlowStep.Stay(WorktreeFlow.Request(listening = true), listOf(Effect.Earcon(EarconKind.Listen)) + listen(false))

    /** The rule for a tap, typed text, a gateway result or a fired timer; null when [input] means nothing here. */
    fun step(flow: WorktreeFlow, input: LoopInput, muted: Boolean): FlowStep? = when (flow) {
        is WorktreeFlow.Request -> request(flow, input)
        is WorktreeFlow.Planning -> planning(flow, input, muted)
        is WorktreeFlow.Choosing -> choosing(flow, input)
        is WorktreeFlow.Card -> card(flow, input)
        is WorktreeFlow.Creating -> creating(flow, input)
        is WorktreeFlow.Failed -> failed(flow, input)
        is WorktreeFlow.Handoff -> handoff(flow, input)
    }

    /**
     * A capture for [flow] (a listening Request or Card) is over. [text] is the transcript when it passed the guards
     * (G1-G4, with answer mode for the card), else null (rejected, not transcribed, or no network): a miss.
     */
    fun heard(flow: WorktreeFlow, text: String?, muted: Boolean): FlowStep = when (flow) {
        is WorktreeFlow.Request ->
            if (text != null) plan(text.trim(), voice = true) else missed(flow.misses + 1, Lines.DIDNT_CATCH, WorktreeLines.TYPE_OR_TAP) { listening, misses ->
                flow.copy(listening = listening, misses = misses)
            }
        is WorktreeFlow.Card -> if (flow.expired) {
            FlowStep.Stay(flow.copy(listening = false), if (flow.voice) listOf(Effect.Say(WorktreeLines.PLAN_EXPIRED)) else emptyList())
        } else when (text?.let(CardPhrase::judge) ?: CardVerdict.Miss) {
            CardVerdict.Create -> createFrom(flow)
            CardVerdict.Cancel -> cancelled()
            CardVerdict.Miss ->
                missed(flow.misses + 1, WorktreeLines.SAY_CREATE_OR_CANCEL, WorktreeLines.TAP_TO_CREATE, answerMode = true) { listening, misses ->
                    flow.copy(listening = listening, misses = misses)
                }
        }
        else -> FlowStep.Stay(flow)
    }

    /** A view of the new session during the hand-off (it is the loop's current worktree). */
    fun onAgent(handoff: WorktreeFlow.Handoff, agent: AgentView): FlowStep = when {
        agent.blocked -> FlowStep.Blocked(handoff.copy(settledPolls = 0))
        agent.settled -> {
            val polls = handoff.settledPolls + 1
            if (polls >= config.handoffSettledViews) FlowStep.SendFirst(handoff.session, handoff.query, handoff.voice)
            else FlowStep.Stay(handoff.copy(settledPolls = polls))
        }
        else -> FlowStep.Stay(handoff.copy(settledPolls = 0))
    }

    /** The mic was lost (focus taken, silenced, broken) in [flow]: the mic goes off, the flow stays. */
    fun micLost(flow: WorktreeFlow, line: String?): FlowStep.Stay {
        val off = when (flow) {
            is WorktreeFlow.Request -> flow.copy(listening = false)
            is WorktreeFlow.Card -> flow.copy(listening = false)
            else -> flow
        }
        return FlowStep.Stay(off, listOfNotNull(line?.let { Effect.Earcon(EarconKind.Error) }, line?.let { Effect.Say(it) }))
    }

    /** The timers that run in [flow]. */
    fun runningTimers(flow: WorktreeFlow): Set<Timer> = when (flow) {
        is WorktreeFlow.Request -> if (flow.listening) setOf(Timer.NoSpeech) else emptySet()
        is WorktreeFlow.Card -> (if (flow.listening) setOf(Timer.NoSpeech) else emptySet()) +
            (if (flow.expired) emptySet() else setOf(Timer.PlanExpiry))
        is WorktreeFlow.Handoff -> setOf(Timer.HandoffLimit)
        else -> emptySet()
    }

    // ---- Per state ------------------------------------------------------------------------------------------------

    private fun request(f: WorktreeFlow.Request, i: LoopInput): FlowStep? = when (i) {
        is LoopInput.NewWorktreeTyped -> typed(i.text)
        LoopInput.Tap ->
            if (f.listening) FlowStep.Stay(f.copy(listening = false))
            else FlowStep.Stay(WorktreeFlow.Request(listening = true), listOf(Effect.Earcon(EarconKind.Listen)) + listen(false))
        is LoopInput.TimerFired ->
            if (i.timer == Timer.NoSpeech && f.listening) FlowStep.Stay(f.copy(listening = false), listOf(Effect.Earcon(EarconKind.Paused)))
            else null
        LoopInput.CancelTap -> cancelled()
        else -> null
    }

    private fun planning(f: WorktreeFlow.Planning, i: LoopInput, muted: Boolean): FlowStep? = when (i) {
        is LoopInput.Planned -> when (val a = i.answer) {
            is PlanAnswer.Plan -> showCard(a, f.query, f.voice, muted)
            is PlanAnswer.Ask -> FlowStep.Stay(
                WorktreeFlow.Choosing(f.query, f.voice, a),
                if (f.voice) {
                    listOf(Effect.Say(a.question), Effect.Say(WorktreeLines.pickRepo(if (a.understood) a.candidates.map { it.name } else emptyList())))
                } else emptyList(),
            )
        }
        is LoopInput.PlanRefused -> fail(i.refusal.message.ifBlank { WorktreeLines.COULDNT_REACH }, f.query, f.voice)
        LoopInput.PlanFailed -> fail(WorktreeLines.COULDNT_REACH, f.query, f.voice)
        LoopInput.CancelTap -> cancelled()
        else -> null
    }

    private fun choosing(f: WorktreeFlow.Choosing, i: LoopInput): FlowStep? = when (i) {
        is LoopInput.CandidateTap ->
            if (f.ask.candidates.any { it.id == i.repoId }) {
                FlowStep.Stay(WorktreeFlow.Planning(f.query, f.voice, repoId = i.repoId), listOf(Effect.PlanWorktree(f.query, i.repoId)))
            } else null
        is LoopInput.NewWorktreeTyped -> typed(i.text)
        LoopInput.CancelTap -> cancelled()
        else -> null
    }

    private fun card(f: WorktreeFlow.Card, i: LoopInput): FlowStep? = when (i) {
        LoopInput.CreateTap -> when {
            f.expired -> plan(f.query, f.voice)
            f.plan.ready -> createFrom(f)
            else -> null
        }
        is LoopInput.BaseTap -> base(f, i.name)
        is LoopInput.BaseTyped -> base(f, i.name)
        LoopInput.Tap -> when {
            f.listening -> FlowStep.Stay(f.copy(listening = false))
            f.plan.ready && !f.expired -> FlowStep.Stay(f.copy(listening = true, misses = 0), listen(true))
            else -> null
        }
        is LoopInput.TimerFired -> when {
            i.timer == Timer.NoSpeech && f.listening -> FlowStep.Stay(f.copy(listening = false))
            i.timer == Timer.PlanExpiry && !f.expired -> FlowStep.Stay(
                f.copy(listening = false, expired = true),
                if (f.voice) listOf(Effect.Say(WorktreeLines.PLAN_EXPIRED)) else emptyList(),
            )
            else -> null
        }
        LoopInput.CancelTap -> cancelled()
        else -> null
    }

    private fun creating(f: WorktreeFlow.Creating, i: LoopInput): FlowStep? = when (i) {
        is LoopInput.Created -> FlowStep.Created(i.answer, f.query, f.voice)
        is LoopInput.CreateRefused -> {
            val r = i.refusal
            val offline = r.offline.takeIf { r.reason == RefusalReason.FetchFailed }
            fail(r.message.ifBlank { WorktreeLines.COULDNT_REACH }, f.query, f.voice, offline)
        }
        LoopInput.CreateMaybeDone -> FlowStep.Stay(
            WorktreeFlow.Failed(WorktreeLines.NOT_SURE_CREATED, null, f.voice, maybeCreated = true),
            listOf(Effect.Earcon(EarconKind.Error), Effect.Say(WorktreeLines.NOT_SURE_CREATED)),
        )
        else -> null
    }

    private fun failed(f: WorktreeFlow.Failed, i: LoopInput): FlowStep? = when (i) {
        LoopInput.OfflineCreateTap -> f.offline?.let {
            FlowStep.Stay(WorktreeFlow.Creating(it.planId, f.query.orEmpty(), f.voice), listOf(Effect.CreateWorktree(it.planId)))
        }
        LoopInput.CreateTap -> f.query?.let { plan(it, f.voice) }
        is LoopInput.NewWorktreeTyped -> typed(i.text)
        LoopInput.CancelTap -> cancelled()
        else -> null
    }

    private fun handoff(f: WorktreeFlow.Handoff, i: LoopInput): FlowStep? = when (i) {
        is LoopInput.TimerFired ->
            if (i.timer == Timer.HandoffLimit) FlowStep.Hold(f.query, f.voice, timedOut = true, listOf(Effect.Say(WorktreeLines.NOT_READY_KEPT)))
            else null
        LoopInput.CancelTap -> FlowStep.Hold(f.query, f.voice, timedOut = false)
        else -> null
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------

    private fun listen(answerMode: Boolean): List<Effect> = listOf(
        Effect.StartCapture(answerMode = answerMode),
        Effect.StartTimer(Timer.NoSpeech, config.answerSilenceMs),
    )

    private fun typed(text: String): FlowStep? = text.trim().takeIf { it.isNotEmpty() }?.let { plan(it, voice = false) }

    private fun plan(query: String, voice: Boolean): FlowStep =
        FlowStep.Stay(WorktreeFlow.Planning(query, voice), listOf(Effect.PlanWorktree(query, null)))

    private fun showCard(plan: PlanAnswer.Plan, query: String, voice: Boolean, muted: Boolean): FlowStep {
        val read = voice && !muted
        val listening = read && plan.ready
        val effects = buildList {
            if (plan.expiresIn > 0) add(Effect.StartTimer(Timer.PlanExpiry, plan.expiresIn * 1_000L))
            if (read) add(Effect.Say(CardSpeech.of(plan)))
            if (listening) addAll(listen(true))
        }
        return FlowStep.Stay(WorktreeFlow.Card(plan, query, voice, listening = listening), effects)
    }

    private fun base(f: WorktreeFlow.Card, name: String): FlowStep? {
        val base = name.trim()
        if (base.isEmpty() || f.expired || f.plan.existing || !f.plan.base.applies) return null
        return FlowStep.Stay(
            WorktreeFlow.Planning(f.query, f.voice, previous = f.copy(listening = false)),
            listOf(Effect.ReplanBase(f.plan.planId, base)),
        )
    }

    private fun createFrom(f: WorktreeFlow.Card): FlowStep = FlowStep.Stay(
        WorktreeFlow.Creating(f.plan.planId, f.query, f.voice, f.copy(listening = false)),
        listOf(Effect.CreateWorktree(f.plan.planId)),
    )

    private fun fail(message: String, query: String?, voice: Boolean, offline: OfflinePlan? = null): FlowStep =
        FlowStep.Stay(
            WorktreeFlow.Failed(message, query, voice, offline),
            listOf(Effect.Earcon(EarconKind.Error), Effect.Say(message)),
        )

    private fun cancelled(): FlowStep = FlowStep.Leave(listOf(Effect.Say(WorktreeLines.CANCELLED)))

    /** A miss: say [again] and listen again, or after [MAX_MISSES] say [off] with the mic off. */
    private fun missed(
        misses: Int,
        again: String,
        off: String,
        answerMode: Boolean = false,
        next: (listening: Boolean, misses: Int) -> WorktreeFlow,
    ): FlowStep =
        if (misses < MAX_MISSES) {
            FlowStep.Stay(next(true, misses), listOf(Effect.Earcon(EarconKind.Rejected), Effect.Say(again)) + listen(answerMode))
        } else {
            FlowStep.Stay(next(false, misses), listOf(Effect.Earcon(EarconKind.Rejected), Effect.Say(off)))
        }

    companion object {
        const val MAX_MISSES = 2
    }
}
