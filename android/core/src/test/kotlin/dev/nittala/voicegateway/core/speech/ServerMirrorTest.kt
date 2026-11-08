package dev.nittala.voicegateway.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lists exist twice (plan, "Risks": "Both sides pin it in a test; a change must touch both"). These tests read
 * the constants straight out of the repo's gateway.py, so editing either side alone fails here.
 */
class ServerMirrorTest {
    private val source: String by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "gateway.py").isFile) dir = dir.parentFile
        assertNotNull("gateway.py not found above ${System.getProperty("user.dir")}", dir)
        File(dir, "gateway.py").readText()
    }

    /** The parenthesised body after `NAME = ` (the server writes each as a tuple or frozenset of literals). */
    private fun body(name: String, pattern: String): String {
        val match = Regex("^$name = $pattern", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(source)
        assertNotNull("$name not found in gateway.py", match)
        return match!!.groupValues[1]
    }

    private fun literals(body: String): List<String> = Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()

    @Test
    fun approvalPhrasesEqualTheServers() {
        val server = literals(body("VOICE_APPROVAL_PHRASES", """\((.*?)\)\n"""))
        assertEquals(listOf("yes go ahead", "yes do it", "yes please proceed", "yes thats right"), server)
        assertEquals(server, ApprovalPhrase.PHRASES)
    }

    /** [ApprovalPhrase.approves] mirrors the server's split: plain yes for ASK:, strict phrases when destructive. */
    @Test
    fun theServerTextSplitsPlainAndDestructiveApproval() {
        val flat = source.replace(Regex("\"\\s*\n\\s*f?\""), "")
        assertTrue(flat.contains("approves the proposed step if it is a plain yes, sure, go ahead or the like, unless the"))
        assertTrue(flat.contains("step is destructive or hard to undo: then it approves only if it is exactly one of: {phrases}"))
        assertTrue(flat.contains("such a step is confirmed by a tap on the phone, never by voice."))
    }

    /** What the app's Yes and No taps send (`"confirm": "tap"`) is the one value the server accepts. */
    @Test
    fun theServerTakesTheTapMark() {
        assertTrue(source.contains("""if "confirm" in payload and payload["confirm"] != "tap":"""))
        assertTrue(source.contains("""tap = voice and payload.get("confirm") == "tap""""))
    }

    @Test
    fun noisePhrasesEqualTheServers() {
        val server = literals(body("VOICE_NOISE_PHRASES", """\((.*?)\)\n"""))
        assertEquals(10, server.size)
        assertEquals(server, TranscriptGuard.NOISE_PHRASES)
    }

    @Test
    fun destructiveWordsEqualTheServers() {
        // frozenset(("delete ... " "... charge").split()): adjacent literals concatenate, then split on whitespace.
        val server = literals(body("VOICE_DESTRUCTIVE_WORDS", """frozenset\(\((.*?)\)\.split\(\)\)""")).joinToString("")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        assertEquals(17, server.size)
        assertEquals(server, DestructiveWords.WORDS)
    }

    @Test
    fun theServerNormalisersAreTheOnesMirrored() {
        // If the server changes how it normalises, these mirrors must be revisited.
        assertTrue(source.contains("""text = text.lower().replace("'", "").replace("\u2019", "")"""))
        assertTrue(source.contains("""return re.sub(r"[\W_]+", " ", text).strip()"""))
        assertTrue(source.contains("""return re.sub(r"[\W_]+", " ", text.lower()).strip() in VOICE_NOISE_PHRASES"""))
        assertTrue(source.contains("""destructive = not VOICE_DESTRUCTIVE_WORDS.isdisjoint(normalise_phrase(rest).split())"""))
        assertTrue(source.contains("""if text.lstrip().startswith(("/", "!")):"""))
    }
}
