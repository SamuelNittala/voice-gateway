package dev.nittala.voicegateway.core.ui

import dev.nittala.voicegateway.core.config.BaseUrlRefusal
import dev.nittala.voicegateway.core.config.BuildVariant
import dev.nittala.voicegateway.core.loop.AgentView
import dev.nittala.voicegateway.core.loop.BLOCKED_AGENT
import dev.nittala.voicegateway.core.loop.CONFIRM
import dev.nittala.voicegateway.core.loop.ErrorKind
import dev.nittala.voicegateway.core.loop.GOOD
import dev.nittala.voicegateway.core.loop.HeldReason
import dev.nittala.voicegateway.core.loop.IDLE_AGENT
import dev.nittala.voicegateway.core.loop.LoopState
import dev.nittala.voicegateway.core.loop.OTHER
import dev.nittala.voicegateway.core.loop.PauseReason
import dev.nittala.voicegateway.core.loop.Phase
import dev.nittala.voicegateway.core.loop.QUESTION
import dev.nittala.voicegateway.core.loop.Sent
import dev.nittala.voicegateway.core.loop.TEXT
import dev.nittala.voicegateway.core.loop.WORKING_AGENT
import dev.nittala.voicegateway.core.loop.WT
import dev.nittala.voicegateway.core.loop.event
import dev.nittala.voicegateway.core.loop.st
import dev.nittala.voicegateway.core.loop.view
import dev.nittala.voicegateway.core.model.Actions
import dev.nittala.voicegateway.core.model.AgentRow
import dev.nittala.voicegateway.core.model.SessionRow
import dev.nittala.voicegateway.core.model.SessionsView
import dev.nittala.voicegateway.core.speech.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceUiMapperTest {
    /** One state per phase: the table every rule below is checked against. */
    private val everyPhase: Map<Phase, VoiceMode> = mapOf(
        Phase.Idle to VoiceMode.Idle,
        Phase.Listening to VoiceMode.Listening,
        Phase.AwaitingAnswer(QUESTION) to VoiceMode.AwaitingAnswer,
        Phase.AwaitingTap(CONFIRM) to VoiceMode.AwaitingTap,
        Phase.Capturing(WT) to VoiceMode.Capturing,
        Phase.Transcribing(WT, GOOD) to VoiceMode.Transcribing,
        Phase.Sending(WT, TEXT) to VoiceMode.Sending,
        Phase.AgentWorking(Sent(TEXT)) to VoiceMode.Working,
        Phase.Speaking(event("e1"), Phase.Listening) to VoiceMode.Speaking,
        Phase.NeedsYou("tok1", "1. Yes\n2. No") to VoiceMode.NeedsYou,
        Phase.Held(TEXT, HeldReason.AgentBusy) to VoiceMode.Held,
        Phase.Paused(PauseReason.User) to VoiceMode.Paused,
        Phase.Stopped() to VoiceMode.Stopped,
        Phase.Error(ErrorKind.PaneChanged, "The pane changed") to VoiceMode.Error,
        Phase.LoggedOut to VoiceMode.LoggedOut,
        Phase.NewWorktree(dev.nittala.voicegateway.core.worktree.WorktreeFlow.Request(listening = false)) to VoiceMode.NewWorktree,
    )

    private fun ui(phase: Phase, agent: AgentView = IDLE_AGENT) = voiceUiState(st(phase, agent))

    @Test
    fun `every phase maps to its own mode with a title`() {
        assertEquals("one sample per phase", 16, everyPhase.size)
        assertEquals(VoiceMode.entries.toSet(), everyPhase.values.toSet())
        everyPhase.forEach { (phase, mode) ->
            val s = ui(phase)
            assertEquals(phase.toString(), mode, s.mode)
            assertTrue(phase.toString(), s.title.isNotBlank())
            assertTrue(phase.toString(), s.announcement.isNotBlank())
            assertEquals(WT, s.worktree)
        }
    }

    @Test
    fun `Yes and No exist only in the tap-only confirmation`() {
        everyPhase.forEach { (phase, mode) ->
            assertEquals(phase.toString(), mode == VoiceMode.AwaitingTap, ui(phase).confirmButtons)
        }
        val s = ui(Phase.AwaitingTap(CONFIRM))
        assertEquals(CONFIRM.text, s.question)
        assertFalse("mic is off while waiting for a tap", s.micOn)
    }

    @Test
    fun `keys only when the agent is blocked with a screen token`() {
        everyPhase.forEach { (phase, mode) ->
            val keys = ui(phase, if (mode == VoiceMode.NeedsYou) BLOCKED_AGENT else WORKING_AGENT).keys
            assertEquals(phase.toString(), mode == VoiceMode.NeedsYou, keys.isNotEmpty())
        }
        assertEquals(VoiceUiState.ALLOWED_KEYS, ui(Phase.NeedsYou("tok1", "x"), BLOCKED_AGENT).keys)
        assertTrue("no token, no keys", ui(Phase.NeedsYou(null, "x"), BLOCKED_AGENT).keys.isEmpty())
    }

    @Test
    fun `keys follow the gateway's allowed list in display order and drop unknown ones`() {
        val v = view(status = "blocked", token = "tok1")
            .copy(actions = Actions(prompt = false, keys = listOf("esc", "1", "delete", "enter"), stop = true, restart = false))
        val s = voiceUiState(st(Phase.NeedsYou("tok1", "x"), BLOCKED_AGENT), view = v)
        assertEquals(listOf("1", "enter", "esc"), s.keys)
    }

    @Test
    fun `a view of another worktree does not change the keys`() {
        val v = view(status = "blocked", token = "t", name = OTHER)
            .copy(actions = Actions(prompt = false, keys = listOf("1"), stop = true, restart = false))
        assertEquals(VoiceUiState.ALLOWED_KEYS, voiceUiState(st(Phase.NeedsYou("tok1", "x"), BLOCKED_AGENT), view = v).keys)
    }

    @Test
    fun `Stop only while the agent works`() {
        everyPhase.forEach { (phase, mode) ->
            assertEquals(phase.toString(), mode == VoiceMode.Working, ui(phase, IDLE_AGENT).canStop)
        }
        assertTrue("held while the agent is busy", ui(Phase.Held(TEXT, HeldReason.AgentBusy), WORKING_AGENT).canStop)
        assertFalse(ui(Phase.Stopped(), AgentView(state = "stopped")).canStop)
        assertFalse(ui(Phase.LoggedOut, WORKING_AGENT).canStop)
    }

    @Test
    fun `Restart only in Stopped and not while restarting`() {
        everyPhase.forEach { (phase, mode) ->
            assertEquals(phase.toString(), mode == VoiceMode.Stopped, ui(phase).canRestart)
        }
        assertFalse(ui(Phase.Stopped(restarting = true)).canRestart)
    }

    @Test
    fun `held text is shown with its reason`() {
        val s = ui(Phase.Held(TEXT, HeldReason.DraftAtMac))
        assertNotNull(s.held)
        val held = s.held!!
        assertEquals(TEXT, held.text)
        assertTrue(held.reason.contains("Mac"))
        assertTrue(held.canSendNow)
        HeldReasons.all.forEach { reason ->
            val h = ui(Phase.Held("x", reason)).held!!
            assertTrue(reason.toString(), h.reason.isNotBlank())
        }
        assertTrue(ui(Phase.Held(TEXT, HeldReason.Refused("server text"))).held!!.reason.contains("server text"))
    }

    @Test
    fun `held without text cannot be sent`() {
        val held = ui(Phase.Held(null, HeldReason.CouldNotTranscribe)).held!!
        assertNull(held.text)
        assertFalse(held.canSendNow)
        assertTrue(held.reason.isNotBlank())
    }

    @Test
    fun `held says when the agent is free again`() {
        val busy = ui(Phase.Held(TEXT, HeldReason.AgentBusy)).held!!.reason
        val free = ui(Phase.Held(TEXT, HeldReason.AgentBusy, agentFree = true)).held!!.reason
        assertTrue(free != busy)
        assertTrue(free.contains("free"))
    }

    @Test
    fun `held is null outside Held`() {
        everyPhase.forEach { (phase, mode) ->
            assertEquals(phase.toString(), mode == VoiceMode.Held, ui(phase).held != null)
        }
    }

    @Test
    fun `the mic is on only while listening and the level shows only then`() {
        val micModes = setOf(VoiceMode.Listening, VoiceMode.AwaitingAnswer, VoiceMode.Capturing)
        everyPhase.forEach { (phase, mode) ->
            val s = voiceUiState(st(phase), micLevel = 0.5f)
            assertEquals(phase.toString(), mode in micModes, s.micOn)
            assertEquals(phase.toString(), if (mode in micModes) 0.5f else 0f, s.level, 0f)
        }
        assertEquals(1f, voiceUiState(st(Phase.Listening), micLevel = 3f).level, 0f)
        assertEquals(0f, voiceUiState(st(Phase.Listening), micLevel = -1f).level, 0f)
    }

    @Test
    fun `the question is shown large while answering and in a pause that keeps it`() {
        assertEquals(QUESTION.text, ui(Phase.AwaitingAnswer(QUESTION)).question)
        assertEquals(QUESTION.text, ui(Phase.Capturing(WT, QUESTION)).question)
        assertEquals(QUESTION.text, ui(Phase.Transcribing(WT, GOOD, QUESTION)).question)
        val paused = LoopState(WT, Phase.Paused(PauseReason.Question), questions = mapOf(WT to QUESTION))
        assertEquals(QUESTION.text, voiceUiState(paused).question)
        assertNull(ui(Phase.Listening).question)
        assertNull(ui(Phase.Paused(PauseReason.User)).question)
    }

    @Test
    fun `a microphone pause says what happened and what to do`() {
        assertTrue(ui(Phase.Paused(PauseReason.MicSilenced)).detail!!.contains("silenced the microphone"))
        assertTrue(ui(Phase.Paused(PauseReason.MicUnavailable)).detail!!.contains("permission"))
    }

    @Test
    fun `orb tap per mode`() {
        val expected = mapOf(
            VoiceMode.Idle to OrbTap.Start, VoiceMode.Paused to OrbTap.Start, VoiceMode.Error to OrbTap.Start,
            VoiceMode.Speaking to OrbTap.Interrupt,
            VoiceMode.Listening to OrbTap.Tap, VoiceMode.AwaitingAnswer to OrbTap.Tap,
            VoiceMode.Capturing to OrbTap.Tap, VoiceMode.AwaitingTap to OrbTap.Tap, VoiceMode.NewWorktree to OrbTap.Tap,
        )
        everyPhase.forEach { (phase, mode) ->
            assertEquals(phase.toString(), expected[mode] ?: OrbTap.None, ui(phase).orbTap)
        }
    }

    @Test
    fun `output is copied literally, markup and all`() {
        val raw = "<b>bold</b> **not bold** &amp; <script>x</script>\n  indented"
        assertEquals(raw, ui(Phase.Idle, IDLE_AGENT.copy(output = raw)).output)
        assertEquals(raw, ui(Phase.NeedsYou("t", raw), BLOCKED_AGENT).output)
        assertEquals(raw, ui(Phase.Speaking(event("e", text = raw), Phase.Listening)).detail)
    }

    @Test
    fun `speaking another worktree's event names it`() {
        val s = ui(Phase.Speaking(event("e", session = OTHER), Phase.Listening))
        assertEquals(OTHER, s.speakingFrom)
        assertNull(ui(Phase.Speaking(event("e"), Phase.Listening)).speakingFrom)
    }

    @Test
    fun `the sheet opens by itself for a dialog and a stopped worktree`() {
        everyPhase.forEach { (phase, mode) ->
            assertEquals(phase.toString(), mode == VoiceMode.NeedsYou || mode == VoiceMode.Stopped, ui(phase).openSheet)
        }
    }

    @Test
    fun `error shows the server's message`() {
        val s = ui(Phase.Error(ErrorKind.NoOrchestrator, "no agent pane"))
        assertTrue(s.detail!!.contains("no agent pane"))
    }

    @Test
    fun `maybe-sent says so`() {
        assertTrue(ui(Phase.AgentWorking(Sent(TEXT, maybeSent = true))).detail!!.contains("May not have been sent"))
    }

    @Test
    fun `typing only when the gateway accepts a prompt for this worktree`() {
        assertTrue(voiceUiState(st(Phase.Idle), view = view(status = "idle")).canType)
        assertFalse(voiceUiState(st(Phase.Idle), view = view(status = "working")).canType)
        assertFalse(voiceUiState(st(Phase.Idle), view = view(status = "idle", name = OTHER)).canType)
        assertFalse(voiceUiState(st(Phase.LoggedOut), view = view(status = "idle")).canType)
    }

    @Test
    fun `picker lists sessions by group with agent state and marks the current one`() {
        val sessions = SessionsView(
            attached = listOf(SessionRow("wt-api", "attached", listOf(AgentRow("b", "working", orchestrator = true)))),
            running = listOf(
                SessionRow(WT, "running", listOf(AgentRow("x", "idle", false), AgentRow("c", "blocked", true))),
            ),
            stopped = listOf(SessionRow("wt-old", "stopped", error = "herdr failed")),
            events = emptyList(),
            waiting = listOf("wt-api"),
        )
        val loop = LoopState(WT, Phase.Idle, questions = mapOf("wt-old" to CONFIRM))
        val items = voiceUiState(loop, sessions = sessions).worktrees
        assertEquals(
            listOf(
                WorktreeItem("wt-api", WorktreeGroup.Attached, "working", waiting = true),
                WorktreeItem(WT, WorktreeGroup.Running, "blocked", current = true),
                WorktreeItem("wt-old", WorktreeGroup.Stopped, null, problem = "herdr failed", openQuestion = true),
            ),
            items,
        )
        assertTrue(voiceUiState(loop).worktrees.isEmpty())
    }

    @Test
    fun `mute, offline and engine info are shown`() {
        val loop = LoopState(WT, Phase.Idle, muted = true, online = false)
        val s = voiceUiState(loop, settings = UiSettings(Engine.Deepgram, "Chatterbox unavailable, used Deepgram", 1_500))
        assertTrue(s.muted)
        assertFalse(s.online)
        assertTrue(s.engineInfo.contains("Deepgram"))
        assertTrue(s.engineInfo.contains("Chatterbox unavailable"))
        assertTrue(s.dictationInfo.contains("1.5"))
        assertTrue(voiceUiState(st(Phase.Idle)).engineInfo.contains("Chatterbox"))
    }

    @Test
    fun `every base URL refusal has its own plain message`() {
        val messages = BaseUrlRefusal.entries.map { baseUrlMessage(it) }
        messages.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(baseUrlMessage(BaseUrlRefusal.HOST_NOT_ALLOWED).contains("ts.net"))
    }

    @Test
    fun `base URL problem is null for a good URL and explains a bad one`() {
        assertNull(baseUrlProblem("https://mac.tail1234.ts.net", BuildVariant.RELEASE))
        assertEquals(baseUrlMessage(BaseUrlRefusal.CLEARTEXT), baseUrlProblem("http://100.64.0.1", BuildVariant.RELEASE))
        assertNull(baseUrlProblem("http://100.64.0.1", BuildVariant.DEBUG))
        assertEquals(baseUrlMessage(BaseUrlRefusal.EMPTY_OR_TOO_LONG), baseUrlProblem("", BuildVariant.DEBUG))
    }
}

private object HeldReasons {
    val all = listOf(
        HeldReason.AgentBusy, HeldReason.DraftAtMac, HeldReason.RetryFailed, HeldReason.NotSent,
        HeldReason.MaybeNotSent, HeldReason.CouldNotTranscribe, HeldReason.Refused("m"),
    )
}
