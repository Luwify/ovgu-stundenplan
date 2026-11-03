@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package java.time

// Kotlin/JS stand-ins for the subset of java.time that :core uses (ISO calendar only, nanosecond precision, zones UTC
// and Europe/Berlin). The JVM build uses the real java.time; JvmJsParityTest-like checks run in the JS tests
// (TimeShimTest) against values the JDK produces. Add members here when :core starts using new ones.

import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.Temporal
import java.time.temporal.TemporalAdjuster
import java.time.temporal.TemporalField

open class DateTimeException(message: String?) : RuntimeException(message)

private fun floorDiv(a: Long, b: Long): Long = Math2.floorDiv(a, b)
private fun floorMod(a: Long, b: Long): Long = Math2.floorMod(a, b)

internal object Math2 {
    fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a xor b) < 0)) q - 1 else q }
    fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
}

private fun pad(v: Int, n: Int) = v.toString().padStart(n, '0')

enum class DayOfWeek {
    MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY;

    val value: Int get() = ordinal + 1

    fun plus(days: Long): DayOfWeek = entries[floorMod(ordinal + days, 7L).toInt()]
    fun minus(days: Long): DayOfWeek = plus(-(days % 7))

    companion object {
        fun of(dayOfWeek: Int): DayOfWeek {
            if (dayOfWeek !in 1..7) throw DateTimeException("Invalid value for DayOfWeek: $dayOfWeek")
            return entries[dayOfWeek - 1]
        }
    }
}

class LocalDate private constructor(val year: Int, val monthValue: Int, val dayOfMonth: Int) : Temporal, Comparable<LocalDate> {
    val dayOfWeek: DayOfWeek get() = DayOfWeek.entries[floorMod(toEpochDay() + 3, 7L).toInt()]
    val dayOfYear: Int get() = (toEpochDay() - of(year, 1, 1).toEpochDay()).toInt() + 1
    val isLeapYear: Boolean get() = isLeap(year.toLong())

    fun lengthOfMonth(): Int = monthLength(monthValue, isLeapYear)
    fun lengthOfYear(): Int = if (isLeapYear) 366 else 365

