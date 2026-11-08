package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.audio.CaptureStats
import dev.nittala.voicegateway.core.api.ApiResult
import dev.nittala.voicegateway.core.model.CreateAnswer
import dev.nittala.voicegateway.core.model.PlanAnswer
import dev.nittala.voicegateway.core.model.TranscribeAnswer
import dev.nittala.voicegateway.core.speech.TranscriptVerdict
import java.util.Locale

/**
 * Pure: the DEBUG diagnostic line of a capture result, numbers and the [code] only (a [CaptureRejection][
 * dev.nittala.voicegateway.audio.CaptureRejection] name, `Ready`, `Listening`). How to read it:
 * - `rms`/`peak`: G1's numbers in dBFS, of the speech span only (no pre-roll, no tail); G1 needs rms > -55 and
 *   peak > -40.
 * - `loud`: the loudest single 32 ms frame of the session (the mic's level at its best).
 * - `speech`/`voiced`/`share`: G2's numbers; G2 needs voiced >= 800 ms and share >= 0.40.
 * - `pmax`: the highest Silero probability seen; speech starts after 3 frames in a row >= 0.60.
 * - `frames`: frames fed to the detector (32 ms each).
 * `-` is no value (still listening), `-inf` digital silence.
 */
fun captureDebugLine(code: String, s: CaptureStats): String =
    "cap $code rms=${db(s.rmsDbfs)} peak=${db(s.peakDbfs)} loud=${db(s.loudestFrameDbfs)} " +
        "speech=${s.speechMs}ms voiced=${s.voicedMs}ms share=${fixed(s.voicedShare, 2)} " +
        "pmax=${fixed(s.maxProbability.toDouble(), 2)} frames=${s.framesFed}"

/**
 * Pure: the DEBUG diagnostic line of a transcribe [result]: HTTP status, the refusal reason's name (never the
 * server's message), and for a transcript only its character and word counts and the [guard]'s verdict. Never the
 * transcript itself.
 */
fun transcriptDebugLine(result: ApiResult<TranscribeAnswer>, guard: TranscriptVerdict?): String = when (result) {
    is ApiResult.Ok -> {
        val text = result.value.text
        val words = text.split(WHITESPACE).count { word -> word.any(Char::isLetterOrDigit) }
        val verdict = when (guard) {
            null -> "-"
            TranscriptVerdict.Accepted -> "ok"
            is TranscriptVerdict.Rejected -> guard.reason.name
        }
        "tx http=200 reason=- chars=${text.length} words=$words guard=$verdict"
    }
    is ApiResult.Refused -> "tx http=${result.refusal.httpStatus} reason=${result.refusal.reason?.name ?: "-"}"
    ApiResult.LoggedOut -> "tx http=401 reason=LoggedOut"
    ApiResult.NetworkFailure -> "tx http=- reason=Network"
    ApiResult.MaybeSent -> "tx http=- reason=MaybeSent"
    is ApiResult.Unexpected -> "tx http=${result.httpStatus} reason=Unexpected"
}

private val WHITESPACE = Regex("\\s+")

private fun db(value: Double): String = when {
    value.isNaN() -> "-"
    value == Double.NEGATIVE_INFINITY -> "-inf"
    else -> fixed(value, 1)
}

private fun fixed(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

/**
 * Pure (W6): the DEBUG line of a new-worktree [call] (`plan`, `create`): HTTP status, the refusal reason's name, and
 * for an answer only its shape in codes and counts (kind, mode, ready, how many choices or candidates, listed, base
 * kind, moved, launch present). Never the request, a repo, branch, path, sha, session name or the server's message.
 */
fun worktreeDebugLine(call: String, result: ApiResult<Any>): String = "wt $call " + when (result) {
    is ApiResult.Ok -> when (val v = result.value) {
        is PlanAnswer.Plan -> "http=200 kind=plan mode=${modeCode(v.mode)} ready=${v.ready} choices=${v.base.choices.size}"
        is PlanAnswer.Ask -> "http=200 kind=ask understood=${v.understood} candidates=${v.candidates.size}"
        is CreateAnswer -> "http=200 listed=${v.listed} base=${if (v.base == null) "existing" else "new"} " +
            "moved=${v.base?.moved == true} launch=${if (v.launch == null) "no" else "yes"}"
        else -> "http=200"
    }
    is ApiResult.Refused ->
        "http=${result.refusal.httpStatus} reason=${result.refusal.reason?.name ?: "-"} offline=${if (result.refusal.offline != null) "yes" else "no"}"
    ApiResult.LoggedOut -> "http=401 reason=LoggedOut"
    ApiResult.NetworkFailure -> "http=- reason=Network"
    ApiResult.MaybeSent -> "http=- reason=MaybeSent"
    is ApiResult.Unexpected -> "http=${result.httpStatus} reason=Unexpected"
}

/** Only the two known modes are shown; anything else is `other`, so no server text reaches the line. */
private fun modeCode(mode: String) = if (mode == "new" || mode == "existing") mode else "other"
