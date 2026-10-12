# Stundenplan MD

Android timetable app for students at the OvGU Magdeburg, imported from the LSF iCalendar export ("iCal-Export" of
the Stundenplan). Offline only: the plan is stored on the device and appears instantly.

- Import via file picker or "Open with"/share of an `.ics` file; re-importing keeps your course settings.
- Week view (Mon–Fri, swipe between weeks, overlapping events side by side) and a Today list.
- Room/time changes and extra appointments are detected and highlighted.
- Subjects with editable short name and color; themes System, Soft, AMOLED and a custom theme.
- Not affiliated with or endorsed by the Otto-von-Guericke-Universität Magdeburg or HIS eG.

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
./gradlew installDebug                           # debug build "Plan Preview" (debug key, own package)
```

Min SDK 26, target SDK 37. Modules: `:core` (pure Kotlin/JVM: iCal parser, model, occurrence expansion, change
detection) and `:app` (Android UI); see `CLAUDE.md` for the architecture and the data-source notes.

## License

Stundenplan MD is licensed under the **GNU General Public License, version 3 or (at your option) any later version**
(GPL-3.0-or-later); see [LICENSE](LICENSE). Third-party material, if any: [NOTICE](NOTICE).
