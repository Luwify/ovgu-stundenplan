package de.luwify.stundenplanmd.data

import de.luwify.stundenplanmd.data.settings.CustomBase
import de.luwify.stundenplanmd.data.settings.CustomThemeSpec
import de.luwify.stundenplanmd.data.settings.ThemeChoice
import de.luwify.stundenplanmd.data.settings.ThemeMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** The theme settings as stored in a backup (plain keys, so the file stays readable when enums change). */
@Serializable
data class ThemeBackup(
    val mode: String,
    val customBase: String,
    val customAccent: Int,
    val customGlow: Boolean,
    val customOverrides: String,
) {
    fun toChoice() = ThemeChoice(
        ThemeMode.fromKey(mode),
        CustomThemeSpec(CustomBase.fromKey(customBase), customAccent and 0xFFFFFF, customGlow, CustomThemeSpec.decodeOverrides(customOverrides)),
    )

    companion object {
        fun of(choice: ThemeChoice) = ThemeBackup(
            choice.mode.key, choice.custom.base.key, choice.custom.accent and 0xFFFFFF, choice.custom.glow,
            CustomThemeSpec.encodeOverrides(choice.custom.overrides),
        )
    }
}

/**
 * What a backup file holds; [ics] is the raw import (null if there was none). [language] is the in-app language key
 * ("system", "de", "en"), null in backups from before version 2: restoring those keeps the current language.
 */
data class Backup(val data: StoredData, val ics: String?, val theme: ThemeBackup, val exportedAt: String?, val language: String? = null)

sealed interface BackupResult {
    data class Ok(val backup: Backup) : BackupResult
    data object NotABackup : BackupResult

    /** Made by a newer version of the app. */
    data object TooNew : BackupResult
    data class Broken(val reason: String) : BackupResult
}

/**
 * One versioned JSON file with everything: plan, subjects/colors/short names, the raw .ics and the theme. The plan data
 * inside carries its own schema version and goes through the same [Migrations] as the stored file.
 */
object BackupCodec {
    const val FORMAT = "stundenplanmd-backup"
    /** 2: `language` (in-app language). Older files are read with it missing. */
    const val VERSION = 2

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun encode(data: StoredData, ics: String?, theme: ThemeChoice, language: String, now: Instant = Instant.now()): String {
        val obj = JsonObject(
            buildMap {
                put("format", JsonPrimitive(FORMAT))
                put("version", JsonPrimitive(VERSION))
                put("exportedAt", JsonPrimitive(now.toString()))
                put("data", json.encodeToJsonElement(StoredData.serializer(), data.copy(version = Migrations.CURRENT)))
                if (ics != null) put("ics", JsonPrimitive(ics))
                put("theme", json.encodeToJsonElement(ThemeBackup.serializer(), ThemeBackup.of(theme)))
                put("language", JsonPrimitive(language))
            },
        )
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun decode(text: String): BackupResult = try {
        val obj = json.parseToJsonElement(text) as? JsonObject
        if (obj == null || obj["format"]?.jsonPrimitive?.content != FORMAT) {
            BackupResult.NotABackup
        } else if ((obj["version"]?.jsonPrimitive?.intOrNull ?: 1) > VERSION) {
            BackupResult.TooNew
        } else {
            val data = json.decodeFromJsonElement(StoredData.serializer(), Migrations.migrate(obj.getValue("data").jsonObject))
            val theme = obj["theme"]?.let { json.decodeFromJsonElement(ThemeBackup.serializer(), it) }
                ?: ThemeBackup.of(ThemeChoice(ThemeMode.SYSTEM))
            BackupResult.Ok(
                Backup(
                    data, obj["ics"]?.jsonPrimitive?.content, theme, obj["exportedAt"]?.jsonPrimitive?.content,
                    language = obj["language"]?.jsonPrimitive?.content,
                ),
            )
        }
    } catch (e: MigrationException) {
        BackupResult.TooNew
    } catch (e: Exception) {
        BackupResult.Broken(e.message ?: e::class.java.simpleName)
    }
}
