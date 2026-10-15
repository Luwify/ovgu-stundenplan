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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import java.time.LocalDate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.material3.DrawerValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.schedule.Timed

private enum class Tab { WEEK, DAY }

/** People kept composed around the shown one (see composedAround); more are composed when swiped to. */
private const val MAX_COMPOSED_PEOPLE = 8

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
    var personalEdit by remember { mutableStateOf<Timed?>(null) }
    var pickDates by remember { mutableStateOf<Timed?>(null) }
    var manualEdit by remember { mutableStateOf<ManualTarget?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var addChoice by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var qr by remember { mutableStateOf<MyQr?>(null) }
    var askName by rememberSaveable { mutableStateOf<ShareAction?>(null) }
    var editShareName by rememberSaveable { mutableStateOf(false) }
    var friendDetails by rememberSaveable { mutableStateOf(false) }
    val update by vm.updates.state.collectAsStateWithLifecycle()
    val download by vm.updates.download.collectAsStateWithLifecycle()
    var updateDialog by rememberSaveable { mutableStateOf<Int?>(null) }
    // Week and day shown, shared by everyone's plan (null = this week / today until the user moves).
    var week by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var day by rememberSaveable { mutableStateOf<LocalDate?>(null) }

    // Vertical pager over my plan and my friends: follows the drawer selection and selects whoever it settles on.
    val pages by vm.pages.collectAsStateWithLifecycle()
    val animate = animationsEnabled()
    val people = rememberPagerState(initialPage = pages.indexOfFirst { it.person.id == selectedId }.coerceAtLeast(0)) { pages.size }
    // Everyone stays composed. Otherwise the pager composes the page beyond the next one when a drag starts (its
    // prefetch): a whole week grid with ~170 texts in one frame, 80–150 ms on a mid-range phone, at every first drag
    // toward someone. After the start one more person is added per idle moment, so the first frame stays as fast.
    var composedAround by remember { mutableIntStateOf(0) }
    LaunchedEffect(pages.size) {
        while (composedAround < minOf(pages.size - 1, MAX_COMPOSED_PEOPLE)) {
            withFrameNanos { }
            delay(80)
            composedAround++
        }
    }
    val latestPages by rememberUpdatedState(pages)
    LaunchedEffect(people) {
        snapshotFlow { people.settledPage }.collect { i -> latestPages.getOrNull(i)?.let { vm.select(it.person.id) } }
    }
    LaunchedEffect(selectedId, pages.map { it.person.id }) {
        val i = pages.indexOfFirst { it.person.id == selectedId }
        if (i >= 0 && i != people.currentPage && !people.isScrollInProgress) {
            // From the drawer: slide to that person (from the page next to it, so far targets don't scroll through all).
            if (animate && people.layoutInfo.visiblePagesInfo.isNotEmpty()) {
                if (kotlin.math.abs(i - people.currentPage) > 1) people.scrollToPage(if (i > people.currentPage) i - 1 else i + 1)
                people.animateScrollToPage(i, animationSpec = tween(Motion.Long, easing = Motion.Standard))
            } else {
                people.scrollToPage(i)
            }
        }
    }

    // A check found a newer version: one snackbar per version, "Anzeigen" opens the update dialog.
    // (Collected, not keyed on the value: clearing it must not cancel the snackbar.)
    LaunchedEffect(vm) {
        vm.updates.announcement.filterNotNull().collect { release ->
            vm.updates.announcement.value = null
            val r = snackbar.showSnackbar(
                res.getString(R.string.update_snackbar, release.versionName),
                actionLabel = res.getString(R.string.update_show),
                duration = SnackbarDuration.Long,
            )
            if (r == SnackbarResult.ActionPerformed) updateDialog = release.versionCode
        }
    }

    // A restored backup changed the language (before Android 13 the activity has to be recreated for it).
    val recreate by vm.recreate.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    LaunchedEffect(recreate) {
        if (recreate) {
            vm.recreated()
            activity?.recreate()
        }
    }

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
                    val text = res.getQuantityString(R.plurals.imported, msg.events, msg.events) +
                        if (msg.droppedAdded > 0) " " + res.getQuantityString(R.plurals.added_dropped, msg.droppedAdded, msg.droppedAdded) else ""
                    val r = snackbar.showSnackbar(
                        text,
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
                is UiMessage.ManualDeleted -> {
                    val r = snackbar.showSnackbar(
                        res.getString(R.string.manual_deleted, msg.title),
                        actionLabel = res.getString(R.string.undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.undoImport(msg.previous)
                }
                is UiMessage.CancelledForMe -> {
                    val date = dayAndDate(msg.date, appLocaleOf(res))
                    val r = snackbar.showSnackbar(
                        res.getString(if (msg.cancelled) R.string.cancelled_for_me else R.string.uncancelled_for_me, msg.title, date),
                        actionLabel = res.getString(R.string.undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.restorePersonal(msg.previous)
                }
                is UiMessage.CoursesAdded -> {
                    val parts = listOfNotNull(
                        res.getQuantityString(R.plurals.courses_added, msg.added, msg.added).takeIf { msg.added > 0 },
                        res.getQuantityString(R.plurals.courses_updated, msg.updated, msg.updated).takeIf { msg.updated > 0 },
                        duplicateText(res, msg.duplicates).takeIf { msg.duplicates.isNotEmpty() },
                    )
                    if (msg.added + msg.updated == 0) {
                        snackbar.showSnackbar(parts.joinToString(" "))
                    } else {
                        val r = snackbar.showSnackbar(parts.joinToString(" "), actionLabel = res.getString(R.string.undo), duration = SnackbarDuration.Long)
                        if (r == SnackbarResult.ActionPerformed) vm.undoImport(msg.previous)
                    }
                }
                is UiMessage.AddedRemoved -> {
                    val r = snackbar.showSnackbar(
                        res.getString(R.string.added_removed, msg.title),
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
                            androidx.compose.foundation.layout.Column {
                                Text(
                                    plan?.takeIf { !it.isMe }?.person?.displayName ?: stringResource(R.string.app_name),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (plan?.person?.fromIcs == true) {
                                    Text(
                                        stringResource(R.string.from_ics),
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        actions = {
                            plan?.takeIf { !it.isMe }?.person?.let { friend ->
                                FavoriteButton(friend.favorite, friend.displayName) { vm.setFavorite(friend.id, !friend.favorite) }
                            }
                            if (plan?.isMe == true) {
                                IconButton(onClick = { manualEdit = ManualTarget(null) }) {
                                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.manual_add))
                                }
                            }
                            if (plan?.isMe == false) {
                                IconButton(onClick = { friendDetails = true }) {
                                    Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.friend_details))
                                }
                            } else {
                                IconButton(onClick = { showSettings = true }) {
                                    if (update.offer != null) {
                                        BadgedBox(badge = { Badge() }) {
                                            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_update_badge))
                                        }
                                    } else {
                                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                                    }
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
                    if (pages.isNotEmpty()) {
                        // One page per person (drawer order); week and day stay where they are when switching.
                        VerticalPager(
                            people, Modifier.fillMaxSize(), beyondViewportPageCount = composedAround,
                            key = { pages.getOrNull(it)?.person?.id ?: it },
                        ) { i ->
                            val page = pages[i]
                            val active = i == people.settledPage
                            val edge = remember(people, animate) { EdgeOnlyConnection(people, animate) }
                            Box(Modifier.fillMaxSize().nestedScroll(edge)) {
                                // The visible page switches Week/Day at once, with a fade through. The others (out of sight) follow
                                // one after the other: switching all of them in one frame composes every person's whole screen.
                                var shownTab by remember { mutableStateOf(tab) }
                                val moving = people.isScrollInProgress
                                LaunchedEffect(tab, active, moving) {
                                    if (!active && !moving) delay(400L + 150L * kotlin.math.abs(i - people.settledPage))
                                    shownTab = tab
                                }
                                AnimatedContent(
                                    if (page.isEmpty) null else shownTab,
                                    transitionSpec = {
                                        if (animate && active) {
                                            fadeIn(tween(Motion.Medium - Motion.FadeInDelay, Motion.FadeInDelay, LinearEasing)) togetherWith
                                                fadeOut(tween(Motion.FadeInDelay, easing = LinearEasing))
                                        } else {
                                            EnterTransition.None togetherWith ExitTransition.None
                                        }
                                    },
                                    label = "tab",
                                ) { shown ->
                                    when (shown) {
                                        null -> EmptyState(onImport)
                                        Tab.WEEK -> WeekScreen(page, now, week, active, onWeek = { week = it }, onOpen = { selected = it }, modifier = Modifier.fillMaxSize())
                                        Tab.DAY -> DayScreen(page, now, day, active, onDay = { day = it }, onOpen = { selected = it }, modifier = Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                        val shown = pages.getOrNull(people.currentPage)
                        PersonIndicator(
                            people,
                            if (shown == null || shown.isMe) stringResource(R.string.my_plan) else shown.person.displayName,
                            Modifier.align(Alignment.TopCenter).padding(top = 76.dp),
                        )
                    }
                    val item = selected
                    if (p != null && item != null) {
                        DetailSheet(
                            p, item,
                            onEditSubject = { editing = it.key },
                            onEditPersonal = { personalEdit = item },
                            onCancelForMe = { cancel -> vm.setCancelledForMe(item.ref, item.event.title, cancel) },
                            onPickDates = { pickDates = item },
                            onEditManual = { manualEdit = ManualTarget(item.event.uid) },
                            onDeleteManual = { vm.deleteManual(item.event.uid) },
                            onRemoveAdded = { vm.removeAdded(item, item.event.title) },
                            onDismiss = { selected = null },
                        )
                    }
                    personalEdit?.let { edit ->
                        PersonalEditor(
                            edit,
                            onSave = { series, values ->
                                personalEdit = null
                                vm.savePersonal(edit.personalOrBlank(), edit.event.title, series, values)
                            },
                            onDismiss = { personalEdit = null },
                        )
                    }
                    val dates = pickDates
                    if (p != null && dates != null) {
                        PickDatesDialog(
                            p, dates,
                            onSave = { pickDates = null; vm.setDates(it, dates.event.title) },
                            onDismiss = { pickDates = null },
                        )
                    }
                }
            }
        }
        Overlay(showSettings) {
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
        Overlay(scanning) {
            ScanScreen(onScanned = { text -> scanning = false; vm.importScanned(text) }, onClose = { scanning = false })
        }
        Overlay(qr) { code ->
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

    update.manifest?.releases?.firstOrNull { it.versionCode == updateDialog }?.let { release ->
        UpdateDialog(
            update.installed.name, release,
            onInstall = { updateDialog = null; vm.updates.install(release) },
            onSkip = { dontShow -> updateDialog = null; if (dontShow) vm.updates.skip(release) },
            onDismiss = { updateDialog = null },
        )
    }
    UpdateDialogs(vm.updates, download)

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

    val mine = myPlan
    manualEdit?.let { target ->
        if (mine != null) {
            ManualCourseEditor(
                mine,
                existing = target.id?.let(vm::manualCourse),
                newId = vm::newManualId,
                defaultEnd = vm::manualDefaultEnd,
                today = now.toLocalDate(),
                onSave = { manualEdit = null; vm.saveManual(it) },
                onDismiss = { manualEdit = null },
            )
        }
    }

    val pending by vm.pendingBackup.collectAsStateWithLifecycle()
    pending?.let { BackupConfirmDialog(it.backup, plan, onConfirm = vm::confirmBackup, onCancel = vm::cancelBackup) }
    val ics by vm.pendingIcs.collectAsStateWithLifecycle()
    ics?.let {
        // A new file asks again from the start.
        key(it) {
            IcsImportDialog(it, onReplace = vm::icsReplaceMine, onAddCourses = vm::icsAddCourses, onFriend = vm::icsAddFriend, onCancel = vm::cancelIcs)
        }
    }
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
            onRhythm = vm::setRhythm,
            onDismiss = { editing = null },
        )
    }
}

/** The manual course being added ([id] null) or edited. */
private data class ManualTarget(val id: String?)

/** "Schon in deinem Plan: Mi 13–15, K09B-110": the appointments of an added file my plan already has (at most two). */
private fun duplicateText(res: android.content.res.Resources, duplicates: List<de.luwify.stundenplanmd.core.model.Event>): String {
    val locale = appLocaleOf(res)
    val slots = duplicates.sortedBy { it.start }.take(2).joinToString("; ") { e ->
        "${dayShort(e.start.toLocalDate(), locale)} ${timeRange(e.start, e.end)}" + (e.location?.room?.let { ", $it" } ?: "")
    } + if (duplicates.size > 2) " …" else ""
    return res.getString(R.string.already_in_plan, slots)
}
