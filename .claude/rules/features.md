---
paths:
  - "core/src/**"
  - "app/src/**"
---

# Stundenplan MD: features and product decisions

## Own changes (my plan)
- `PersonalChange` (`:core`): note, s.t., cancelled for me, room, time. Keyed by appointment UID + original date
  (`Timed.ref`: for an LSF-moved occurrence the slot it was moved from); `series` = from that date on (optional `until`)
  for that UID; a single change wins field by field. `Schedule(events, changes)` applies them: cancelled ->
  `Cancellation.byMe` (no "Vorlesungsfrei"; out of together matching and the now-line), room/time replace LSF's. Where LSF
  moved or cancelled the date, my room/time/cancel are `overruled`: shown next to the official change, never applied over
  it. Series edit ends the earlier series the day before. `orphans` (no slot left after a re-import) -> cleanup list in
  settings, never dropped silently. UI: detail sheet actions (editor, "Fällt für mich aus" + undo, "Termine
  auswählen": every date of the group incl. ones cancelled for me or hidden (DATE `Hidden`), checked = takes place; for
  the unchecked ones it asks each time "Als ausgefallen zeigen" / "Ausblenden" (preselected only if they already share
  one state; nothing remembered), `PlanRepository.setDates` writes cancellations and hidden dates at once, never both), markers: dashed violet border + pencil (`PlanColors.own`), note icon, s.t. badge; LSF changes keep amber.
- Sharing: notes never; the rest while `MyShare.withChanges` ("Eigene Änderungen teilen"); friend's in
  `Plan.sharedChanges`, shown as "Von <Name> geändert".

## Hiding (any plan, local)
- `Hidden` (`:core`): kind SUBJECT (subject key + its course keys when hidden; matches either, so a course id changed by a
  re-import or a course moved in by hand stays hidden), COURSE (= a type, course key, all its groups), GROUP (course key +
  series UID, `Groups.baseUid`: the dates LSF moved out of it go with it; offered only when `Groups.count` > 1), DATE
  (`Timed.ref`: UID + original date). Plus title/type/day/time/room for the list. `Hidden.add` replaces the same target
  and drops narrower items the new one covers. Stored per person: `StoredData.hidden[personId]`, never touched by imports.
- `Schedule(events, changes, hiding)` drops hidden occurrences and cancellations after change detection (no "fällt aus",
  no "Vorlesungsfrei" from them), so the views, together matching (both sides) and the drawer now-line never see them.
  `StoredData.settingsOf`/`hidingOf` give the shown settings (subject keys) per person. Subjects list (settings, editor)
  is built from all events, so automatic colors don't shift.
- My share: `Hiding.forSharing` leaves out events whose every date is hidden and lists the other hidden dates (UID + date
  of the slot) -> `SharedPlan.hidden` -> `Plan.sharedHidden`, hidden on the friend's side; changes only for events sent.
  Forwarding passes a friend's `sharedHidden` on.
- UI: detail sheet "Termin ausblenden" (mine: next to "Fällt für mich aus"; friends: next to "Fach bearbeiten") and
  "Ausblenden" (menu: Fach / Nur <Typ> / Nur diese Gruppe; a plain "Fach ausblenden" button when only that applies);
  subject editor: "Fach ausblenden", course row menu "Optionen" (Nur <Typ> ausblenden; my plan: Kurs bearbeiten/löschen
  for manual courses, Aus meinem Plan entfernen for added ones; under "Verschieben" the move targets), so every subject
  is reachable from the settings' subject list whatever its days. Snackbar with undo. Settings section
  "Ausgeblendet" -> `HiddenScreen` (per person, me first, "Wiederherstellen", empty state).

## Short names
- `CourseSetting.shortName` = prefix for all types of the subject ("Kürzel (alle Typen)", same in every course),
  `typeShortName` = this course's part ("Kürzel (dieser Typ)"). Shown: `Subject.displayNameOf(courseKey)` = prefix (or the
  subject name) + space + part; no part = only the prefix. Storage 7 -> 8 keeps old short names as the prefix (nothing
  changes visibly). Editor: type chips pick the course whose part is edited (opened from a date: its course), live preview.

