package de.luwify.stundenplanmd.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.data.Backup
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Says what a backup import will replace (now -> backup) and asks before doing it. */
@Composable
fun BackupConfirmDialog(backup: Backup, current: PlanUi?, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val locale = appLocale()
    val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
    fun date(i: Instant?) = i?.atZone(PlanZone)?.format(fmt) ?: "–"
    val now = current?.takeIf { !it.isEmpty }
    val nowLine = if (now == null) {
        stringResource(R.string.backup_now_empty)
    } else {
        pluralStringResource(R.plurals.backup_events, now.schedule.events.size, now.schedule.events.size) + " · " + date(now.importedAt)
    }
    val plan = backup.data.myPlan
    val backupLine = if (plan == null) stringResource(R.string.backup_now_empty) else
        pluralStringResource(R.plurals.backup_events, plan.events.size, plan.events.size) + " · " + date(plan.importedAt)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_confirm_title)) },
        text = {
            Text(
                stringResource(R.string.backup_confirm_body, nowLine, backupLine, current?.subjects?.size ?: 0, backup.data.courseSettings.size) +
                    (backup.exportedAt?.let { "\n\n" + stringResource(R.string.backup_made_at, date(runCatching { Instant.parse(it) }.getOrNull())) } ?: ""),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.backup_replace)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
