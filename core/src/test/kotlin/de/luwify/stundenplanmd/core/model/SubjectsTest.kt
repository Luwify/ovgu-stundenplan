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
        val gft = Subjects.build(events, settings).single { it.name == "Grundlagen der Feldtheorie III" }
        settings = Subjects.setStyle(settings, gft, " GFT III ", 0x1565C0)
        assertEquals(setOf("lsf:900154", "lsf:900198"), settings.keys)

        // Move the Praktikum into GFT III: it takes over the style.
        val subjects = Subjects.build(events, settings)
        val praktikum = subjects.single { it.name.endsWith("Teil2") }.courses.single()
        settings = Subjects.move(settings, praktikum, gft.key, subjects)
        val merged = Subjects.build(events, settings).single { it.key == gft.key }
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

    @Test
    fun resolvedFillsAutomaticColorsPerSubject() {
        val settings = mapOf("lsf:900154" to CourseSetting("lsf:900154", "x", shortName = "GFT", color = 7))
        val resolved = Subjects.resolved(events, settings) { 1000 + it }
        val subjects = Subjects.build(events, emptyMap())
        assertEquals(Subjects.courses(events).map { it.key }.toSet(), resolved.keys)
        assertEquals(7, resolved.getValue("lsf:900198").color) // same subject as the styled course
        assertEquals("GFT", resolved.getValue("lsf:900198").shortName)
        val other = subjects.indexOfFirst { s -> s.courses.none { it.key == "lsf:900154" } }
        assertEquals(1000 + other, resolved.getValue(subjects[other].courses.first().key).color)
    }

    @Test
    fun friendPlanUsesLocalThenMineThenTheirs() {
        val n4vl = events.first { it.courseKey == "lsf:900066" }
        val theirs = n4vl.copy(uid = "777777000001", title = "Numerik N4 (GÜ XYZ)", summary = "Numerik N4 (GÜ XYZ)", category = "Übung (Ü)")
        val friend = listOf(n4vl, theirs)
        val mine = Subjects.resolved(events, emptyMap()) { 0x111111 }
        val shared = mapOf(
            "lsf:900066" to CourseSetting("lsf:900066", "Numerik N4 (VL)", shortName = "Mathe", color = 0x222222),
            "lsf:777777" to CourseSetting("lsf:777777", "Numerik N4 (GÜ XYZ)", shortName = "Mathe", color = 0x222222),
        )
        var shown = Subjects.forFriend(friend, emptyMap(), mine, shared)
        assertEquals(0x111111, shown.getValue("lsf:900066").color) // a course I take: my color
        assertEquals(0x222222, shown.getValue("lsf:777777").color) // only theirs: their default
        // In one subject, the first course in type order (V before Ü) sets the style: mine.
        assertEquals(0x111111, Subjects.build(friend, shown).single().color)

        // Editing their subject stores only what changed as my local override, which then wins.
        val subject = Subjects.build(friend, shown).single()
        val edited = Subjects.setStyle(shown, subject, "N4", 0x333333)
        val local = Subjects.overrides(emptyMap(), shown, edited)
        assertEquals(setOf("lsf:900066", "lsf:777777"), local.keys)
        shown = Subjects.forFriend(friend, local, mine, shared)
        assertEquals(listOf(0x333333, 0x333333), listOf(shown.getValue("lsf:900066").color, shown.getValue("lsf:777777").color))
        // Without anything: automatic (no entry).
        assertEquals(emptyMap<String, CourseSetting>(), Subjects.forFriend(listOf(theirs), emptyMap(), emptyMap(), emptyMap()))
    }

    @Test
    fun shortNameIsThePrefixPlusThePartOfTheType() {
        val subject = Subjects.build(events, emptyMap()).single { it.name == "Numerik N4" }
        var settings = Subjects.setStyle(emptyMap(), subject, "N4", 0x3366CC)
        val vl = subject.courses.single { it.key == "lsf:900066" }
        settings = Subjects.setTypeShortName(settings, vl, " VL ")
        val n4 = Subjects.build(events, settings).single { it.name == "Numerik N4" }
        assertEquals("N4 VL", n4.displayNameOf("lsf:900066"))
        // No part for the type: only the prefix, as before.
        assertEquals("N4", n4.displayNameOf("lsf:900022"))
        assertEquals("N4", n4.displayName)
        // A part without a prefix follows the full name; nothing set at all: no short name.
        val noPrefix = Subjects.build(events, Subjects.setStyle(settings, n4, null, null)).single { it.name == "Numerik N4" }
        assertEquals("Numerik N4 VL", noPrefix.displayNameOf("lsf:900066"))
        assertEquals("Numerik N4 VL", noPrefix.shortNameOf("lsf:900066"))
        assertEquals(null, noPrefix.shortNameOf("lsf:900022"))
        // Blank = none.
        val cleared = Subjects.setTypeShortName(settings, vl, "  ")
        assertEquals(null, cleared["lsf:900066"]!!.typeShortName)
    }

    @Test
    fun typePartsStayWithTheirCourseWhenStyledMovedOrResolved() {
        val subject = Subjects.build(events, emptyMap()).single { it.name == "Numerik N4" }
        val vl = subject.courses.single { it.key == "lsf:900066" }
        var settings = Subjects.setTypeShortName(Subjects.setStyle(emptyMap(), subject, "N4", null), vl, "VL")
        // A new prefix keeps the parts.
        settings = Subjects.setStyle(settings, Subjects.build(events, settings).single { it.name == "Numerik N4" }, "Num", null)
        assertEquals("Num VL", Subjects.build(events, settings).single { it.name == "Numerik N4" }.displayNameOf("lsf:900066"))
        // Shared/resolved settings carry the part.
        assertEquals("VL", Subjects.resolved(events, settings) { 0 }.getValue("lsf:900066").typeShortName)
        // Moved to another subject: the part goes along, the prefix is the target's.
        val subjects = Subjects.build(events, settings)
        val target = subjects.single { it.name == "Wellen und Felder" }
        val moved = Subjects.move(Subjects.setStyle(settings, target, "WuF", null), vl, target.key, Subjects.build(events, Subjects.setStyle(settings, target, "WuF", null)))
        assertEquals("WuF VL", Subjects.build(events, moved).single { it.key == target.key }.displayNameOf("lsf:900066"))
    }
}