    fun toEpochDay(): Long {
        val y = year.toLong()
        val m = monthValue.toLong()
        var total = 365 * y
        total += if (y >= 0) (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400 else -(y / -4 - y / -100 + y / -400)
        total += (367 * m - 362) / 12
        total += dayOfMonth - 1
        if (m > 2) { total--; if (!isLeapYear) total-- }
        return total - DAYS_0000_TO_1970
    }

    fun plusDays(days: Long): LocalDate = if (days == 0L) this else ofEpochDay(toEpochDay() + days)
    fun minusDays(days: Long): LocalDate = plusDays(-days)
    fun plusWeeks(weeks: Long): LocalDate = plusDays(weeks * 7)
    fun minusWeeks(weeks: Long): LocalDate = plusDays(-weeks * 7)
    fun plusMonths(months: Long): LocalDate {
        if (months == 0L) return this
        val calc = year * 12L + (monthValue - 1) + months
        val y = floorDiv(calc, 12).toInt()
        val m = floorMod(calc, 12).toInt() + 1
        return of(y, m, minOf(dayOfMonth, monthLength(m, isLeap(y.toLong()))))
    }
    fun minusMonths(months: Long): LocalDate = plusMonths(-months)
    fun plusYears(years: Long): LocalDate = plusMonths(years * 12)
    fun minusYears(years: Long): LocalDate = plusMonths(-years * 12)
    fun plus(amount: Long, unit: ChronoUnit): LocalDate = when (unit) {
        ChronoUnit.DAYS -> plusDays(amount)
        ChronoUnit.WEEKS -> plusWeeks(amount)
        ChronoUnit.MONTHS -> plusMonths(amount)
        ChronoUnit.YEARS -> plusYears(amount)
        else -> throw DateTimeException("Unsupported unit: $unit")
    }

    fun withDayOfMonth(day: Int): LocalDate = of(year, monthValue, day)
    fun withMonth(month: Int): LocalDate = of(year, month, minOf(dayOfMonth, monthLength(month, isLeapYear)))
    fun withYear(y: Int): LocalDate = of(y, monthValue, minOf(dayOfMonth, monthLength(monthValue, isLeap(y.toLong()))))
    fun with(adjuster: TemporalAdjuster): LocalDate = adjuster.adjustInto(this)

    fun get(field: TemporalField): Int = field.getFrom(this)

    fun atTime(hour: Int, minute: Int): LocalDateTime = LocalDateTime.of(this, LocalTime.of(hour, minute))
    fun atTime(hour: Int, minute: Int, second: Int): LocalDateTime = LocalDateTime.of(this, LocalTime.of(hour, minute, second))
    fun atTime(time: LocalTime): LocalDateTime = LocalDateTime.of(this, time)
    fun atStartOfDay(): LocalDateTime = LocalDateTime.of(this, LocalTime.MIDNIGHT)
    fun atStartOfDay(zone: ZoneId): ZonedDateTime = atStartOfDay().atZone(zone)

    fun isBefore(other: LocalDate) = compareTo(other) < 0
    fun isAfter(other: LocalDate) = compareTo(other) > 0
    fun isEqual(other: LocalDate) = compareTo(other) == 0

    fun until(end: LocalDate, unit: ChronoUnit): Long = unit.between(this, end)

    override fun compareTo(other: LocalDate): Int {
        var c = year - other.year
        if (c == 0) c = monthValue - other.monthValue
        if (c == 0) c = dayOfMonth - other.dayOfMonth
        return c
    }

    override fun equals(other: Any?) = other is LocalDate && compareTo(other) == 0
    override fun hashCode() = (year and -0x800) xor ((year shl 11) + (monthValue shl 6) + dayOfMonth)

    override fun toString(): String {
        val y = when {
            year > 9999 -> "+$year"
            year < 0 -> "-" + pad(-year, 4)
            else -> pad(year, 4)
        }
        return y + "-" + pad(monthValue, 2) + "-" + pad(dayOfMonth, 2)
    }

    companion object {
        internal const val DAYS_0000_TO_1970 = 146097L * 5 - (30L * 365 + 7)
        val MIN: LocalDate = LocalDate(-999999999, 1, 1)
        val MAX: LocalDate = LocalDate(999999999, 12, 31)
        val EPOCH: LocalDate = LocalDate(1970, 1, 1)

        internal fun isLeap(y: Long) = (y and 3) == 0L && (y % 100 != 0L || y % 400 == 0L)
        internal fun monthLength(m: Int, leap: Boolean) = when (m) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        fun of(year: Int, month: Int, dayOfMonth: Int): LocalDate {
            if (year < -999999999 || year > 999999999) throw DateTimeException("Invalid value for Year: $year")
            if (month !in 1..12) throw DateTimeException("Invalid value for MonthOfYear: $month")
            if (dayOfMonth < 1 || dayOfMonth > monthLength(month, isLeap(year.toLong()))) {
                throw DateTimeException("Invalid date: $year-$month-$dayOfMonth")
            }
            return LocalDate(year, month, dayOfMonth)
        }

        fun ofEpochDay(epochDay: Long): LocalDate {
            var zeroDay = epochDay + DAYS_0000_TO_1970
            zeroDay -= 60
            var adjust = 0L
            if (zeroDay < 0) {
                val adjustCycles = (zeroDay + 1) / 146097 - 1
                adjust = adjustCycles * 400
                zeroDay += -adjustCycles * 146097
            }
            var yearEst = (400 * zeroDay + 591) / 146097
            var doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
            if (doyEst < 0) {
                yearEst--
                doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
            }
            yearEst += adjust
            val marchDoy0 = doyEst.toInt()
            val marchMonth0 = (marchDoy0 * 5 + 2) / 153
            val month = (marchMonth0 + 2) % 12 + 1
            val dom = marchDoy0 - (marchMonth0 * 306 + 5) / 10 + 1
            yearEst += (marchMonth0 / 10).toLong()
            return of(yearEst.toInt(), month, dom)
        }

        private val iso = Regex("^([+-]?\\d{4,9})-(\\d{2})-(\\d{2})$")

        fun parse(text: CharSequence): LocalDate {
            val m = iso.matchEntire(text) ?: throw DateTimeParseException("Text '$text' could not be parsed", text, 0)
            try {
                return of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            } catch (e: DateTimeException) {
                throw DateTimeParseException("Text '$text' could not be parsed: ${e.message}", text, 0)
            }
        }

        fun parse(text: CharSequence, formatter: DateTimeFormatter): LocalDate = formatter.parseDate(text)
    }
}

class LocalTime private constructor(val hour: Int, val minute: Int, val second: Int, val nano: Int) : Temporal, Comparable<LocalTime> {
    fun toSecondOfDay(): Int = hour * 3600 + minute * 60 + second
    fun toNanoOfDay(): Long = toSecondOfDay() * 1_000_000_000L + nano

