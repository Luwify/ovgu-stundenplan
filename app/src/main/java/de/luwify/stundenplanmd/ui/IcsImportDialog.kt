package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.data.IcsRoute

/**
 * What to do with an .ics: from the settings with far fewer courses than my plan, replace it or add the courses; from
 * anywhere else, add it as a friend (new, or update an .ics friend) or add the courses to my plan (never replace it).
 */
@Composable
fun IcsImportDialog(
    pending: PendingIcs,
    onReplace: () -> Unit,
    onAddCourses: () -> Unit,
    onFriend: (name: String, existingId: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var asFriend by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val courses = pluralStringResource(R.plurals.ics_courses, pending.incomingCourses, pending.incomingCourses)
    if (asFriend) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.ics_friend_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        name, { name = it.take(40) }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ics_friend_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    )
                    if (pending.icsFriends.isNotEmpty()) {
                        Text(stringResource(R.string.ics_friend_update_heading), style = MaterialTheme.typography.titleSmall)
                        pending.icsFriends.forEach { f ->
                            ChoiceRow(R.drawable.ic_edit, stringResource(R.string.ics_friend_update, f.displayName), stringResource(R.string.ics_friend_update_hint)) {
                                onFriend(f.name, f.id)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onFriend(name.trim(), null) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.ics_friend_new)) } },
            dismissButton = { TextButton(onClick = { asFriend = false }) { Text(stringResource(R.string.back)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(if (pending.route == IcsRoute.AskReplaceOrAdd) R.string.ics_partial_title else R.string.ics_choice_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val route = pending.route) {
                    IcsRoute.AskReplaceOrAdd -> {
                        Text(stringResource(R.string.ics_partial_body, courses, pluralStringResource(R.plurals.ics_courses, pending.currentCourses, pending.currentCourses)))
                        ChoiceRow(R.drawable.ic_file_open, stringResource(R.string.ics_replace), stringResource(R.string.ics_replace_hint), onReplace)
                        ChoiceRow(R.drawable.ic_add, stringResource(R.string.ics_add_courses), stringResource(R.string.ics_add_courses_hint), onAddCourses)
                    }
                    is IcsRoute.AskFriendOrAdd -> {
                        Text(stringResource(R.string.ics_choice_body, courses))
                        ChoiceRow(R.drawable.ic_group, stringResource(R.string.ics_as_friend), stringResource(R.string.ics_as_friend_hint)) { asFriend = true }
                        ChoiceRow(R.drawable.ic_add, stringResource(R.string.ics_add_courses), stringResource(R.string.ics_add_courses_hint), onAddCourses)
                        if (route.canBeMine) {
                            ChoiceRow(R.drawable.ic_file_open, stringResource(R.string.ics_as_mine), stringResource(R.string.ics_as_mine_hint), onReplace)
                        }
                    }
                    IcsRoute.ReplaceMine -> Unit
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
