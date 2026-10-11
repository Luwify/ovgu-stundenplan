package de.luwify.stundenplanmd.core.ical

import de.luwify.stundenplanmd.core.model.Location

/** Heuristics for the free-text fields of the LSF export. */
object LsfText {
    private val spaces = Regex("\\s+")
    private val capacity = Regex("\\s*\\((\\d+)\\s*Pl\\.?\\)\\s*$")
    private val buildingRoom = Regex("^(\\S+)\\s+-\\s+(.+)$")
    private val trailingParen = Regex("^(.*\\S)\\s*\\(([^()]+)\\)$")
    private val dashTail = Regex("\\s*-\\s+|\\s+-\\s*")

    /** Words that make a parenthesized group or a title tail an event type, not a lecturer. */
    private val typeAbbreviations = setOf("v", "vl", "ü", "ue", "hsü", "gü", "kü", "p", "s", "se", "t", "tut", "pr", "sem")
    private val typeWords = listOf(
        "vorlesung", "übung", "uebung", "praktikum", "seminar", "tutorium", "kolloquium", "labor", "projekt",
        "exkursion", "repetitorium", "klausur", "prüfung",
    )

    fun normalizeSpaces(text: String) = text.trim().replace(spaces, " ")

    /** `K18 - K18-012 (60 Pl.)`, `K11 - K11-118`, `K07 - K07-H2 (K07-215) (120 Pl.)`; null when empty. */
    fun location(raw: String): Location? {
        val text = normalizeSpaces(raw)
        if (text.isEmpty()) return null
        var rest = text
        val cap = capacity.find(rest)?.let { m ->
            rest = rest.substring(0, m.range.first)
            m.groupValues[1].toIntOrNull()
        }
        var building: String? = null
        buildingRoom.find(rest)?.let { m ->
            building = m.groupValues[1]
            rest = m.groupValues[2]
        }
        var alias: String? = null
        trailingParen.find(rest)?.let { m ->
            rest = m.groupValues[1]
            alias = m.groupValues[2].trim()
        }
        return Location(raw = raw, building = building, room = rest.trim(), alias = alias, capacity = cap)
    }

    /**
     * Parenthesized groups that are followed by more text in some summary of the plan, e.g.
     * "(Bordelektronik)" in "Bausteine der Sensorik (Bordelektronik) (Name)". Such a group is
     * part of a title even when another summary ends with it.
     */
    fun titleParts(summaries: Collection<String>): Set<String> = buildSet {
        for (summary in summaries) {
            val text = normalizeSpaces(summary)
            for (m in Regex("\\(([^()]+)\\)").findAll(text)) {
                if (text.substring(m.range.last + 1).isNotBlank()) add(m.groupValues[1].trim())
            }
        }
    }

    /**
     * SUMMARY "Title (Lecturer, Lecturer)" -> title and lecturers. The last parenthesized group counts as
     * lecturers only if it looks like names: every comma part has a lower-case letter, no digit or slash, no
     * type word, and the group isn't used inside another title ([titleParts]). Otherwise everything is title.
     */
    fun splitSummary(summary: String, titleParts: Set<String> = emptySet()): Pair<String, List<String>> {
        val text = normalizeSpaces(summary)
        val m = trailingParen.find(text) ?: return text to emptyList()
        val group = m.groupValues[2].trim()
        if (group in titleParts || !looksLikeLecturers(group)) return text to emptyList()
        return m.groupValues[1].trim() to group.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun looksLikeLecturers(group: String): Boolean {
        val parts = group.split(',').map { it.trim() }
        return parts.all { part ->
            part.isNotEmpty() && part.any { it.isLowerCase() } && part.none { it.isDigit() } && '/' !in part &&
                !isTypeLike(part)
        }
    }

    private fun isTypeLike(text: String): Boolean {
        val words = text.lowercase().split(' ', ',', '/', '-').filter { it.isNotEmpty() }
        return words.any { w -> w in typeAbbreviations || typeWords.any { it in w } }
    }

    /**
     * Title without its event-type part, used to group the courses of one subject:
     * "Numerik N4 (VL)" / "(HSÜ)" / "(GÜ PQR, ST)" -> "Numerik N4",
     * "GDL (...) Vorlesung" / "Übung" / "Praktikum" -> "GDL (...)",
     * "Angewandte Statik 2 - Übung - VT" / "2- Hörsaalübung" -> "Angewandte Statik 2".
     */
    fun titleStem(title: String): String {
        var stem = normalizeSpaces(title)
        while (true) {
            val before = stem
            trailingParen.find(stem)?.let { m ->
                val group = m.groupValues[2]
                if (isTypeLike(group)) stem = m.groupValues[1]
            }
            val lastWord = stem.substringAfterLast(' ')
            if (lastWord != stem && isTypeLike(lastWord)) stem = stem.dropLast(lastWord.length)
            dashTail.findAll(stem).firstOrNull { isTypeLike(stem.substring(it.range.last + 1)) }?.let {
                stem = stem.substring(0, it.range.first)
            }
            stem = stem.trim().trimEnd('-', '–', ',', ':').trim()
            if (stem == before || stem.isEmpty()) break
        }
        return stem.ifEmpty { normalizeSpaces(title) }
    }
}
