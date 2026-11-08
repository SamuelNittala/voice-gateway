package dev.nittala.voicegateway.core.speech

/** A voice for an event. [wire] is the gateway's `engine` name (`phone` is the phone's own TTS, never sent). */
enum class Engine(val wire: String, val label: String) {
    Chatterbox("chatterbox", "Chatterbox"),
    Deepgram("deepgram", "Deepgram"),
    Phone("phone", "the phone's voice"),
}

/**
 * Spec (A5, plan section 4; mirrors the page's `engineChain`/`playAudio` in static/app.js): the voices to try for
 * ONE event, in order, each at most once.
 * - [of]: the gateway's `voices` (unknown names and duplicates ignored) and the user's choice (null: the gateway's
 *   default, which is Chatterbox first). Chatterbox chosen -> Chatterbox, Deepgram; Deepgram chosen -> Deepgram;
 *   Phone chosen -> nothing from the gateway. Only engines listed in `voices` stay. [Engine.Phone] always ends it.
 *   A chosen gateway engine that is not listed counts as already failed (for [note]).
 * - [current]: the engine to try now; null once every engine failed (give up and acknowledge).
 * - [failed]: the current engine failed; moves on once. Failing an engine that is not [current] (a repeat, a late
 *   report) changes nothing, so no engine is ever tried twice.
 * - [note]: "Chatterbox unavailable, used Deepgram" once an engine after a failure is in use; "" when the first
 *   choice is in use.
 */
data class EngineChain(val order: List<Engine>, val position: Int = 0, val unavailable: List<Engine> = emptyList()) {
    val current: Engine? get() = order.getOrNull(position)

    fun failed(engine: Engine): EngineChain =
        if (engine != current) this else copy(position = position + 1, unavailable = unavailable + engine)

    fun note(): String {
        val used = current ?: return ""
        if (unavailable.isEmpty()) return ""
        return "${unavailable.joinToString(" and ") { it.label }} unavailable, used ${used.label}"
    }

    companion object {
        private val SERVER = listOf(Engine.Chatterbox, Engine.Deepgram)

        fun of(voices: List<String>, choice: Engine? = null): EngineChain {
            val listed = SERVER.filter { engine -> engine.wire in voices }.toSet()
            val wanted = when (choice) {
                null, Engine.Chatterbox -> listOf(Engine.Chatterbox, Engine.Deepgram)
                Engine.Deepgram -> listOf(Engine.Deepgram)
                Engine.Phone -> emptyList()
            }
            val missing = listOfNotNull(choice?.takeIf { it != Engine.Phone && it !in listed })
            return EngineChain(order = wanted.filter { it in listed } + Engine.Phone, unavailable = missing)
        }
    }
}
