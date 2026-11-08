package dev.nittala.voicegateway.storage

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nittala.voicegateway.core.config.BuildVariant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Spec (A7), on a device: the base URL survives in the real DataStore file and is validated again on read. */
@RunWith(AndroidJUnit4::class)
class StoredSettingsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString().replace("-", "")
    private val prefsFile = "vg_test_settings_$id"
    private val secretsFile = "vg_test_secrets_$id"
    private val keys = AndroidKeystoreKeys("vg_test_$id")
    private val prefs = DataStoreKeyValueStore.open(context, prefsFile)
    private val secrets = EncryptedSecretStore(AesGcmCipher(keys), DataStoreKeyValueStore.open(context, secretsFile))

    @After
    fun cleanUp() {
        keys.delete()
        context.preferencesDataStoreFile(prefsFile).delete()
        context.preferencesDataStoreFile(secretsFile).delete()
    }

    private fun settings() = StoredSettings(prefs, secrets, PersistentCookieStore(secrets), BuildVariant.RELEASE)

    @Test
    fun baseUrlPersistsInCanonicalForm() {
        settings().setBaseUrl("HTTPS://Gateway.Example.TS.NET/")
        assertEquals("https://gateway.example.ts.net", settings().baseUrl())
    }

    @Test
    fun tamperedStoredUrlReadsAsNull() {
        prefs.put(StoredSettings.BASE_URL_KEY, "https://a.ts.net.evil.com")
        assertNull(settings().baseUrl())
        assertNull(prefs.get(StoredSettings.BASE_URL_KEY))
    }
}
