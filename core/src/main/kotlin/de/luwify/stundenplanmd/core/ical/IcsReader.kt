package de.luwify.stundenplanmd.core.ical

/** One content line `NAME;PARAM=V;...:value`; name and parameter keys are upper case. */
data class IcsProperty(val name: String, val params: Map<String, String>, val value: String)

data class IcsComponent(
    val name: String,
    val properties: List<IcsProperty>,
    val children: List<IcsComponent>,
) {
    fun first(name: String): IcsProperty? = properties.firstOrNull { it.name == name }
    fun all(name: String): List<IcsProperty> = properties.filter { it.name == name }

    /** This component and all nested ones, depth first. */
    fun walk(): Sequence<IcsComponent> = sequence {
        yield(this@IcsComponent)
        children.forEach { yieldAll(it.walk()) }
    }
}

/**
 * Lenient reader for the iCalendar subset of the LSF export.
 *
 * - Line breaks may be CRLF, LF, CR or the `\r\r\n` LSF writes inside multi-line values.
 * - Proper folding (continuation line starts with space/tab) is joined without a newline.
 * - LSF writes multi-line DESCRIPTIONs without folding: every line that doesn't start a known property
 *   (`NAME[;params]:`) continues the previous value with a newline. To keep text like "Hinweis: ..." or
 *   "WICHTIG: ..." in a description, only upper-case RFC 5545 names and `X-` names start a property.
 */
object IcsReader {
    private val lineBreak = Regex("\r*\n|\r+")
    private val propertyStart = Regex(
        "^([A-Z][A-Z0-9-]*)((?:;[A-Za-z0-9-]+=(?:\"[^\"]*\"|[^\";:,]*)(?:,(?:\"[^\"]*\"|[^\";:,]*))*)*):",
    )
    private val param = Regex(";([A-Za-z0-9-]+)=(\"[^\"]*\"|[^\";:]*)")

    private val knownNames = setOf(
        "BEGIN", "END", "VERSION", "PRODID", "CALSCALE", "METHOD",
        "TZID", "TZNAME", "TZOFFSETFROM", "TZOFFSETTO", "TZURL",
        "DTSTART", "DTEND", "DTSTAMP", "DUE", "DURATION", "UID", "SUMMARY", "DESCRIPTION", "LOCATION",
        "CATEGORIES", "CLASS", "CREATED", "LAST-MODIFIED", "SEQUENCE", "STATUS", "TRANSP",
        "RRULE", "RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID", "ORGANIZER", "ATTENDEE", "CONTACT",
        "COMMENT", "URL", "GEO", "PRIORITY", "RESOURCES", "RELATED-TO", "ATTACH", "ACTION", "TRIGGER",
        "REPEAT", "FREEBUSY", "REQUEST-STATUS", "COLOR", "NAME", "REFRESH-INTERVAL", "SOURCE",
    )

    /** Top-level components (normally one VCALENDAR). */
    fun read(text: String): List<IcsComponent> {
        val roots = mutableListOf<IcsComponent>()
        val stack = ArrayDeque<Builder>()
        for (property in properties(text)) {
            when (property.name) {
                "BEGIN" -> stack.addLast(Builder(property.value.trim().uppercase()))
                "END" -> {
                    val name = property.value.trim().uppercase()
                    // Unbalanced END: close up to the matching BEGIN, ignore if there is none.
                    if (stack.none { it.name == name }) continue
                    while (true) {
                        val done = stack.removeLast().build()
                        val parent = stack.lastOrNull()
                        if (parent == null) roots += done else parent.children += done
                        if (done.name == name) break
                    }
                }
                else -> stack.lastOrNull()?.properties?.add(property)
            }
        }
        // Missing END lines at EOF: close what is open.
        while (stack.isNotEmpty()) {
            val done = stack.removeLast().build()
            stack.lastOrNull()?.children?.add(done) ?: roots.add(done)
        }
        return roots
    }

    /** Unfolded, joined properties in file order. */
    fun properties(text: String): List<IcsProperty> {
        val logical = mutableListOf<StringBuilder>()
        for (line in text.removePrefix("﻿").split(lineBreak)) {
            val current = logical.lastOrNull()
            when {
                current != null && (line.startsWith(' ') || line.startsWith('\t')) -> current.append(line, 1, line.length)
                startsProperty(line) || current == null -> logical += StringBuilder(line)
                else -> current.append('\n').append(line)
            }
        }
        return logical.mapNotNull { parse(it.toString()) }
    }

    private fun startsProperty(line: String): Boolean {
        val name = propertyStart.find(line)?.groupValues?.get(1) ?: return false
        return name in knownNames || name.startsWith("X-")
    }

    private fun parse(line: String): IcsProperty? {
        val match = propertyStart.find(line) ?: return null
        val params = param.findAll(match.groupValues[2]).associate { m ->
            m.groupValues[1].uppercase() to m.groupValues[2].removeSurrounding("\"")
        }
        // Trailing blank lines (e.g. at EOF) end up as continuation; they never carry content.
        return IcsProperty(match.groupValues[1], params, line.substring(match.range.last + 1).trimEnd('\n'))
    }

    /** Undo RFC 5545 TEXT escaping (`\n`, `\,`, `\;`, `\\`); LSF doesn't escape, so this is a no-op there. */
    fun unescapeText(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    ',', ';', '\\' -> out.append(next)
                    else -> out.append(c).append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private class Builder(val name: String) {
        val properties = mutableListOf<IcsProperty>()
        val children = mutableListOf<IcsComponent>()
        fun build() = IcsComponent(name, properties.toList(), children.toList())
    }
}
