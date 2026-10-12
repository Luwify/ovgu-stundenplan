package de.luwify.stundenplanmd.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.luwify.stundenplanmd.StundenplanApp
import de.luwify.stundenplanmd.core.model.Course
import de.luwify.stundenplanmd.core.model.Subject
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.schedule.Schedule
import de.luwify.stundenplanmd.data.Backup
import de.luwify.stundenplanmd.data.BackupCodec
import de.luwify.stundenplanmd.data.BackupResult
import de.luwify.stundenplanmd.data.StoreRead
import de.luwify.stundenplanmd.data.IcsSource
import de.luwify.stundenplanmd.data.ImportOutcome
import de.luwify.stundenplanmd.data.StoredData
import de.luwify.stundenplanmd.data.settings.CustomThemeSpec
import de.luwify.stundenplanmd.data.settings.ThemeChoice
import de.luwify.stundenplanmd.data.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** My plan as the UI needs it. */
data class PlanUi(
    val schedule: Schedule,
    val subjects: List<Subject>,
    /** Course key -> subject. */
    val subjectOf: Map<String, Subject>,
    val importedAt: java.time.Instant?,
) {
    val isEmpty: Boolean get() = schedule.events.isEmpty()
}

sealed interface UiMessage {
    data class Imported(val events: Int, val previous: StoredData) : UiMessage
    data object ImportFailed : UiMessage
    data object NotACalendar : UiMessage
    data object NoEvents : UiMessage

    /** The stored plan could not be read; the file was set aside as [keptAs]. */
    data class DataUnreadable(val keptAs: String?) : UiMessage
    data object BackupExported : UiMessage
    data object BackupFailed : UiMessage
    data object BackupNotABackup : UiMessage
    data object BackupTooNew : UiMessage
    data class BackupRestored(val previous: Snapshot) : UiMessage
}

/** Everything a backup import replaces, kept for undo. */
data class Snapshot(val data: StoredData, val ics: String?, val theme: ThemeChoice)

/** A backup file read and waiting for the user's confirmation. */
data class PendingBackup(val backup: Backup)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as StundenplanApp).container
    private val repo = container.plans
    private val customDraft = MutableStateFlow<CustomThemeSpec?>(null)

    /** Null until DataStore answered: the activity draws nothing until then, so there is no theme flash. */
    val theme: StateFlow<ThemeChoice?> = combine(container.theme.themeMode, container.theme.customTheme, customDraft) { mode, custom, draft ->
        ThemeChoice(mode, draft ?: custom)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.theme.setThemeMode(mode) }
    }

    /**
     * Changes the custom theme. [commit] false (slider moving) only updates the live preview; true saves it. The
     * draft is dropped only after the save, so the theme never jumps back for a frame.
     */
    fun setCustomTheme(spec: CustomThemeSpec, commit: Boolean = true) {
        customDraft.value = spec
        if (commit) {
            viewModelScope.launch {
                container.theme.setCustomTheme(spec)
                if (customDraft.value == spec) customDraft.value = null
            }
        }
    }

    /** Null while loading. */
    val plan: StateFlow<PlanUi?> = repo.data.filterNotNull().map { data ->
        val events = data.myPlan?.events.orEmpty()
        val subjects = Subjects.build(events, data.courseSettings)
        PlanUi(Schedule(events), subjects, subjects.flatMap { s -> s.courses.map { it.key to s } }.toMap(), data.myPlan?.importedAt)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Wall-clock time, updated at every full minute (now line, Today list). */
    val now: StateFlow<LocalDateTime> = flow {
        while (true) {
            val t = LocalDateTime.now()
            emit(t)
            delay(ChronoUnit.MILLIS.between(t, t.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)).coerceAtLeast(1))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalDateTime.now())

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _pendingBackup = MutableStateFlow<PendingBackup?>(null)
    val pendingBackup: StateFlow<PendingBackup?> = _pendingBackup

    init {
        viewModelScope.launch {
            container.theme.migrate()
            (repo.load() as? StoreRead.Unreadable)?.let { _messages.send(UiMessage.DataUnreadable(it.keptAs)) }
        }
    }

    /** Writes everything (plan, raw .ics, settings, theme) as one backup file to [uri]. */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val ok = runCatching {
                val data = repo.data.value ?: error("not loaded")
                val text = BackupCodec.encode(data, repo.readIcs(), theme.value ?: ThemeChoice(ThemeMode.SYSTEM))
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.encodeToByteArray()) }
                }
            }.isSuccess
            _messages.send(if (ok) UiMessage.BackupExported else UiMessage.BackupFailed)
        }
    }

    /** Reads a backup file; the user confirms before anything is replaced. */
    fun readBackup(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            when (val r = text?.let(BackupCodec::decode)) {
                null -> _messages.send(UiMessage.BackupFailed)
                is BackupResult.Ok -> _pendingBackup.value = PendingBackup(r.backup)
                BackupResult.NotABackup -> _messages.send(UiMessage.BackupNotABackup)
                BackupResult.TooNew -> _messages.send(UiMessage.BackupTooNew)
                is BackupResult.Broken -> _messages.send(UiMessage.BackupFailed)
            }
        }
    }

    fun cancelBackup() {
        _pendingBackup.value = null
    }

    fun confirmBackup() {
        val backup = _pendingBackup.value?.backup ?: return
        _pendingBackup.value = null
        viewModelScope.launch {
            val before = Snapshot(repo.data.value ?: StoredData(), repo.readIcs(), theme.value ?: ThemeChoice(ThemeMode.SYSTEM))
            runCatching {
                repo.replaceAll(backup.data, backup.ics)
                container.theme.restore(backup.theme.toChoice())
            }.onSuccess { _messages.send(UiMessage.BackupRestored(before)) }.onFailure { _messages.send(UiMessage.BackupFailed) }
        }
    }

    fun undoBackup(previous: Snapshot) {
        viewModelScope.launch {
            repo.replaceAll(previous.data, previous.ics)
            container.theme.restore(previous.theme)
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val text = withContext(Dispatchers.IO) { IcsSource.read(resolver, uri) }
            if (text == null) {
                _messages.send(UiMessage.ImportFailed)
                return@launch
            }
            repo.load()
            _messages.send(
                runCatching { repo.importIcs(text) }.fold(
                    onSuccess = { r ->
                        when (r) {
                            is ImportOutcome.Imported -> UiMessage.Imported(r.events, r.previous)
                            ImportOutcome.NotACalendar -> UiMessage.NotACalendar
                            ImportOutcome.NoEvents -> UiMessage.NoEvents
                        }
                    },
                    onFailure = { UiMessage.ImportFailed },
                ),
            )
        }
    }

    fun undoImport(previous: StoredData) {
        viewModelScope.launch { repo.restore(previous) }
    }

    fun setSubjectStyle(subject: Subject, shortName: String?, color: Int?) {
        viewModelScope.launch { repo.updateSettings { Subjects.setStyle(it, subject, shortName, color) } }
    }

    fun moveCourse(course: Course, target: String) {
        val subjects = plan.value?.subjects.orEmpty()
        viewModelScope.launch { repo.updateSettings { Subjects.move(it, course, target, subjects) } }
    }
}