    fun plusSeconds(seconds: Long): LocalTime {
        if (seconds == 0L) return this
        val s = floorMod(toSecondOfDay() + seconds, 86400L).toInt()
        return LocalTime(s / 3600, s / 60 % 60, s % 60, nano)
    }
    fun plusMinutes(minutes: Long): LocalTime = plusSeconds(floorMod(minutes, 1440L) * 60)
    fun plusHours(hours: Long): LocalTime = plusSeconds(floorMod(hours, 24L) * 3600)
    fun minusMinutes(minutes: Long): LocalTime = plusMinutes(-floorMod(minutes, 1440L))
    fun minusHours(hours: Long): LocalTime = plusHours(-floorMod(hours, 24L))
    fun minusSeconds(seconds: Long): LocalTime = plusSeconds(-floorMod(seconds, 86400L))
    fun withHour(h: Int) = of(h, minute, second, nano)
    fun withMinute(m: Int) = of(hour, m, second, nano)
    fun withSecond(s: Int) = of(hour, minute, s, nano)
    fun withNano(n: Int) = of(hour, minute, second, n)
    fun atDate(date: LocalDate): LocalDateTime = LocalDateTime.of(date, this)

    fun isBefore(other: LocalTime) = compareTo(other) < 0
    fun isAfter(other: LocalTime) = compareTo(other) > 0

    override fun compareTo(other: LocalTime): Int = toNanoOfDay().compareTo(other.toNanoOfDay())
    override fun equals(other: Any?) = other is LocalTime && compareTo(other) == 0
    override fun hashCode(): Int = toNanoOfDay().hashCode()

    override fun toString(): String {
        val sb = StringBuilder(pad(hour, 2)).append(':').append(pad(minute, 2))
        if (second > 0 || nano > 0) {
            sb.append(':').append(pad(second, 2))
            if (nano > 0) sb.append('.').append(fraction(nano))
        }
        return sb.toString()
    }

    companion object {
        val MIDNIGHT: LocalTime = LocalTime(0, 0, 0, 0)
        val MIN: LocalTime = MIDNIGHT
        val NOON: LocalTime = LocalTime(12, 0, 0, 0)
        val MAX: LocalTime = LocalTime(23, 59, 59, 999_999_999)

        fun of(hour: Int, minute: Int): LocalTime = of(hour, minute, 0, 0)
        fun of(hour: Int, minute: Int, second: Int): LocalTime = of(hour, minute, second, 0)
        fun of(hour: Int, minute: Int, second: Int, nanoOfSecond: Int): LocalTime {
            if (hour !in 0..23) throw DateTimeException("Invalid value for HourOfDay: $hour")
            if (minute !in 0..59) throw DateTimeException("Invalid value for MinuteOfHour: $minute")
            if (second !in 0..59) throw DateTimeException("Invalid value for SecondOfMinute: $second")
            if (nanoOfSecond !in 0..999_999_999) throw DateTimeException("Invalid value for NanoOfSecond: $nanoOfSecond")
            return LocalTime(hour, minute, second, nanoOfSecond)
        }

        fun ofSecondOfDay(secondOfDay: Long): LocalTime {
            if (secondOfDay !in 0L..86399L) throw DateTimeException("Invalid value for SecondOfDay: $secondOfDay")
            val s = secondOfDay.toInt()
            return LocalTime(s / 3600, s / 60 % 60, s % 60, 0)
        }

        private val iso = Regex("^(\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?$")

        fun parse(text: CharSequence): LocalTime {
            val m = iso.matchEntire(text) ?: throw DateTimeParseException("Text '$text' could not be parsed", text, 0)
            try {
                val frac = m.groupValues[4]
                return of(
                    m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "0" }.toInt(),
                    if (frac.isEmpty()) 0 else frac.padEnd(9, '0').toInt(),
                )
            } catch (e: DateTimeException) {
                throw DateTimeParseException("Text '$text' could not be parsed: ${e.message}", text, 0)
            }
        }
    }
}

/** java.time's fraction style: 3, 6 or 9 digits. */
internal fun fraction(nano: Int): String = when {
    nano % 1_000_000 == 0 -> pad(nano / 1_000_000, 3)
    nano % 1000 == 0 -> pad(nano / 1000, 6)
    else -> pad(nano, 9)
}

