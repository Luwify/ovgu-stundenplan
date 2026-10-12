package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.schedule.Timed

private enum class Tab { WEEK, DAY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, onImport: () -> Unit, onExportBackup: () -> Unit, onImportBackup: () -> Unit) {
    val plan by vm.plan.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val res = LocalResources.current
    var tab by rememberSaveable { mutableStateOf(Tab.WEEK) }
    var selected by remember { mutableStateOf<Timed?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // Registered by the visible screen: jumps back to the current week/day, null while already there.
    var goToday by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.messages.collect { msg ->
            when (msg) {
                is UiMessage.Imported -> {
                    val r = snackbar.showSnackbar(
                        res.getQuantityString(R.plurals.imported, msg.events, msg.events),
                        actionLabel = res.getString(R.string.undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.undoImport(msg.previous)
                }
                is UiMessage.DataUnreadable -> snackbar.showSnackbar(
                    res.getString(R.string.data_unreadable, msg.keptAs ?: "data.json"),
                    duration = SnackbarDuration.Indefinite,
                    withDismissAction = true,
                )
                UiMessage.BackupExported -> snackbar.showSnackbar(res.getString(R.string.backup_exported))
                UiMessage.BackupFailed -> snackbar.showSnackbar(res.getString(R.string.backup_failed))
                UiMessage.BackupNotABackup -> snackbar.showSnackbar(res.getString(R.string.backup_not_a_backup))
                UiMessage.BackupTooNew -> snackbar.showSnackbar(res.getString(R.string.backup_too_new))
                is UiMessage.BackupRestored -> {
                    val r = snackbar.showSnackbar(
                        res.getString(R.string.backup_restored),
                        actionLabel = res.getString(R.string.undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.undoBackup(msg.previous)
                }
                UiMessage.ImportFailed -> snackbar.showSnackbar(res.getString(R.string.import_failed))
                UiMessage.NotACalendar -> snackbar.showSnackbar(res.getString(R.string.import_not_calendar))
                UiMessage.NoEvents -> snackbar.showSnackbar(res.getString(R.string.import_no_events))
                is UiMessage.FriendImported -> {
                    val r = snackbar.showSnackbar(
                        res.getString(if (msg.replaced) R.string.friend_updated else R.string.friend_added, msg.name),
                        actionLabel = res.getString(R.string.undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.undoImport(msg.previous)
                }
                UiMessage.ShareOwn -> snackbar.showSnackbar(res.getString(R.string.share_own))
                UiMessage.ShareTooNew -> snackbar.showSnackbar(res.getString(R.string.share_too_new))
                UiMessage.ShareBroken -> snackbar.showSnackbar(res.getString(R.string.share_broken))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        if (plan?.isEmpty == false) {
                            TextButton(onClick = { goToday?.invoke() }, enabled = goToday != null) { Text(stringResource(R.string.this_week)) }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                        }
                    },
                )
            },
            bottomBar = {
                if (plan?.isEmpty == false) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == Tab.WEEK,
                            onClick = { tab = Tab.WEEK },
                            icon = { Icon(WeekIcon, null) },
                            label = { Text(stringResource(R.string.tab_week)) },
                        )
                        NavigationBarItem(
                            selected = tab == Tab.DAY,
                            onClick = { tab = Tab.DAY },
                            icon = { Icon(TodayIcon, null) },
                            label = { Text(stringResource(R.string.tab_day)) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val p = plan
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    p == null -> Unit
                    p.isEmpty -> EmptyState(onImport)
                    tab == Tab.WEEK -> WeekScreen(p, now, onOpen = { selected = it }, onToday = { goToday = it }, modifier = Modifier.fillMaxSize())
                    else -> DayScreen(p, now, onOpen = { selected = it }, onToday = { goToday = it }, modifier = Modifier.fillMaxSize())
                }
                val item = selected
                if (p != null && item != null) {
                    DetailSheet(p, item, onEditSubject = { selected = null; editing = it.key }, onDismiss = { selected = null })
                }
            }
        }
        if (showSettings) {
            SettingsScreen(vm, plan, onEditSubject = { editing = it.key }, onImport = onImport, onExportBackup = onExportBackup, onImportBackup = onImportBackup, onBack = { showSettings = false })
        }
    }

    val pending by vm.pendingBackup.collectAsStateWithLifecycle()
    pending?.let { BackupConfirmDialog(it.backup, plan, onConfirm = vm::confirmBackup, onCancel = vm::cancelBackup) }
    val friend by vm.pendingFriend.collectAsStateWithLifecycle()
    friend?.let { FriendConfirmDialog(it, onConfirm = vm::confirmFriend, onCancel = vm::cancelFriend) }

    val current = plan
    val key = editing
    if (current != null && key != null) {
        SubjectEditor(
            current, key,
            onSave = { name, color ->
                current.subjects.firstOrNull { it.key == key }?.let { vm.setSubjectStyle(it, name, color) }
                editing = null
            },
            onMove = vm::moveCourse,
            onDismiss = { editing = null },
        )
    }
}
