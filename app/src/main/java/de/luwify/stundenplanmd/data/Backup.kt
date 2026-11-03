package de.luwify.stundenplanmd.data

import de.luwify.stundenplanmd.core.store.BackupFileResult
import de.luwify.stundenplanmd.core.store.BackupFormat
import de.luwify.stundenplanmd.core.store.DisplayBackup
import de.luwify.stundenplanmd.core.store.StoredData
import de.luwify.stundenplanmd.core.store.ThemeBackup
import de.luwify.stundenplanmd.core.store.UpdatesBackup
import de.luwify.stundenplanmd.data.settings.CustomBase
import de.luwify.stundenplanmd.data.settings.CustomThemeSpec
import de.luwify.stundenplanmd.data.settings.ThemeChoice
import de.luwify.stundenplanmd.data.settings.ThemeMode
import java.time.Instant

// The backup format itself (envelope, versions, plan data) is in :core (store/BackupFormat), shared with the web app;
// this file maps the theme to the app's ThemeChoice.

fun ThemeBackup.toChoice() = ThemeChoice(
    ThemeMode.fromKey(mode),
    CustomThemeSpec(CustomBase.fromKey(customBase), customAccent and 0xFFFFFF, customGlow, CustomThemeSpec.decodeOverrides(customOverrides)),
)

fun ThemeChoice.toBackup() = ThemeBackup(
    mode.key, custom.base.key, custom.accent and 0xFFFFFF, custom.glow, CustomThemeSpec.encodeOverrides(custom.overrides),
)

/** What a backup file holds; see [de.luwify.stundenplanmd.core.store.BackupFile]. */
data class Backup(
    val data: StoredData,
    val ics: String?,
    val theme: ThemeBackup,
    val exportedAt: String?,
    val language: String? = null,
    val updates: UpdatesBackup? = null,
    val display: DisplayBackup? = null,
)

sealed interface BackupResult {
    data class Ok(val backup: Backup) : BackupResult
    data object NotABackup : BackupResult

    /** Made by a newer version of the app. */
    data object TooNew : BackupResult
    data class Broken(val reason: String) : BackupResult
}

object BackupCodec {
    const val FORMAT = BackupFormat.FORMAT
    const val VERSION = BackupFormat.VERSION

    fun encode(data: StoredData, ics: String?, theme: ThemeChoice, language: String, updates: UpdatesBackup, display: DisplayBackup = DisplayBackup(), now: Instant = Instant.now()): String =
        BackupFormat.encode(data, ics, theme.toBackup(), language, updates, display, now)

    fun decode(text: String): BackupResult = when (val r = BackupFormat.decode(text)) {
        is BackupFileResult.Ok -> r.backup.let { b ->
            BackupResult.Ok(Backup(b.data, b.ics, b.theme ?: ThemeChoice(ThemeMode.SYSTEM).toBackup(), b.exportedAt, b.language, b.updates, b.display))
        }
        BackupFileResult.NotABackup -> BackupResult.NotABackup
        BackupFileResult.TooNew -> BackupResult.TooNew
        is BackupFileResult.Broken -> BackupResult.Broken(r.reason)
    }
}
