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

/** The updater settings in a backup: the automatic check and the version the user said "don't show again" for. */
@Serializable
data class UpdatesBackup(
    val autoCheck: Boolean = true,
    val skippedCode: Int? = null,
)

/** Display settings in a backup (since version 4): the landscape fit of the week grid. */
@Serializable
data class DisplayBackup(
    val fitLandscape: Boolean = true,
)

/**
 * What a backup file holds; [ics] is the raw import (null if there was none). [language] is the in-app language key
 * ("system", "de", "en"), null in backups from before version 2: restoring those keeps the current language.
 */
data class Backup(
    val data: StoredData,
    val ics: String?,
    val theme: ThemeBackup,
    val exportedAt: String?,
    val language: String? = null,
    /** Null in backups from before version 3: restoring those keeps the current update settings. */
    val updates: UpdatesBackup? = null,
    /** Null in backups from before version 4: restoring those keeps the current display settings. */
    val display: DisplayBackup? = null,
)

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
    /**
     * 2: `language` (in-app language). 3: `updates` (automatic check, skipped version). 4: `display` (landscape fit). Older files are read through
     * [upgrade]: the missing parts stay missing, so a restore keeps the current values for them.
     */
    const val VERSION = 4

    /** Version n -> n+1 of the backup envelope (the plan data inside has its own [Migrations]). */
    private val STEPS: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        1 to { o -> o }, // no language yet: keep the current one
        2 to { o -> o }, // no update settings yet: keep the current ones
        3 to { o -> o }, // no display settings yet: keep the current ones
    )

    /** Brings an older backup envelope to [VERSION], step by step. */
    fun upgrade(obj: JsonObject): JsonObject {
        var version = obj["version"]?.jsonPrimitive?.intOrNull ?: 1
        var out = obj
        while (version < VERSION) {
            out = STEPS.getValue(version)(out)
            version++
        }
        return JsonObject(out + ("version" to JsonPrimitive(VERSION)))
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun encode(data: StoredData, ics: String?, theme: ThemeChoice, language: String, updates: UpdatesBackup, display: DisplayBackup = DisplayBackup(), now: Instant = Instant.now()): String {
        val obj = JsonObject(
            buildMap {
                put("format", JsonPrimitive(FORMAT))
                put("version", JsonPrimitive(VERSION))
                put("exportedAt", JsonPrimitive(now.toString()))
                put("data", json.encodeToJsonElement(StoredData.serializer(), data.copy(version = Migrations.CURRENT)))
                if (ics != null) put("ics", JsonPrimitive(ics))
                put("theme", json.encodeToJsonElement(ThemeBackup.serializer(), ThemeBackup.of(theme)))
                put("language", JsonPrimitive(language))
                put("updates", json.encodeToJsonElement(UpdatesBackup.serializer(), updates))
                put("display", json.encodeToJsonElement(DisplayBackup.serializer(), display))
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
            val obj = upgrade(obj)
            val data = json.decodeFromJsonElement(StoredData.serializer(), Migrations.migrate(obj.getValue("data").jsonObject))
            val theme = obj["theme"]?.let { json.decodeFromJsonElement(ThemeBackup.serializer(), it) }
                ?: ThemeBackup.of(ThemeChoice(ThemeMode.SYSTEM))
            BackupResult.Ok(
                Backup(
                    data, obj["ics"]?.jsonPrimitive?.content, theme, obj["exportedAt"]?.jsonPrimitive?.content,
                    language = obj["language"]?.jsonPrimitive?.content,
                    updates = obj["updates"]?.let { json.decodeFromJsonElement(UpdatesBackup.serializer(), it) },
                    display = obj["display"]?.let { json.decodeFromJsonElement(DisplayBackup.serializer(), it) },
                ),
            )
        }
    } catch (e: MigrationException) {
        BackupResult.TooNew
    } catch (e: Exception) {
        BackupResult.Broken(e.message ?: e::class.java.simpleName)
    }
}
