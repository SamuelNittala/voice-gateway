package dev.nittala.voicegateway.playback

import dev.nittala.voicegateway.audio.AudioFocusGate
import dev.nittala.voicegateway.audio.FocusEvent
import dev.nittala.voicegateway.audio.FocusResult
import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.model.EventKind
import dev.nittala.voicegateway.core.speech.Engine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

fun event(id: String = "ev-1", text: String = "Tests pass.", session: String = "wt-api") =
    Event(id = id, kind = EventKind.Summary, session = session, worktree = session, text = text)

/** One step of a scripted engine. */
sealed interface Step {
    data class Wait(val ms: Long) : Step
    data class Emit(val event: ClipEvent) : Step
    data object Hang : Step
}

object Scripts {
    val plays = listOf(Step.Wait(300), Step.Emit(ClipEvent.Started), Step.Wait(2_000), Step.Emit(ClipEvent.Finished))
    fun failsBeforeStart(reason: String = "HTTP 502") = listOf(Step.Wait(200), Step.Emit(ClipEvent.Failed(reason)))
    val neverStarts = listOf(Step.Hang)
    val startsAndHangs = listOf(Step.Wait(300), Step.Emit(ClipEvent.Started), Step.Hang)
    fun breaksPartWay(reason: String = "stream cut") =
        listOf(Step.Wait(300), Step.Emit(ClipEvent.Started), Step.Wait(1_000), Step.Emit(ClipEvent.Failed(reason)))
    /** Starts 20 s in: after the 15 s start limit. */
    val startsLate = listOf(Step.Wait(20_000), Step.Emit(ClipEvent.Started), Step.Wait(1_000), Step.Emit(ClipEvent.Finished))
}

/** An [EngineSpeaker] per engine following [scripts]; logs every ask and every stop (collection cancelled). */
class ScriptedVoice(private val scripts: Map<Engine, List<Step>>) : EngineSpeaker {
    val asked = mutableListOf<Engine>()
    val stopped = mutableListOf<Engine>()
    val texts = mutableListOf<Pair<String, Boolean>>()

    override fun play(event: Event, withName: Boolean, engine: Engine): Flow<ClipEvent> = flow {
        asked += engine
        texts += event.text to withName
        try {
            for (step in scripts.getValue(engine)) when (step) {
                is Step.Wait -> delay(step.ms)
                is Step.Emit -> emit(step.event)
                Step.Hang -> awaitCancellation()
            }
        } catch (e: CancellationException) {
            stopped += engine
            throw e
        }
    }
}

class FakeFocusGate(var answer: FocusResult = FocusResult.Granted) : AudioFocusGate {
    private val channel = Channel<FocusEvent>(Channel.UNLIMITED)
    override val events: Flow<FocusEvent> = channel.receiveAsFlow()
    var acquired = 0
    var released = 0
    val held get() = acquired > released

    override fun acquire(): FocusResult {
        if (answer == FocusResult.Granted) acquired++
        return answer
    }

    override fun release() {
        if (held) released++
    }

    fun lose(permanent: Boolean) = channel.trySend(FocusEvent.Lost(permanent))
    fun duck() = channel.trySend(FocusEvent.Ducked)
}