class LocalDateTime private constructor(private val date: LocalDate, private val time: LocalTime) : Temporal, Comparable<LocalDateTime> {
    val year: Int get() = date.year
    val monthValue: Int get() = date.monthValue
    val dayOfMonth: Int get() = date.dayOfMonth
    val dayOfWeek: DayOfWeek get() = date.dayOfWeek
    val dayOfYear: Int get() = date.dayOfYear
    val hour: Int get() = time.hour
    val minute: Int get() = time.minute
    val second: Int get() = time.second
    val nano: Int get() = time.nano

    fun toLocalDate(): LocalDate = date
    fun toLocalTime(): LocalTime = time

    /** Seconds since 1970-01-01T00:00 of this wall-clock time read as UTC. */
    internal fun toEpochSecondUtc(): Long = date.toEpochDay() * 86400 + time.toSecondOfDay()

    private fun plusSecondsAndNanos(seconds: Long, nanos: Long): LocalDateTime {
        if (seconds == 0L && nanos == 0L) return this
        val totalNanos = time.nano + nanos
        val secs = toEpochSecondUtc() + seconds + floorDiv(totalNanos, 1_000_000_000L)
        val nano = floorMod(totalNanos, 1_000_000_000L).toInt()
        return ofEpochSecondUtc(secs, nano)
    }

    fun plusDays(days: Long) = if (days == 0L) this else LocalDateTime(date.plusDays(days), time)
    fun minusDays(days: Long) = plusDays(-days)
    fun plusWeeks(weeks: Long) = plusDays(weeks * 7)
    fun minusWeeks(weeks: Long) = plusDays(-weeks * 7)
    fun plusMonths(months: Long) = LocalDateTime(date.plusMonths(months), time)
    fun minusMonths(months: Long) = plusMonths(-months)
    fun plusYears(years: Long) = LocalDateTime(date.plusYears(years), time)
    fun plusHours(hours: Long) = plusSecondsAndNanos(hours * 3600, 0)
    fun minusHours(hours: Long) = plusHours(-hours)
    fun plusMinutes(minutes: Long) = plusSecondsAndNanos(minutes * 60, 0)
    fun minusMinutes(minutes: Long) = plusMinutes(-minutes)
    fun plusSeconds(seconds: Long) = plusSecondsAndNanos(seconds, 0)
    fun minusSeconds(seconds: Long) = plusSeconds(-seconds)
    fun plusNanos(nanos: Long) = plusSecondsAndNanos(0, nanos)
    fun plus(d: Duration) = plusSecondsAndNanos(d.seconds, d.nano.toLong())
    fun minus(d: Duration) = plusSecondsAndNanos(-d.seconds, -d.nano.toLong())
    fun plus(amount: Long, unit: ChronoUnit): LocalDateTime = when (unit) {
        ChronoUnit.NANOS -> plusNanos(amount)
        ChronoUnit.SECONDS -> plusSeconds(amount)
        ChronoUnit.MINUTES -> plusMinutes(amount)
        ChronoUnit.HOURS -> plusHours(amount)
        else -> LocalDateTime(date.plus(amount, unit), time)
    }

    fun with(adjuster: TemporalAdjuster): LocalDateTime = LocalDateTime(adjuster.adjustInto(date), time)
    fun with(date: LocalDate): LocalDateTime = LocalDateTime(date, time)
    fun with(time: LocalTime): LocalDateTime = LocalDateTime(date, time)
    fun withHour(h: Int) = LocalDateTime(date, time.withHour(h))
    fun withMinute(m: Int) = LocalDateTime(date, time.withMinute(m))
    fun withSecond(s: Int) = LocalDateTime(date, time.withSecond(s))
    fun withNano(n: Int) = LocalDateTime(date, time.withNano(n))
    fun withDayOfMonth(d: Int) = LocalDateTime(date.withDayOfMonth(d), time)
    fun truncatedTo(unit: ChronoUnit): LocalDateTime = when (unit) {
        ChronoUnit.DAYS -> LocalDateTime(date, LocalTime.MIDNIGHT)
        ChronoUnit.HOURS -> LocalDateTime(date, LocalTime.of(time.hour, 0))
        ChronoUnit.MINUTES -> LocalDateTime(date, LocalTime.of(time.hour, time.minute))
        ChronoUnit.SECONDS -> LocalDateTime(date, LocalTime.of(time.hour, time.minute, time.second))
        else -> this
    }

