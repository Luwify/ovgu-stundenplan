package de.luwify.stundenplanmd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luwify.stundenplanmd.data.settings.ColorRole
import de.luwify.stundenplanmd.data.settings.CustomBase
import de.luwify.stundenplanmd.data.settings.ThemeMode
import de.luwify.stundenplanmd.ui.theme.CustomTheme
import de.luwify.stundenplanmd.ui.ThemeCrossfade
import de.luwify.stundenplanmd.ui.theme.StundenplanTheme
import de.luwify.stundenplanmd.data.IcsSource
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.ui.MainScreen
import de.luwify.stundenplanmd.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    // In-app language before Android 13 (from 13 the system applies the per-app language itself).
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Opened with / shared an .ics file. Not again after a configuration change.
        if (savedInstanceState == null) {
            handleFile(intent)
        }
        setContent {
            // Many file managers label .ics as octet-stream or plain text: the content is checked on import.
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.importOwn(uri)
            }
            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) vm.exportBackup(uri)
            }
            val friendFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.import(uri)
            }
            val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.readBackup(uri)
            }
            val theme by vm.theme.collectAsStateWithLifecycle()
            // AMOLED is always dark: black bars with light icons whatever the system setting is. A custom theme
            // with a fixed base gets matching bar icons; everything else follows the system (auto).
            val custom = theme?.takeIf { it.mode == ThemeMode.CUSTOM }?.custom
            val systemDark = isSystemInDarkTheme()
            val customDark = custom?.takeIf { it.base != CustomBase.SYSTEM || ColorRole.BACKGROUND in it.overrides }
                ?.let { CustomTheme.isDark(it, systemDark) }
            val blackBars = theme?.mode == ThemeMode.AMOLED ||
                (custom?.base == CustomBase.BLACK && ColorRole.BACKGROUND !in custom.overrides)
            DisposableEffect(theme?.mode, blackBars, customDark) {
                when {
                    blackBars -> enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(Color.BLACK),
                        navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
                    )
                    customDark == true -> enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    )
                    customDark == false -> enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    )
                    else -> enableEdgeToEdge()
                }
                onDispose { }
            }
            // Nothing until the stored theme is known (a few ms), so the wrong theme never flashes.
            theme?.let {
              ThemeCrossfade(it.mode to systemDark, de.luwify.stundenplanmd.ui.animationsEnabled()) {
                StundenplanTheme(it.mode, it.custom) {
                    val fit by vm.fitLandscape.collectAsStateWithLifecycle()
                    androidx.compose.runtime.CompositionLocalProvider(
                        de.luwify.stundenplanmd.ui.LocalCompactHeight provides de.luwify.stundenplanmd.ui.compactHeight(fit),
                    ) {
                    MainScreen(
                        vm,
                        onImport = { picker.launch(PICKER_TYPES) },
                        onExportBackup = { exportLauncher.launch("stundenplan-sicherung-${java.time.LocalDate.now()}.json") },
                        onImportBackup = { importBackupLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        // .stundenplan is unknown to Android: file pickers label it octet-stream. The content decides.
                        // The drawer's file: a share or an .ics (a friend's, or courses to add to mine).
                        onPickFriendFile = { friendFileLauncher.launch(PICKER_TYPES) },
                    )
                    }
                }
              }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from "install unknown apps": go on with the update if it was allowed.
        (application as StundenplanApp).container.updates.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleFile(intent)
    }

    private fun handleFile(intent: Intent?) {
        intent?.let(IcsSource::uriOf)?.let(vm::import)
    }

    private companion object {
        val PICKER_TYPES = arrayOf("text/calendar", "text/x-vcalendar", "application/ics", ShareCodec.MIME, "application/octet-stream", "text/plain")
    }
}
