package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.core.api.ApiResult
import dev.nittala.voicegateway.core.api.GatewayApi
import dev.nittala.voicegateway.core.model.SessionView
import dev.nittala.voicegateway.core.model.SessionsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Failure backoff of both polls (plan section 5): 2, 4, 8, then 15 s for every further failure in a row. */
val POLL_BACKOFF_MS: List<Long> = listOf(2_000, 4_000, 8_000, 15_000)

/** The sessions list is polled every 5 s while the app is visible (the picker's data). */
const val SESSIONS_POLL_MS: Long = 5_000

/**
 * Pure: the wait before the next poll after [failures] failed polls in a row: [intervalMs] when the last poll worked,
 * else the backoff step for that many failures, never shorter than [intervalMs].
 */
fun pollDelayMs(intervalMs: Long, failures: Int): Long =
    if (failures <= 0) intervalMs else maxOf(intervalMs, POLL_BACKOFF_MS[minOf(failures, POLL_BACKOFF_MS.size) - 1])

/**
 * Spec (A10, plan section 5): polls the existing `GET /api/session?name=<worktree>` and `GET /api/sessions`; no new
 * endpoint (the server only makes events while someone polls).
 *
 * - Session poll: every [setInterval] ms (the loop's `SetPoll`: 2 s while a send waits or the agent works, 5 s
 *   otherwise, none when the loop is off in the background), for [setWorktree] (none while it is blank). Setting a
 *   different interval or worktree polls at once, then on the new schedule.
 * - Sessions poll: every [SESSIONS_POLL_MS] while [setSessionsWanted] is on (the app is visible).
 * - Each poll's answer: Ok -> the listener; [ApiResult.LoggedOut] (401) -> the listener's [Listener.onLoggedOut]
 *   once and both polls stop until set again; anything else counts as a failure and the next poll waits
 *   [pollDelayMs]. Never two polls of the same kind at once.
 * - [stop] ends both. The api is read through [api] on every poll (login may replace it); null skips the poll.
 *
 * Call from one thread (the controller's).
 */
class Poller(
    private val api: () -> GatewayApi?,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onView(view: SessionView)
        fun onSessions(view: SessionsView)
        fun onLoggedOut()
    }

    private var intervalMs: Long? = null
    private var worktree = ""
    private var sessionsWanted = false
    private var sessionJob: Job? = null
    private var sessionsJob: Job? = null

    fun setInterval(intervalMs: Long?) {
        if (intervalMs == this.intervalMs) return
        this.intervalMs = intervalMs
        restartSession()
    }

    fun setWorktree(name: String) {
        if (name == worktree) return
        worktree = name
        restartSession()
    }

    fun setSessionsWanted(on: Boolean) {
        if (on == sessionsWanted) return
        sessionsWanted = on
        sessionsJob?.cancel()
        sessionsJob = if (on) scope.launch { loop(SESSIONS_POLL_MS) { pollSessions() } } else null
    }

    fun stop() {
        intervalMs = null
        sessionsWanted = false
        sessionJob?.cancel()
        sessionsJob?.cancel()
        sessionJob = null
        sessionsJob = null
    }

    private fun restartSession() {
        sessionJob?.cancel()
        val interval = intervalMs
        val name = worktree
        sessionJob = if (interval != null && name.isNotBlank()) scope.launch { loop(interval) { pollSession(name) } } else null
    }

    /** Polls with [poll] until cancelled or logged out; [poll] answers true (worked), false (failed) or null (401). */
    private suspend fun loop(intervalMs: Long, poll: suspend () -> Boolean?) {
        var failures = 0
        while (true) {
            when (poll()) {
                null -> return loggedOut()
                true -> failures = 0
                false -> failures++
            }
            delay(pollDelayMs(intervalMs, failures))
        }
    }

    private suspend fun pollSession(name: String): Boolean? = when (val r = api()?.session(name) ?: return false) {
        is ApiResult.Ok -> {
            listener.onView(r.value)
            true
        }
        ApiResult.LoggedOut -> null
        else -> false
    }

    private suspend fun pollSessions(): Boolean? = when (val r = api()?.sessions() ?: return false) {
        is ApiResult.Ok -> {
            listener.onSessions(r.value)
            true
        }
        ApiResult.LoggedOut -> null
        else -> false
    }

    private fun loggedOut() {
        val reported = intervalMs != null || sessionsWanted
        val current = listOf(sessionJob, sessionsJob)
        stop()
        if (reported) listener.onLoggedOut()
        current.forEach { it?.cancel() }
    }
}
