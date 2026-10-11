package de.luwify.stundenplanmd.core.ical

import de.luwify.stundenplanmd.core.model.Event
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.model.Recurrence
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ImportResult(
    val events: List<Event>,
    /** PRODID, "QIS-LSF HIS eG" for the LSF export. */
    val source: String?,
    /** Things that were skipped or guessed; for diagnostics, not shown one by one. */
    val warnings: List<String>,
)

/** Parses an LSF iCalendar export into [Event]s. Lenient: bad events are skipped with a warning. */
object LsfImport {
    private val dateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val date = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val days = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )

    fun parse(text: String): ImportResult {
        val roots = IcsReader.read(text)
        val warnings = mutableListOf<String>()
        val source = roots.firstNotNullOfOrNull { it.first("PRODID")?.value?.trim() }
        val vevents = roots.flatMap { root -> root.walk().filter { it.name == "VEVENT" }.toList() }

        val raw = vevents.mapIndexedNotNull { index, component ->
            try {
                rawEvent(component, index, warnings)
            } catch (e: RuntimeException) {
                warnings += "event #$index skipped: ${e.message}"
                null
            }
        }
        val titleParts = LsfText.titleParts(raw.map { it.summary })
        val events = raw.map { event ->
            val (title, lecturers) = LsfText.splitSummary(event.summary, titleParts)
            event.copy(title = title, lecturers = lecturers)
        }
        return ImportResult(events, source, warnings)
    }

    private fun rawEvent(c: IcsComponent, index: Int, warnings: MutableList<String>): Event? {
        val summary = c.first("SUMMARY")?.let { IcsReader.unescapeText(it.value) }?.trim().orEmpty()
        val uid = c.first("UID")?.value?.trim()?.takeIf { it.isNotEmpty() }
        val label = uid ?: "#$index"
        val startProp = c.first("DTSTART") ?: run {
            warnings += "$label: no DTSTART, skipped"
            return null
        }
        if (isDateOnly(startProp)) {
            warnings += "$label: all-day event, skipped"
            return null
        }
        val start = dateTime(startProp) ?: run {
            warnings += "$label: bad DTSTART '${startProp.value}', skipped"
            return null
        }
        var end = c.first("DTEND")?.let { dateTime(it) }
            ?: c.first("DURATION")?.value?.trim()?.let { runCatching { start.plus(Duration.parse(it)) }.getOrNull() }
            ?: start.plusHours(1)
        if (!end.isAfter(start)) {
            warnings += "$label: end not after start, using one hour"
            end = start.plusHours(1)
        }
        if (c.first("RDATE") != null) warnings += "$label: RDATE ignored"
        return Event(
            uid = uid ?: "generated-${(summary + start).hashCode().toUInt()}",
            summary = summary,
            title = summary,
            category = c.first("CATEGORIES")?.let { IcsReader.unescapeText(it.value) }?.trim().orEmpty(),
            location = c.first("LOCATION")?.let { LsfText.location(IcsReader.unescapeText(it.value)) },
            notes = c.first("DESCRIPTION")?.let { HtmlText.toPlain(IcsReader.unescapeText(it.value)) }.orEmpty(),
            start = start,
            end = end,
            recurrence = c.first("RRULE")?.let { recurrence(it.value, c.all("EXDATE"), label, warnings) },
        )
    }

    private fun recurrence(
        rule: String,
        exdateProps: List<IcsProperty>,
        label: String,
        warnings: MutableList<String>,
    ): Recurrence? {
        val parts = rule.trim().split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim().uppercase() to part.substring(i + 1).trim()
        }.toMap()
        val frequency = when (parts["FREQ"]?.uppercase()) {
            "WEEKLY" -> Recurrence.Frequency.WEEKLY
            "DAILY" -> Recurrence.Frequency.DAILY
            else -> {
                warnings += "$label: unsupported RRULE '$rule', treated as single date"
                return null
            }
        }
        val exdates = mutableSetOf<LocalDateTime>()
        val exdays = mutableSetOf<LocalDate>()
        for (prop in exdateProps) {
            // LSF: trailing comma, and empty EXDATE lines on single events.
            for (value in prop.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                if (value.length == 8) {
                    runCatching { LocalDate.parse(value, date) }.getOrNull()?.let { exdays += it }
                } else {
                    dateTime(value, prop.params["TZID"])?.let { exdates += it }
                }
            }
        }
        return Recurrence(
            frequency = frequency,
            interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            byDay = parts["BYDAY"]?.split(',')?.mapNotNull { days[it.trim().takeLast(2).uppercase()] }?.toSet().orEmpty(),
            until = parts["UNTIL"]?.let(::until),
            count = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 },
            exdates = exdates,
            exdays = exdays,
        )
    }

    private fun isDateOnly(p: IcsProperty) = p.params["VALUE"].equals("DATE", ignoreCase = true) || p.value.trim().length == 8

    private fun dateTime(p: IcsProperty): LocalDateTime? = dateTime(p.value, p.params["TZID"])

    /** Wall-clock time in [PlanZone]; UTC (`Z`) and other TZIDs are converted, TZID=Europe/Berlin is taken as is. */
    internal fun dateTime(value: String, tzid: String?): LocalDateTime? {
        val v = value.trim()
        return runCatching {
            if (v.endsWith("Z") || v.endsWith("z")) {
                LocalDateTime.parse(v.dropLast(1), dateTime).atZone(ZoneId.of("UTC")).withZoneSameInstant(PlanZone)
                    .toLocalDateTime()
            } else {
                val local = LocalDateTime.parse(v, dateTime)
                val zone = tzid?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                if (zone == null || zone == PlanZone) local else local.atZone(zone).withZoneSameInstant(PlanZone).toLocalDateTime()
            }
        }.getOrNull()
    }

    private fun until(value: String): Instant? {
        val v = value.trim()
        if (v.length == 8) {
            return runCatching { LocalDate.parse(v, date).plusDays(1).atStartOfDay(PlanZone).toInstant().minusSeconds(1) }.getOrNull()
        }
        return dateTime(v, null)?.atZone(PlanZone)?.toInstant()
    }
}
