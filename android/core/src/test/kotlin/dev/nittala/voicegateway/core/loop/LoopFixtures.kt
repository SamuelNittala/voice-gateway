package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.api.Refusal
import dev.nittala.voicegateway.core.api.RefusalReason
import dev.nittala.voicegateway.core.model.Actions
import dev.nittala.voicegateway.core.model.AskKind
import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.model.EventKind
import dev.nittala.voicegateway.core.model.OrchestratorInfo
import dev.nittala.voicegateway.core.model.SessionView
import dev.nittala.voicegateway.core.speech.EndReason
import dev.nittala.voicegateway.core.speech.Utterance
import dev.nittala.voicegateway.core.speech.UtteranceStats

const val WT = "wt-scratch"
const val OTHER = "wt-other"
const val TEXT = "run the unit tests please"

val IDLE_AGENT = AgentView(state = "running", status = "idle")
val WORKING_AGENT = AgentView(state = "running", status = "working")
val BLOCKED_AGENT = AgentView(state = "running", status = "blocked", screenToken = "tok1", output = "1. Yes")

fun view(
    status: String? = "idle",
    state: String = "running",
    events: List<Event> = emptyList(),
    waiting: Boolean = false,
    token: String? = null,
    output: String = "out",
    name: String = WT,
): SessionView = SessionView(
    name = name,
    state = state,
    orchestrator = status?.let { OrchestratorInfo(name = "claude", kind = "claude", status = it) },
    problem = "",
    output = output,
    screenToken = token,
    draft = "unknown",
    actions = Actions(prompt = status == "idle", keys = emptyList(), stop = false, restart = false),
    events = events,
    waiting = if (waiting) listOf(name) else emptyList(),
)

fun event(
    id: String,
    kind: EventKind = EventKind.Summary,
    ask: AskKind = AskKind.None,
    session: String = WT,
    text: String = "All tests pass.",
): Event = Event(id = id, kind = kind, session = session, worktree = session.removePrefix("wt-"), text = text, ask = ask)

val QUESTION = event("q1", ask = AskKind.Answer, text = "Did you mean wt-api or wt-web?")
val CONFIRM = event("c1", ask = AskKind.Confirm, text = "Delete branch wt-old?")

/** Loud PCM (about -12 dBFS) with [voicedFrames] voiced of [spanFrames] (32 ms frames). */
fun utterance(voicedFrames: Int = 50, spanFrames: Int = 60, loud: Boolean = true): Utterance {
    val pcm = ShortArray(16_000) { if (!loud) 0 else if (it % 2 == 0) 8_000 else -8_000 }
    return Utterance(pcm, UtteranceStats(0, spanFrames, voicedFrames, 0, 32.0), EndReason.Silence)
}

val GOOD = utterance()
val QUIET = utterance(loud = false)
val SPARSE = utterance(voicedFrames = 10, spanFrames = 60)

fun refusal(
    reason: RefusalReason?,
    status: Int = 409,
    message: String = "server says no",
    retryAfter: Int? = null,
    screenToken: String? = null,
    output: String? = null,
): Refusal = Refusal(status, reason, message, retryAfterSeconds = retryAfter, screenToken = screenToken, output = output)

fun st(phase: Phase, agent: AgentView = IDLE_AGENT, worktree: String = WT): LoopState =
    LoopState(worktree = worktree, phase = phase, agent = agent)

/** Feeds [inputs] in order; returns the final state and every effect in order. */
fun VoiceLoop.run(state: LoopState, vararg inputs: LoopInput): Transition =
    inputs.fold(Transition(state)) { acc, input ->
        val t = step(acc.state, input)
        Transition(t.state, acc.effects + t.effects)
    }

val Transition.phase: Phase get() = state.phase
val Transition.sends: List<Effect.Send> get() = effects.filterIsInstance<Effect.Send>()
val Transition.says: List<String> get() = effects.filterIsInstance<Effect.Say>().map { it.text }
val Transition.captures: List<Effect.StartCapture> get() = effects.filterIsInstance<Effect.StartCapture>()