    fun get(field: TemporalField): Int = field.getFrom(date)

    fun atZone(zone: ZoneId): ZonedDateTime = ZonedDateTime.ofLocal(this, zone)

    fun isBefore(other: LocalDateTime) = compareTo(other) < 0
    fun isAfter(other: LocalDateTime) = compareTo(other) > 0
    fun isEqual(other: LocalDateTime) = compareTo(other) == 0

    fun until(end: LocalDateTime, unit: ChronoUnit): Long = unit.between(this, end)

    override fun compareTo(other: LocalDateTime): Int = date.compareTo(other.date).let { if (it != 0) it else time.compareTo(other.time) }
    override fun equals(other: Any?) = other is LocalDateTime && date == other.date && time == other.time
    override fun hashCode() = date.hashCode() xor time.hashCode()
    override fun toString() = "${date}T$time"

    companion object {
        val MIN: LocalDateTime = LocalDateTime(LocalDate.MIN, LocalTime.MIN)
        val MAX: LocalDateTime = LocalDateTime(LocalDate.MAX, LocalTime.MAX)

        fun of(date: LocalDate, time: LocalTime) = LocalDateTime(date, time)
        fun of(year: Int, month: Int, day: Int, hour: Int, minute: Int) = LocalDateTime(LocalDate.of(year, month, day), LocalTime.of(hour, minute))
        fun of(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int) =
            LocalDateTime(LocalDate.of(year, month, day), LocalTime.of(hour, minute, second))
        fun of(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, nano: Int) =
            LocalDateTime(LocalDate.of(year, month, day), LocalTime.of(hour, minute, second, nano))

        internal fun ofEpochSecondUtc(epochSecond: Long, nano: Int): LocalDateTime {
            val day = floorDiv(epochSecond, 86400)
            val sod = floorMod(epochSecond, 86400).toInt()
            return LocalDateTime(LocalDate.ofEpochDay(day), LocalTime.of(sod / 3600, sod / 60 % 60, sod % 60, nano))
        }

        fun ofEpochSecond(epochSecond: Long, nanoOfSecond: Int, offset: ZoneOffset): LocalDateTime =
            ofEpochSecondUtc(epochSecond + offset.totalSeconds, nanoOfSecond)

        fun ofInstant(instant: Instant, zone: ZoneId): LocalDateTime =
            ofEpochSecondUtc(instant.epochSecond + zone.offsetAt(instant.epochSecond), instant.nano)

        fun parse(text: CharSequence): LocalDateTime {
            val s = text.toString()
            val t = s.indexOf('T')
            if (t < 0) throw DateTimeParseException("Text '$text' could not be parsed", text, 0)
            try {
                return LocalDateTime(LocalDate.parse(s.substring(0, t)), LocalTime.parse(s.substring(t + 1)))
            } catch (e: DateTimeParseException) {
                throw DateTimeParseException("Text '$text' could not be parsed", text, 0)
            }
        }

        fun parse(text: CharSequence, formatter: DateTimeFormatter): LocalDateTime = formatter.parseDateTime(text)
    }
}

class Instant private constructor(val epochSecond: Long, val nano: Int) : Temporal, Comparable<Instant> {
    fun toEpochMilli(): Long = epochSecond * 1000 + nano / 1_000_000
    fun plusSeconds(s: Long) = ofEpochSecond(epochSecond + s, nano.toLong())
    fun minusSeconds(s: Long) = plusSeconds(-s)
    fun plusMillis(ms: Long) = ofEpochSecond(epochSecond, nano + ms * 1_000_000)
    fun plus(d: Duration) = ofEpochSecond(epochSecond + d.seconds, nano.toLong() + d.nano)
    fun minus(d: Duration) = ofEpochSecond(epochSecond - d.seconds, nano.toLong() - d.nano)
    fun isAfter(other: Instant) = compareTo(other) > 0
    fun isBefore(other: Instant) = compareTo(other) < 0
    fun atZone(zone: ZoneId): ZonedDateTime = ZonedDateTime.ofInstant(this, zone)

    override fun compareTo(other: Instant): Int = epochSecond.compareTo(other.epochSecond).let { if (it != 0) it else nano - other.nano }
    override fun equals(other: Any?) = other is Instant && epochSecond == other.epochSecond && nano == other.nano
    override fun hashCode() = epochSecond.hashCode() + 51 * nano

