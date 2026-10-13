package de.luwify.stundenplanmd.core.ical

import de.luwify.stundenplanmd.core.model.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LsfTextTest {
    @Test
    fun locations() {
        assertEquals(Location("K18 - K18-012 (60 Pl.)", "K18", "K18-012", null, 60), LsfText.location("K18 - K18-012 (60 Pl.)"))
        assertEquals(Location("K11 - K11-118", "K11", "K11-118"), LsfText.location("K11 - K11-118"))
        assertEquals(
            Location("K07 - K07-H2 (K07-215) (120 Pl.)", "K07", "K07-H2", "K07-215", 120),
            LsfText.location("K07 - K07-H2 (K07-215) (120 Pl.)"),
        )
        assertEquals("K09B", LsfText.location("K09B - K09B-110 (70 Pl.)")?.building)
        assertEquals(Location("Online", null, "Online"), LsfText.location("Online"))
        assertNull(LsfText.location(""))
        assertNull(LsfText.location("  "))
    }

    private fun split(summary: String, parts: Set<String> = emptySet()) = LsfText.splitSummary(summary, parts)

    @Test
    fun summariesWithLecturers() {
        assertEquals("Angewandte Statik 2 - Übung - VT" to listOf("Albers"), split("Angewandte Statik 2 -  Übung - VT (Albers)"))
        assertEquals("Numerik N4 (HSÜ)" to listOf("Brandt"), split("Numerik N4 (HSÜ) (Brandt)"))
        assertEquals("Wellen und Felder (Übung 2)" to listOf("Hahn", "Arndt-Beck"), split("Wellen und Felder (Übung 2) (Hahn, Arndt-Beck)"))
        assertEquals("Numerik N4 (GÜ PQR, ST)" to listOf("Tutor FMA"), split("Numerik N4 (GÜ PQR, ST) (Tutor FMA)"))
        assertEquals(
            "Grundlagen der Feldtheorie III (Großübung)" to listOf("Nolte", "Mohr", "Lenz"),
            split("Grundlagen der Feldtheorie III (Großübung) (Nolte, Mohr, Lenz)"),
        )
        assertEquals("Bausteine der Sensorik (Bordelektronik) (Seminar)" to listOf("Quast"), split("Bausteine der Sensorik (Bordelektronik) (Seminar) (Quast)"))
    }

    @Test
    fun summariesWithoutLecturers() {
        val gdl = "Grundlagen der Digitalen Logik (Nachrichtentechnik/Bordnetztechnik) Vorlesung"
        assertEquals(gdl to emptyList<String>(), split(gdl))
        for (s in listOf("Numerik N4 (VL)", "Grundlagen der Aktorik (Ü)", "Wellen und Felder (Übung 2)", "Analysis (Seminar)", "Physik (P PQR/STU)", "Kurs"))
            assertEquals(s to emptyList<String>(), split(s))
    }

    @Test
    fun groupUsedInsideAnotherTitleIsNotALecturer() {
        val parts = LsfText.titleParts(listOf("Bausteine der Sensorik (Bordelektronik) (Engel)"))
        assertEquals(setOf("Bordelektronik"), parts)
        assertEquals("Bausteine der Sensorik (Bordelektronik)" to emptyList<String>(), split("Bausteine der Sensorik (Bordelektronik)", parts))
        // Without the plan context it is a guess: one capitalized word looks like a name.
        assertEquals(listOf("Bordelektronik"), split("Bausteine der Sensorik (Bordelektronik)").second)
    }

    @Test
    fun titleStems() {
        val stems = mapOf(
            "Numerik N4 (VL)" to "Numerik N4",
            "Numerik N4 (HSÜ)" to "Numerik N4",
            "Numerik N4 (GÜ PQR, ST)" to "Numerik N4",
            "Grundlagen der Digitalen Logik (Nachrichtentechnik/Bordnetztechnik) Vorlesung" to
                "Grundlagen der Digitalen Logik (Nachrichtentechnik/Bordnetztechnik)",
            "Grundlagen der Digitalen Logik (Nachrichtentechnik/Bordnetztechnik) Übung" to
                "Grundlagen der Digitalen Logik (Nachrichtentechnik/Bordnetztechnik)",
            "Grundlagen der Digitalen Logik (Nachrichtentechnik/Bordnetztechnik) Praktikum" to
                "Grundlagen der Digitalen Logik (Nachrichtentechnik/Bordnetztechnik)",
            "Grundlagen der Feldtheorie III (Großübung)" to "Grundlagen der Feldtheorie III",
            "Grundlagen der Feldtheorie III" to "Grundlagen der Feldtheorie III",
            "Grundlagen der Feldtheorie (P PQR/STU) Teil2" to "Grundlagen der Feldtheorie (P PQR/STU) Teil2",
            "Angewandte Statik 2 -  Übung - VT" to "Angewandte Statik 2",
            "Angewandte Statik 2- Hörsaalübung" to "Angewandte Statik 2",
            "Bausteine der Sensorik (Bordelektronik) (Seminar)" to "Bausteine der Sensorik (Bordelektronik)",
            "Wellen und Felder (Übung 2)" to "Wellen und Felder",
            "Grundlagen der Aktorik (Ü)" to "Grundlagen der Aktorik",
            "Ausgabe GFT III Skript" to "Ausgabe GFT III Skript",
            "Übung" to "Übung",
            // Type words only as whole words or as the head of a compound.
            "Datenbanktechnik & IT-Projektmanagement" to "Datenbanktechnik & IT-Projektmanagement",
            "Projektmanagement" to "Projektmanagement",
            "Grundlagen der Werkstoffprüfung" to "Grundlagen der Werkstoffprüfung",
            "Einführung Softwareprojekt" to "Einführung Softwareprojekt",
            "Seminarvortrag Robotik" to "Seminarvortrag Robotik",
            "Laborversuche Elektronik" to "Laborversuche Elektronik",
            "Programmierung (Tafelübung)" to "Programmierung",
            "Elektrotechnik Laborpraktikum" to "Elektrotechnik",
            "Robotik Proseminar" to "Robotik",
            "Softwaretechnik Projekt" to "Softwaretechnik",
        )
        for ((title, stem) in stems) assertEquals(title, stem, LsfText.titleStem(title))
    }
}
