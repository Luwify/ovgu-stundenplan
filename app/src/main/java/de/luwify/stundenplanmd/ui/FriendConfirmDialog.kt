package de.luwify.stundenplanmd.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.luwify.stundenplanmd.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Asks before a friend's plan is added, or replaces the one I have of them (my name and colors for them stay). */
@Composable
fun FriendConfirmDialog(pending: PendingFriend, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(appLocale())
    fun date(d: LocalDate?) = d?.format(fmt) ?: "–"
    val plan = pending.plan
    val name = pending.existing?.displayName ?: plan.name
    val body = buildString {
        append(stringResource(R.string.friend_confirm_stand, date(plan.planDate)))
        append(" · ")
        append(pluralStringResource(R.plurals.backup_events, plan.events.size, plan.events.size))
        append("\n")
        append(stringResource(R.string.friend_shared_on, date(plan.exportedOn)))
        if (pending.existing != null) {
            append("\n\n")
            append(stringResource(R.string.friend_confirm_replace, name, date(pending.existingPlan?.planDate)))
        }
        if (!plan.complete) {
            append("\n\n")
            append(stringResource(R.string.friend_confirm_qr_notes))
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(if (pending.existing != null) R.string.friend_confirm_update_title else R.string.friend_confirm_add_title, name)) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(if (pending.existing != null) R.string.friend_update else R.string.friend_add)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