    override fun toString(): String {
        val ldt = LocalDateTime.ofEpochSecondUtc(epochSecond, 0)
        val d = ldt.toLocalDate()
        val sb = StringBuilder(d.toString()).append('T')
            .append(pad(ldt.hour, 2)).append(':').append(pad(ldt.minute, 2)).append(':').append(pad(ldt.second, 2))
        if (nano > 0) sb.append('.').append(fraction(nano))
        return sb.append('Z').toString()
    }

    companion object {
        val EPOCH: Instant = Instant(0, 0)

        fun ofEpochSecond(epochSecond: Long): Instant = Instant(epochSecond, 0)
        fun ofEpochSecond(epochSecond: Long, nanoAdjustment: Long): Instant =
            Instant(epochSecond + floorDiv(nanoAdjustment, 1_000_000_000L), floorMod(nanoAdjustment, 1_000_000_000L).toInt())
        fun ofEpochMilli(epochMilli: Long): Instant = Instant(floorDiv(epochMilli, 1000), floorMod(epochMilli, 1000).toInt() * 1_000_000)
        fun now(): Instant = ofEpochMilli(kotlin.js.Date.now().toLong())

        private val iso = Regex("^([+-]?\\d{4,9}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}(?::\\d{2}(?:[.,]\\d{1,9})?)?)(Z|[+-]\\d{2}:\\d{2})$")

        fun parse(text: CharSequence): Instant {
            val m = iso.matchEntire(text) ?: throw DateTimeParseException("Text '$text' could not be parsed", text, 0)
            try {
                val ldt = LocalDateTime.of(LocalDate.parse(m.groupValues[1]), LocalTime.parse(m.groupValues[2]))
                val off = m.groupValues[3].let { if (it == "Z") 0 else ZoneOffset.parseSeconds(it) }
                return Instant(ldt.toEpochSecondUtc() - off, ldt.nano)
            } catch (e: DateTimeException) {
                throw DateTimeParseException("Text '$text' could not be parsed", text, 0)
            }
        }
    }
}

class Duration private constructor(val seconds: Long, val nano: Int) : Comparable<Duration> {
    fun getSeconds(): Long = seconds
    val isZero: Boolean get() = seconds == 0L && nano == 0
    val isNegative: Boolean get() = seconds < 0

    fun toDays(): Long = seconds / 86400
    fun toHours(): Long = seconds / 3600
    fun toMinutes(): Long = seconds / 60
    fun toMillis(): Long = seconds * 1000 + nano / 1_000_000
    fun toSeconds(): Long = seconds
    fun plus(o: Duration) = ofSeconds(seconds + o.seconds, nano.toLong() + o.nano)
    fun minus(o: Duration) = ofSeconds(seconds - o.seconds, nano.toLong() - o.nano)
    fun plusMinutes(m: Long) = ofSeconds(seconds + m * 60, nano.toLong())
    fun negated() = ofSeconds(-seconds, -nano.toLong())
    fun abs() = if (isNegative) negated() else this
    fun multipliedBy(n: Long) = ofSeconds(seconds * n, nano.toLong() * n)

    override fun compareTo(other: Duration): Int = seconds.compareTo(other.seconds).let { if (it != 0) it else nano - other.nano }
    override fun equals(other: Any?) = other is Duration && seconds == other.seconds && nano == other.nano
    override fun hashCode() = seconds.hashCode() + 51 * nano
    override fun toString(): String {
        if (isZero) return "PT0S"
        var effective = seconds
        if (effective < 0 && nano > 0) effective++
        val hours = effective / 3600
        val minutes = ((effective % 3600) / 60).toInt()
        val secs = (effective % 60).toInt()
        val sb = StringBuilder("PT")
        if (hours != 0L) sb.append(hours).append('H')
        if (minutes != 0) sb.append(minutes).append('M')
        if (secs == 0 && nano == 0 && sb.length > 2) return sb.toString()
        if (seconds < 0 && nano > 0 && secs == 0) sb.append("-0") else sb.append(secs)
        if (nano > 0) {
            val n = if (seconds < 0) 2 * 1_000_000_000 - nano else nano + 1_000_000_000
            sb.append('.').append(n.toString().substring(1).trimEnd('0'))
        }
        return sb.append('S').toString()
    }

