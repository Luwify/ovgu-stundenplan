package de.luwify.stundenplanmd

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The in-app language choice (as in Mensa MD). [tag] is null for "follow the system". LSF content (course titles,
 * rooms, notes) stays as imported.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null), GERMAN("de"), ENGLISH("en");

    companion object {
        private const val PREFS = "language"
        private const val KEY = "tag"

        /** Backup value: the tag, or "system". */
        fun fromKey(key: String?): AppLanguage? = when (key) {
            "system" -> SYSTEM
            else -> entries.firstOrNull { it.tag != null && it.tag == key }
        }

        fun key(language: AppLanguage): String = language.tag ?: "system"

        /** Android 13+: the system stores it (and shows it in the system's per-app language settings). */
        fun current(context: Context): AppLanguage {
            val tag = if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            }
            return entries.firstOrNull { it.tag != null && it.tag == tag } ?: SYSTEM
        }

        /** The caller recreates the activity on API < 33; the system does it from API 33. */
        fun set(context: Context, language: AppLanguage) {
            if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    if (language.tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
            }
        }

        /** API < 33 only: wraps an activity's base context with the stored language. */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= 33) return base
            val tag = current(base).tag ?: return base
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = android.content.res.Configuration(base.resources.configuration)
            config.setLocales(LocaleList(locale))
            return base.createConfigurationContext(config)
        }
    }
}
