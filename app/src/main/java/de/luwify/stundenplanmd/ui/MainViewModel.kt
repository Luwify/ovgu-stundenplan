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
}

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

    init {
        viewModelScope.launch { repo.load() }
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
                when (val r = repo.importIcs(text)) {
                    is ImportOutcome.Imported -> UiMessage.Imported(r.events, r.previous)
                    ImportOutcome.NotACalendar -> UiMessage.NotACalendar
                    ImportOutcome.NoEvents -> UiMessage.NoEvents
                },
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
