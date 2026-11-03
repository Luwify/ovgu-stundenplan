package java.time.temporal

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Marker for the JS stand-ins (java.time.temporal.Temporal on the JVM). */
interface Temporal

fun interface TemporalAdjuster {
    fun adjustInto(date: LocalDate): LocalDate
}

object TemporalAdjusters {
    fun previousOrSame(dow: DayOfWeek) = TemporalAdjuster { d -> d.minusDays(((d.dayOfWeek.value - dow.value + 7) % 7).toLong()) }
    fun previous(dow: DayOfWeek) = TemporalAdjuster { d -> d.minusDays(((d.dayOfWeek.value - dow.value + 6) % 7 + 1).toLong()) }
    fun nextOrSame(dow: DayOfWeek) = TemporalAdjuster { d -> d.plusDays(((dow.value - d.dayOfWeek.value + 7) % 7).toLong()) }
    fun next(dow: DayOfWeek) = TemporalAdjuster { d -> d.plusDays(((dow.value - d.dayOfWeek.value + 6) % 7 + 1).toLong()) }
    fun firstDayOfMonth() = TemporalAdjuster { d -> d.withDayOfMonth(1) }
    fun lastDayOfMonth() = TemporalAdjuster { d -> d.withDayOfMonth(d.lengthOfMonth()) }
}

interface TemporalField {
    fun getFrom(date: LocalDate): Int
}

object IsoFields {
    private fun weekBasedYear(d: LocalDate): Int {
        val thursday = d.plusDays((4 - d.dayOfWeek.value).toLong())
        return thursday.year
    }

    val WEEK_OF_WEEK_BASED_YEAR: TemporalField = object : TemporalField {
        override fun getFrom(date: LocalDate): Int {
            val thursday = date.plusDays((4 - date.dayOfWeek.value).toLong())
            return (thursday.dayOfYear - 1) / 7 + 1
        }
        override fun toString() = "WeekOfWeekBasedYear"
    }
    val WEEK_BASED_YEAR: TemporalField = object : TemporalField {
        override fun getFrom(date: LocalDate): Int = weekBasedYear(date)
        override fun toString() = "WeekBasedYear"
    }
}

enum class ChronoUnit {
    NANOS, MICROS, MILLIS, SECONDS, MINUTES, HOURS, HALF_DAYS, DAYS, WEEKS, MONTHS, YEARS;

    fun between(start: Temporal, end: Temporal): Long {
        if (start is LocalDate && end is LocalDate) {
            val days = end.toEpochDay() - start.toEpochDay()
            return when (this) {
                DAYS -> days
                WEEKS -> days / 7
                MONTHS, YEARS -> {
                    val packed1 = start.year * 12L * 32 + (start.monthValue - 1) * 32L + start.dayOfMonth
                    val packed2 = end.year * 12L * 32 + (end.monthValue - 1) * 32L + end.dayOfMonth
                    val months = (packed2 - packed1) / 32
                    if (this == MONTHS) months else months / 12
                }
                else -> throw DateTimeException("Unsupported unit: $this")
            }
        }
        if (start is LocalDateTime && end is LocalDateTime && this >= DAYS) {
            var endDate = end.toLocalDate()
            if (endDate.isAfter(start.toLocalDate()) && end.toLocalTime().isBefore(start.toLocalTime())) endDate = endDate.minusDays(1)
            else if (endDate.isBefore(start.toLocalDate()) && end.toLocalTime().isAfter(start.toLocalTime())) endDate = endDate.plusDays(1)
            return between(start.toLocalDate(), endDate)
        }
        val d = Duration.between(start, end)
        return when (this) {
            NANOS -> d.seconds * 1_000_000_000L + d.nano
            MICROS -> d.seconds * 1_000_000L + d.nano / 1000
            MILLIS -> d.toMillis()
            SECONDS -> d.seconds
            MINUTES -> d.toMinutes()
            HOURS -> d.toHours()
            HALF_DAYS -> d.seconds / 43200
            else -> throw DateTimeException("Unsupported unit: $this")
        }
    }
}
