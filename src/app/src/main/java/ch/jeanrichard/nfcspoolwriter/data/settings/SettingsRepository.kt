package ch.jeanrichard.nfcspoolwriter.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persisted app settings. Currently only the Spoolman base URL — Spoolman has no built-in
 * API authentication, so there is no credential to store alongside it (DESIGN.md DEC-06).
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /** Emits the configured Spoolman base URL, or `null` while none has been set. */
    val spoolmanBaseUrl: Flow<String?> =
        dataStore.data.map { prefs -> prefs[KEY_SPOOLMAN_BASE_URL]?.takeIf { it.isNotBlank() } }

    suspend fun setSpoolmanBaseUrl(url: String) {
        val normalized = normalizeBaseUrl(url)
        dataStore.edit { prefs ->
            if (normalized == null) prefs.remove(KEY_SPOOLMAN_BASE_URL)
            else prefs[KEY_SPOOLMAN_BASE_URL] = normalized
        }
    }

    private companion object {
        val KEY_SPOOLMAN_BASE_URL = stringPreferencesKey("spoolman_base_url")

        /**
         * Trims whitespace and drops trailing slashes so callers can append API paths
         * (`/api/v1/spool`) without worrying about double slashes. Returns null for input
         * that holds nothing but whitespace/slashes. Beyond that, no validation happens
         * here — the settings screen's "test connection" action is what tells the user
         * whether the URL actually works.
         */
        fun normalizeBaseUrl(raw: String): String? =
            raw.trim().trimEnd('/').takeIf { it.isNotEmpty() }
    }
}
