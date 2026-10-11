package de.luwify.stundenplanmd.core.model

import de.luwify.stundenplanmd.core.Fixture
import org.junit.Assert.assertEquals
import org.junit.Test

class SubjectsTest {
    private val events get() = Fixture.events
    private fun grouping(settings: Map<String, CourseSetting> = emptyMap()) =
        Subjects.build(events, settings).associate { s -> s.name to s.courses.map { "${it.key.removePrefix("lsf:")} ${it.type.short}" } }

    @Test
    fun fixtureSubjects() {
        assertEquals(
            mapOf(
                "Ausgabe GFT III Skript" to listOf("900033 SLV"),
                "Bausteine der Sensorik (Bordelektronik)" to listOf("900055 V", "900209 S"),
                "Grundlagen der Feldtheorie (P PQR/STU) Teil2" to listOf("900165 P"),
                "Grundlagen der Feldtheorie III" to listOf("900154 V", "900198 Ü"),
                "Grundlagen der Aktorik" to listOf("900088 V", "900110 Ü"),
                "Grundlagen der Digitalen Logik (Nachrichtentechnik/Bordnetztechnik)" to
                    listOf("900121 V", "900143 Ü", "900077 P"),
                "Numerik N4" to listOf("900066 V", "900022 V/Ü", "900187 Ü"),
                "Wellen und Felder" to listOf("900176 V", "900099 Ü"),
                "Angewandte Statik 2" to listOf("900132 V", "900011 Ü", "900044 Ü"),
            ),
            grouping(),
        )
    }

    @Test
    fun styleAndMove() {
        var settings = emptyMap<String, CourseSetting>()
        val get = Subjects.build(events, settings).single { it.name == "Grundlagen der Feldtheorie III" }
        settings = Subjects.setStyle(settings, get, " GFT III ", 0x1565C0)
        assertEquals(setOf("lsf:900154", "lsf:900198"), settings.keys)

        // Move the Praktikum into GFT III: it takes over the style.
        val subjects = Subjects.build(events, settings)
        val praktikum = subjects.single { it.name.endsWith("Teil2") }.courses.single()
        settings = Subjects.move(settings, praktikum, get.key, subjects)
        val merged = Subjects.build(events, settings).single { it.key == get.key }
        assertEquals(listOf("lsf:900154", "lsf:900198", "lsf:900165"), merged.courses.map { it.key })
        assertEquals("GFT III", merged.displayName)
        assertEquals(0x1565C0, merged.color)

        // And out again into its own subject.
        settings = Subjects.move(settings, praktikum, Subjects.ownKey(praktikum.key), Subjects.build(events, settings))
        assertEquals(listOf("lsf:900165"), Subjects.build(events, settings).single { it.key == Subjects.ownKey("lsf:900165") }.courses.map { it.key })
    }

    @Test
    fun movingBackToTheAutomaticSubjectClearsTheOverride() {
        val tm = Subjects.courses(events).single { it.key == "lsf:900044" }
        var settings = Subjects.move(emptyMap(), tm, Subjects.ownKey(tm.key), emptyList())
        settings = Subjects.move(settings, tm, Subjects.autoKey(tm.title), Subjects.build(events, settings))
        assertEquals(null, settings.getValue("lsf:900044").subject)
    }

    @Test
    fun carryOverByTitleWhenTheCourseIdChanged() {
        val settings = mapOf(
            "lsf:123456" to CourseSetting("lsf:123456", "Wellen und Felder", shortName = "WuF", color = 1),
            "lsf:900099" to CourseSetting("lsf:900099", "Wellen und Felder (Übung 2)", color = 2),
            "lsf:999999" to CourseSetting("lsf:999999", "Gone"),
        )
        val out = Subjects.carryOver(settings, events)
        assertEquals("WuF", out.getValue("lsf:900176").shortName)
        assertEquals(2, out.getValue("lsf:900099").color)
        assertEquals(setOf("lsf:123456", "lsf:900099", "lsf:999999", "lsf:900176"), out.keys)
    }
}
