# Stundenplan MD

Android timetable for an OvGU semester plan, imported from the LSF iCalendar export. GPL-3.0-or-later
(LICENSE, README, NOTICE like Mensa MD, the author's other app).

This file is public (GitHub). Personal details (devices, paths, release infrastructure, test notes) live in the
git-ignored `CLAUDE.local.md` at the repo root, which Claude Code loads after this file.

## Stack
- Kotlin, Jetpack Compose, Material 3. Single Activity.
- Package `de.luwify.stundenplanmd`, minSdk 26 (java.time native), target/compile 37.
- Build setup mirrors Mensa MD (AGP 9 built-in Kotlin, version catalog, Gradle 9.8.0, kotlinx.serialization).
- Modules: `:core` (pure Kotlin/JVM: parser, model, expansion, layout math, share codec, update policy;
  no Android imports), `:app` (Android, UI, storage, import intents).
- JDK 21 (Android Studio's JBR); a newer system JDK doesn't work with this Gradle/AGP.
- `./gradlew testDebugUnitTest` also runs `:core:test`; `lint` also lints `:core`.
- Device tests only on the debug build (package `de.luwify.stundenplanmd.preview`, launcher label "Plan Preview"),
  never on the real app `de.luwify.stundenplanmd` (see Rules).

## Architecture
- `:core` (pure JVM, tests in `core/src/test`, fixture `lsf_scrubbed.ics`):
  - `ical/IcsReader` (lenient lines/props/components), `HtmlText`, `LsfText` (LOCATION/SUMMARY split,
    `titleStem`), `LsfImport` -> `ImportResult(events, source, warnings)`.
  - `model/`: `Event`, `Location`, `Recurrence`, `Plan`, `Person`, `CourseKeys` (UID hypothesis),
    `CourseType`, `Subjects` (grouping, `setStyle`, `move`, `carryOver`, `resolved` = automatic colors filled in,
    `forFriend`/`overrides` for a friend's plan), `CourseSetting`, `PersonalChange` (own changes, see below); kotlinx JSON.
  - `schedule/`: `Expansion` (slots incl. cancelled), `Schedule` (occurrences with `Status`
    Regular/Changed/Extra, unreplaced `cancellations`, `day`/`week`, `hours`), `DayPlan`/`WeekPlan`
    (`free` = all cancelled -> "Vorlesungsfrei"), `Navigation` (`weekToOpen`, `weeks` = strip/pager range from 1 week
    before the first event, `dayToOpen`, `TodayView`), `DayLayout`, `NowStatus` (drawer now-line), `Together`
    (matching, `OccurrenceKey`), `Personal` (own changes resolved per occurrence, `PersonalChanges` editing + orphans).
  - `share/`: `Base45` (RFC 9285), `ShareCodec` (`SharedPlan`, `ShareResult`, QR text / file, limits).
  - `update/`: `UpdateManifest`, `UpdatePolicy` (see "In-app updater").
- `:app`: `data/PlanRepository` (one atomic JSON file `files/plans/data.json` + `me.ics`, `StoredData`
  with people/plans/courseSettings/friendSettings/share, import with undo, friend import/rename/delete,
  `shareIdentity`), `data/PersonIds`, `data/IcsSource` (uri -> bytes, text UTF-8/1252),
  `data/settings/ThemeSettings` (DataStore, Mensa MD keys). UI: `MainScreen` (Week/Day tabs, `VerticalPager` over
  `vm.pages` = me + friends in drawer order, synced both ways with `vm.selected`; shared `week`/`day` state so switching
  people keeps the week/day; detail sheet, subject editor, settings overlay), `PeoplePager` (`EdgeOnlyConnection`:
  content scrolls first, the pager moves only for drags that start at the content's edge, the connection moves and
  settles the pager itself; `FollowSharedPage`; `PersonIndicator` name pill while switching), `WeekView` (hour column
  background fills in primaryContainer from the top of the day range down to now (hour labels on it in
  onPrimaryContainer, 2dp primary edge at now), `DayProgress` in `:core`: this week only, 0 before / 1 after the day
  range and on the weekend; no red now line), `DayScreen` (day pager, Mon–Fri, cancellations only near today),
  `PlanStrip` (`ChipStrip`: scrolling week/day chips; selected = primary fill, current = primary outline, muted = no
  events; while the current chip is less than half visible a round tonal chevron button at that edge jumps back
  (selects it and scrolls the strip); no "Heute" button any more; animations off when the system's animator scale is
  0), `DetailSheet` (note hint from `Event.noteHint`; "Zusammen mit" lists favorites first with a heart),
  `SubjectEditor`, `SettingsScreen`, `PlanDrawer` (drawer: names titleMedium semi-bold, Stand/now-line bodySmall
  onSurfaceVariant; friends reordered by long-press drag via `ColumnReorder` (vertical port of Mensa MD's
  `RowReorder`); heart toggle `FavoriteButton`, also in a friend's top bar; add/name/friend dialogs, now-line text),
  `QrScreens` (show QR, scan), `ShareSheet`, `FriendConfirmDialog`; `MainViewModel` (`selected` person, `plan` = shown
  `PlanUi` incl. `together`, `myPlan`, `friends`, one import dispatcher); `ui/theme/` from Mensa MD
  (`StundenplanTheme`, `PlanColors` instead of `MensaColors`: card, card border, change highlight; custom roles
  BG/CARD/TEXT/BORDER/HIGHLIGHT; "glow" = 1dp accent card border, no halo; `favorite` = red, nudged to 3:1 on surface,
  surfaceContainerLow, page and card; custom role FAVORITE), `SubjectColors` (tint + stripe, contrast-nudged).
  `TogetherBadge` (WeekView): group icon on a round surface badge, onSurfaceVariant, or the favorite red when a
  favorite friend attends (`PlanUi.withFavorite`).
- Performance: `MainScreen` keeps every person composed (`composedAround`, one more per idle moment after the start,
  max 8): otherwise the pager's prefetch composes the page beyond the next one at the start of a drag (a whole week
  grid, 80–150 ms). Build type `perf` (`assemblePerf`: R8 like release, not debuggable, debug-signed, `.preview` id)
  for frame times: `dumpsys gfxinfo <pkg>` / `atrace -a <pkg> view gfx`; it installs over the debug copy and keeps
  its test data. A fresh `adb install` is only verified (no AOT); compare runs with the same compile state.
- Baseline profile: `:baselineprofile` (Macrobenchmark `BaselineProfileGenerator`, `androidx.baselineprofile`) writes
  `app/src/main/generated/baselineProfiles/baseline-prof.txt` (committed, merged into every build type, rules for ART).
  Regenerate after big UI changes: debug copy with fake plan + 3 friends installed on the phone (the plugin's
  `nonMinifiedRelease`/`benchmarkRelease` variants are forced to `.preview` + debug key in `app/build.gradle.kts`, never the
  real app), then `ANDROID_SERIAL=<serial> ./gradlew :app:generateBaselineProfile` (~6 min; the task uninstalls the copy
  afterwards). Android applies it only after the first launch at the next idle compile (an `adb install` or an in-app
  update stays `status=verify` until then).
- Dev tools (outputs contain no lecturers or notes; exports to compare live in the git-ignored `private/`):
  - `./gradlew :core:planReport --args="private/<export>.ics 4"` prints occurrences, changes/extras, cancellations,
    subjects and the QR/file share size of an export.
  - `./gradlew :core:planCompare --args="private/a.ics private/b.ics 4"` compares two exports: shared course ids,
    same-UID field diffs, same subject with other ids, same-time near misses, and the "together" occurrences (with
    the rule that fired, via `Together.matches`).
  - `./gradlew :core:shareFile --args="<ics> <out> <name> <16 hex id>"` writes a friend share (`<out>` binary file,
    `<out>.txt` QR text) for preview-app tests; open it with
    `am start -a VIEW -t application/octet-stream -d file:///data/user/0/<pkg>/cache/...`.
- Device testing with fake data: debug build (`<pkg>` = `de.luwify.stundenplanmd.preview`), `adb push` the fixture
  to /data/local/tmp, `run-as` copy into `cache/`,
  `am start -a VIEW -t text/calendar -d file:///data/user/0/<pkg>/cache/...`. Uninstall that copy afterwards (never
  the real app). Several devices may be connected: always `adb -s <serial>`, for Gradle installs `ANDROID_SERIAL`.
  Check `isKeyguardShowing` before screenshots. Rotation for layout checks: `settings put system user_rotation 0|1`
  with `accelerometer_rotation 0`; read the values first and restore them.

## Own changes (my plan)
- `PersonalChange` (`:core`): note, s.t., cancelled for me, room, time. Keyed by appointment UID + original date
  (`Timed.ref`: for an LSF-moved occurrence the slot it was moved from); `series` = from that date on (optional `until`)
  for that UID; a single change wins field by field. `Schedule(events, changes)` applies them: cancelled ->
  `Cancellation.byMe` (no "Vorlesungsfrei"; out of together matching and the now-line), room/time replace LSF's. Where LSF
  moved or cancelled the date, my room/time/cancel are `overruled`: shown next to the official change, never applied over
  it. Series edit ends the earlier series the day before. `orphans` (no slot left after a re-import) -> cleanup list in
  settings, never dropped silently. UI: detail sheet actions (editor, "Fällt für mich aus" + undo, "Termine
  auswählen"), markers: dashed violet border + pencil (`PlanColors.own`), note icon, s.t. badge; LSF changes keep amber.
- Sharing: notes never; the rest while `MyShare.withChanges` ("Eigene Änderungen teilen"); friend's in
  `Plan.sharedChanges`, shown as "Von <Name> geändert".

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

## Persistence (user data must survive every update)
- Files in `files/plans/`: `data.json` (`StoredData`: people (list order = drawer order, me first; `Person.favorite`),
  plans, courseSettings, friendSettings, share, personalChanges, manualCourses, rhythms, addedEvents; field `version`, currently 6; step 2 -> 3 sorts friends
  by shown name, the order v2 displayed; step 3 -> 4 adds `personalChanges`; 4 -> 5 adds `manualCourses`; 5 -> 6 adds `rhythms` and `addedEvents` (.ics friends need no step: `ics-` ids);
  versions 4 and 5 are on a real device, never change their meaning) and `me.ics` (raw import). Theme: DataStore `settings` (key `schema_version`).
  `data/PlanStore` does the I/O: atomic writes (tmp + fsync + rename, `AtomicFiles`), a leftover `data.json.bak` from
  the old AtomicFile scheme is restored, an unreadable or too-new `data.json` is renamed to `data.json.broken[.n]`
  (never overwritten; the app shows a snackbar), an older version is migrated step by step (`data/Migrations`:
  `STEPS[n]` = n -> n+1) and the old file is kept as `data.json.v<n>`.
- **Any change to the stored format: raise `Migrations.CURRENT` (or `ThemeSettings.SCHEMA`), add the step, add a test
  in `PersistenceTest`.** Never drop or rename stored keys without a step.
- Backup (settings page): one versioned JSON (`BackupCodec` version 3: plan data incl. course settings and friends, raw
  .ics, theme, `language` (since 2: "system"/"de"/"en"), `updates` (since 3: autoCheck, skippedCode); `upgrade` steps
  the envelope, missing parts of older files keep the current values on restore; undo restores them too), export via
  CreateDocument, import via OpenDocument with a confirmation dialog and undo (`Snapshot`). `allowBackup` cloud backup
  stays excluded (data_extraction_rules).

## Product decisions (v1)
- Import: file picker + VIEW/SEND of `text/calendar` (octet-stream accepted if content is VCALENDAR).
  Re-import replaces the plan, keeps settings.
- .ics routing (`data/ImportRouting`, pure; `IcsImportDialog`): my plan is replaced only from the settings import (and
  the empty plan's button, `vm.importOwn`); there, a file with far fewer courses (`AddedCourses.looksPartial`: fewer than
  half, plan has >= 3) asks "Plan ersetzen" / "Kurse zu meinem Plan hinzufügen". Drawer "+" file and open with/share
  (`vm.import`) ask "Als Freund hinzufügen" (name, or update an existing .ics friend) / "Kurse zu meinem Plan
  hinzufügen" (+ "Als meinen Plan importieren" only while I have none). Shares (`SPMD`) always go to the friend import.
- Added LSF courses (`StoredData.addedEvents`, `:core` `schedule/AddedCourses`): keyed by course key (UID prefix), full
  LSF UIDs kept (together, own changes, rhythms work). `myLsfEvents` = plan + added ones the plan lacks; adding replaces
  the same course (re-import of one course updates it), skips courses the plan has; a full import drops duplicates
  (`dedupe`, snackbar notice). Detail sheet "Zu deinem Plan hinzugefügter Kurs" + remove (drops my changes/rhythms for
  it; undo). In my share like the rest of my LSF plan.
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
  Events are read-only. Top bar for a friend: name, favorite heart, edit icon -> details dialog (rename = local name,
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
- **Share codec** (`:core`): binary payload, raw deflate (level 9). QR text = `SPMD:` + Base45(payload)
  (alphanumeric mode, ECC L). File: extension `.stundenplan`, MIME `application/vnd.luwify.stundenplan`,
  content = magic `SPMD` + payload. Payload: format version, flags, 8-byte random person id, export day,
  planDate (my last .ics import = "Stand"), display name, base day, string table (titles, categories,
  lecturers, rooms as `room|alias|capacity` when the raw LOCATION rebuilds exactly, else `=raw`, notes),
  course ids sorted + delta, course settings (course ref, subject key, short name, color), then events column-wise:
  course, appointment id (delta in course), title, category, lecturers, location, note, day offset,
  start/length in quarter hours (escape for other minutes), slot count (0 = single), interval, bitmask of
  cancelled slots. Escapes for non-LSF UIDs and other RRULEs. Format 2 appends own changes (UID, date, series/until,
  s.t., cancelled, room, time; no notes); a plan without shareable changes is still written as 1, so older apps import
  it (they reject 2 as "newer version"). Dropped: off-rhythm EXDATEs, PRODID.
  QR profile: lecturers, notes only of single events, capped at 80 chars ("…"); series notes dropped; both are
  flagged (`Event.notesShortened`) and the detail sheet says so (`Event.noteHint`; `NoteHintTest` covers
  .ics/file/QR). SUMMARY = title (raw one not sent). File profile: all notes and the raw SUMMARY. A file holding the
  QR text (`SPMD:`) is accepted too (checked before the binary magic `SPMD`, which it also starts with).
  A real semester plan with name and styles: QR ~1,600 chars -> version 24-L (113 modules); file ~1.9 KB. Above
  version 30 the QR is refused ("share as a file"). Test: decode(encode(plan)) gives the identical Schedule.
  Decoder rejects newer versions and caps sizes (256 KB inflated, event and string limits).
- **Identity:** person id from SecureRandom, made at my first share; display name asked at the first share,
  editable in settings. My own id is rejected on import.
- **Import:** one content dispatcher for VIEW/SEND of the own MIME and `application/octet-stream` (VCALENDAR
  -> my plan import, `SPMD` -> friend import); no `*/*`, no `text/plain`. Confirmation dialog (add/replace,
  name, Stand) with undo. The same person id replaces that friend's plan and keeps my local settings for them.
- **QR:** scan with CameraX 1.6.2 (core, camera2, lifecycle, compose `CameraXViewfinder`) + ZXing core 3.5.4 (both
  Apache 2.0; no ML Kit, no Play Services), 1080p analysis frames, QR only. CAMERA permission requested only when
  scanning; `uses-feature camera required=false`. Show: ZXing writer, dark on white whatever the theme, quiet zone,
  max brightness, screen kept on. Code size = the smaller of available width and height (portrait keeps ~230 dp
  for the texts below), quiet zone inside, never larger than the screen; landscape: code left, name/Stand/hints
  right. If `ShareCodec.qrOmitsDetails` (shortened/dropped notes, or a SUMMARY that is more than "title (lecturers)"):
  info line "Der QR-Code enthält nicht alle Details (z. B. Notizen). Für alles: als Datei teilen." + file share
  button; neither when nothing is left out. Share file via FileProvider (`${applicationId}.files`, cache/share only).
- **Storage:** friends' plans as `Plan`s (`exportedOn`, `planDate`, `sharedSettings`), `Person.localName` (my
  rename), `friendSettings`, `share` (`MyShare`: id, name), `Event.notesShortened`. Friend's `Person.id` = their share
  id. Friends are in the backup via `StoredData`.
- **Privacy:** no server; INTERNET only for the updater (manifest + APKs of the GitHub releases, nothing else, no
  identifiers, User-Agent `StundenplanMD/<version>`; check the merged release manifest); camera frames only in
  memory; imported data is untrusted; cloud backup stays excluded.
- **Icons:** Material Symbols Outlined 24px (Apache 2.0) as single vector drawables, each listed in NOTICE:
  menu, group, add, share, qr_code_2, qr_code_scanner, file_open, edit, delete, close, warning, favorite (fill 0/1),
  chevron_left/right. ZXing and CameraX in NOTICE.

## Releases
- **Version:** `version.properties` (`version=major.minor.patch`) is the only source; `app/build.gradle.kts` derives
  versionName and versionCode = major*10000 + minor*100 + patch (minor, patch < 100). Raise it in a commit before
  releasing (with `changelog/<ver>.de.md` and `.en.md`, short, for users); the script doesn't bump.
  Changelog style (`unreleased.*.md` and release files): impersonal and user-facing, grouped under short headings
  (as in 1.0.1, 1.1.0); describe what the app does now ("Das Freunde-Symbol zeigt jetzt …"), no first person, "du" only
  where needed; no internal details (class names, profiles, timings, test numbers).
- **Signing:** release build type uses `signingConfigs.release` from the Gradle properties `LUWIFY_STORE_FILE`,
  `LUWIFY_STORE_PASSWORD`, `LUWIFY_KEY_ALIAS`, `LUWIFY_KEY_PASSWORD` (in the user's `~/.gradle/gradle.properties`).
  Missing -> `checkReleaseSigning` fails the release build (never debug-key fallback); debug builds don't need them.
  Pinned certificate SHA-256 in `release/signing-cert.sha256` (apksigner or keytool format, normalized by the script;
  it refuses a real release without a valid pin). Current certificate (apksigner): `d717d70f…941549af` (CN=Luwify).
- **GitHub releases** (repo `Luwify/ovgu-stundenplan`): every release has the APK `stundenplan-md-<ver>.apk`, the GPL
  source archive and `updates.json` with the full history. The app reads
  `https://github.com/Luwify/ovgu-stundenplan/releases/latest/download/updates.json`.
  `updates.json`: `:core` `update/UpdateManifest` (`schema` 1, `app`, `releases` newest first: versionName,
  versionCode, date, minSdk, changelog de/en, apkUrl `.../releases/download/v<ver>/stundenplan-md-<ver>.apk`, sha256,
  size, withdrawn), `Manifests.parse` (TooNew/Broken, validation, unknown keys ignored), `updateFor(installed, sdk)`.
  Written only via `./gradlew :core:manifest` (ManifestTool.kt in core/src/test: add/withdraw/check), so the writer
  is the app's parser.
- **tools/release.sh <ver> [--dry-run]** (settings `release/app.conf`, helpers `tools/release-common.sh`, gh CLI):
  preflight (gh login, remote = the GitHub repo, repo public, branch main, clean tree, version.properties == ver,
  changelogs, tag neither local nor on the remote, no such release, no newer release, tests + lint),
  assembleRelease with `-PupdateManifestUrl` set explicitly, apksigner cert == pin, aapt2 package/versionCode/
  versionName/not debuggable/minSdk, manifest URL in the APK, sha256 + size, `git archive` source tarball
  (`.gitattributes` export-ignore: .claude/, CLAUDE.md, notes/, prompt.md, tools/, release/), new updates.json from the
  newest release's one + diff; dry run stops there (output in `build/release/<ver>/`). Real run: tag + push, draft
  release with notes and all three files, check the asset sizes, publish (`--latest`), then an anonymous outsider
  check (https only, at most 3 redirects, final host = the ones the app allows). On failure it prints what was done
  and how to clean up (deletes nothing). **Only the user runs the real release.**
- `tools/release.sh --backfill <tag> [--dry-run]`: an existing tag rebuilt in a temporary worktree, release with
  `--latest=false`, the tag's date, an "Archiv, neu gebaut" note; only in version order and before any newer release.
- `tools/withdraw.sh <ver>` sets withdrawn=true in updates.json of the latest release (with confirmation).
- `tools/forgejo/`: bridge to the old self-hosted release repo, which installs up to 1.0.2 read (README there;
  settings in the git-ignored `release/forgejo.conf`).

## Language
- `AppLanguage` (copied from Mensa MD): System / Deutsch / English dropdown at the top of the settings. Android 13+:
  the system's per-app language (`LocaleManager`, in sync with the system setting; the system recreates the
  activity). Before 13: SharedPreferences `language` + `attachBaseContext` wrap + `recreate()`. State survives the
  recreate (saveable / ViewModel). LSF content stays German.

## In-app updater
- `:core` `update/UpdatePolicy.kt`: `UpdateSource` (manifest URL, APK prefix `.../releases/download/`, redirect
  rules, `override` for debug; `forManifestUrl` accepts only `https://github.com/<owner>/<repo>/releases/latest/
  download/<file>`), `UpdatePolicy` (auto check at most every 24 h, overview hides withdrawn except the installed
  one, `canInstall` = newer + not withdrawn + minSdk, `offer` minus the skipped version, snackbar once per version),
  `ApkCheck` (size, sha256, package, versionCode > installed and == manifest, signer set == installed). Manifest
  validation allows plain http only for 127.0.0.1/localhost (the release source still requires https).
- **Manifest URL per build:** Gradle property `updateManifestUrl` (default the GitHub URL above) ->
  `BuildConfig.UPDATE_MANIFEST_URL` -> `data/update/ReleaseSource.kt`; `ReleaseSourceTest` checks that it parses.
- **Redirects:** GitHub answers `latest/download/<f>` with 302 -> `github.com/<repo>/releases/download/<tag>/<f>`
  -> 302 -> `release-assets.githubusercontent.com/...?<signed query>`. `UpdateHttp` follows redirects by hand, each
  hop through `UpdateSource.redirect`: https only, no user info, default port, no fragment; either below the repo's
  `releases/download/` on github.com (no query, no dot segments, no `%`) or any path on
  `release-assets.githubusercontent.com` (`GITHUB_ASSET_HOSTS`); at most `MAX_REDIRECTS` = 3. Nothing else
  (not `objects.githubusercontent.com`, GitHub's former asset host: if GitHub moves again, the updater fails safe
  and a new version must add the host). The initial APK URL must be below the APK prefix (`allowsApk`).
- `:app` `data/update/`: `Updates` (process-wide in `AppContainer`: state, check at app start in
  `MainActivity.onCreate`, download to `cacheDir/updates` with progress/cancel, verify, PackageInstaller session,
  "install unknown apps" explanation once then `ACTION_MANAGE_UNKNOWN_APP_SOURCES`, continues in `onResume`; APKs
  deleted after the session write and at every start), `UpdateHttp` (HttpURLConnection, checked redirects),
  `ApkInspector`/`ApkInstaller`/`InstallResultReceiver`, `UpdateSettings` (own DataStore `updates`, `schema_version`
  1: auto_check, last_check, manifest (last good one, shown offline), announced_code, skipped_code,
  unknown_sources_explained; auto_check and skipped_code are in the backup).
- UI `UpdateScreens.kt`: settings section "Version" (App-Version, Versionsübersicht, "Update auf x", Nach Updates
  suchen + status, Automatisch suchen), overview (radio = installed), detail (changelog de/en, Installieren only if
  installable, App teilen), `UpdateDialog` (snackbar "Anzeigen"; "nicht erneut anzeigen" = skip), download dialog
  (Abbrechen/Minimieren; while installing "Schließen", because Android may never report back, e.g. Play Protect),
  failure dialog. Badge on the settings gear. App teilen: QR with the direct APK link of the newest version
  (`UpdateSource.apkUrl`) + installed APK via share sheet (`cache/share/stundenplan-md-<ver>.apk`).
- **Debug-only override** (src/debug; src/release has none): `files/debug_update_url` in the .preview app, e.g.
  `http://127.0.0.1:8765/updates.json` served on the dev machine with `adb reverse tcp:8765 tcp:8765`; cleartext only
  to 127.0.0.1/localhost (debug network_security_config); no redirects. Test APKs: debug builds with a raised
  version.properties (never commit that), manifest via `:core:manifest add` with app id
  `de.luwify.stundenplanmd.preview`.

## Rules
- **Secrets:** never read, print, cat, grep or copy `~/.gradle/gradle.properties`, the keystore or any token; never
  put secrets into the repo, logs or commit messages. Scripts read them at runtime and never echo them (no `set -x`).
  Builds that use the signing config and `tools/release.sh --dry-run` are allowed; the real release only the user
  runs.
- **Never touch the data of the real app `de.luwify.stundenplanmd` on ANY connected device:** no `pm clear`, no
  uninstall, no `run-as` on it, no importing fixtures into it. It holds the user's real plan and subject settings.
  It updates itself from the releases; install over it only with `installRelease` when the user asks. Debug builds
  have the applicationIdSuffix `.preview` (`de.luwify.stundenplanmd.preview`, launcher label "Plan Preview"): all
  fake-data tests go there; only that copy may be cleared or uninstalled.
- Never run sudo or install system/SDK packages: tell the user the command.
- Stay inside this directory (reading the Mensa MD repo is allowed, never modify it).
- `private/` holds real exports: never commit them, never copy them into tests, never quote them into public files
  (this file, code, commit messages). Test fixtures are derived and scrubbed: fake lecturers, mail addresses, rooms
  and course titles, UIDs remapped consistently; keep structure and every quirk listed below.
- Small commits. After each: `./gradlew testDebugUnitTest assembleDebug lint`.
- Write own lenient iCal parser for the LSF subset; no iCal library.

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