## Manual courses (my plan)
- `ManualCourse` (`:core`): id `manual-<16 hex>` = UID of its one event (never a 12-digit LSF UID), course key
  `manual:<id>` (never merged by title); type, room, lecturer, note, `st`, once / weekly / every 2 weeks, start/end date,
  times, `skipFreeWeeks`. `ManualCourses.merge(lsf, courses)` = my events (`StoredData.myEvents`): EXDATEs on dates in
  weeks/days the LSF plan alone marks free, so free-week detection is unchanged. Style/subject are normal course settings
  (new subject = `own:` key). `Event.st` (course-level s.t.; own changes compare against it). Delete removes its settings
  and my changes to it (undo = previous StoredData). UI: "+" in my top bar -> `ManualCourseEditor`; plus badge
  (`ManualBadge`) in week/day view, "Eigener Kurs" box with edit/delete in the detail sheet; own-change actions apply.
- Shared with the own changes (no notes); share format 2 also carries s.t. flags; manual events or s.t. -> version 2.
  Together: only the exact fallback (no LSF course id).

## Product decisions (v1)
- Import: file picker + VIEW/SEND of `text/calendar` (octet-stream accepted if content is VCALENDAR).
  Re-import replaces the plan, keeps settings.
- .ics routing (`data/ImportRouting`, pure; `IcsImportDialog`): my plan is replaced only from the settings import (and
  the empty plan's button, `vm.importOwn`); there, a file with far fewer courses (`AddedCourses.looksPartial`: fewer than
  half, plan has >= 3) asks "Plan ersetzen" / "Kurse zu meinem Plan hinzufügen". Drawer "+" file and open with/share
  (`vm.import`) ask "Als Freund hinzufügen" (name, or update an existing .ics friend) / "Kurse zu meinem Plan
  hinzufügen" (+ "Als meinen Plan importieren" only while I have none). Shares (`SPMD`) always go to the friend import.
- Added LSF courses (`StoredData.addedEvents`, `:core` `schedule/AddedCourses`): duplicates by full UID (course id +
  appointment id), never by course id or title: same UID as the plan -> left out (notice names day, time, room), same UID
  as an added one -> updated, same course id + other appointment id -> another group, added (same course key, so same
  subject). `myLsfEvents` = plan + added ones whose UID the plan lacks; a full import drops only added events whose UID
  it has (`dedupe`, notice). `Groups` (`:core`): a group = one series UID + the dates LSF moved out of it (single extras
  join a course's only group); "Termine auswählen" and removing an added group (detail sheet, undo; drops my changes
  and rhythms for those UIDs) work per group. In my share like the rest of my LSF plan.
