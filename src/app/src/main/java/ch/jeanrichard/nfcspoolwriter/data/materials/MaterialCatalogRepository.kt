package ch.jeanrichard.nfcspoolwriter.data.materials

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.jeanrichard.nfcspoolwriter.data.spoolman.AppJson
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

/**
 * The material catalog as the user sees it: the built-in list plus their own changes, which are
 * persisted as a [MaterialOverlay].
 *
 * The overlay lives in the same preferences store as the other settings, as one JSON value: it is a
 * few kilobytes at most and changes only when the user edits a material, which makes a database or
 * a dedicated store more machinery than the data warrants.
 */
class MaterialCatalogRepository(
    private val builtIn: List<MaterialEntry>,
    private val dataStore: DataStore<Preferences>,
) {
    private val builtInById: Map<String, MaterialEntry> = builtIn.associateBy { it.id }

    /** Emits a fresh merged catalog whenever the user's changes change. */
    val catalog: Flow<MaterialCatalog> = dataStore.data.map { prefs ->
        MaterialCatalog.merged(builtIn, decode(prefs[KEY_OVERLAY]))
    }

    fun isBuiltIn(id: String): Boolean = id in builtInById

    /**
     * Stores [entry], as an override when its ID is built in and as a custom entry otherwise.
     *
     * An override identical to the shipped values is not stored: the entry simply reverts to
     * built in, so "edit it back" and "revert" leave the same state. Saving a custom entry under a
     * different ID than it had, [replacingId], drops the old one.
     *
     * The caller validates first — the ID's shape and its uniqueness are checked where the user
     * can be told about them; this only refuses what would corrupt the store.
     */
    suspend fun save(entry: MaterialEntry, replacingId: String? = null) {
        require(MaterialEntry.isValidId(entry.id)) { "material id must be 5 digits, was '${entry.id}'" }
        require(replacingId == null || !isBuiltIn(replacingId)) {
            "a built-in entry cannot be renumbered"
        }
        update { overlay ->
            val custom = overlay.custom.filterNot { it.id == entry.id || it.id == replacingId }
            val shipped = builtInById[entry.id]
            when {
                shipped == null -> overlay.copy(custom = custom + entry)
                entry.values() == shipped.values() ->
                    overlay.copy(custom = custom, overrides = overlay.overrides - entry.id)
                else ->
                    overlay.copy(custom = custom, overrides = overlay.overrides + (entry.id to entry))
            }
        }
    }

    /** Removes a custom entry. A built-in ID is left alone — see [revert]. */
    suspend fun delete(id: String) = update { overlay ->
        overlay.copy(custom = overlay.custom.filterNot { it.id == id })
    }

    /** Restores a built-in entry's shipped values. A custom ID is left alone — see [delete]. */
    suspend fun revert(id: String) = update { overlay ->
        overlay.copy(overrides = overlay.overrides - id)
    }

    /** Drops every change the user made, custom entries included. */
    suspend fun resetAll() = update { MaterialOverlay.EMPTY }

    private suspend fun update(transform: (MaterialOverlay) -> MaterialOverlay) {
        dataStore.edit { prefs ->
            val next = transform(decode(prefs[KEY_OVERLAY]))
            if (next.isEmpty) prefs.remove(KEY_OVERLAY)
            else prefs[KEY_OVERLAY] = AppJson.encodeToString(next)
        }
    }

    private companion object {
        val KEY_OVERLAY = stringPreferencesKey("material_overlay")

        /**
         * A value this app cannot read is treated as no changes rather than as a crash at startup:
         * the built-in catalog still works, and the user can rebuild their changes. Unknown keys are
         * ignored by `AppJson`, so a newer schema's additions do not land here.
         */
        fun decode(json: String?): MaterialOverlay {
            if (json.isNullOrBlank()) return MaterialOverlay.EMPTY
            return try {
                AppJson.decodeFromString<MaterialOverlay>(json)
            } catch (e: SerializationException) {
                MaterialOverlay.EMPTY
            } catch (e: IllegalArgumentException) {
                MaterialOverlay.EMPTY
            }
        }
    }
}
