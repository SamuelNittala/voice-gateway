package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.audio.CaptureStats
import dev.nittala.voicegateway.core.api.ApiResult
import dev.nittala.voicegateway.core.api.Refusal
import dev.nittala.voicegateway.core.api.RefusalReason
import dev.nittala.voicegateway.core.model.CreateAnswer
import dev.nittala.voicegateway.core.model.TranscribeAnswer
import dev.nittala.voicegateway.core.model.planAnswerOf
import dev.nittala.voicegateway.core.speech.TranscriptRejection
import dev.nittala.voicegateway.core.speech.TranscriptVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Spec (debug diagnostics): the DEBUG line is numbers and reason codes only, never text that was spoken. */
class DebugLinesTest {
    private val stats = CaptureStats(
        framesFed = 212,
        maxProbability = 0.987f,
        loudestFrameDbfs = -38.24,
        rmsDbfs = -47.31,
        peakDbfs = -31.06,
        speechMs = 2400,
        voicedMs = 1920,
        voicedShare = 0.8,
    )

    @Test
    fun captureLineShowsEveryNumber() {
        assertEquals(
            "cap TooQuiet rms=-47.3 peak=-31.1 loud=-38.2 speech=2400ms voiced=1920ms share=0.80 pmax=0.99 frames=212",
            captureDebugLine("TooQuiet", stats),
        )
    }

    @Test
    fun silenceAndMissingValuesAreMarked() {
        val silent = stats.copy(rmsDbfs = Double.NaN, peakDbfs = Double.NEGATIVE_INFINITY, loudestFrameDbfs = Double.NEGATIVE_INFINITY)
        assertEquals(
            "cap Listening rms=- peak=-inf loud=-inf speech=2400ms voiced=1920ms share=0.80 pmax=0.99 frames=212",
            captureDebugLine("Listening", silent),
        )
    }

    @Test
    fun transcriptLineHasCountsAndTheGuardReason() {
        assertEquals(
            "tx http=200 reason=- chars=21 words=4 guard=ok",
            transcriptDebugLine(ApiResult.Ok(TranscribeAnswer("launch the rocket now")), TranscriptVerdict.Accepted),
        )
        assertEquals(
            "tx http=200 reason=- chars=9 words=2 guard=TooFast",
            transcriptDebugLine(
                ApiResult.Ok(TranscribeAnswer("hello you")),
                TranscriptVerdict.Rejected(TranscriptRejection.TooFast),
            ),
        )
    }

    @Test
    fun transcriptLineNeverContainsTheTranscript() {
        val texts = listOf(
            "launch the rocket now", "passcode hunter2", "Привет мир", "/yes", "zebra", "  spaced   phrases  ",
            "12345 67890", "rocket",
        )
        for (text in texts) {
            val line = transcriptDebugLine(ApiResult.Ok(TranscribeAnswer(text)), TranscriptVerdict.Accepted)
            for (word in text.split(Regex("\\s+")).filter { it.length >= 3 }) {
                assertFalse("'$word' leaked into: $line", line.contains(word, ignoreCase = true))
            }
        }
    }

    @Test
    fun refusalsShowStatusAndReasonButNotTheServerMessage() {
        val refused = ApiResult.Refused(Refusal(429, RefusalReason.TranscribeRate, "slow down secretword", retryAfterSeconds = 7))
        val line = transcriptDebugLine(refused, null)
        assertEquals("tx http=429 reason=TranscribeRate", line)
        assertEquals("tx http=403 reason=-", transcriptDebugLine(ApiResult.Refused(Refusal(403, null, "nope")), null))
    }

    @Test
    fun otherOutcomesHaveTheirOwnCodes() {
        assertEquals("tx http=- reason=Network", transcriptDebugLine(ApiResult.NetworkFailure, null))
        assertEquals("tx http=401 reason=LoggedOut", transcriptDebugLine(ApiResult.LoggedOut, null))
        assertEquals("tx http=502 reason=Unexpected", transcriptDebugLine(ApiResult.Unexpected(502), null))
        assertEquals("tx http=- reason=MaybeSent", transcriptDebugLine(ApiResult.MaybeSent, null))
    }

    // ---- W6: the new-worktree flow: codes and counts only -----------------------------------------------------------

    private fun plan(name: String) = GatewayFixtures.result(name, decode = ::planAnswerOf)
    private fun create(name: String) = GatewayFixtures.result(name) {
        dev.nittala.voicegateway.core.model.GatewayJson.decodeFromString(CreateAnswer.serializer(), it)
    }

    @Test
    fun worktreeLinesAreCodesAndCountsOnly() {
        assertEquals("wt plan http=200 kind=plan mode=new ready=true choices=3", worktreeDebugLine("plan", plan("plan_new_ready")))
        assertEquals("wt plan http=200 kind=ask understood=true candidates=2", worktreeDebugLine("plan", plan("plan_ask_candidates")))
        assertEquals("wt create http=200 listed=true base=new moved=false launch=yes", worktreeDebugLine("create", create("create_ok_launch")))
        assertEquals("wt create http=200 listed=true base=existing moved=false launch=no", worktreeDebugLine("create", create("create_ok_existing")))
        assertEquals("wt create http=502 reason=FetchFailed offline=yes", worktreeDebugLine("create", create("refusal_fetch_failed_offline")))
        assertEquals("wt plan http=409 reason=PlanBusy offline=no", worktreeDebugLine("plan", ApiResult.Refused(Refusal(409, RefusalReason.PlanBusy, "x"))))
        assertEquals("wt create http=- reason=MaybeSent", worktreeDebugLine("create", ApiResult.MaybeSent))
        assertEquals("wt plan http=- reason=Network", worktreeDebugLine("plan", ApiResult.NetworkFailure))
        assertEquals("wt plan http=401 reason=LoggedOut", worktreeDebugLine("plan", ApiResult.LoggedOut))
        assertEquals("wt plan http=200 reason=Unexpected", worktreeDebugLine("plan", ApiResult.Unexpected(200)))
    }

    @Test
    fun worktreeLinesNeverHoldTheRequestRepoBranchPathOrMessage() {
        val results = listOf(plan("plan_new_ready"), plan("plan_existing"), plan("plan_ask_candidates"),
            create("create_ok"), create("refusal_fetch_failed_offline"), create("refusal_repo_changed"))
        for (r in results) {
            val line = worktreeDebugLine("x", r)
            for (word in listOf("web-app", "notes", "fix/login", "~/", "origin/", "production", "wt-web", "Could not", "changed")) {
                assertFalse("$line has $word", line.contains(word))
            }
        }
    }
}
