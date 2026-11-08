package dev.nittala.voicegateway.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Spec (A7): a string key-value store. Blocking: never call it on the main thread. [put] with null removes the key.
 * Holds no meaning of its own: [EncryptedSecretStore] puts only ciphertext in one, [StoredSettings] puts plain
 * settings in another.
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** [KeyValueStore] on a Preferences DataStore. One instance per file per process (a DataStore rule). */
class DataStoreKeyValueStore(private val dataStore: DataStore<Preferences>) : KeyValueStore {
    override fun get(key: String): String? = runBlocking { dataStore.data.first()[stringPreferencesKey(key)] }

    override fun put(key: String, value: String?) {
        runBlocking {
            dataStore.edit { prefs ->
                val k = stringPreferencesKey(key)
                if (value == null) prefs.remove(k) else prefs[k] = value
            }
        }
    }

    companion object {
        /** The settings file (plain values). */
        const val SETTINGS_FILE = "vg_settings"

        /** The secrets file (ciphertext only). */
        const val SECRETS_FILE = "vg_secrets"

        /**
         * Opens the DataStore file [name] under the app's files directory. A corrupt file is replaced by an empty
         * one (fails closed: lost settings or secrets, never a crash loop). Call once per name per process.
         */
        fun open(context: Context, name: String): DataStoreKeyValueStore = DataStoreKeyValueStore(
            PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }) {
                context.applicationContext.preferencesDataStoreFile(name)
            },
        )
    }
}
