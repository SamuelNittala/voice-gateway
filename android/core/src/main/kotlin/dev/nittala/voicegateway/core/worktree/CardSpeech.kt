package dev.nittala.voicegateway.core.worktree

import dev.nittala.voicegateway.core.model.PlanAnswer

/**
 * Spec (W6, plan section 5 "cardSpeech(plan)"): what the phone says when the plan card appears, so the user has heard
 * what they confirm before the mic opens. Pure; at most [MAX_CHARS] characters; never a path or a sha (the subject's
 * words that look like a sha or a path are left out).
 * - New branch, ready: "New branch <branch> in <repo>, from origin <base>: <subject cut to 80 at a word>. Say create
 *   it, or cancel." With no subject (not downloaded) the ": <subject>" part is left out.
 * - Existing branch: "Existing branch <branch> in <repo>, checked out as it is. Say create it, or cancel."
 * - Not ready: "<repo>, branch <branch>, needs a base branch. Pick one on the screen."
 * - "On origin, not downloaded yet." goes before the last sentence when a new branch's base is not local.
 * When a long branch or repo name would pass [MAX_CHARS], the first part is cut at a word; the last sentence stays.
 */
object CardSpeech {
    const val MAX_CHARS = 250
    const val SUBJECT_CHARS = 80
    const val SAY_CREATE = "Say create it, or cancel."
    const val PICK_BASE = "Pick one on the screen."
    const val NOT_DOWNLOADED = "On origin, not downloaded yet."

    fun of(plan: PlanAnswer.Plan): String {
        val base = plan.base
        val (first, last) = when {
            plan.existing -> "Existing branch ${plan.branch} in ${plan.repo.name}, checked out as it is." to SAY_CREATE
            !plan.ready -> "${plan.repo.name}, branch ${plan.branch}, needs a base branch." to PICK_BASE
            else -> {
                val subject = base.subject?.let(::spokenSubject)?.takeIf { it.isNotEmpty() && base.local }
                val from = "New branch ${plan.branch} in ${plan.repo.name}, from origin ${base.name.orEmpty()}"
                val head = if (subject != null) "$from: $subject." else "$from."
                (if (base.local) head else "$head $NOT_DOWNLOADED") to SAY_CREATE
            }
        }
        return "${cutAtWord(first, MAX_CHARS - last.length - 1)} $last"
    }

    /** Pure: [text] cut to at most [max] characters at a word boundary (no cut inside a word unless it is one word). */
    fun cutAtWord(text: String, max: Int): String {
        if (text.length <= max) return text
        val space = text.lastIndexOf(' ', max)
        return (if (space > 0) text.substring(0, space) else text.substring(0, max)).trimEnd(' ', ',', ':', ';')
    }

    /** The subject without words that look like a sha or a path, cut to [SUBJECT_CHARS] at a word. */
    private fun spokenSubject(subject: String): String = cutAtWord(
        subject.split(' ').filter { it.isNotEmpty() && !SHA.matches(it.trim(',', '.', ')', '(')) && !isPath(it) }
            .joinToString(" ").trim().trimEnd('.'),
        SUBJECT_CHARS,
    )

    private fun isPath(word: String) = word.startsWith("/") || word.startsWith("~") || word.startsWith("./")

    private val SHA = Regex("(?=.*[0-9])[0-9a-fA-F]{7,40}")
}
