package de.luwify.stundenplanmd.core.ical

/** Turns the HTML that LSF puts into DESCRIPTION into plain text with paragraphs as lines. */
object HtmlText {
    private val anyTag = Regex("<[^>]*>")
    private val blockEnd = Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr|ul|ol)\\s*>")
    private val listItem = Regex("(?i)<li(\\s[^>]*)?>")
    private val entity = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
    private val spaces = Regex("[ \\t\\u00A0\\u2007\\u202F]+")
    private val named = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü",
        "szlig" to "ß", "euro" to "€", "ndash" to "–", "mdash" to "—", "hellip" to "…", "bdquo" to "„",
        "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "lsquo" to "‘", "rsquo" to "’", "shy" to "",
    )

    fun toPlain(input: String): String {
        val text = if (anyTag.containsMatchIn(input)) {
            // HTML: source line breaks are just whitespace, structure comes from the tags.
            input.replace('\r', ' ').replace('\n', ' ')
                .replace(listItem, "• ")
                .replace(blockEnd, "\n")
                .replace(anyTag, "")
        } else {
            input.replace("\r", "")
        }
        val lines = decode(text).split('\n').map { it.replace(spaces, " ").trim() }
        // Collapse runs of empty lines (LSF uses <p>&nbsp;</p> as spacer) into one, drop them at the ends.
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out += line
        }
        while (out.lastOrNull()?.isEmpty() == true) out.removeAt(out.lastIndex)
        return out.joinToString("\n")
    }

    private fun decode(text: String): String = text.replace(entity) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
            else -> named[name]
        } ?: m.value
    }

    private fun codePoint(cp: Int): String? =
        if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null
}
