package dev.nittala.voicegateway.audio

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Frame helpers for the tests: 512-sample frames. */
object Frames {
    const val SIZE = 512

    /** A square wave of [amplitude]: RMS and peak both 20*log10(amplitude/32768) dBFS. */
    fun tone(amplitude: Int): ShortArray = ShortArray(SIZE) { i -> (if (i % 2 == 0) amplitude else -amplitude).toShort() }

    fun zeros(): ShortArray = ShortArray(SIZE)

    /** Loud enough for G1 (-12 dBFS). */
    val LOUD: ShortArray get() = tone(8_000)

    /** Under G1 (about -56 dBFS). */
    val QUIET: ShortArray get() = tone(50)
}

/** Log of what the fakes did, in order, shared so tests can check release order across sessions. */
class Journal {
    val entries: MutableList<String> = Collections.synchronizedList(mutableListOf())
    fun add(entry: String) {
        entries += entry
    }
    fun snapshot(): List<String> = synchronized(entries) { entries.toList() }
}

/**
 * A [FrameSource] that plays [frames] and then blocks, like a live mic, until [stop]; reads after [stop] return -1.
 * [readError] makes the read with that index return -1.
 */
class ScriptedFrameSource(
    private val frames: List<ShortArray>,
    private val journal: Journal,
    private val name: String = "source",
    override val sessionId: Int = 42,
    private val failStart: Boolean = false,
    private val readError: Int = -1,
) : FrameSource {
    private val stopped = CountDownLatch(1)
    @Volatile var reads = 0
        private set
    @Volatile var released = 0
        private set

    override fun start() {
        journal.add("$name.start")
        check(!failStart) { "busy" }
    }

    override fun read(into: ShortArray): Int {
        val index = reads++
        if (stopped.count == 0L || index == readError) return -1
        if (index < frames.size) {
            frames[index].copyInto(into)
            return into.size
        }
        stopped.await(10, TimeUnit.SECONDS)
        return -1
    }

    override fun stop() {
        journal.add("$name.stop")
        stopped.countDown()
    }

    override fun release() {
        released++
        journal.add("$name.release")
    }
}

/** A [VoiceProbability] that returns [script] in order, then [after]. */
class ScriptedVoiceProbability(
    private val script: List<Float>,
    private val journal: Journal,
    private val after: Float = 0f,
) : VoiceProbability {
    private var index = 0
    @Volatile var closed = 0
        private set

    override fun probability(frame: ShortArray): Float = script.getOrElse(index++) { after }

    override fun close() {
        closed++
        journal.add("vad.close")
    }
}

/** A [MicSilenceMonitor] that reports silenced from the [silencedFromCheck]-th call of [isSilenced] (0-based). */
class FakeMicSilenceMonitor(private val journal: Journal, var silencedFromCheck: Int = Int.MAX_VALUE) : MicSilenceMonitor {
    @Volatile var watching: Int? = null
        private set
    @Volatile var stops = 0
        private set
    private var checks = 0

    override fun start(sessionId: Int) {
        watching = sessionId
        journal.add("monitor.start($sessionId)")
    }

    override fun isSilenced(): Boolean = watching != null && checks++ >= silencedFromCheck

    override fun stop() {
        watching = null
        stops++
        journal.add("monitor.stop")
    }
}

/** A [FocusRequester] that answers [answer] and lets the test deliver focus changes with [change]. */
class FakeFocusRequester(var answer: Int) : FocusRequester {
    var requests = 0
        private set
    var abandons = 0
        private set
    private var listener: ((Int) -> Unit)? = null

    override fun request(onChange: (Int) -> Unit): Int {
        requests++
        listener = onChange
        return answer
    }

    override fun abandon() {
        abandons++
    }

    /** A change from the system; delivered even after abandon, as a late callback could be. */
    fun change(value: Int) {
        listener?.invoke(value)
    }
}

/** [AudioFocusGate] fake for loop tests (A10): grants while [grant], records calls, [emit] sends an event. */
class FakeAudioFocusGate(var grant: Boolean = true) : AudioFocusGate {
    private val channel = Channel<FocusEvent>(Channel.UNLIMITED)
    override val events: Flow<FocusEvent> = channel.receiveAsFlow()
    var held = false
        private set
    var acquires = 0
        private set

    override fun acquire(): FocusResult {
        acquires++
        held = grant
        return if (grant) FocusResult.Granted else FocusResult.Denied
    }

    override fun release() {
        held = false
    }

    fun emit(event: FocusEvent) {
        channel.trySend(event)
    }
}

/** [AudioCapture] fake for loop tests (A10): records starts and stops, [emit] sends an event. */
class FakeAudioCapture : AudioCapture {
    private val channel = Channel<CaptureEvent>(Channel.UNLIMITED)
    override val events: Flow<CaptureEvent> = channel.receiveAsFlow()
    var running = false
        private set
    var starts = 0
        private set
    var finishes = 0
        private set

    override fun start(config: dev.nittala.voicegateway.core.speech.DetectorConfig): Boolean {
        if (running) return false
        running = true
        starts++
        return true
    }

    override fun finishUtterance() {
        finishes++
    }

    override fun stop() {
        running = false
    }

    fun emit(event: CaptureEvent) {
        if (event.endsSession) running = false
        channel.trySend(event)
    }
}
