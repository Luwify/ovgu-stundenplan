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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.schedule.Timed

private enum class Tab { WEEK, DAY }

private enum class ShareAction { FILE, QR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, onImport: () -> Unit, onExportBackup: () -> Unit, onImportBackup: () -> Unit, onPickFriendFile: () -> Unit) {
    val plan by vm.plan.collectAsStateWithLifecycle()
    val myPlan by vm.myPlan.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val selectedId by vm.selected.collectAsStateWithLifecycle()
    val myShare by vm.myShare.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val res = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var tab by rememberSaveable { mutableStateOf(Tab.WEEK) }
    var selected by remember { mutableStateOf<Timed?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var addChoice by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var qr by remember { mutableStateOf<MyQr?>(null) }
    var askName by rememberSaveable { mutableStateOf<ShareAction?>(null) }
    var editShareName by rememberSaveable { mutableStateOf(false) }
    var friendDetails by rememberSaveable { mutableStateOf(false) }

    fun share(action: ShareAction) {
        scope.launch {
            drawer.close()
            when {
                myPlan?.isEmpty != false -> snackbar.showSnackbar(res.getString(R.string.no_plan_to_share))
                myShare.name == null -> askName = action
                action == ShareAction.FILE -> vm.writeShareFile()?.let { openShareSheet(context, it) }
                    ?: snackbar.showSnackbar(res.getString(R.string.share_failed))
                else -> qr = vm.myQr() ?: run { snackbar.showSnackbar(res.getString(R.string.share_failed)); null }
            }
        }
    }

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
                is UiMessage.FriendDeleted -> {
                    val r = snackbar.showSnackbar(
                        res.getString(R.string.friend_deleted, msg.name),
                        actionLabel = res.getString(R.string.undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.undoImport(msg.previous)
                }
                UiMessage.NoPlanToShare -> snackbar.showSnackbar(res.getString(R.string.no_plan_to_share))
                UiMessage.ShareFailed -> snackbar.showSnackbar(res.getString(R.string.share_failed))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawer,
            // Swiping stays with the week and day pagers; the drawer opens with the menu button.
            gesturesEnabled = drawer.isOpen,
            drawerContent = {
                PlanDrawer(
                    my = myPlan,
                    friends = friends,
                    selected = selectedId,
                    now = now,
                    onSelect = { id -> vm.select(id); scope.launch { drawer.close() } },
                    onReorder = vm::reorderFriends,
                    onFavorite = vm::setFavorite,
                    onAdd = { addChoice = true; scope.launch { drawer.close() } },
                    onShare = { share(ShareAction.FILE) },
                    onQr = { share(ShareAction.QR) },
                )
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }) {
                                Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.open_drawer))
                            }
                        },
                        title = {
                            Text(
                                plan?.takeIf { !it.isMe }?.person?.displayName ?: stringResource(R.string.app_name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        actions = {
                            plan?.takeIf { !it.isMe }?.person?.let { friend ->
                                FavoriteButton(friend.favorite, friend.displayName) { vm.setFavorite(friend.id, !friend.favorite) }
                            }
                            if (plan?.isMe == false) {
                                IconButton(onClick = { friendDetails = true }) {
                                    Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.friend_details))
                                }
                            } else {
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                                }
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
                    // Each person's plan gets fresh pagers (their weeks differ).
                    key(p?.person?.id) {
                        when {
                            p == null -> Unit
                            p.isEmpty -> EmptyState(onImport)
                            tab == Tab.WEEK -> WeekScreen(p, now, onOpen = { selected = it }, modifier = Modifier.fillMaxSize())
                            else -> DayScreen(p, now, onOpen = { selected = it }, modifier = Modifier.fillMaxSize())
                        }
                    }
                    val item = selected
                    if (p != null && item != null) {
                        DetailSheet(p, item, onEditSubject = { selected = null; editing = it.key }, onDismiss = { selected = null })
                    }
                }
            }
        }
        if (showSettings) {
            SettingsScreen(
                vm, myPlan, myShare.name,
                onEditSubject = { editing = it.key },
                onImport = onImport,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup,
                onEditShareName = { editShareName = true },
                onBack = { showSettings = false },
            )
        }
        if (scanning) {
            ScanScreen(onScanned = { text -> scanning = false; vm.importScanned(text) }, onClose = { scanning = false })
        }
        qr?.let { code ->
            QrShowScreen(
                text = code.text,
                omitsDetails = code.omitsDetails,
                name = myShare.name.orEmpty(),
                stand = standText(myPlan?.planDate),
                onShareFile = { qr = null; share(ShareAction.FILE) },
                onClose = { qr = null },
            )
        }
    }

    if (addChoice) {
        AddFriendDialog(
            onScan = { addChoice = false; scanning = true },
            onPickFile = { addChoice = false; onPickFriendFile() },
            onDismiss = { addChoice = false },
        )
    }
    askName?.let { action ->
        ShareNameDialog(null, onSave = { name ->
            askName = null
            scope.launch { vm.setShareName(name); share(action) }
        }, onDismiss = { askName = null })
    }
    if (editShareName) {
        ShareNameDialog(myShare.name, onSave = { name -> editShareName = false; scope.launch { vm.setShareName(name) } }, onDismiss = { editShareName = false })
    }
    val friendPlan = plan?.takeIf { !it.isMe }
    if (friendDetails && friendPlan != null) {
        FriendDialog(
            friendPlan,
            onRename = { vm.renameFriend(friendPlan.person.id, it); friendDetails = false },
            onDelete = { friendDetails = false; vm.deleteFriend(friendPlan.person.id) },
            onDismiss = { friendDetails = false },
        )
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
