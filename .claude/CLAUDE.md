# Stundenplan MD

Android timetable for an OvGU semester plan, imported from the LSF iCalendar export. GPL-3.0-or-later
(LICENSE, README, NOTICE like Mensa MD, the author's other app).

This file is public (GitHub). Personal details (devices, paths, release infrastructure, test notes) live in the
git-ignored `CLAUDE.local.md` at the repo root, which Claude Code loads after this file.

## Stack
- Kotlin, Jetpack Compose, Material 3. Single Activity.
- Package `de.luwify.stundenplanmd`, minSdk 26 (java.time native), target/compile 37.
- Build setup mirrors Mensa MD (AGP 9 built-in Kotlin, version catalog, Gradle 9.8.0, kotlinx.serialization).
- Modules: `:core` (Kotlin Multiplatform, JVM + JS: parser, model, expansion, layout math, share codec, stored data
  format + backup (`store/`), update policy (JVM only); no Android imports), `:app` (Android, UI, storage I/O, import
  intents), `web/` (PWA for iPad/iPhone friends: Preact + TypeScript + Vite on the JS build of `:core`, GitHub Pages;
  see `web/CLAUDE.md`).
- JDK 21 (Android Studio's JBR); a newer system JDK doesn't work with this Gradle/AGP.
- `./gradlew testDebugUnitTest` also runs `:core:test` (= `jvmTest` + `jsNodeTest`, the JS tests check that the JS build
  gives the JVM build's bytes); `lint` also lints `:core`. Kotlin/JS downloads its own Node.js into `~/.gradle/nodejs`.
- Device tests only on the debug build (package `de.luwify.stundenplanmd.preview`, launcher label "Stundenplan Preview"),
  never on the real app `de.luwify.stundenplanmd` (see Rules).

## Where the details live (loaded on demand)
- `.claude/rules/features.md` (loads with any file under `core/src` or `app/src`): own changes, hiding, short
  names, manual courses, product decisions, sharing and friends (UI, matching, colors, identity, import).
- `core/CLAUDE.md`: `:core` packages, LSF export findings (parser quirks, rhythms, UID hypothesis).
- `core/src/main/kotlin/de/luwify/stundenplanmd/core/share/CLAUDE.md`: share codec formats 1–4, animated QR.
- `app/src/main/java/de/luwify/stundenplanmd/ui/CLAUDE.md`: UI components (pagers, top bar, snackbars, dropdowns, landscape, performance,
  language, QR screens).
- `app/src/main/java/de/luwify/stundenplanmd/data/CLAUDE.md`: storage, migrations, backup.
- `web/CLAUDE.md`: the web app (architecture, iPad layout, tests, deploy).
- `app/src/main/java/de/luwify/stundenplanmd/data/update/CLAUDE.md`: in-app updater, Mensa MD banner.
- Skills: `release` (versioning, changelogs, signing, release scripts), `device-testing` (dev tools, fake-data
  device tests, perf build, baseline profile).

## Persistence (user data must survive every update)
- **Any change to the stored format: raise `Migrations.CURRENT` (or `ThemeSettings.SCHEMA`), add the step, add a test
  in `PersistenceTest`.** Never drop or rename stored keys without a step. `StoredData`, `Migrations` and the backup
  envelope (`BackupFormat`) live in `:core` `store/`: the web app stores and backs up the same format.
- Details (files, migration history, backup format): `app/src/main/java/de/luwify/stundenplanmd/data/CLAUDE.md`.

## Privacy
- No server; INTERNET only for the updater (manifest + APKs of the GitHub releases, and the same for the Mensa MD banner, nothing else, no
  identifiers, User-Agent `StundenplanMD/<version>`; check the merged release manifest); camera frames only in
  memory; imported data is untrusted; cloud backup stays excluded.
- Icons: Material Symbols Outlined 24px (Apache 2.0) as single vector drawables, each listed in NOTICE (add new
  ones there); ZXing and CameraX in NOTICE.

## Rules
- **Secrets:** never read, print, cat, grep or copy `~/.gradle/gradle.properties`, the keystore or any token; never
  put secrets into the repo, logs or commit messages. Scripts read them at runtime and never echo them (no `set -x`).
  Builds that use the signing config and `tools/release.sh --dry-run` are allowed; the real release only the user
  runs.
- **Devices:** the device list and all device / adb input safety rules are in the maintainer's global
  `~/.claude/CLAUDE.md` (real app vs. test copy, guards, DND, rotation, keyboard, privacy). Here: the real app is
  `de.luwify.stundenplanmd` (it holds the user's real plan and subject settings: never import fixtures into it; it
  updates itself from the releases); the test copy is `de.luwify.stundenplanmd.preview` ("Stundenplan Preview"),
  where all fake-data tests go.
- Never run sudo or install system/SDK packages: tell the user the command.
- Stay inside this directory (reading the Mensa MD repo is allowed, never modify it).
- `private/` holds real exports: never commit them, never copy them into tests, never quote them into public files
  (this file, code, commit messages). Test fixtures are derived and scrubbed: fake lecturers, mail addresses, rooms
  and course titles, UIDs remapped consistently; keep structure and every quirk listed in `core/CLAUDE.md`.
- Small commits. After each: `./gradlew testDebugUnitTest assembleDebug lint`.
- Write own lenient iCal parser for the LSF subset; no iCal library.