    companion object {
        val ZERO = Duration(0, 0)

        fun ofSeconds(seconds: Long): Duration = Duration(seconds, 0)
        fun ofSeconds(seconds: Long, nanoAdjustment: Long): Duration =
            Duration(seconds + floorDiv(nanoAdjustment, 1_000_000_000L), floorMod(nanoAdjustment, 1_000_000_000L).toInt())
        fun ofMinutes(m: Long) = Duration(m * 60, 0)
        fun ofHours(h: Long) = Duration(h * 3600, 0)
        fun ofDays(d: Long) = Duration(d * 86400, 0)
        fun ofMillis(ms: Long) = ofSeconds(floorDiv(ms, 1000), floorMod(ms, 1000) * 1_000_000)

        fun between(start: Temporal, end: Temporal): Duration = when {
            start is LocalDateTime && end is LocalDateTime ->
                ofSeconds(end.toEpochSecondUtc() - start.toEpochSecondUtc(), end.nano.toLong() - start.nano)
            start is LocalTime && end is LocalTime -> ofSeconds(0, end.toNanoOfDay() - start.toNanoOfDay())
            start is Instant && end is Instant -> ofSeconds(end.epochSecond - start.epochSecond, end.nano.toLong() - start.nano)
            start is ZonedDateTime && end is ZonedDateTime -> between(start.toInstant(), end.toInstant())
            else -> throw DateTimeException("Unsupported temporals for Duration.between")
        }

        private val pattern = Regex(
            "([-+]?)P(?:([-+]?[0-9]+)D)?(T(?:([-+]?[0-9]+)H)?(?:([-+]?[0-9]+)M)?(?:([-+]?[0-9]+)(?:[.,]([0-9]{0,9}))?S)?)?",
            RegexOption.IGNORE_CASE,
        )

        fun parse(text: CharSequence): Duration {
            val m = pattern.matchEntire(text)
            val g = m?.groupValues
            if (g == null || g[3] == "T" || (g[2].isEmpty() && g[4].isEmpty() && g[5].isEmpty() && g[6].isEmpty())) {
                throw DateTimeParseException("Text cannot be parsed to a Duration", text, 0)
            }
            try {
                val days = g[2].ifEmpty { "0" }.toLong()
                val hours = g[4].ifEmpty { "0" }.toLong()
                val mins = g[5].ifEmpty { "0" }.toLong()
                val secs = g[6].ifEmpty { "0" }.toLong()
                val neg6 = g[6].startsWith("-")
                var nanos = g[7].ifEmpty { "0" }.padEnd(9, '0').toLong()
                if (neg6) nanos = -nanos
                val d = ofSeconds(days * 86400 + hours * 3600 + mins * 60 + secs, nanos)
                return if (g[1] == "-") d.negated() else d
            } catch (e: NumberFormatException) {
                throw DateTimeParseException("Text cannot be parsed to a Duration: overflow", text, 0)
            }
        }
    }
}

/** UTC and Europe/Berlin (all .ics times of LSF and every plan time); other ids throw like an unknown zone. */
abstract class ZoneId internal constructor(val id: String) {
    abstract fun offsetAt(epochSecond: Long): Int
    fun getId() = id
    override fun equals(other: Any?) = other is ZoneId && id == other.id
    override fun hashCode() = id.hashCode()
    override fun toString() = id

    companion object {
        fun of(zoneId: String): ZoneId = when (zoneId) {
            "Z" -> ZoneOffset.UTC
            "UTC", "GMT", "UT", "Etc/UTC", "Etc/GMT" -> FixedZone(zoneId, 0)
            "Europe/Berlin" -> CentralEurope("Europe/Berlin")
            else -> if (zoneId.startsWith("+") || zoneId.startsWith("-")) {
                ZoneOffset.ofTotalSeconds(ZoneOffset.parseSeconds(zoneId))
            } else {
                throw DateTimeException("Unknown time-zone ID: $zoneId")
            }
        }
    }
}

internal class FixedZone(id: String, private val offset: Int) : ZoneId(id) {
    override fun offsetAt(epochSecond: Long) = offset
}

class ZoneOffset private constructor(val totalSeconds: Int) : ZoneId(idOf(totalSeconds)) {
    override fun offsetAt(epochSecond: Long) = totalSeconds

