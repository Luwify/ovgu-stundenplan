package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.schedule.NowStatus
import de.luwify.stundenplanmd.ui.theme.planColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun standText(date: LocalDate?): String =
    stringResource(R.string.friend_confirm_stand, date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(appLocale())) ?: "–")

/** "K14-102 bis 13:00", "frei, ab 13:00 K14-102", "frei"; null on weekends and outside the semester. */
@Composable
fun nowText(status: NowStatus): String? {
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    fun place(o: de.luwify.stundenplanmd.core.schedule.Occurrence) = o.room ?: o.event.title
    return when (status) {
        is NowStatus.In -> stringResource(R.string.now_in, place(status.occurrence), status.until.format(hm))
        is NowStatus.FreeUntil -> stringResource(R.string.now_free_until, status.next.start.format(hm), place(status.next))
        NowStatus.FreeToday -> stringResource(R.string.now_free)
        NowStatus.None -> null
    }
}

/** Navigation drawer: my plan, friends (Stand, where they are now), and add / share / QR at the bottom. */
@Composable
fun PlanDrawer(
    my: PlanUi?,
    friends: List<FriendEntry>,
    selected: String,
    now: LocalDateTime,
    onSelect: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onFavorite: (String, Boolean) -> Unit,
    onAdd: () -> Unit,
    onShare: () -> Unit,
    onQr: () -> Unit,
) {
    // About 80 % of the window, at most 320 dp: leaves a comfortable scrim on the right to tap the drawer closed.
    val window = LocalWindowInfo.current.containerSize
    val width = with(LocalDensity.current) { (window.width.toDp() * 0.8f).coerceAtMost(320.dp) }
    ModalDrawerSheet(Modifier.width(width)) {
        Column(Modifier.fillMaxHeight().navigationBarsPadding()) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 12.dp),
            )
            val meSelected = selected == my?.person?.id || friends.none { it.person.id == selected }
            NavigationDrawerItem(
                label = {
                    PersonLabel(stringResource(R.string.my_plan), meSelected, listOfNotNull(my?.takeIf { !it.isEmpty }?.let { standText(it.planDate) }))
                },
                selected = meSelected,
                onClick = { onSelect(de.luwify.stundenplanmd.data.StoredData.ME) },
                modifier = Modifier.padding(horizontal = 12.dp).height(if (my?.isEmpty == false) 64.dp else 56.dp),
            )
            HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
            Text(
                stringResource(R.string.friends),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = if (friends.size > 1) 0.dp else 4.dp),
            )
            if (friends.size > 1) {
                Text(
                    stringResource(R.string.drawer_reorder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
                )
            }
            val listState = rememberLazyListState()
            val reorder = rememberColumnReorder(friends, listState, key = { it.person.id }, onReorder = onReorder)
            LazyColumn(Modifier.weight(1f).then(reorder.gestures), state = listState, userScrollEnabled = !reorder.isDragging) {
                if (friends.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.friends_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                    }
                }
                items(reorder.order, key = { it.person.id }) { f ->
                    val status = nowText(NowStatus.of(f.schedule, now))
                    val isSelected = selected == f.person.id
                    val dragging = f.person.id == reorder.draggedKey
                    NavigationDrawerItem(
                        label = { PersonLabel(f.person.displayName, isSelected, listOfNotNull(standText(f.planDate), status)) },
                        badge = { FavoriteButton(f.person.favorite, f.person.displayName) { onFavorite(f.person.id, !f.person.favorite) } },
                        selected = isSelected || dragging,
                        onClick = { onSelect(f.person.id) },
                        modifier = Modifier
                            .reorderItem(this, reorder, f.person.id)
                            .padding(horizontal = 12.dp)
                            .height(if (status != null) 84.dp else 64.dp),
                    )
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                DrawerAction(R.drawable.ic_add, stringResource(R.string.friend_add_action), onAdd)
                DrawerAction(R.drawable.ic_share, stringResource(R.string.share_action), onShare)
                DrawerAction(R.drawable.ic_qr_code_2, stringResource(R.string.qr_action), onQr)
            }
        }
    }
}

/** Name prominent (titleMedium, semi-bold), Stand and now-line small below it. Selected rows keep the item's content color. */
@Composable
private fun PersonLabel(name: String, selected: Boolean, lines: List<String>) {
    val scheme = MaterialTheme.colorScheme
    val content = LocalContentColor.current
    Column {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) content else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        for (line in lines) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = if (selected) content else scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Heart toggle for a favorite friend: filled in the favorite color, outlined otherwise. */
@Composable
fun FavoriteButton(favorite: Boolean, name: String, onToggle: () -> Unit) {
    IconToggleButton(checked = favorite, onCheckedChange = { onToggle() }) {
        Icon(
            painterResource(if (favorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite),
            contentDescription = stringResource(R.string.favorite_toggle, name),
            tint = if (favorite) planColors().favorite else LocalContentColor.current,
        )
    }
}

@Composable
private fun DrawerAction(icon: Int, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = null)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Plus in the drawer: scan a QR code or pick a shared file. */
@Composable
fun AddFriendDialog(onScan: () -> Unit, onPickFile: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.friend_add_title)) },
        text = {
            Column {
                ChoiceRow(R.drawable.ic_qr_code_scanner, stringResource(R.string.scan_title), stringResource(R.string.friend_add_scan_hint), onScan)
                ChoiceRow(R.drawable.ic_file_open, stringResource(R.string.friend_add_file), stringResource(R.string.friend_add_file_hint), onPickFile)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceRow(icon: Int, title: String, hint: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(hint) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick),
    )
}

/** The name friends see; asked before the first share and editable in the settings. */
@Composable
fun ShareNameDialog(initial: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_name_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.share_name_hint))
                OutlinedTextField(
                    name, { name = it.take(40) },
                    label = { Text(stringResource(R.string.share_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A friend's details: rename (empty = the name they shared), Stand, when they shared it, remove. */
@Composable
fun FriendDialog(plan: PlanUi, onRename: (String?) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val person = plan.person
    var name by rememberSaveable { mutableStateOf(person.localName.orEmpty()) }
    val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(appLocale())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(person.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it.take(40) },
                    label = { Text(stringResource(R.string.friend_name_label)) },
                    placeholder = { Text(person.name) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
                Text(stringResource(R.string.friend_shared_name, person.name), style = MaterialTheme.typography.bodySmall)
                Text(standText(plan.planDate), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.friend_shared_on, plan.exportedOn?.format(fmt) ?: "–"), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.friend_update_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDelete, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.friend_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name.trim().ifEmpty { null }) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
