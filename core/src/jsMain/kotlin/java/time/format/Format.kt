package java.time.format

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class DateTimeParseException(message: String?, val parsedString: CharSequence, val errorIndex: Int) : DateTimeException(message)

/** Fixed-width numeric patterns only (yyyy MM dd HH mm ss and 'literals'), what :core parses .ics values with. */
class DateTimeFormatter private constructor(private val tokens: List<Token>) {
    private sealed class Token {
        class Field(val letter: Char, val width: Int) : Token()
        class Literal(val text: String) : Token()
    }

    private fun fields(text: CharSequence): Map<Char, Int> {
        val s = text.toString()
        var pos = 0
        val out = HashMap<Char, Int>()
        for (t in tokens) when (t) {
            is Token.Literal -> {
                if (!s.startsWith(t.text, pos)) throw DateTimeParseException("Text '$s' could not be parsed at index $pos", s, pos)
                pos += t.text.length
            }
            is Token.Field -> {
                val part = if (pos + t.width <= s.length) s.substring(pos, pos + t.width) else ""
                if (part.length != t.width || !part.all { it in '0'..'9' }) {
                    throw DateTimeParseException("Text '$s' could not be parsed at index $pos", s, pos)
                }
                out[t.letter] = part.toInt()
                pos += t.width
            }
        }
        if (pos != s.length) throw DateTimeParseException("Text '$s' could not be parsed, unparsed text found at index $pos", s, pos)
        return out
    }

    /** java.time's default SMART resolver: a day of month 1..31 past the month's end becomes its last day. */
    private fun date(f: Map<Char, Int>, text: CharSequence): LocalDate = try {
        val y = f['y'] ?: f['u'] ?: missing(text)
        val m = f['M'] ?: missing(text)
        val d = f['d'] ?: missing(text)
        if (m !in 1..12 || d !in 1..31) throw DateTimeException("Invalid date")
        LocalDate.of(y, m, minOf(d, LocalDate.of(y, m, 1).lengthOfMonth()))
    } catch (e: DateTimeParseException) {
        throw e
    } catch (e: DateTimeException) {
        throw DateTimeParseException("Text '$text' could not be parsed: ${e.message}", text, 0)
    }

    private fun missing(text: CharSequence): Nothing = throw DateTimeParseException("Text '$text' could not be parsed", text, 0)

    internal fun parseDate(text: CharSequence): LocalDate = date(fields(text), text)

    internal fun parseDateTime(text: CharSequence): LocalDateTime {
        val f = fields(text)
        val d = date(f, text)
        val t = try {
            LocalTime.of(f['H'] ?: missing(text), f['m'] ?: 0, f['s'] ?: 0)
        } catch (e: DateTimeParseException) {
            throw e
        } catch (e: DateTimeException) {
            throw DateTimeParseException("Text '$text' could not be parsed: ${e.message}", text, 0)
        }
        return LocalDateTime.of(d, t)
    }

    companion object {
        fun ofPattern(pattern: String): DateTimeFormatter {
            val tokens = mutableListOf<Token>()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '\'' -> {
                        val end = pattern.indexOf('\'', i + 1)
                        require(end > i) { "unterminated literal" }
                        tokens += Token.Literal(if (end == i + 1) "'" else pattern.substring(i + 1, end))
                        i = end + 1
                    }
                    c in "yuMdHms" -> {
                        var j = i
                        while (j < pattern.length && pattern[j] == c) j++
                        tokens += Token.Field(c, if (c == 'y' && j - i == 2) 2 else j - i)
                        i = j
                    }
                    c.isLetter() -> throw IllegalArgumentException("Unsupported pattern letter $c (JS stand-in)")
                    else -> { tokens += Token.Literal(c.toString()); i++ }
                }
            }
            return DateTimeFormatter(tokens)
        }
    }
}
