package de.luwify.stundenplanmd.core.share

/**
 * Preset dictionary of share format 4 (deflate): generic wording of LSF exports, so the first type, room pattern or
 * common title word of a plan is already a back reference (about 7 % smaller on a real plan). Only generic strings,
 * nothing from a real plan; the most frequent parts come last (nearer back references are cheaper).
 *
 * **Part of the format: never change it.** Every version-4 code ever shown was compressed against exactly these bytes
 * (`ShareCodecTest` pins its CRC). A different dictionary needs a new format version.
 */
internal object ShareDictionary {
    private val parts = listOf(
        // Notes and lecturers.
        "Bitte beachten Sie: Die Veranstaltung findet statt. entfällt Anmeldung über Moodle Klausur Prüfung " +
            "Nachholtermin Ersatztermin Raumänderung Online Zoom Webex https://www. .de Termine nach Absprache " +
            "Abgabe Testat Tutorium Sprechstunde Gruppe 1 Gruppe 2 Gruppe 3 Woche gerade ungerade KW ",
        "Prof. Dr. Prof. Dr.-Ing. Dr. Dr.-Ing. Dipl.-Ing. M.Sc. B.Sc. Tutor apl. Prof. Jun.-Prof. PD Dr. ",
        // Title words.
        "Einführung in die Grundlagen der Mathematik Analysis Lineare Algebra Numerik Statistik Physik Chemie " +
            "Informatik Programmierung Software Systeme Technische Mechanik Elektrotechnik Regelungstechnik " +
            "Werkstoffe Konstruktion Thermodynamik Strömungslehre Messtechnik Signale Theorie Praxis Methoden " +
            "Projekt Labor Seminar Kolloquium Teil 1 Teil 2 I II III für Ingenieure und der des im ",
        // Types (CATEGORIES) and title parts.
        "Sonst. LV (SLV)Seminar (S)Praktikum (P)Vorlesung/Übung (V/Ü)Übung (Ü)Vorlesung (V)" +
            " (Seminar) (Praktikum) (Großübung) (HSÜ) (Übung 2) (Übung) (Ü) (VL) ",
        // Rooms: "K18 - K18-012 (60 Pl.)", the share keeps "room|alias|capacity".
        "|120|100|80|60|50|40|30|25|20|| (120 Pl.) (60 Pl.) (40 Pl.) (30 Pl.) (20 Pl.) Pl.) -H1 -H2 -H3 -0 -1 -2 -3 - K",
    )

    val bytes: ByteArray = parts.joinToString("").toByteArray()
}
