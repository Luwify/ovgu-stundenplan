package de.luwify.stundenplanmd.core

import de.luwify.stundenplanmd.core.ical.LsfImport

/** Scrubbed copy of a real LSF export (fake lecturers and UIDs, structure and quirks unchanged). */
object Fixture {
    val text: String by lazy {
        requireNotNull(Fixture::class.java.getResourceAsStream("/lsf_scrubbed.ics")).use { it.readBytes().decodeToString() }
    }
    val result by lazy { LsfImport.parse(text) }
    val events get() = result.events

    fun event(uid: String) = events.single { it.uid == uid }
}
