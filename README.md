# Stundenplan MD

> [!WARNING]
> **This project is completely vibecoded.** The code was written by an AI (Claude Code) from prompts, not by hand.
> Expect bugs, use it at your own risk, and don't treat the code as a reference for how things should be done.

Android timetable app for students at the OvGU Magdeburg, imported from the LSF iCalendar export ("iCal-Export" of
the Stundenplan). Offline only: the plan is stored on the device and appears instantly.

- Import via file picker or "Open with"/share of an `.ics` file; re-importing keeps your course settings.
- Week view (Mon–Fri, swipe between weeks, overlapping events side by side) and a Today list.
- Room/time changes and extra appointments are detected and highlighted.
- Subjects with editable short name and color; themes System, Soft, AMOLED and a custom theme.
- Not affiliated with or endorsed by the Otto-von-Guericke-Universität Magdeburg or HIS eG.

## Web version

For friends with an iPad or iPhone: **https://luwify.github.io/ovgu-stundenplan/**. Same timetable from the LSF
`.ics`, friends by QR code or file, sharing and backups in the Android app's formats (it runs the same Kotlin code,
compiled to JavaScript). Everything stays in the browser, no account, no server.

In Safari, add it to the home screen (Share → "Add to Home Screen"): it then opens like an app, works offline, and
Safari keeps its data (pages that are not on the home screen lose their data after 7 days without a visit). Updated with
every Android release; the code is in `web/` (see `web/CLAUDE.md`).

## Build

Requirements: JDK 21 and the Android SDK (compileSdk 37). Put `sdk.dir=/path/to/Sdk` in `local.properties` or set
`ANDROID_HOME`.

```bash
./gradlew testDebugUnitTest assembleDebug lint   # unit tests (incl. :core), debug APK, lint
./gradlew assembleRelease                        # R8-minified APK, needs the release signing properties
```

Release builds are signed with the maintainer's key; they need the Gradle properties `LUWIFY_STORE_FILE`,
`LUWIFY_STORE_PASSWORD`, `LUWIFY_KEY_ALIAS` and `LUWIFY_KEY_PASSWORD` and fail without them. To build your own
release, point them at your own keystore. The version lives in `version.properties`.

```bash
./gradlew installDebug                           # debug build "Stundenplan Preview" (debug key, own package)
```

Min SDK 26, target SDK 37. Modules: `:core` (Kotlin, JVM and JS: iCal parser, model, occurrence expansion, change
detection, share and backup formats), `:app` (Android UI) and `web/` (the web version); see `CLAUDE.md` for the architecture and the data-source notes.

## License

Stundenplan MD is licensed under the **GNU General Public License, version 3 or any later version**
(GPL-3.0-or-later); see [LICENSE](LICENSE). Third-party material, if any: [NOTICE](NOTICE).
