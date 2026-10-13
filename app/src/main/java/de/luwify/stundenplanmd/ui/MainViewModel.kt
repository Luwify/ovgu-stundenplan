package de.luwify.stundenplanmd.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.luwify.stundenplanmd.StundenplanApp
import de.luwify.stundenplanmd.core.model.Course
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Subject
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.schedule.Schedule
import de.luwify.stundenplanmd.core.schedule.Occurrence
import de.luwify.stundenplanmd.core.schedule.OccurrenceKey
import de.luwify.stundenplanmd.core.schedule.Timed
import de.luwify.stundenplanmd.core.schedule.Together
import de.luwify.stundenplanmd.core.schedule.OccurrenceRef
import de.luwify.stundenplanmd.core.schedule.OccurrenceValues
import de.luwify.stundenplanmd.core.schedule.Personal
import de.luwify.stundenplanmd.core.schedule.PersonalChanges
import de.luwify.stundenplanmd.core.schedule.ManualCourses
import de.luwify.stundenplanmd.core.model.ManualCourse
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.data.Backup
import de.luwify.stundenplanmd.AppLanguage
import de.luwify.stundenplanmd.data.BackupCodec
import de.luwify.stundenplanmd.data.BackupResult
import de.luwify.stundenplanmd.data.StoreRead
import de.luwify.stundenplanmd.data.IcsSource
import de.luwify.stundenplanmd.data.ImportOutcome
import de.luwify.stundenplanmd.data.StoredData
import de.luwify.stundenplanmd.data.FriendImport
import de.luwify.stundenplanmd.data.MyShare
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import java.io.File
import java.time.LocalDate
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.Plan
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.ShareResult
import de.luwify.stundenplanmd.core.share.SharedPlan
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

/** A plan (mine or a friend's) as the UI needs it. */
data class PlanUi(
    val person: Person,
    val schedule: Schedule,
    val subjects: List<Subject>,
    /** Course key -> subject. */
    val subjectOf: Map<String, Subject>,
    val importedAt: java.time.Instant?,
    /** The course settings shown (for a friend: my overrides, mine for shared courses, theirs). */
    val settings: Map<String, CourseSetting>,
    /** "Stand": my last import, or the day a friend imported their .ics. */
    val planDate: LocalDate?,
    /** Friend's plan: the day they shared it. */
    val exportedOn: LocalDate? = null,
    /** Occurrences attended together with others: their person ids ([StoredData.ME] = me). */
    val together: Map<OccurrenceKey, List<String>> = emptyMap(),
    /** Display names of friends by id. */
    val names: Map<String, String> = emptyMap(),
    /** Ids of my favorite friends. */
    val favorites: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = schedule.events.isEmpty()
    val isMe: Boolean get() = person.isMe

    /** Who else attends [item] (person ids); empty for cancellations. */
    fun togetherWith(item: Timed): List<String> = (item as? Occurrence)?.let { together[OccurrenceKey.of(it)] }.orEmpty()

    /** At least one favorite friend attends [item]: the together icon turns red. */
    fun withFavorite(item: Timed): Boolean = togetherWith(item).any { it in favorites }

    companion object {
        /** Me and every friend, in drawer order (my plan first); each schedule is expanded once. */
        fun all(data: StoredData): List<PlanUi> {
            val schedules = data.plans.associate { it.personId to data.schedule(it) } + (StoredData.ME to data.mySchedule())
            val ids = listOf(StoredData.ME) + data.friends.map { it.id }
            return ids.map { of(data, it, schedules) }
        }

        fun of(data: StoredData, personId: String, schedules: Map<String, Schedule> = emptyMap()): PlanUi {
            val person = data.person(personId) ?: Person(StoredData.ME, "", isMe = true)
            val plan = data.planOf(person.id)
            val events = if (person.isMe) data.myEvents else plan?.events.orEmpty()
            val settings = if (person.isMe) data.courseSettings else Subjects.forFriend(
                events,
                local = data.friendSettings[person.id].orEmpty(),
                mine = Subjects.resolved(data.myEvents, data.courseSettings, SubjectColors::defaultColor),
                shared = plan?.sharedSettings.orEmpty(),
            )
            val subjects = Subjects.build(events, settings)
            val schedule = schedules[person.id] ?: if (person.isMe) data.mySchedule() else plan?.let(data::schedule) ?: Schedule(events)
            // In my plan: friends. In a friend's: me and the other friends.
            val others = data.people.filter { it.id != person.id }
                .mapNotNull { p -> (schedules[p.id] ?: data.planOf(p.id)?.let(data::schedule))?.let { p.id to it } }
            return PlanUi(
                person = person,
                schedule = schedule,
                subjects = subjects,
                subjectOf = subjects.flatMap { s -> s.courses.map { it.key to s } }.toMap(),
                importedAt = plan?.importedAt,
                settings = settings,
                planDate = if (person.isMe) plan?.importedAt?.atZone(PlanZone)?.toLocalDate() else plan?.planDate,
                exportedOn = plan?.exportedOn,
                together = Together.of(schedule, others),
                names = data.friends.associate { it.id to it.displayName },
                favorites = data.friends.filter { it.favorite }.map { it.id }.toSet(),
            )
        }
    }
}

