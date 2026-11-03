# :core

## Kotlin Multiplatform (JVM + JS)
- JVM target (used by `:app`): `src/main/kotlin` compiled exactly as before, tests `src/test/kotlin` (`jvmTest`).
- JS target (the web app): the same `src/main/kotlin` (minus `update/`) against small stand-ins for the `java.*`
  classes core uses, in `src/jsMain/kotlin/java/...`: `java.time` (LocalDate/Time/DateTime, Instant, Duration,
  ZoneId with UTC + Europe/Berlin EU rules, DateTimeFormatter for fixed-width patterns with the SMART day resolution,
  ChronoUnit, IsoFields, TemporalAdjusters), `java.util.zip` (CRC32; Deflater/Inflater via pako 2.1.0, inflate
  capped), `BitSet`, `ByteArrayOutputStream`, `ConcurrentHashMap`; `share/JvmText.kt` = `String.toByteArray()` with the
  JVM's '?' for lone surrogates. **New java.* API in core = add it to the stand-in** (the JS compile fails otherwise).
  No inline regex flags (`(?i)`): use `RegexOption` (JS has none). No `String.format`, `Character`, `System`, `Math`.
- `src/jsMain/kotlin/.../web/WebCore.kt`: the exported API (`@JsExport`, JSON strings in/out) the web app calls.
- `src/compatTest/kotlin` (in jvmTest and jsTest): `CompatProbe.describe` = state JSON, schedules, together, subjects,
  a java.time probe, QR payloads, files, frames, backup; `CompatEdits` = every `StoreEdits` step in a fixed sequence (JVM
  states golden; `CompatTest.webCoreEditsGiveTheJvmStates` drives the same steps through WebCore's API). Task `compatGolden` writes the JVM result as `CompatGolden.kt`
  (build/generated) for jsTest; `CompatTest` (jsNodeTest) must reproduce it byte for byte (deflate output too, pako ==
  the JDK's zlib so far) and checks `WebCore`. `:core:test` runs jvmTest + jsNodeTest.
- `:core:webCore` syncs the optimized JS build (whole-program DCE, ES module) into `web/src/generated/core`.
- `:core:qrTool` (QrTools.kt, ZXing on the test classpath only): `write <dir>` = fixtures for the web tests (a friend's
  share as file, ZXing QR image and animated frames, an Android backup), `read <png>...` = decode like the app's
  scanner, `file <share>` = read a share file.

## Packages
- `:core` (tests in `core/src/test`, fixture `lsf_scrubbed.ics`):
  - `ical/IcsReader` (lenient lines/props/components), `HtmlText`, `LsfText` (LOCATION/SUMMARY split,
    `titleStem`), `LsfImport` -> `ImportResult(events, source, warnings)`.
  - `model/`: `Event`, `Location`, `Recurrence`, `Plan`, `Person`, `CourseKeys` (UID hypothesis),
    `CourseType`, `Subjects` (grouping, `setStyle`, `move`, `carryOver`, `resolved` = automatic colors filled in,
    `forFriend`/`overrides` for a friend's plan), `CourseSetting`, `PersonalChange` (own changes, see below), `Hidden`
    (see "Hiding"); kotlinx JSON.
  - `schedule/`: `Expansion` (slots incl. cancelled), `Schedule` (occurrences with `Status`
    Regular/Changed/Extra, unreplaced `cancellations`, `day`/`week` (Mon–Fri plus Sat/Sun only when something is on them,
    hidden dates don't count; `WeekPlan.lastDay`), `hours`), `DayPlan`/`WeekPlan`
    (`free` = all cancelled -> "Vorlesungsfrei"), `Navigation` (`weekToOpen` (with a schedule: this week on a weekend day
    while something of it is still to come), `weeks` = strip/pager range from 1 week before the first event, `days` /
    `dayPage` = the day pager's pages (the days each week shows), `dayToOpen`, `TodayView`), `DayLayout`, `NowStatus` (drawer now-line), `Together`
    (matching, `OccurrenceKey`), `Personal` (own changes resolved per occurrence, `PersonalChanges` editing + orphans),
    `Hiding` (what a `Schedule` leaves out, `forSharing`), `AddedCourses`/`Groups`, `FriendCourses` ("Zu meinem Plan"), `FreeGaps`/`FreeGap` (`DayPlan.gaps`: breaks of >= 45 min between occurrences, cancelled/hidden leave a gap), `HourScale` (hour height of the week grid: fill the height, minimum, margin).
  - `share/`: `Base45` (RFC 9285), `ShareCodec` (`SharedPlan`, `ShareResult`, QR text / file, limits).
  - `store/`: `StoredData` + `MyShare` + `SubjectPalette` (default subject colors), `Migrations`, `BackupFormat` (the
    backup envelope; `:app`'s `BackupCodec` maps the theme), `StoreOps` (pure import, friend import, my share),
    `StoreEdits` (every other edit of the stored state, pure: styles, moves, rhythms, hiding, "Termine auswählen",
    manual courses, added/friend courses, .ics friends, friend order/name/favorite, share identity; `PlanRepository`
    and WebCore both call it), `HideTargets` (what "Ausblenden" hides), `ImportRouting` (.ics routing).
  - `update/` (JVM only): `UpdateManifest`, `UpdatePolicy` (see "In-app updater").
- Share codec details: `core/src/main/kotlin/de/luwify/stundenplanmd/core/share/CLAUDE.md`; updater: `app/src/main/java/de/luwify/stundenplanmd/data/update/CLAUDE.md`.

## LSF export findings (PRODID "QIS-LSF HIS eG")
- CRLF line endings; inside multi-line DESCRIPTION the line breaks are `\r\r\n`. Split on `\r*\n`.
- No line folding in this export, but support it (leading space/tab). Any line that is not
  `NAME[;params]:` is a continuation of the previous property (real newline).
- No RFC escaping: SUMMARY/LOCATION contain raw commas. Unescape `\, \; \n \\` only if present.
- DESCRIPTION is HTML (`<p>`, `<strong>`, `<span style>`, `<a href="mailto:">`, `&nbsp;` as U+00A0).
  Strip to plain text: block tags -> newline, drop tags, decode entities, NBSP -> space, drop empty lines.
- EXDATE: trailing comma, empty value (`EXDATE;TZID=Europe/Berlin:`) on single events, not sorted,
  may contain DTSTART itself and dates outside the INTERVAL=2 rhythm (harmless, just ignore).
- RRULE: always `FREQ=WEEKLY;UNTIL=...Z;INTERVAL=1|2;BYDAY=<one day>`; BYDAY equals DTSTART's weekday.
  UNTIL is `T235900Z` (inclusive). Expand in local time (LocalDateTime + Europe/Berlin), so DST
  switches keep the wall-clock time. INTERVAL=2 means LSF's "gKW/uKW" (even/odd ISO calendar week), which differs from
  14-day steps after a year with a week 53 (2026: KW53 and KW1 both odd). Real exports: the UNTIL of every such series
  but one is on the calendar-week parity, not on the 14-day rhythm; one Praktikum's UNTIL is on the 14-day rhythm only.
  So `Expansion.rhythmOf` decides per series from UNTIL: parity of DTSTART's ISO week, unless only the 14-day rhythm
  ends on UNTIL's day. `Recurrence.rhythm` (set by hand) wins: manual courses (gerade/ungerade KW, alle 14 Tage =
  `BIWEEKLY`, its meaning in v5) and my overrides per series (`StoredData.rhythms[person][uid]`, subject editor, per
  biweekly series of a course, also for friends' plans; mine are shared with my own changes). Shares carry no rhythm:
  `Expansion.portable` rewrites a series set by hand (first date, UNTIL = last date), the codec sends a series as
  "plain" only in the parity rhythm of its first date (the reader rebuilds that), else as a full rule whose UNTIL tells.
- Events without RRULE are single dates (exam repeats, extra appointments).
- Rescheduling is modelled as: series gets an EXDATE + a separate VEVENT (same course prefix,
  new appointment id) with another room/time, sometimes as a short series with its own RRULE.
- LOCATION: `K18 - K18-012 (60 Pl.)`; may be empty, have no capacity (`K11 - K11-118`) or an
  alias in parentheses (`K07 - K07-H2 (K07-215) (120 Pl.)`). Building codes include letters (`K09B`).
  (Room examples are the fixture's fake ones.)
- SUMMARY: `Title (Lecturers)` where lecturers are comma-separated; last paren group is not always
  a lecturer: `(VL)`, `(Ü)`, `(HSÜ)`, `(Übung 2)`, `(Seminar)`, `(Großübung)` are title parts;
  some summaries have no lecturer at all; `(Tutor FMA)` is a lecturer. Double spaces occur.
  Always keep the raw summary.
- CATEGORIES is the type, e.g. `Vorlesung (V)`, `Übung (Ü)`, `Vorlesung/Übung (V/Ü)`,
  `Praktikum (P)`, `Seminar (S)`, `Sonst. LV (SLV)`.
- UID hypothesis: 12 digits = 6-digit LSF course id + 6-digit appointment id. Consistent so far:
  all appointments of one course share the prefix, including rescheduled ones. Note: one subject
  has several course ids (in the fixture: Numerik N4 VL, HSÜ, GÜ are separate courses).
- Real overlaps exist (two events Tue 07–09). Back-to-back events touch (end == start): not overlap.
- Times span 07:00–19:00, Mon–Fri. VTIMEZONE block ignored.
