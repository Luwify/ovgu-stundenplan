package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.NoteHint
import de.luwify.stundenplanmd.core.model.Subject
import de.luwify.stundenplanmd.core.model.WeekRhythm
import de.luwify.stundenplanmd.core.schedule.Expansion
import de.luwify.stundenplanmd.core.schedule.Groups
import de.luwify.stundenplanmd.core.schedule.Cancellation
import de.luwify.stundenplanmd.core.schedule.Occurrence
import de.luwify.stundenplanmd.core.schedule.Status
import de.luwify.stundenplanmd.core.schedule.Timed
import de.luwify.stundenplanmd.ui.theme.SubjectColors
import de.luwify.stundenplanmd.ui.theme.planColors
import de.luwify.stundenplanmd.ui.theme.withContrast
import java.time.LocalDate

/** Material's drag handle: 4 dp bar with 22 dp above and below. */
private val DragHandleHeight = 48.dp

/** Everything about one tapped occurrence or cancellation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    plan: PlanUi,
    item: Timed,
    onEditSubject: (Subject) -> Unit,
    onEditPersonal: () -> Unit,
    onCancelForMe: (Boolean) -> Unit,
    onPickDates: () -> Unit,
    onEditManual: () -> Unit,
    onDeleteManual: () -> Unit,
    onRemoveAdded: () -> Unit,
    onHide: (de.luwify.stundenplanmd.core.model.Hidden) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val locale = appLocale()
    val event = item.event
    val subject = plan.subjectOf[event.courseKey]
    val rgb = plan.colorOf(item)
    val stripe = remember(rgb, scheme.surfaceContainerLow) { SubjectColors.block(rgb, scheme.surfaceContainerLow).stripe }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // An action that makes the appointment disappear closes the sheet by sliding it away, like a swipe or back.
    fun leave(action: () -> Unit) {
        action()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    // An action that opens an editor or dialog slides the sheet away first; the screen brings it back when that closes.
    fun open(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }
    // The room the sheet gives its content grows while it is dragged down: it slides out from under the status bar, up to
    // the inset more. Content just taller than the room at rest (long German labels wrapping onto more lines) then flipped
    // between "scrolls" and "fits" during a drag, which changed the sheet's height and anchors and pulled it back up, over
    // and over. Pinning the scroll area to the room at rest (window - top inset - the 48 dp drag handle, less a hair) keeps
    // the sheet's size independent of where it is.
    // Material's own content insets do the same with the status bar: as much top padding as the sheet is under it. Pushed
    // past its rest position (a fling at the end of the scroll area, in landscape), the sheet grew, its anchor moved up
    // with it, and it chased itself forever. The cap already keeps it below the status bar and the scroll area pads for the
    // navigation bar, so the sheet gets no insets of its own.
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val topInset = WindowInsets.systemBars.getTop(density)
    val contentMax = with(density) { (windowHeight - topInset).toDp() } - DragHandleHeight - 1.dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, contentWindowInsets = { WindowInsets(0) }) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = contentMax).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(stripe))
                Text(
                    listOf(subject?.shortNameOf(event.courseKey), event.type.name.ifEmpty { null }).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            SelectionContainer { Text(event.title, style = MaterialTheme.typography.headlineSmall) }

            StatusBox(item)
            if (event.isManual) ManualBox(who = if (plan.isMe) null else plan.person.displayName)
            if (plan.isMe && (item.event.uid in plan.addedUids || Groups.baseUid(item) in plan.addedUids)) AddedBox()
            PersonalBox(item, who = if (plan.isMe) null else plan.person.displayName)

            Field(
                stringResource(R.string.detail_when),
                "${longDate(item.date, locale)}\n${timeRange(item.start, item.end)}" + rhythm(event, locale, byMe = event.uid in plan.rhythms)?.let { "\n$it" }.orEmpty(),
            )
            Field(stringResource(R.string.detail_room), if (item.room != event.location?.room) item.room.orEmpty() else roomText(event))
            val with = plan.togetherWith(item)
            if (with.isNotEmpty()) TogetherField(plan, with)
            if (event.lecturers.isNotEmpty()) Field(stringResource(R.string.detail_lecturers), event.lecturers.joinToString(", "))
            val hint = when (event.noteHint) {
                NoteHint.NONE -> null
                NoteHint.SHORTENED -> stringResource(R.string.notes_shortened)
                NoteHint.NOT_SENT -> stringResource(R.string.notes_not_sent)
            }
            if (event.notes.isNotBlank() || hint != null) {
                Field(stringResource(R.string.detail_notes), listOfNotNull(event.notes.takeIf { it.isNotBlank() }, hint).joinToString("\n\n"))
            }
            if (event.summary != event.title) Field(stringResource(R.string.detail_lsf_title), event.summary)

            // One row: the actions most used on a single date first, the rest under "Mehr". An action that doesn't apply
            // here leaves the row (the next one moves up), so the row always has the same columns.
            val hide = SheetAction(
                HideIcon, stringResource(R.string.hide), stringResource(R.string.hide),
                menu = hideChoices(plan, item, subject).map { (label, make) -> MenuEntry(label) { leave { onHide(make()) } } },
            )
            val editSubject = subject?.let {
                SheetAction(EditIcon, stringResource(R.string.action_short_subject), stringResource(R.string.edit_subject), onClick = { open { onEditSubject(it) } })
            }
            if (plan.isMe) {
                // Own changes: my plan only (a friend's plan is read-only).
                val lsfCancelled = item is Cancellation && !item.byMe
                val cancelled = item.cancelledForMe
                val cancel = SheetAction(
                    CancelDateIcon,
                    stringResource(if (cancelled) R.string.action_short_uncancel else R.string.action_short_cancel),
                    stringResource(if (cancelled) R.string.action_uncancel else R.string.action_cancel_for_me),
                    selected = cancelled,
                    onClick = { onCancelForMe(!cancelled) },
                ).takeUnless { lsfCancelled }
                val note = SheetAction(NoteIcon, stringResource(R.string.action_short_note), stringResource(R.string.action_edit_personal), onClick = { open(onEditPersonal) })
                val added = item.event.uid in plan.addedUids || Groups.baseUid(item) in plan.addedUids
                SheetActionRow(
                    primary = listOfNotNull(note, cancel, hide, editSubject),
                    more = listOfNotNull(
                        MenuEntry(stringResource(R.string.action_pick_dates)) { open(onPickDates) },
                        MenuEntry(stringResource(R.string.manual_edit)) { open(onEditManual) }.takeIf { event.isManual },
                        MenuEntry(stringResource(R.string.manual_delete)) { leave(onDeleteManual) }.takeIf { event.isManual },
                        MenuEntry(stringResource(R.string.added_remove)) { leave(onRemoveAdded) }.takeIf { added },
                    ),
                )
            } else {
                SheetActionRow(primary = listOfNotNull(editSubject, hide), more = emptyList())
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun StatusBox(item: Timed) {
    val scheme = MaterialTheme.colorScheme
    val hl = planColors()
    val locale = appLocale()
    val lines: List<String> = when {
        item is Cancellation && !item.byMe -> listOf(stringResource(R.string.cancelled_on, longDate(item.date, locale)))
        item is Occurrence && item.status is Status.Changed -> {
            val orig = (item.status as Status.Changed).original
            changeLines(item) + stringResource(
                R.string.regular_slot,
                "${dayAndDate(orig.date, locale)} ${timeRange(orig.start, orig.end)}",
                orig.event.location?.room ?: stringResource(R.string.no_room),
            )
        }
        item is Occurrence && item.status == Status.Extra -> listOf(stringResource(R.string.extra_long))
        else -> return
    }
    val bg = scheme.surfaceContainerHigh
    val text = remember(hl, bg) { hl.highlight.withContrast(4.5, listOf(bg)) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = bg) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEachIndexed { i, line ->
                Text(
                    line,
                    color = if (i == 0) text else scheme.onSurface,
                    fontWeight = if (i == 0) FontWeight.SemiBold else null,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** Who else attends: favorite friends first, each with a heart, then the others (and me in a friend's plan). */