/** My QR code's text; [omitsDetails]: the file share has more (notes, ...). */
data class MyQr(val text: String, val omitsDetails: Boolean)

/** A friend in the drawer: name, "Stand" and their schedule for the now-line. */
data class FriendEntry(val person: Person, val planDate: LocalDate?, val schedule: Schedule)

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
    data class FriendImported(val name: String, val replaced: Boolean, val previous: StoredData) : UiMessage
    data object ShareOwn : UiMessage
    data class FriendDeleted(val name: String, val previous: StoredData) : UiMessage
    data object NoPlanToShare : UiMessage
    data object ShareFailed : UiMessage
    data object ShareTooNew : UiMessage
    data object ShareBroken : UiMessage

    /** A date cancelled for me ([cancelled]) or back again; undo restores [previous]. */
    data class ManualDeleted(val title: String, val previous: StoredData) : UiMessage
    data class CancelledForMe(val title: String, val date: LocalDate, val cancelled: Boolean, val previous: List<PersonalChange>) : UiMessage
}

/** A friend's plan read from a file or QR code, waiting for the user's confirmation. */
data class PendingFriend(val plan: SharedPlan, val existing: Person?, val existingPlan: Plan?)

/** Everything a backup import replaces, kept for undo. */
data class Snapshot(
    val data: StoredData,
    val ics: String?,
    val theme: ThemeChoice,
    val language: AppLanguage? = null,
    val updates: de.luwify.stundenplanmd.data.UpdatesBackup? = null,
)

