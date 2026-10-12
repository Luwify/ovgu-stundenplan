package de.luwify.stundenplanmd.data.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.updatesDataStore: DataStore<Preferences> by preferencesDataStore(name = "updates")

fun updateSettings(context: Context) = UpdateSettings(context.applicationContext.updatesDataStore)

/** What the updater keeps between starts (own DataStore `updates`, not in the backup: it's about this install). */
data class UpdatePrefs(
    val autoCheck: Boolean = true,
    val lastCheckMillis: Long? = null,
    /** The last manifest that parsed, shown while offline. */
    val manifest: String? = null,
    /** versionCode the snackbar was shown for (once per version). */
    val announcedCode: Int? = null,
    /** versionCode the user chose "don't show again" for: no badge, no snackbar for it. */
    val skippedCode: Int? = null,
    /** The "install unknown apps" explanation was shown once. */
    val unknownSourcesExplained: Boolean = false,
)

class UpdateSettings(private val store: DataStore<Preferences>) {
    private val schemaKey = intPreferencesKey("schema_version")
    private val autoCheckKey = booleanPreferencesKey("auto_check")
    private val lastCheckKey = longPreferencesKey("last_check")
    private val manifestKey = stringPreferencesKey("manifest")
    private val announcedKey = intPreferencesKey("announced_code")
    private val skippedKey = intPreferencesKey("skipped_code")
    private val explainedKey = booleanPreferencesKey("unknown_sources_explained")

    val prefs: Flow<UpdatePrefs> = store.data.map { p ->
        UpdatePrefs(
            autoCheck = p[autoCheckKey] ?: true,
            lastCheckMillis = p[lastCheckKey],
            manifest = p[manifestKey],
            announcedCode = p[announcedKey],
            skippedCode = p[skippedKey],
            unknownSourcesExplained = p[explainedKey] ?: false,
        )
    }

    suspend fun current(): UpdatePrefs = prefs.first()

    /** Same rule as the theme settings: keys are only added or renamed with a step, never dropped. */
    suspend fun migrate() {
        store.edit { migratePrefs(it) }
    }

    fun migratePrefs(prefs: MutablePreferences) {
        val version = prefs[schemaKey] ?: 1
        if (version > SCHEMA) return
        prefs[schemaKey] = SCHEMA
    }

    suspend fun setAutoCheck(on: Boolean) = store.edit { it[autoCheckKey] = on }

    /** A check that reached the server: its time and, if it parsed, the manifest. */
    suspend fun checked(millis: Long, manifest: String?) = store.edit {
        it[lastCheckKey] = millis
        if (manifest != null) it[manifestKey] = manifest
    }

    suspend fun announced(code: Int) = store.edit { it[announcedKey] = code }

    suspend fun skip(code: Int) = store.edit { it[skippedKey] = code }

    suspend fun explainedUnknownSources() = store.edit { it[explainedKey] = true }

    /** Backup import (and its undo): the automatic check and the skipped version. */
    suspend fun restore(autoCheck: Boolean, skippedCode: Int?) = store.edit {
        it[autoCheckKey] = autoCheck
        if (skippedCode == null) it.remove(skippedKey) else it[skippedKey] = skippedCode
    }

    companion object {
        const val SCHEMA = 1
    }
}