@Composable
private fun TogetherField(plan: PlanUi, ids: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.detail_together), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TogetherNames(plan, ids, MaterialTheme.typography.bodyLarge)
    }
}

/** The names of [ids] ([StoredData.ME] = "you"): favorites first, each with a heart, then the others. */
@Composable
fun TogetherNames(plan: PlanUi, ids: List<String>, style: TextStyle, color: Color = Color.Unspecified) {
    val you = stringResource(R.string.together_you)
    val heart = stringResource(R.string.favorite)
    val red = planColors().favorite
    val (favorites, others) = ids.partition { it in plan.favorites }
    fun name(id: String) = if (id == de.luwify.stundenplanmd.data.StoredData.ME) you else plan.names[id] ?: "?"
    val text = buildAnnotatedString {
        (favorites + others).forEachIndexed { i, id ->
            if (i > 0) append(", ")
            if (id in plan.favorites) { appendInlineContent("heart", "♥"); append(" ") }
            append(name(id))
        }
    }
    val inline = mapOf(
        "heart" to InlineTextContent(Placeholder(16.sp, 16.sp, PlaceholderVerticalAlign.TextCenter)) {
            Icon(painterResource(R.drawable.ic_favorite_filled), contentDescription = heart, tint = red, modifier = Modifier.fillMaxSize())
        },
    )
    Text(text, inlineContent = inline, style = style, color = color)
}

@Composable
private fun Field(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun roomText(e: Event): String {
    val l = e.location ?: return stringResource(R.string.no_room)
    val parts = listOfNotNull(
        l.room + (l.alias?.let { " ($it)" } ?: ""),
        l.building?.let { stringResource(R.string.building, it) },
        l.capacity?.let { pluralStringResource(R.plurals.seats, it, it) },
    )
    return parts.joinToString(" · ")
}

/** "Weekly on Mon until 25.1." / "Odd calendar weeks on Tue ..." for series; [byMe]: a rhythm I set by hand. */
@Composable
private fun rhythm(e: Event, locale: java.util.Locale, byMe: Boolean): String? {
    val r = e.recurrence ?: return null
    // The last date, not UNTIL: a rhythm set by hand can end earlier.
    val until: LocalDate? = r.until?.let { Expansion.slots(e).lastOrNull()?.start?.toLocalDate() }
    val day = dayShort(e.start.toLocalDate(), locale)
    val base = when (Expansion.rhythmOf(e)) {
        WeekRhythm.EVEN_WEEKS -> stringResource(R.string.rhythm_even_weeks, day)
        WeekRhythm.ODD_WEEKS -> stringResource(R.string.rhythm_odd_weeks, day)
        WeekRhythm.EVERY_14_DAYS -> stringResource(R.string.rhythm_14_days, day)
        null -> if (r.interval == 1) stringResource(R.string.rhythm_weekly, day) else stringResource(R.string.rhythm_n_weeks, r.interval, day)
    } + if (byMe) " " + stringResource(R.string.rhythm_by_me) else ""
    return if (until != null) stringResource(R.string.rhythm_until, base, dayAndDate(until, locale)) else base
}

/** An LSF course added to my plan from another .ics: marked (removing it is under the sheet's "Mehr", with undo). */
@Composable
private fun AddedBox() {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.added_course), style = MaterialTheme.typography.bodyLarge)
            }
            Text(stringResource(R.string.added_course_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