    companion object {
        val UTC = ZoneOffset(0)
        private fun idOf(s: Int): String = if (s == 0) "Z" else {
            val a = kotlin.math.abs(s)
            (if (s < 0) "-" else "+") + pad(a / 3600, 2) + ":" + pad(a / 60 % 60, 2) + (if (a % 60 != 0) ":" + pad(a % 60, 2) else "")
        }
        fun ofTotalSeconds(s: Int) = if (s == 0) UTC else ZoneOffset(s)
        fun ofHours(h: Int) = ofTotalSeconds(h * 3600)
        internal fun parseSeconds(text: String): Int {
            val m = Regex("^([+-])(\\d{2}):?(\\d{2})(?::?(\\d{2}))?$").matchEntire(text)
                ?: throw DateTimeException("Invalid ID for ZoneOffset: $text")
            val v = m.groupValues[2].toInt() * 3600 + m.groupValues[3].toInt() * 60 + m.groupValues[4].ifEmpty { "0" }.toInt()
            return if (m.groupValues[1] == "-") -v else v
        }
    }
}

/**
 * CET/CEST with the EU rule (since 1996: summer time from the last Sunday of March to the last Sunday of October,
 * 01:00 UTC; 1981–1995 it ended on the last Sunday of September). Before 1980: CET only.
 */
internal class CentralEurope(id: String) : ZoneId(id) {
    private fun lastSunday(year: Int, month: Int): LocalDate {
        val last = LocalDate.of(year, month, LocalDate.monthLength(month, LocalDate.isLeap(year.toLong())))
        return last.minusDays(last.dayOfWeek.value.toLong() % 7)
    }

    /** Start and end of summer time in [year] as UTC epoch seconds, or null. */
    private fun summer(year: Int): Pair<Long, Long>? {
        if (year < 1980) return null
        val start = lastSunday(year, 3).toEpochDay() * 86400 + 3600
        val end = lastSunday(year, if (year < 1996) 9 else 10).toEpochDay() * 86400 + 3600
        return start to end
    }

    override fun offsetAt(epochSecond: Long): Int {
        val year = LocalDate.ofEpochDay(floorDiv(epochSecond, 86400)).year
        val s = summer(year) ?: return 3600
        return if (epochSecond >= s.first && epochSecond < s.second) 7200 else 3600
    }
}

class ZonedDateTime private constructor(private val dateTime: LocalDateTime, val zone: ZoneId, private val offsetSeconds: Int) : Temporal {
    val offset: ZoneOffset get() = ZoneOffset.ofTotalSeconds(offsetSeconds)
    val hour: Int get() = dateTime.hour
    val minute: Int get() = dateTime.minute
    val dayOfWeek: DayOfWeek get() = dateTime.dayOfWeek

    fun toLocalDateTime() = dateTime
    fun toLocalDate() = dateTime.toLocalDate()
    fun toLocalTime() = dateTime.toLocalTime()
    fun toEpochSecond(): Long = dateTime.toEpochSecondUtc() - offsetSeconds
    fun toInstant(): Instant = Instant.ofEpochSecond(toEpochSecond(), dateTime.nano.toLong())
    fun withZoneSameInstant(zone: ZoneId): ZonedDateTime = if (zone == this.zone) this else ofInstant(toInstant(), zone)

    override fun equals(other: Any?) = other is ZonedDateTime && dateTime == other.dateTime && zone == other.zone && offsetSeconds == other.offsetSeconds
    override fun hashCode() = dateTime.hashCode() xor offsetSeconds xor zone.hashCode()
    override fun toString(): String {
        val off = ZoneOffset.ofTotalSeconds(offsetSeconds)
        return "$dateTime$off" + if (zone is ZoneOffset) "" else "[$zone]"
    }

    companion object {
        fun ofInstant(instant: Instant, zone: ZoneId): ZonedDateTime {
            val off = zone.offsetAt(instant.epochSecond)
            return ZonedDateTime(LocalDateTime.ofEpochSecondUtc(instant.epochSecond + off, instant.nano), zone, off)
        }

        /** java.time's rules: in an overlap the earlier offset, in a gap the time moves on by the gap's length. */
        fun ofLocal(local: LocalDateTime, zone: ZoneId): ZonedDateTime {
            val utc = local.toEpochSecondUtc()
            val candidates = listOf(zone.offsetAt(utc - 86400), zone.offsetAt(utc + 86400)).distinct()
            val valid = candidates.filter { zone.offsetAt(utc - it) == it }
            return when {
                valid.isEmpty() -> {
                    val before = candidates.min()
                    val after = candidates.max()
                    ZonedDateTime(local.plusSeconds((after - before).toLong()), zone, after)
                }
                else -> ZonedDateTime(local, zone, valid.max())
            }
        }

        fun of(local: LocalDateTime, zone: ZoneId) = ofLocal(local, zone)
    }
}
