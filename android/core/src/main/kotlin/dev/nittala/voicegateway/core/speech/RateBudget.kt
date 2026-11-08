package dev.nittala.voicegateway.core.speech

/** What [RateBudget.check] allows now. */
sealed interface BudgetDecision {
    /** Upload now. */
    data object Go : BudgetDecision

    /** An upload is in flight: uploads never overlap. Ask again when it finishes. */
    data object InFlight : BudgetDecision

    /** Not before [atMs] (the caller's clock): the rolling-minute limit or a server `Retry-After`. */
    data class WaitUntil(val atMs: Long, val why: WaitReason) : BudgetDecision
}

enum class WaitReason { Rate, RetryAfter }

/**
 * Spec (A5, plan section 2): the app's own transcribe budget, below the server's 12 a minute.
 * - At most [limit] upload starts in any rolling [windowMs] (a start at t counts until t + windowMs, exclusive).
 * - One upload in flight at a time.
 * - A 429's `Retry-After` blocks starts until now + seconds; a later, shorter one never shortens the block.
 * Pure and immutable: every call takes the caller's time in ms and returns a new budget. Starts outside the window
 * are dropped on every update, so the budget stays small. Order of precedence in [check]: in flight, then
 * Retry-After, then the rate (the later of the two waits wins when both apply).
 */
data class RateBudget(
    val limit: Int = 10,
    val windowMs: Long = 60_000,
    val starts: List<Long> = emptyList(),
    val inFlight: Boolean = false,
    val blockedUntilMs: Long = 0,
) {
    fun check(nowMs: Long): BudgetDecision {
        if (inFlight) return BudgetDecision.InFlight
        val recent = recent(nowMs)
        val rateAt = if (recent.size >= limit) recent[recent.size - limit] + windowMs else 0L
        return when {
            blockedUntilMs > nowMs && blockedUntilMs >= rateAt -> BudgetDecision.WaitUntil(blockedUntilMs, WaitReason.RetryAfter)
            rateAt > nowMs -> BudgetDecision.WaitUntil(rateAt, WaitReason.Rate)
            else -> BudgetDecision.Go
        }
    }

    /** Records a start at [nowMs]. Throws [IllegalStateException] unless [check] says [BudgetDecision.Go]. */
    fun started(nowMs: Long): RateBudget {
        val decision = check(nowMs)
        kotlin.check(decision == BudgetDecision.Go) { "upload not allowed now: $decision" }
        return copy(starts = recent(nowMs) + nowMs, inFlight = true)
    }

    /** The upload in flight is over (any outcome). */
    fun finished(): RateBudget = copy(inFlight = false)

    /** A 429 asked to wait [seconds] (negative is treated as 0). */
    fun retryAfter(nowMs: Long, seconds: Int): RateBudget =
        copy(starts = recent(nowMs), blockedUntilMs = maxOf(blockedUntilMs, nowMs + seconds.coerceAtLeast(0) * 1_000L))

    /** Starts still inside the window at [nowMs], oldest first. */
    private fun recent(nowMs: Long): List<Long> = starts.filter { it + windowMs > nowMs }
}
