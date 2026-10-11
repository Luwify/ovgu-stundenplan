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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.schedule.Timed

private enum class Tab { WEEK, TODAY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, onImport: () -> Unit) {
    val plan by vm.plan.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val res = LocalResources.current
    var tab by rememberSaveable { mutableStateOf(Tab.WEEK) }
    var selected by remember { mutableStateOf<Timed?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

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
                UiMessage.ImportFailed -> snackbar.showSnackbar(res.getString(R.string.import_failed))
                UiMessage.NotACalendar -> snackbar.showSnackbar(res.getString(R.string.import_not_calendar))
                UiMessage.NoEvents -> snackbar.showSnackbar(res.getString(R.string.import_no_events))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (plan?.isEmpty == false) {
                        IconButton(onClick = onImport) { Icon(ImportIcon, stringResource(R.string.import_file)) }
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
                        selected = tab == Tab.TODAY,
                        onClick = { tab = Tab.TODAY },
                        icon = { Icon(TodayIcon, null) },
                        label = { Text(stringResource(R.string.tab_today)) },
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
                tab == Tab.WEEK -> WeekScreen(p, now, onOpen = { selected = it }, Modifier.fillMaxSize())
                else -> TodayScreen(p, now, onOpen = { selected = it }, Modifier.fillMaxSize())
            }
            val item = selected
            if (p != null && item != null) {
                DetailSheet(p, item, onEditSubject = { selected = null; editing = it.key }, onDismiss = { selected = null })
            }
            val key = editing
            if (p != null && key != null) {
                SubjectEditor(
                    p, key,
                    onSave = { name, color ->
                        p.subjects.firstOrNull { it.key == key }?.let { vm.setSubjectStyle(it, name, color) }
                        editing = null
                    },
                    onMove = vm::moveCourse,
                    onPickCustomColor = null,
                    onDismiss = { editing = null },
                )
            }
        }
    }
}
