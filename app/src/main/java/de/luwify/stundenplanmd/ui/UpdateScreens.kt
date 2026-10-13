package de.luwify.stundenplanmd.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.update.ApkProblem
import de.luwify.stundenplanmd.core.update.Release
import de.luwify.stundenplanmd.core.update.UpdatePolicy
import de.luwify.stundenplanmd.data.update.CheckResult
import de.luwify.stundenplanmd.data.update.DownloadState
import de.luwify.stundenplanmd.data.update.Failure
import de.luwify.stundenplanmd.data.update.UpdateState
import de.luwify.stundenplanmd.data.update.Updates
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A release's changelog in the app's language, with "- " list items shown as bullets. */
private fun changelogText(release: Release, locale: Locale): String =
    release.changelog.forLanguage(locale.language).lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("- ") || t.startsWith("* ")) "•  " + t.drop(2) else line
    }

private fun checkedAt(millis: Long, locale: Locale): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))

private fun percent(bytes: Long, size: Long): Int = (bytes * 100 / size.coerceAtLeast(1)).toInt().coerceIn(0, 100)

/** Settings section "Version": installed version, version overview, update row, manual check, automatic check. */
fun LazyListScope.versionSection(
    state: UpdateState,
    download: DownloadState,
    onOverview: () -> Unit,
    onOpen: (Release) -> Unit,
    onCheck: () -> Unit,
    onAutoCheck: (Boolean) -> Unit,
) {
    item(key = "version-title") { SectionHeading(stringResource(R.string.settings_version)) }
    item(key = "version-installed") {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_version), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(state.installed.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item(key = "version-overview") {
        NavRow(painterResource(R.drawable.ic_list), stringResource(R.string.version_overview), onClick = onOverview)
    }
    state.offer?.let { offer ->
        item(key = "version-offer") {
            NavRow(
                painterResource(R.drawable.ic_download),
                stringResource(R.string.update_row, offer.versionName),
                accent = true,
                onClick = { onOpen(offer) },
            )
        }
    }
    (download as? DownloadState.Running)?.let { running ->
        item(key = "version-download") {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    stringResource(R.string.update_running_row, running.release.versionName, percent(running.bytes, running.release.size)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { percent(running.bytes, running.release.size) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
    item(key = "version-check") {
        val locale = appLocale()
        val last = state.lastCheckMillis?.let { checkedAt(it, locale) }
        val status = when {
            state.checking -> stringResource(R.string.check_running)
            state.result == CheckResult.TOO_NEW -> stringResource(R.string.check_too_new)
            state.result == CheckResult.FAILED -> last?.let { stringResource(R.string.check_failed, it) } ?: stringResource(R.string.check_failed_never)
            last == null -> stringResource(R.string.check_never)
            state.result == CheckResult.OK && state.offer == null -> stringResource(R.string.check_up_to_date, last)
            else -> stringResource(R.string.check_last, last)
        }
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !state.checking, onClick = onCheck).heightIn(min = 56.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.check_updates), style = MaterialTheme.typography.bodyLarge)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    item(key = "version-auto") {
        SwitchRow(stringResource(R.string.auto_check), stringResource(R.string.auto_check_hint), state.autoCheck, onAutoCheck)
    }
}

/** A row that leads somewhere: icon, label, chevron. */
@Composable
private fun NavRow(icon: Painter, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (accent) color else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f).padding(start = 24.dp), style = MaterialTheme.typography.bodyLarge, color = color)
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Header of the version pages: back arrow and title. */
@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = stringResource(R.string.back)) }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/** All versions from the manifest, newest first, withdrawn ones hidden; the installed one marked. */
@Composable
fun VersionOverviewScreen(state: UpdateState, onOpen: (Release) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(stringResource(R.string.version_overview), onBack)
            val manifest = state.manifest
            if (manifest == null) {
                Text(
                    stringResource(R.string.version_list_empty),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val installedLabel = stringResource(R.string.version_installed)
            LazyColumn(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
                contentPadding = PaddingValues(bottom = bottom + 16.dp),
            ) {
                items(UpdatePolicy.versions(manifest, state.installed.code), key = { it.versionCode }) { release ->
                    val installed = release.versionCode == state.installed.code
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (installed) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier)
                            .clickable { onOpen(release) }
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = installed, onClick = null, Modifier.padding(8.dp))
                        Text(
                            if (installed) "${release.versionName}  ·  $installedLabel" else release.versionName,
                            Modifier.weight(1f).padding(start = 16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** One version: its changelog and the actions (install only for newer versions; sharing the app always). */
@Composable
fun VersionDetailScreen(release: Release, updates: Updates, onShare: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val locale = appLocale()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(stringResource(R.string.version_title, release.versionName), onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                    .padding(start = 16.dp, end = 16.dp, bottom = bottom + 24.dp),
            ) {
                SectionHeading(stringResource(R.string.version_changes))
                Text(changelogText(release, locale), style = MaterialTheme.typography.bodyLarge)
                SectionHeading(stringResource(R.string.version_actions), Modifier.padding(top = 12.dp))
                val newer = release.versionCode > updates.installed.code
                if (updates.canInstall(release)) {
                    ActionRow(painterResource(R.drawable.ic_download), stringResource(R.string.version_install)) { updates.install(release) }
                } else if (newer && !release.withdrawn) {
                    Text(
                        stringResource(R.string.version_needs_android),
                        Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ActionRow(painterResource(R.drawable.ic_share), stringResource(R.string.share_app), onClick = onShare)
            }
        }
    }
}

@Composable
private fun ActionRow(icon: Painter, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f).padding(start = 24.dp), style = MaterialTheme.typography.bodyLarge)
        Icon(painterResource(R.drawable.ic_open_in_new), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "App teilen": a QR code with the public download link (first install) and the installed APK as a file. */
@Composable
fun AppShareScreen(updates: Updates, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    FullBrightness()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (linkVersion, link) = remember { updates.shareLink() }
    val qr = remember(link) { runCatching { Encoder.encode(link, ErrorCorrectionLevel.M) }.getOrNull() }
    var failed by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OverlayHeader(stringResource(R.string.share_app), onClose)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                qr?.let { QrCode(it.matrix, Modifier.widthIn(max = 320.dp).fillMaxWidth().height(320.dp)) }
                Text(stringResource(R.string.app_share_qr_hint, linkVersion), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                SelectionContainer {
                    Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        val file = runCatching { updates.shareableApk() }.getOrNull()
                        if (file == null) failed = true else shareApk(context, file)
                    }
                }) {
                    Icon(painterResource(R.drawable.ic_share), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.app_share_file))
                }
                Text(
                    stringResource(if (failed) R.string.app_share_failed else R.string.app_share_file_hint, updates.installed.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun shareApk(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/vnd.android.package-archive")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.app_share_chooser)))
}

/** From the snackbar: what's new, install or skip ("don't show again" = no badge/snackbar for this version). */
@Composable
fun UpdateDialog(installed: String, release: Release, onInstall: () -> Unit, onSkip: (dontShowAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontShow by rememberSaveable { mutableStateOf(false) }
    val locale = appLocale()
    val template = stringResource(R.string.update_dialog_text, "\u0000", "\u0001")
    val text: AnnotatedString = remember(template, installed, release.versionName) {
        buildAnnotatedString {
            template.forEach { c ->
                when (c) {
                    '\u0000' -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(installed) }
                    '\u0001' -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(release.versionName) }
                    else -> append(c)
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Column {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.update_dialog_changes), style = MaterialTheme.typography.titleSmall)
                Box(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text(changelogText(release, locale), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(
                    Modifier.fillMaxWidth().toggleable(dontShow, role = Role.Checkbox) { dontShow = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontShow, onCheckedChange = null)
                    Text(stringResource(R.string.update_dont_show_again), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onInstall) { Text(stringResource(R.string.version_install)) } },
        dismissButton = { TextButton(onClick = { onSkip(dontShow) }) { Text(stringResource(R.string.update_skip)) } },
    )
}

/** Dialogs of a running update, wherever the user is: permission, download progress, failure. */
@Composable
fun UpdateDialogs(updates: Updates, download: DownloadState) {
    val context = LocalContext.current
    val permission by updates.permission.collectAsStateWithLifecycle()
    permission?.let { ask ->
        if (ask.explain) {
            AlertDialog(
                onDismissRequest = updates::permissionDeclined,
                title = { Text(stringResource(R.string.unknown_sources_title)) },
                text = { Text(stringResource(R.string.unknown_sources_text)) },
                confirmButton = {
                    TextButton(onClick = { updates.permissionHandled(explained = true); openUnknownSources(context) }) {
                        Text(stringResource(R.string.unknown_sources_open))
                    }
                },
                dismissButton = { TextButton(onClick = updates::permissionDeclined) { Text(stringResource(R.string.cancel)) } },
            )
        } else {
            LaunchedEffect(ask) {
                updates.permissionHandled(explained = false)
                openUnknownSources(context)
            }
        }
    }

    var minimizedFor by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(download) {
        if (download is DownloadState.Idle || download is DownloadState.Failed) minimizedFor = null
    }
    val release = when (download) {
        is DownloadState.Running -> download.release
        is DownloadState.Verifying -> download.release
        is DownloadState.Installing -> download.release
        else -> null
    }
    if (release != null && minimizedFor != release.versionCode) {
        val title = when (download) {
            is DownloadState.Running -> R.string.update_downloading
            is DownloadState.Verifying -> R.string.update_verifying
            else -> R.string.update_installing
        }
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(title, release.versionName)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (download is DownloadState.Running) {
                        val p = percent(download.bytes, release.size)
                        LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.update_percent, p),
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { minimizedFor = release.versionCode }) { Text(stringResource(R.string.update_minimize)) }
            },
            dismissButton = {
                if (download is DownloadState.Installing) {
                    // Android may never report back (e.g. Play Protect stops the install): never trap the user here.
                    TextButton(onClick = updates::forgetInstall) { Text(stringResource(R.string.close)) }
                } else {
                    TextButton(onClick = updates::cancel) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }

    (download as? DownloadState.Failed)?.let { failed ->
        AlertDialog(
            onDismissRequest = updates::dismissFailure,
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(failureText(failed.failure)) },
            confirmButton = { TextButton(onClick = updates::dismissFailure) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun failureText(failure: Failure): String = when (failure) {
    Failure.Network -> stringResource(R.string.update_failed_network)
    is Failure.Install -> listOfNotNull(stringResource(R.string.update_failed_install), failure.message).joinToString("\n\n")
    is Failure.Check -> (listOf(stringResource(R.string.update_failed_check)) + failure.problems.map {
        "•  " + stringResource(
            when (it) {
                ApkProblem.SIZE -> R.string.update_problem_size
                ApkProblem.HASH -> R.string.update_problem_hash
                ApkProblem.UNREADABLE -> R.string.update_problem_unreadable
                ApkProblem.PACKAGE -> R.string.update_problem_package
                ApkProblem.NOT_NEWER -> R.string.update_problem_not_newer
                ApkProblem.VERSION -> R.string.update_problem_version
                ApkProblem.SIGNER -> R.string.update_problem_signer
            },
        )
    }).joinToString("\n")
}

/** The system page "Install unknown apps" for this app. */
private fun openUnknownSources(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(intent) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } }
}
