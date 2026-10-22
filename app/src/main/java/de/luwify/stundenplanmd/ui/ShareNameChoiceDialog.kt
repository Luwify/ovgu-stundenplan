package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R

/** Asked when a renamed friend's plan is passed on: which name goes into the share. The original is preselected. */
@Composable
fun ShareNameChoiceDialog(original: String, local: String, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    var useLocal by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_as_title)) },
        text = {
            Column {
                Text(stringResource(R.string.share_as_hint), modifier = Modifier.padding(bottom = 8.dp))
                NameOption(stringResource(R.string.share_as_original, original), !useLocal) { useLocal = false }
                NameOption(stringResource(R.string.share_as_local, local), useLocal) { useLocal = true }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(if (useLocal) local else original) }) { Text(stringResource(R.string.share_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NameOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, modifier = Modifier.padding(start = 12.dp))
    }
}
