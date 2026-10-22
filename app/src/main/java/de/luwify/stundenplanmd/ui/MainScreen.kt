package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import java.time.LocalDate
import androidx.compose.ui.unit.dp
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
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.schedule.Timed

private enum class Tab { WEEK, DAY }

/** People kept composed around the shown one (see composedAround); more are composed when swiped to. */
private const val MAX_COMPOSED_PEOPLE = 8

private enum class ShareAction { FILE, QR }

/** A QR code on screen: my plan ([friendId] null) or a friend's passed on. */
private data class QrShown(val code: MyQr, val friendId: String?, val name: String, val planDate: LocalDate?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, onImport: () -> Unit, onExportBackup: () -> Unit, onImportBackup: () -> Unit, onPickFriendFile: () -> Unit) {
    val plan by vm.plan.collectAsStateWithLifecycle()
    val myPlan by vm.myPlan.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val selectedId by vm.selected.collectAsStateWithLifecycle()
    val myShare by vm.myShare.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val fitLandscape by vm.fitLandscape.collectAsStateWithLifecycle()
    val compact = LocalCompactHeight.current
    val snackbar = remember { SnackbarHostState() }
    val res = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var tab by rememberSaveable { mutableStateOf(Tab.WEEK) }
    // The appointment whose detail sheet is open; looked up again in the plan shown, so it shows every change at once.
    var selected by remember { mutableStateOf<SheetKey?>(null) }
    var personalEdit by remember { mutableStateOf<Timed?>(null) }
    var pickDates by remember { mutableStateOf<Timed?>(null) }
    var manualEdit by remember { mutableStateOf<ManualTarget?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // The course the subject editor was opened from (its part of the short name comes first).
    var editingCourse by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var addChoice by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var qr by remember { mutableStateOf<QrShown?>(null) }
    var friendShareMenu by remember { mutableStateOf(false) }
    var nameChoice by rememberSaveable { mutableStateOf<Pair<String, ShareAction>?>(null) }
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
    // While the drawer's choice moves the pager: the page next to the target it jumps to first counts as settled for a
    // moment, and selecting that person would cancel the slide on to the one chosen. (Read in the flow: once the move
    // ends, also one that a swipe interrupted, whoever the pager then sits on counts.)
    var following by remember { mutableStateOf(false) }
    LaunchedEffect(people) {
        snapshotFlow { if (following) null else people.settledPage }.collect { i ->
            if (i != null) latestPages.getOrNull(i)?.let { vm.select(it.person.id) }
        }
    }
    LaunchedEffect(selectedId, pages.map { it.person.id }) {
        val i = pages.indexOfFirst { it.person.id == selectedId }
        if (i >= 0 && i != people.currentPage && !people.isScrollInProgress) {
            following = true
            try {
                // From the drawer: slide to that person (from the page next to it, so far targets don't scroll through all).
                if (animate && people.layoutInfo.visiblePagesInfo.isNotEmpty()) {
                    if (kotlin.math.abs(i - people.currentPage) > 1) people.scrollToPage(if (i > people.currentPage) i - 1 else i + 1)
                    people.animateScrollToPage(i, animationSpec = tween(Motion.Long, easing = Motion.Standard))
                } else {
                    people.scrollToPage(i)
                }
            } finally {
                following = false
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
                else -> qr = vm.myQr()?.let { QrShown(it, null, myShare.name.orEmpty(), myPlan?.planDate) }
                    ?: run { snackbar.showSnackbar(res.getString(R.string.share_failed)); null }
            }
        }
    }

    /** A friend's plan passed on as they shared it (file or QR). */
    fun shareFriend(friend: Person, planDate: LocalDate?, action: ShareAction, name: String? = null) {
        if (name == null && friend.renamed) {
            nameChoice = friend.id to action
            return
        }
        val shareName = name ?: friend.name
        scope.launch {
            when (action) {
                ShareAction.FILE -> vm.writeFriendShareFile(friend.id, shareName)?.let { openShareSheet(context, it) }
                    ?: snackbar.showSnackbar(res.getString(R.string.share_failed))
                ShareAction.QR -> qr = vm.friendQr(friend.id, shareName)?.let { QrShown(it, friend.id, shareName, planDate) }
                    ?: run { snackbar.showSnackbar(res.getString(R.string.share_failed)); null }
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
                is UiMessage.Hid -> {
                    val r = snackbar.showSnackbar(
                        res.getString(R.string.hidden_snackbar, msg.what),
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
                        expandedHeight = if (compact) 48.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }) {
                                Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.open_drawer))
                            }
                        },
                        title = { PagerTitle(people, pages, animate) },
                        // Like the title: the icons of the two people around the pager's position, faded with it.
                        actions = {
                            PagerLayers(people, pages, animate, Alignment.CenterEnd) { shown, near ->
                                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!shown.isMe) {
                                        val friend = shown.person
                                        FavoriteButton(friend.favorite, friend.displayName) { vm.setFavorite(friend.id, !friend.favorite) }
                                        Box {
                                            IconButton(onClick = { friendShareMenu = true }) {
                                                Icon(painterResource(R.drawable.ic_share), stringResource(R.string.friend_share, friend.displayName))
                                            }
                                            // Only the nearer page's menu (both pages' icons are there while switching).
                                            DropdownMenu(expanded = friendShareMenu && near, onDismissRequest = { friendShareMenu = false }) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.share_as_file)) },
                                                    leadingIcon = { Icon(painterResource(R.drawable.ic_share), null) },
                                                    onClick = { friendShareMenu = false; shareFriend(friend, shown.planDate, ShareAction.FILE) },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.friend_share_qr)) },
                                                    leadingIcon = { Icon(painterResource(R.drawable.ic_qr_code_2), null) },
                                                    onClick = { friendShareMenu = false; shareFriend(friend, shown.planDate, ShareAction.QR) },
                                                )
                                            }
                                        }
                                        IconButton(onClick = { vm.select(friend.id); friendDetails = true }) {
                                            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.friend_details))
                                        }
                                    } else {
                                        IconButton(onClick = { manualEdit = ManualTarget(null) }) {
                                            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.manual_add))
                                        }
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
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (plan?.isEmpty == false && !compact) {
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
            ) { padding ->
                val p = plan
                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(padding)) {
                if (plan?.isEmpty == false && compact) {
                    // A low screen: the tabs move to the left, so the plan keeps the height.
                    NavigationRail {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        NavigationRailItem(selected = tab == Tab.WEEK, onClick = { tab = Tab.WEEK }, icon = { Icon(WeekIcon, null) }, label = { Text(stringResource(R.string.tab_week)) })
                        NavigationRailItem(selected = tab == Tab.DAY, onClick = { tab = Tab.DAY }, icon = { Icon(TodayIcon, null) }, label = { Text(stringResource(R.string.tab_day)) })
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (pages.isNotEmpty()) {
                        // One page per person (drawer order); week and day stay where they are when switching.
                        val edge = remember(people, animate) { EdgeOnlyConnection(people, animate) }
                        LaunchedEffect(edge) { edge.keepSettled() }
                        val userMoving = remember(people) { Moving(people) }
                        VerticalPager(
                            people, Modifier.fillMaxSize().edgeTouches(edge), beyondViewportPageCount = composedAround,
                            // Moved by touch only through EdgeOnlyConnection (its own drag raced the content's).
                            userScrollEnabled = false,
                            key = { pages.getOrNull(it)?.person?.id ?: it },
                        ) { i ->
                            val page = pages[i]
                            val active = i == people.settledPage
                            val personShown = remember(people, i) { { people.layoutInfo.visiblePagesInfo.any { it.index == i } } }
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().edgeSwitching(edge)) {
                                if (page.legacyShare) LegacyShareHint()
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
                                    modifier = Modifier.weight(1f),
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
                                    CompositionLocalProvider(LocalMoving provides userMoving, LocalPersonShown provides personShown) {
                                        when (shown) {
                                            null -> EmptyState(onImport)
                                            Tab.WEEK -> WeekScreen(page, now, week, active, fitLandscape, onWeek = { week = it }, onOpen = { selected = SheetKey.of(page, it) }, modifier = Modifier.fillMaxSize())
                                            Tab.DAY -> DayScreen(page, now, day, active, onDay = { day = it }, onOpen = { selected = SheetKey.of(page, it) }, modifier = Modifier.fillMaxSize())
                                        }
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
                    val item = selected?.let { k -> p?.let { k.find(it) } }
                    // Gone (hidden, removed, another person shown): the sheet closes.
                    LaunchedEffect(selected, item == null) { if (selected != null && item == null) selected = null }
                    // An action opened from the sheet (editor, dialog) hides it while open; closing it in any way
                    // (save, cancel, back, outside) brings the sheet back.
                    val subOpen = personalEdit != null || pickDates != null || editing != null || manualEdit != null
                    if (p != null && item != null && !subOpen) {
                        DetailSheet(
                            p, item,
                            onEditSubject = { editing = it.key; editingCourse = item.event.courseKey },
                            onEditPersonal = { personalEdit = item },
                            onCancelForMe = { cancel -> vm.setCancelledForMe(item.ref, item.event.title, cancel) },
                            onPickDates = { pickDates = item },
                            onEditManual = { manualEdit = ManualTarget(item.event.uid) },
                            onDeleteManual = { selected = null; vm.deleteManual(item.event.uid) },
                            onRemoveAdded = { selected = null; vm.removeAdded(item, item.event.title) },
                            onHide = { h -> selected = null; vm.hide(h, hiddenName(res, h)) },
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
                            hiddenDates = p.hidden,
                            onDismiss = { pickDates = null },
                        )
                    }
                }
                }
            }
        }
        Overlay(showSettings) {
            SettingsScreen(
                vm, myPlan, myShare.name,
                onEditSubject = { editing = it.key; editingCourse = null },
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
        Overlay(qr) { shown ->
            QrShowScreen(
                text = shown.code.text,
                omitsDetails = shown.code.omitsDetails,
                name = shown.name,
                stand = standText(shown.planDate),
                onShareFile = {
                    qr = null
                    val friend = shown.friendId?.let { id -> friends.firstOrNull { it.person.id == id } }
                    if (friend != null) shareFriend(friend.person, shown.planDate, ShareAction.FILE, shown.name) else share(ShareAction.FILE)
                },
                onClose = { qr = null },
            )
        }
        // Above the pages that cover the plan (settings), so an undo from there is seen: over the bottom bar on the plan.
        val overPlan = !showSettings && !scanning && qr == null && plan?.isEmpty == false
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (overPlan) 80.dp else 0.dp),
        )
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
    nameChoice?.let { (id, action) ->
        val friend = friends.firstOrNull { it.person.id == id }
        if (friend == null) {
            nameChoice = null
        } else {
            ShareNameChoiceDialog(
                original = friend.person.name,
                local = friend.person.displayName,
                onChoose = { name -> nameChoice = null; shareFriend(friend.person, friend.planDate, action, name) },
                onDismiss = { nameChoice = null },
            )
        }
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
            current, key, editingCourse,
            onSave = { name, color, typed ->
                current.subjects.firstOrNull { it.key == key }?.let { vm.setSubjectStyle(it, name, color, typed) }
                editing = null
            },
            onMove = vm::moveCourse,
            onRhythm = vm::setRhythm,
            onHide = { h -> editing = null; selected = null; vm.hide(h, hiddenName(res, h)) },
            onDismiss = { editing = null },
            courseActions = if (current.isMe) CourseActions(
                editManual = { uid -> editing = null; manualEdit = ManualTarget(uid) },
                deleteManual = { uid -> editing = null; selected = null; vm.deleteManual(uid) },
                removeAdded = { c -> editing = null; selected = null; vm.removeAddedCourse(c) },
            ) else null,
        )
    }
}

