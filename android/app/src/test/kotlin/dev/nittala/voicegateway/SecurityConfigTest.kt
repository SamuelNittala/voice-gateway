package dev.nittala.voicegateway

import dev.nittala.voicegateway.storage.DataStoreKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Spec (A8 wiring, plan section 1): the manifest, network security and backup rules, read from the source XML so
 * they cannot silently regress.
 *
 * - Manifest: RECORD_AUDIO and INTERNET; `allowBackup="false"`; `dataExtractionRules` and `fullBackupContent` set;
 *   `networkSecurityConfig` set; `usesCleartextTraffic` never true.
 * - Manifest (A10): the foreground-service, notification, wake-lock and network-state permissions; the
 *   VoiceLoopService declared not exported with type microphone|mediaPlayback; the Application class (composition
 *   root); a launch theme that follows the system light/dark setting (values and values-night).
 * - Release (main) network config: no cleartext anywhere (base-config false, no domain-config allowing it), system
 *   trust anchors only.
 * - Debug network config: in `src/debug` only. Network security config cannot express an IP range (100.64.0.0/10),
 *   so debug permits cleartext at the config level and `validateBaseUrl(.., DEBUG)` limits `http://` to tailnet
 *   IPs and 10.0.2.2; user-added CAs are never trusted.
 * - Backup: every domain excluded from cloud backup and device transfer, the DataStore files named explicitly, and
 *   nothing included.
 */
class SecurityConfigTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element {
        val file = File(path)
        assertTrue("missing $path (working dir ${File(".").absolutePath})", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.android(name: String): String = getAttributeNS(androidNs, name)

    private val manifest by lazy { xml("src/main/AndroidManifest.xml") }
    private val application by lazy { manifest.children("application").single() }

    @Test
    fun manifestAsksForTheMicAndTheNetworkOnly() {
        val permissions = manifest.children("uses-permission").map { it.android("name") }.toSet()
        assertTrue("android.permission.RECORD_AUDIO" in permissions)
        assertTrue("android.permission.INTERNET" in permissions)
    }

    @Test
    fun manifestAsksForTheForegroundServiceNotificationAndWakeLockPermissions() {
        val permissions = manifest.children("uses-permission").map { it.android("name") }.toSet()
        val wanted = listOf(
            "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MICROPHONE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "POST_NOTIFICATIONS", "WAKE_LOCK", "ACCESS_NETWORK_STATE",
        ).map { "android.permission.$it" }
        assertTrue(permissions.toString(), permissions.containsAll(wanted))
    }

    @Test
    fun theVoiceLoopServiceIsAPrivateMicrophoneAndMediaPlaybackForegroundService() {
        val service = application.children("service").single { it.android("name") == ".service.VoiceLoopService" }
        assertEquals("false", service.android("exported"))
        assertEquals(setOf("microphone", "mediaPlayback"), service.android("foregroundServiceType").split("|").toSet())
        assertEquals(".VoiceGatewayApplication", application.android("name"))
    }

    @Test
    fun theLaunchThemeFollowsTheSystemLightOrDarkSetting() {
        assertEquals("@style/Theme.VoiceGateway", application.android("theme"))
        fun parentOf(path: String): String =
            xml(path).children("style").single { it.getAttribute("name") == "Theme.VoiceGateway" }.getAttribute("parent")
        assertEquals("android:Theme.Material.Light.NoActionBar", parentOf("src/main/res/values/themes.xml"))
        assertEquals("android:Theme.Material.NoActionBar", parentOf("src/main/res/values-night/themes.xml"))
    }

    @Test
    fun backupIsOffAndBothRuleFilesAreSet() {
        assertEquals("false", application.android("allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.android("dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.android("fullBackupContent"))
    }

    @Test
    fun cleartextIsNeverSwitchedOnInTheManifest() {
        assertEquals("@xml/network_security_config", application.android("networkSecurityConfig"))
        listOf("src/main/AndroidManifest.xml", "src/debug/AndroidManifest.xml", "src/release/AndroidManifest.xml")
            .map(::File).filter { it.isFile }
            .forEach { assertFalse(it.path, it.readText().contains("usesCleartextTraffic=\"true\"")) }
    }

    @Test
    fun releaseNetworkConfigHasNoCleartextAnywhere() {
        val config = xml("src/main/res/xml/network_security_config.xml")
        val base = config.children("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        assertTrue(config.children("domain-config").none { it.getAttribute("cleartextTrafficPermitted") != "false" })
        assertEquals(listOf("system"), config.children("certificates").map { it.getAttribute("src") })
        assertTrue(config.children("debug-overrides").isEmpty())
    }

    @Test
    fun debugNetworkConfigIsDebugOnlyAndTrustsOnlySystemCertificates() {
        val config = xml("src/debug/res/xml/network_security_config.xml")
        assertEquals("true", config.children("base-config").single().getAttribute("cleartextTrafficPermitted"))
        assertEquals(listOf("system"), config.children("certificates").map { it.getAttribute("src") })
        assertFalse(File("src/release/res/xml/network_security_config.xml").exists())
    }

    @Test
    fun backupAndTransferExcludeEverything() {
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        val allDomains = setOf("root", "file", "database", "sharedpref", "external")
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val block = rules.children(section).single()
            assertTrue("$section includes something", block.children("include").isEmpty())
            val excludes = block.children("exclude").map { it.getAttribute("domain") to it.getAttribute("path") }
            assertTrue(section, excludes.containsAll(allDomains.map { it to "." }))
            assertTrue(section, ("file" to DATASTORE_DIR) in excludes)
        }
        val legacy = xml("src/main/res/xml/backup_rules.xml")
        assertTrue(legacy.children("include").isEmpty())
        val excludes = legacy.children("exclude").map { it.getAttribute("domain") to it.getAttribute("path") }
        assertTrue(excludes.containsAll(allDomains.map { it to "." }))
        assertTrue(("file" to DATASTORE_DIR) in excludes)
    }

    @Test
    fun theDataStoreFilesLiveInTheExcludedDirectory() {
        // preferencesDataStoreFile(name) is files/datastore/<name>.preferences_pb.
        listOf(DataStoreKeyValueStore.SETTINGS_FILE, DataStoreKeyValueStore.SECRETS_FILE).forEach {
            assertFalse(it.contains('/'))
        }
    }

    private companion object {
        const val DATASTORE_DIR = "datastore/"
    }
}
