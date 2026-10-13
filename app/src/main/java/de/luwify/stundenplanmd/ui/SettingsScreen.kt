package de.luwify.stundenplanmd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luwify.stundenplanmd.AppLanguage
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.model.Subject
import de.luwify.stundenplanmd.data.settings.ThemeMode
import de.luwify.stundenplanmd.ui.theme.LocalThemeMode
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import de.luwify.stundenplanmd.ui.theme.rgbColor
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The settings page (gear in the top bar): theme, subjects, the imported plan. An overlay closed with back. */
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    plan: PlanUi?,
    shareName: String?,
    onEditSubject: (Subject) -> Unit,
    onImport: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onEditShareName: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var showColors by rememberSaveable { mutableStateOf(false) }
    val updates = vm.updates
    val update by updates.state.collectAsStateWithLifecycle()
    val download by updates.download.collectAsStateWithLifecycle()
    var showVersions by rememberSaveable { mutableStateOf(false) }
    var openVersion by rememberSaveable { mutableStateOf<Int?>(null) }
    var shareApp by rememberSaveable { mutableStateOf(false) }
    val choice by vm.theme.collectAsStateWithLifecycle()
    val locale = appLocale()
    val myShare by vm.myShare.collectAsStateWithLifecycle()
    val orphans by vm.orphans.collectAsStateWithLifecycle()
    var undoOrphans by remember { mutableStateOf<List<de.luwify.stundenplanmd.core.model.PersonalChange>?>(null) }

    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = stringResource(R.string.back)) }
                    Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
                }
                LazyColumn(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom + 24.dp),
                ) {
                    item(key = "language-title") { SectionHeading(stringResource(R.string.language_title)) }
                    item(key = "language-picker") {
                        val context = LocalContext.current
                        var chosen by remember { mutableStateOf(AppLanguage.current(context)) }
                        SettingsDropdown(
                            label = stringResource(R.string.language_label),
                            options = listOf(
                                AppLanguage.SYSTEM to stringResource(R.string.language_system),
                                AppLanguage.GERMAN to "Deutsch",
                                AppLanguage.ENGLISH to "English",
                            ),
                            selected = chosen,
                            onSelect = {
                                chosen = it
                                AppLanguage.set(context, it)
                                // From API 33 the system recreates the activity itself. Screen state is saveable
                                // (week, day, tab, open pages) or in the ViewModel (selected person), so it stays.
                                if (android.os.Build.VERSION.SDK_INT < 33) (context as? android.app.Activity)?.recreate()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    item(key = "theme-title") { SectionHeading(stringResource(R.string.theme_title)) }
                    item(key = "theme-picker") {
                        val theme = LocalThemeMode.current
                        Column {
                            SettingsDropdown(
                                label = stringResource(R.string.theme_label),
                                options = listOf(
                                    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                                    ThemeMode.SOFT to stringResource(R.string.theme_soft),
                                    ThemeMode.AMOLED to stringResource(R.string.theme_amoled),
                                    ThemeMode.CUSTOM to stringResource(R.string.theme_custom),
                                ),
                                selected = theme,
                                onSelect = vm::setThemeMode,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val spec = choice?.custom
                            if (theme == ThemeMode.CUSTOM && spec != null) {
                                CustomThemeEditor(spec, vm::setCustomTheme, onAdvanced = { showColors = true })
                                SwitchRow(stringResource(R.string.custom_glow), stringResource(R.string.custom_glow_hint), spec.glow) {
                                    vm.setCustomTheme(spec.copy(glow = it))
                                }
                            }
                        }
                    }

                    val subjects = plan?.subjects.orEmpty()
                    if (subjects.isNotEmpty()) {
                        item(key = "subjects-title") { SectionHeading(stringResource(R.string.settings_subjects)) }
                        items(subjects, key = { "subject-${it.key}" }) { subject ->
                            SubjectRow(subject, subject.color ?: SubjectColors.defaultColor(subjects.indexOf(subject))) { onEditSubject(subject) }
                        }
                    }

                    item(key = "plan-title") { SectionHeading(stringResource(R.string.settings_plan)) }
                    item(key = "plan") {
                        Column {
                            plan?.importedAt?.let {
                                Text(
                                    stringResource(
                                        R.string.plan_imported_at,
                                        it.atZone(PlanZone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = onImport, modifier = Modifier.padding(top = 8.dp)) {
                                Icon(ImportIcon, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.import_file))
                            }
                        }
                    }

                    item(key = "share-title") { SectionHeading(stringResource(R.string.settings_share)) }
                    item(key = "share") {
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onEditShareName).heightIn(min = 56.dp).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.share_name_setting), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    shareName ?: stringResource(R.string.share_name_unset),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(EditIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    item(key = "share-changes") {
                        SwitchRow(stringResource(R.string.share_changes), stringResource(R.string.share_changes_hint), myShare.withChanges, vm::setShareChanges)
                    }

                    if (orphans.isNotEmpty() || undoOrphans != null) {
                        item(key = "orphans-title") { SectionHeading(stringResource(R.string.orphans_title)) }
                        item(key = "orphans-hint") {
                            Text(stringResource(R.string.orphans_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(orphans, key = { "orphan:${it.uid}:${it.date}:${it.series}" }) { c ->
                            OrphanRow(c, locale) { undoOrphans = vm.deleteChange(c) }
                        }
                        undoOrphans?.let { previous ->
                            item(key = "orphans-undo") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.orphan_deleted), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.material3.TextButton(onClick = { vm.restorePersonal(previous); undoOrphans = null }) {
                                        Text(stringResource(R.string.undo))
                                    }
                                }
                            }
                        }
                    }

                    item(key = "backup-title") { SectionHeading(stringResource(R.string.settings_backup)) }
                    item(key = "backup") {
                        Column {
                            Text(
                                stringResource(R.string.backup_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onExportBackup) { Text(stringResource(R.string.backup_export)) }
                                OutlinedButton(onClick = onImportBackup) { Text(stringResource(R.string.backup_import)) }
                            }
                        }
                    }

                    versionSection(
                        update, download,
                        onOverview = { showVersions = true },
                        onOpen = { openVersion = it.versionCode },
                        onCheck = updates::checkNow,
                        onAutoCheck = updates::setAutoCheck,
                    )
                }
            }
        }
        if (showVersions) VersionOverviewScreen(update, onOpen = { openVersion = it.versionCode }, onBack = { showVersions = false })
        update.manifest?.releases?.firstOrNull { it.versionCode == openVersion }?.let { release ->
            VersionDetailScreen(release, updates, onShare = { shareApp = true }, onBack = { openVersion = null })
        }
        if (shareApp) AppShareScreen(updates, onClose = { shareApp = false })
        val spec = choice?.custom
        if (showColors && spec != null) {
            ColorEditorScreen(spec, onChange = { vm.setCustomTheme(it) }, onBack = { showColors = false })
        }
    }
}

@Composable
private fun SubjectRow(subject: Subject, rgb: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(rgbColor(rgb)))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(subject.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    subject.name.takeIf { subject.shortName != null },
                    pluralStringResource(R.plurals.course_count, subject.courses.size, subject.courses.size) +
                        " · " + subject.courses.joinToString("/") { it.type.short },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(EditIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A setting with a title, an explanation and a switch; the whole row toggles. */
@Composable
internal fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** An own change whose date is gone: course, date (or "from" date for a series), what it did, delete button. */
@Composable
private fun OrphanRow(c: de.luwify.stundenplanmd.core.model.PersonalChange, locale: java.util.Locale, onDelete: () -> Unit) {
    val what = listOfNotNull(
        c.note?.takeIf { it.isNotBlank() },
        stringResource(R.string.personal_cancelled).takeIf { c.cancelled },
        c.room?.let { "${stringResource(R.string.editor_room)} $it" },
        if (c.start != null && c.end != null) timeRange(c.date.atTime(c.start), c.date.atTime(c.end)) else null,
        stringResource(R.string.st_badge).takeIf { c.st == true },
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.title.ifEmpty { c.uid }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val date = dayAndDate(c.date, locale)
            Text(
                (if (c.series) stringResource(R.string.orphan_series, date) else date) + if (what.isNotEmpty()) " · $what" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_delete), stringResource(R.string.delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