/** A backup file read and waiting for the user's confirmation. */
data class PendingBackup(val backup: Backup)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as StundenplanApp).container
    private val repo = container.plans
    val updates = container.updates
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

    /** Whose plan is shown: [StoredData.ME] or a friend's id. */
    private val _selected = MutableStateFlow(StoredData.ME)
    val selected: StateFlow<String> = _selected

    fun select(personId: String) {
        _selected.value = personId
    }

    /** Every plan in drawer order (mine first): the pages of the vertical pager. Empty while loading. */
    val pages: StateFlow<List<PlanUi>> = repo.data.filterNotNull().map { PlanUi.all(it) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The plan shown (mine or the selected friend's; mine again when that friend is gone). Null while loading. */
    val plan: StateFlow<PlanUi?> = combine(pages, _selected) { pages, id ->
        pages.firstOrNull { it.person.id == id } ?: pages.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** My plan, for settings and backup. Null while loading. */
    val myPlan: StateFlow<PlanUi?> = pages.map { it.firstOrNull() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val friends: StateFlow<List<FriendEntry>> = repo.data.filterNotNull().map { data ->
        data.friends.map { p -> data.planOf(p.id).let { FriendEntry(p, it?.planDate, it?.let(data::schedule) ?: Schedule(emptyList())) } }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
                val language = AppLanguage.key(AppLanguage.current(getApplication()))
                val text = BackupCodec.encode(data, repo.readIcs(), theme.value ?: ThemeChoice(ThemeMode.SYSTEM), language, updates.backupState())
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
            val app = getApplication<Application>()
            val before = Snapshot(
                repo.data.value ?: StoredData(), repo.readIcs(), theme.value ?: ThemeChoice(ThemeMode.SYSTEM), AppLanguage.current(app),
                updates.backupState(),
            )
            runCatching {
                repo.replaceAll(backup.data, backup.ics)
                container.theme.restore(backup.theme.toChoice())
                backup.updates?.let { updates.restore(it) }
            }.onSuccess {
                _messages.send(UiMessage.BackupRestored(before))
                // Last: a different language recreates the activity (the restored data is saved by then).
                AppLanguage.fromKey(backup.language)?.let(::applyLanguage)
            }.onFailure { _messages.send(UiMessage.BackupFailed) }
        }
    }

    fun undoBackup(previous: Snapshot) {
        viewModelScope.launch {
            repo.replaceAll(previous.data, previous.ics)
            container.theme.restore(previous.theme)
            previous.updates?.let { updates.restore(it) }
            previous.language?.let(::applyLanguage)
        }
    }

    /** Sets the app language if it differs; before Android 13 the screen recreates the activity (see [recreate]). */
    private fun applyLanguage(language: AppLanguage) {
        val app = getApplication<Application>()
        if (AppLanguage.current(app) == language) return
        AppLanguage.set(app, language)
        if (android.os.Build.VERSION.SDK_INT < 33) _recreate.value = true
    }

    private val _recreate = MutableStateFlow(false)

    /** API < 33: the activity must be recreated to show a restored language; the screen resets this. */
    val recreate: StateFlow<Boolean> = _recreate

    fun recreated() {
        _recreate.value = false
    }

    private val _pendingFriend = MutableStateFlow<PendingFriend?>(null)
    val pendingFriend: StateFlow<PendingFriend?> = _pendingFriend

    /** A picked, opened or shared file: a friend's plan (content starts with SPMD) or my .ics. */
    fun import(uri: Uri) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val bytes = withContext(Dispatchers.IO) { IcsSource.readBytes(resolver, uri) }
            if (bytes == null) {
                _messages.send(UiMessage.ImportFailed)
                return@launch
            }
            if (ShareCodec.looksLikeShare(bytes)) {
                receiveShare(withContext(Dispatchers.Default) { ShareCodec.parse(bytes) })
                return@launch
            }
            val text = withContext(Dispatchers.Default) { IcsSource.text(bytes) }
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

    /** A scanned QR text. */
    fun importScanned(text: String) {
        viewModelScope.launch { receiveShare(withContext(Dispatchers.Default) { ShareCodec.parse(text) }) }
    }

    private suspend fun receiveShare(result: ShareResult) {
        repo.load()
        val data = repo.data.value ?: StoredData()
        when (result) {
            is ShareResult.Ok -> if (result.plan.personId == data.share.id) {
                _messages.send(UiMessage.ShareOwn)
            } else {
                _pendingFriend.value = PendingFriend(result.plan, data.person(result.plan.personId), data.planOf(result.plan.personId))
            }
            ShareResult.TooNew -> _messages.send(UiMessage.ShareTooNew)
            ShareResult.NotAShare, is ShareResult.Broken -> _messages.send(UiMessage.ShareBroken)
        }
    }

    fun cancelFriend() {
        _pendingFriend.value = null
    }

    fun confirmFriend() {
        val pending = _pendingFriend.value ?: return
        _pendingFriend.value = null
        viewModelScope.launch {
            val msg = runCatching { repo.importFriend(pending.plan) }.fold(
                onSuccess = { r ->
                    when (r) {
                        is FriendImport.Imported -> UiMessage.FriendImported(r.person.displayName, r.replaced, r.previous)
                        FriendImport.Own -> UiMessage.ShareOwn
                    }
                },
                onFailure = { UiMessage.ImportFailed },
            )
            _messages.send(msg)
        }
    }

    /** My share identity (id made on first share); the name is asked before the first share. */
    val myShare: StateFlow<MyShare> = repo.data.filterNotNull().map { it.share }.stateIn(viewModelScope, SharingStarted.Eagerly, MyShare())

    suspend fun setShareName(name: String) {
        repo.shareIdentity(name)
    }

    /** My plan as shared: events, and short names and colors of every course as shown (automatic ones resolved). */
    suspend fun mySharedPlan(): SharedPlan? {
        val identity = repo.shareIdentity()
        val data = repo.data.value ?: return null
        val plan = data.myPlan
        val name = identity.name ?: return null
        // Manual courses go with my own changes ("Eigene Änderungen teilen"), without their notes (notes are mine).
        val withChanges = data.share.withChanges
        val events = plan?.events.orEmpty() +
            if (withChanges) ManualCourses.merge(plan?.events.orEmpty(), data.manualCourses).filter { it.isManual }.map { it.copy(notes = "") } else emptyList()
        if (events.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            SharedPlan(
                personId = identity.id!!,
                name = name,
                exportedOn = LocalDate.now(PlanZone),
                planDate = (plan?.importedAt ?: java.time.Instant.now()).atZone(PlanZone).toLocalDate(),
                events = events,
                settings = Subjects.resolved(events, data.courseSettings, SubjectColors::defaultColor),
                complete = true,
                // Notes never leave the phone ([PersonalChange.forSharing]); the rest only while the setting is on.
                changes = if (withChanges) data.personalChanges.mapNotNull { it.forSharing() } else emptyList(),
            )
        }
    }

    /** My plan as QR text, and whether the QR leaves out details the file has. */
    suspend fun myQr(): MyQr? = mySharedPlan()?.let { withContext(Dispatchers.Default) { MyQr(ShareCodec.qrText(it), ShareCodec.qrOmitsDetails(it)) } }

    /** Writes my plan as a share file into cache/share (for the share sheet); null on failure. */
    suspend fun writeShareFile(): File? {
        val plan = mySharedPlan() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(getApplication<Application>().cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val safe = plan.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "Stundenplan" }
                File(dir, "Stundenplan $safe.${ShareCodec.FILE_EXTENSION}").apply { writeBytes(ShareCodec.file(plan)) }
            }.getOrNull()
        }
    }

    fun undoImport(previous: StoredData) {
        viewModelScope.launch { repo.restore(previous) }
    }

    /** In my plan: my settings. In a friend's: my local overrides for them; no short name and color = their defaults. */
    fun setSubjectStyle(subject: Subject, shortName: String?, color: Int?) {
        val p = plan.value ?: return
        viewModelScope.launch {
            if (p.isMe) {
                repo.updateSettings { Subjects.setStyle(it, subject, shortName, color) }
            } else {
                val edited = Subjects.setStyle(p.settings, subject, shortName, color)
                repo.updateFriendSettings(p.person.id) { local ->
                    val keys = subject.courses.map { it.key }.toSet()
                    if (shortName.isNullOrBlank() && color == null) {
                        local.filterKeys { it !in keys || local[it]?.subject != null }
                            .mapValues { (k, v) -> if (k in keys) v.copy(shortName = null, color = null) else v }
                    } else {
                        Subjects.overrides(local, p.settings, edited)
                    }
                }
            }
        }
    }

    fun moveCourse(course: Course, target: String) {
        val p = plan.value ?: return
        viewModelScope.launch {
            if (p.isMe) {
                repo.updateSettings { Subjects.move(it, course, target, p.subjects) }
            } else {
                repo.updateFriendSettings(p.person.id) { local -> Subjects.overrides(local, p.settings, Subjects.move(p.settings, course, target, p.subjects)) }
            }
        }
    }

    /** Own changes no date of my plan matches any more (after a re-import): the cleanup list in the settings. */
    val orphans: StateFlow<List<PersonalChange>> = repo.data.filterNotNull()
        .map { PersonalChanges.orphans(it.myEvents, it.personalChanges) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Saves note, s.t., room and time of [p] for that date only or, [series], for every later date of its series. */
    fun savePersonal(p: Personal, title: String, series: Boolean, desired: OccurrenceValues) {
        viewModelScope.launch {
            repo.updatePersonalChanges {
                if (series) PersonalChanges.setSeries(it, p, title, desired) else PersonalChanges.setSingle(it, p, title, desired)
            }
        }
    }

    fun setCancelledForMe(ref: OccurrenceRef, title: String, cancelled: Boolean) {
        viewModelScope.launch {
            val previous = repo.data.value?.personalChanges ?: return@launch
            repo.updatePersonalChanges { PersonalChanges.setCancelled(it, ref, title, cancelled) }
            _messages.send(UiMessage.CancelledForMe(title, ref.date, cancelled, previous))
        }
    }

    /** "Termine auswählen": [choices] = date -> takes place for me. */
    fun setDates(choices: Map<OccurrenceRef, Boolean>, title: String) {
        viewModelScope.launch {
            repo.updatePersonalChanges { changes ->
                choices.entries.fold(changes) { acc, (ref, on) -> PersonalChanges.setCancelled(acc, ref, title, !on) }
            }
        }
    }

    /** Deletes [change] (cleanup list); returns the changes before, for undo. */
    fun deleteChange(change: PersonalChange): List<PersonalChange>? {
        val previous = repo.data.value?.personalChanges ?: return null
        viewModelScope.launch { repo.updatePersonalChanges { PersonalChanges.remove(it, change) } }
        return previous
    }

    fun restorePersonal(previous: List<PersonalChange>) {
        viewModelScope.launch { repo.updatePersonalChanges { previous } }
    }

    /** Adds or replaces a manual course with its subject. */
    fun saveManual(edit: ManualEdit) {
        val p = myPlan.value ?: return
        viewModelScope.launch { repo.saveManual(edit.course, edit.setting(p)) }
    }

    fun deleteManual(id: String) {
        viewModelScope.launch {
            val title = repo.data.value?.manualCourses?.firstOrNull { it.id == id }?.title ?: return@launch
            val previous = repo.deleteManual(id) ?: return@launch
            _messages.send(UiMessage.ManualDeleted(title, previous))
        }
    }

    fun manualCourse(id: String): ManualCourse? = repo.data.value?.manualCourses?.firstOrNull { it.id == id }

    fun newManualId(): String = ManualCourse.newId(de.luwify.stundenplanmd.data.PersonIds.new())

    /** Default last date of a new series: the end of the imported semester. */
    fun manualDefaultEnd(start: LocalDate): LocalDate = ManualCourses.defaultEnd(repo.data.value?.myPlan?.events.orEmpty(), start)

    fun setShareChanges(on: Boolean) {
        viewModelScope.launch { repo.setShareChanges(on) }
    }

    fun reorderFriends(ids: List<String>) {
        viewModelScope.launch { repo.reorderFriends(ids) }
    }

    fun setFavorite(id: String, favorite: Boolean) {
        viewModelScope.launch { repo.setFavorite(id, favorite) }
    }

    fun renameFriend(id: String, name: String?) {
        viewModelScope.launch { repo.renameFriend(id, name) }
    }

    fun deleteFriend(id: String) {
        viewModelScope.launch {
            val name = repo.data.value?.person(id)?.displayName ?: return@launch
            val previous = repo.deleteFriend(id) ?: return@launch
            _selected.value = StoredData.ME
            _messages.send(UiMessage.FriendDeleted(name, previous))
        }
    }
}