/**
 * The top bar title: "Stundenplan MD" on my plan, the friend's name on theirs. Follows the people pager's live position
 * (not the settled page), so it moves with the finger: the name of the page leaving slides and fades out, the next one
 * comes in. Without animations the nearer page's name is shown at once.
 */
@Composable
private fun PagerTitle(state: androidx.compose.foundation.pager.PagerState, pages: List<PlanUi>, animate: Boolean) {
    if (pages.isEmpty()) {
        Box(Modifier.height(TopBarBlock), contentAlignment = Alignment.CenterStart) { TitleText(null) }
        return
    }
    PagerLayers(state, pages, animate, Alignment.CenterStart) { page, _ -> TitleText(page) }
}

/** Height of the title and action blocks: fixed, so a subtitle (an .ics friend's) never changes the bar or moves a name. */
private val TopBarBlock = 48.dp

/**
 * The top bar part of the two pages around the pager's position, stacked: each laid out with its own content, centered
 * on its own in a block of fixed height, faded and slid by its distance from the position (read while drawing, no
 * recomposition per frame). Only the nearer one takes taps and is read out; without animations only it is shown.
 */
@Composable
private fun PagerLayers(
    state: androidx.compose.foundation.pager.PagerState,
    pages: List<PlanUi>,
    animate: Boolean,
    alignment: Alignment,
    content: @Composable (page: PlanUi, near: Boolean) -> Unit,
) {
    val lower by remember(state) { androidx.compose.runtime.derivedStateOf { kotlin.math.floor(state.currentPage + state.currentPageOffsetFraction).toInt() } }
    // The page after [lower] only while the pager is between the two (settled, it is out of sight: not composed at all).
    val between by remember(state) { androidx.compose.runtime.derivedStateOf { state.currentPageOffsetFraction != 0f } }
    val nearest by remember(state) { androidx.compose.runtime.derivedStateOf { kotlin.math.round(state.currentPage + state.currentPageOffsetFraction).toInt() } }
    Box(Modifier.height(TopBarBlock).clipToBounds(), contentAlignment = alignment) {
        // The farther page first, so the nearer one lies on top and gets the taps.
        val shown = (if (between) lower..lower + 1 else nearest..nearest).filter { pages.getOrNull(it) != null }.sortedBy { if (it == nearest) 1 else 0 }
        for (i in shown) {
            val page = pages[i]
            key(page.person.id) {
                val near = i == nearest
                Box(
                    Modifier
                        .graphicsLayer {
                            val d = i - (state.currentPage + state.currentPageOffsetFraction)
                            if (animate) {
                                alpha = (1f - kotlin.math.abs(d) * 1.6f).coerceIn(0f, 1f)
                                translationY = d * size.height * 0.8f
                            } else {
                                alpha = if (kotlin.math.abs(d) < 0.5f) 1f else 0f
                            }
                        }
                        .then(if (near) Modifier else Modifier.clearAndSetSemantics { }),
                    contentAlignment = alignment,
                ) { content(page, near) }
                // Taps on the farther page's icons go nowhere.
                if (!near) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
            }
        }
    }
}

@Composable
private fun TitleText(page: PlanUi?, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier) {
        Text(
            page?.takeIf { !it.isMe }?.person?.displayName ?: stringResource(R.string.app_name),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (page?.person?.fromIcs == true) {
            Text(
                stringResource(R.string.from_ics),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The appointment of the open detail sheet: whose plan, its [ref] (stays the same across my changes) and event UID. */
private data class SheetKey(val personId: String, val ref: de.luwify.stundenplanmd.core.schedule.OccurrenceRef, val uid: String) {
    fun find(plan: PlanUi): Timed? {
        if (plan.person.id != personId) return null
        val all: List<Timed> = plan.schedule.occurrences + plan.schedule.cancellations
        return all.firstOrNull { it.ref == ref && it.event.uid == uid } ?: all.firstOrNull { it.ref == ref }
    }

    companion object {
        fun of(plan: PlanUi, item: Timed) = SheetKey(plan.person.id, item.ref, item.event.uid)
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

/** A friend's plan shared by an older version: biweekly dates after New Year were converted and may differ. */
@Composable
private fun LegacyShareHint() {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_warning), null,
            Modifier.size(18.dp),
            tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.legacy_share_hint),
            Modifier.padding(start = 12.dp),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
