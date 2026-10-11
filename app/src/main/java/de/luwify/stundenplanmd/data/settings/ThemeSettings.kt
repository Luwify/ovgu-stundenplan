package de.luwify.stundenplanmd.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

fun themeSettings(context: Context) = ThemeSettings(context.applicationContext.settingsDataStore)

/** Theme choice and the custom theme, in DataStore (same keys as Mensa MD). */
class ThemeSettings(private val store: DataStore<Preferences>) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val customBaseKey = stringPreferencesKey("custom_theme_base")
    private val customAccentKey = intPreferencesKey("custom_theme_accent")
    private val customGlowKey = booleanPreferencesKey("custom_theme_glow")
    private val customOverridesKey = stringPreferencesKey("custom_theme_overrides")

    val themeMode: Flow<ThemeMode> = store.data.map { ThemeMode.fromKey(it[themeKey]) }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[themeKey] = mode.key }
    }

    /** The user's own theme (used when the theme mode is CUSTOM). */
    val customTheme: Flow<CustomThemeSpec> = store.data.map { prefs ->
        CustomThemeSpec(
            base = CustomBase.fromKey(prefs[customBaseKey]),
            accent = prefs[customAccentKey]?.and(0xFFFFFF) ?: CustomThemeSpec.DEFAULT_ACCENT,
            glow = prefs[customGlowKey] ?: false,
            overrides = CustomThemeSpec.decodeOverrides(prefs[customOverridesKey]),
        )
    }

    suspend fun setCustomTheme(spec: CustomThemeSpec) {
        store.edit {
            it[customBaseKey] = spec.base.key
            it[customAccentKey] = spec.accent and 0xFFFFFF
            it[customGlowKey] = spec.glow
            if (spec.overrides.isEmpty()) it.remove(customOverridesKey)
            else it[customOverridesKey] = CustomThemeSpec.encodeOverrides(spec.overrides)
        }
    }
}
