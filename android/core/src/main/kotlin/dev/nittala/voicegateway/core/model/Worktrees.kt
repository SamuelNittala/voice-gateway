package dev.nittala.voicegateway.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * Spec (W6, plan section 5 ":core additions"): the new-worktree views of gateway.py, as the server writes them (see
 * TASKS.md "New worktree from the phone", "Base branch for new worktrees" and "Orchestrator launch and new worktree
 * from the app"). Fixtures come from the real views via android/tools/make_fixtures.py `worktrees()`.
 *
 * Same rules as Models.kt: unknown keys are ignored; a key the app depends on has no default, so a view without it
 * fails to parse. Values the server may extend (`mode`, `how`, `trust`, `effort`) are kept as plain strings.
 */

/** One repo as the gateway lists it: [id] (a hash of its path), [name] (its folder), [where] (the folder above). */
@Serializable
data class RepoRef(val id: String, val name: String, val where: String)

/**
 * The base of a planned worktree, `base` of a plan.
 * - New branch ([applies] true): [name] and [ref] (`origin/<name>`) are null until a base is picked; [how] is
 *   `named`, `remembered`, `origin-head` or null; [sha] is the short sha; [subject] only when [local] (the copy is
 *   downloaded here); [committedAt] / [fetchedAt] are epoch seconds or null; [said] is the base as the user said it;
 *   [message] says why no base was picked ("" when one was); [choices] are the base names offered as taps.
 * - Existing branch ([applies] false): [ref] is the branch itself, [sha] and [subject] its commit, [message] says the
 *   base does not apply. The server sends none of the other keys: [local] then reads as true (the branch is here).
 */
@Serializable
data class PlanBase(
    val applies: Boolean,
    val ref: String?,
    val sha: String?,
    val subject: String?,
    val message: String,
    val name: String? = null,
    val how: String? = null,
    @SerialName("committed_at") val committedAt: Long? = null,
    @SerialName("fetched_at") val fetchedAt: Long? = null,
    val local: Boolean = true,
    val said: String? = null,
    val choices: List<String> = emptyList(),
)

/** `POST /api/worktree/plan`: a plan to show on the card, or a question with repos to tap. */
sealed interface PlanAnswer {
    /**
     * `kind: "plan"`. [mode] is `new` or `existing` (anything else is kept as given); [ready] when Create may be
     * tapped (an existing branch, or a base picked); [path] is shown as `~/...`; [expiresIn] seconds until the server
     * forgets the plan. [planId] stays in the loop state, never in a UI model.
     */
    @Serializable
    data class Plan(
        @SerialName("plan_id") val planId: String,
        val repo: RepoRef,
        val branch: String,
        val mode: String,
        val ready: Boolean,
        val base: PlanBase,
        val path: String,
        @SerialName("expires_in") val expiresIn: Int,
    ) : PlanAnswer {
        val existing: Boolean get() = mode == "existing"
    }

    /**
     * `kind: "ask"`. [understood] is false when the request could not be read at all ([reason] says why, in the
     * server's words); then every repo is a candidate. Candidates are taps only.
     */
    @Serializable
    data class Ask(
        val understood: Boolean,
        val question: String,
        val candidates: List<RepoRef>,
        val reason: String? = null,
    ) : PlanAnswer
}

/**
 * Pure: the [PlanAnswer] in a plan body. `kind` decides; a body with no `kind`, an unknown `kind` or missing keys
 * throws [SerializationException] (the client then answers `Unexpected`).
 */
fun planAnswerOf(body: String): PlanAnswer {
    val root = try {
        GatewayJson.parseToJsonElement(body).jsonObject
    } catch (e: IllegalArgumentException) {
        throw SerializationException("not a JSON object")
    }
    val kind = (root["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (kind) {
        "plan" -> GatewayJson.decodeFromJsonElement(PlanAnswer.Plan.serializer(), root)
        "ask" -> GatewayJson.decodeFromJsonElement(PlanAnswer.Ask.serializer(), root)
        else -> throw SerializationException("unknown plan kind")
    }
}

/** `base` of a create answer for a new branch (null for an existing one): where the worktree really started. */
@Serializable
data class CreatedBase(
    val ref: String,
    val sha: String,
    val subject: String,
    val fetched: Boolean,
    val moved: Boolean,
    @SerialName("planned_sha") val plannedSha: String,
)

/**
 * `launch` of a create answer: what `wt` started the orchestrator with, from its `wt-launch:` line. [model] and
 * [effort] are an id/level or `default`; [trust] is `written`, `already`, `skipped` or `off`.
 */
@Serializable
data class Launch(
    val model: String,
    val effort: String,
    val playbook: Boolean,
    val settings: Boolean,
    val trust: String,
)

/**
 * `POST /api/worktree/create` success. [session] is the new Herdr session (the first prompt goes there and nowhere
 * else); [listed] when Herdr already lists it; [base] null for an existing branch; [launch] null when `wt` printed
 * no launch line (or an older gateway sent no key).
 */
@Serializable
data class CreateAnswer(
    val ok: Boolean,
    val session: String,
    val listed: Boolean,
    val path: String,
    val base: CreatedBase?,
    val launch: Launch? = null,
)

/** `GET /api/worktree/setup`. [message] is "" when all is set up. */
@Serializable
data class WorktreeSetup(val enabled: Boolean, val interpreter: Boolean, val repos: Int, val message: String)

/**
 * `offline` of a `fetch_failed` refusal: a plan that creates from the copy already downloaded, without fetching.
 * Offered as its own tap, never taken automatically.
 */
@Serializable
data class OfflinePlan(
    @SerialName("plan_id") val planId: String,
    val ref: String,
    val sha: String,
    val subject: String,
    @SerialName("fetched_at") val fetchedAt: Long? = null,
    @SerialName("committed_at") val committedAt: Long? = null,
)

/** Pure: [OfflinePlan] from a refusal body's `offline` object, or null when it is absent or malformed. */
internal fun offlineOf(value: JsonObject?): OfflinePlan? = value?.let {
    try {
        GatewayJson.decodeFromJsonElement(OfflinePlan.serializer(), it)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