- "Zu meinem Plan" (friend's detail sheet, `:core` `schedule/FriendCourses`): copies the tapped date's group
  (`Groups.of` on the friend's plain `Schedule(events)`, so their shared changes, their `sharedHidden` and my hiding for
  them stay behind; my rhythms for their plan too) through the added-courses mechanism (`PlanRepository.addFromFriend` ->
  `AddedCourses.add`, same UIDs, so a re-import dedupes it); a friend's manual course becomes a `ManualCourse` of mine
  with the same id (note not copied, my free weeks). `FriendCourses.state` by series UID: ADDABLE -> "Zu meinem Plan",
  ADDED (in `addedEvents`, from a friend or an .ics) -> "Entfernen" (`removeAdded`), IN_PLAN (imported plan or a manual
  course with that id) -> "In deinem Plan" disabled. Color (`FriendCourses.settings`): course key or title-stem subject
  already mine -> mine, else the friend's shown color as a course setting. Snackbar with undo (previous StoredData).
- Automatic colors (by place in the sorted subject list) never move when my courses are added or removed (added .ics,
  "Zu meinem Plan", removing added ones, manual save/delete): `Subjects.keepColors` (via `StoredData.keepingColors`)
  stores the shown color of every subject (same key) whose automatic one would change. A full re-import doesn't.
- .ics friends: `Person.fromIcs` (id `ics-` + 16 hex, local; no share identity, no shared changes), `planDate` = import
  day, "aus ICS importiert" in the drawer, top bar and details dialog. Updating keeps local name, favorite, order,
  friendSettings (carryOver) and rhythms.
- Several people: me + friends imported from shares (see "Sharing and friends").
- **Subjects:** courses (= UID course id) are grouped into subjects by title stem (type part stripped,
  e.g. N4 VL/HSÜ/GÜ, GDL VL/Übung/Praktikum, GFT III VL/Großübung in the fixture). Short name and color are per
  subject; the type is a small label (V/Ü/P/S) on the event. Grouping is a heuristic: the edit dialog lists the course
  ids of a subject and can move a course to another subject or its own. Settings are stored keyed by course id
  (fallback title), so re-imports keep them.
- **Changes/extras** (logic in `:core`): a single event or short series of a course that falls in a
  week where the same course has an EXDATE is a CHANGE; compare with the regular slot and say what
  changed (room, time, day), e.g. "Raum geändert: K11-117 statt K14-102", "Zeit geändert: 17–19 statt
  11–13". A single event without a matching EXDATE is an EXTRA appointment, labeled with its note.
  Highlight both in week view and Today list, strongest in their week.
- Cancelled dates without replacement: faint, struck through, "fällt aus" in the week view (tap ->
  detail says cancelled); in the Today list only for the current and next week, highlighted like
  changes. All events of a day cancelled -> one "Vorlesungsfrei" label; whole week -> one banner.
  Derived from the data (no holiday list).
- Week view: today's column tinted, day progress fill in the hour column (the red now line is gone); launcher label
  "Stundenplan", in-app "Stundenplan MD".
- UI language: English default + German (`values-de`), like Mensa MD.
- Sat/Sun: open on next week; Today list shows the next day with events. Clear message before the
  semester start and after the last event.
- Themes copied from Mensa MD: System (subtle Material You), Soft, AMOLED, Custom (+ editor, contrast
  tests). No Mensa-specific parts (aura, favorites, glows unless needed by AMOLED/custom). Subject
  colors are contrast-nudged per theme like Mensa MD's accents.

## Sharing and friends
- **UI:** hamburger (top left) opens a navigation drawer: my plan on top, then friends with name,
  "Stand: <planDate>" and a now-line: during an event "K14-102 bis 13:00", between events "frei, ab 13:00
  K14-102", after their last event of the day "frei", nothing on weekends or outside their semester. Drawer
  bottom: plus (choice: scan QR / pick file), share (my plan as a file via the share sheet), QR (show my plan).
  Drawer width: 80 % of the window, at most 320 dp (scrim stays tappable on phones).
  A friend's plan opens in the same Week/Day views, feature complete like mine: detail sheet and the **full
  subject editor** (short name, color, moving courses between subjects), stored as my local settings for that
  friend (`friendSettings[id]`, only what differs from what is shown; "automatic color" = back to the defaults).
  Events are read-only. Top bar for a friend: name, favorite heart, share icon (menu: file / QR; `share/Forwarding`: their
  plan as they shared it, their id and shared name, none of my local settings; an .ics friend's id = the 16 hex digits of
  its local id), edit icon -> details dialog (rename = local name,
  empty = shared name; Stand; shared on <export date>; remove with undo). Settings gear only on my plan.
  The drawer opens with the menu button only (swipes stay with the pagers).
- **Together marker:** Material Symbols "group" icon at the top right of an event block (in narrow split lanes
  next to the type label instead, so titles keep their width) and in the day card's title row. In my plan:
  events shared with friends; in a friend's plan: shared with me or other friends. Detail sheet: "Zusammen mit".
- **Matching** (`:core`, on expanded occurrences, so changes and cancellations count): same date and same
  start/end, and either (a) same LSF course id and (same full UID, or the UID of the other side's original slot
  of a Changed occurrence = stale plan that misses a room change, or same room, or a room missing on one side;
  the room check keeps parallel groups of one course apart), or (b) exact title + category + location raw,
  location present (catches cross-listed courses with other ids). In real exports, shared courses had the identical
  full UID for the same appointment, and title, category, room, time, RRULE, EXDATE were identical. `TogetherTest`
  covers parallel groups (no match), cross-listing (match only on exact title/category/time/room), stale plans (room
  change: match; time change: no match at either time).
- **Colors in a friend's plan:** my local override for that friend > my style if the course id is in my plan
  (resolved, incl. automatic colors) > the friend's shared default > automatic. A share carries every course's
  shown short name and color (automatic ones resolved), so friends see my colors.
- **Identity:** person id from SecureRandom, made at my first share; display name asked at the first share,
  editable in settings. My own id is rejected on import.
- **Import:** one content dispatcher for VIEW/SEND of the own MIME and `application/octet-stream` (VCALENDAR
  -> my plan import, `SPMD` -> friend import); no `*/*`, no `text/plain`. Confirmation dialog (add/replace,
  name, Stand) with undo. The same person id replaces that friend's plan and keeps my local settings for them.
- **Storage:** friends' plans as `Plan`s (`exportedOn`, `planDate`, `sharedSettings`), `Person.localName` (my
  rename), `friendSettings`, `share` (`MyShare`: id, name), `Event.notesShortened`. Friend's `Person.id` = their share
  id. Friends are in the backup via `StoredData`.
- Share codec and animated QR: `core/src/main/kotlin/de/luwify/stundenplanmd/core/share/CLAUDE.md`; QR screens: `app/src/main/java/de/luwify/stundenplanmd/ui/CLAUDE.md`.
