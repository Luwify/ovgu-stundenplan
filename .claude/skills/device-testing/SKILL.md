---
name: device-testing
description: Stundenplan MD dev tools and device tests - planReport/planCompare/shareRead/shareFile Gradle
  tools, fake-data tests on the .preview debug build, perf build and frame times, baseline profile
  regeneration. Use before running anything on a phone or tablet or comparing exports.
---

Follow the Rules in `.claude/CLAUDE.md` (never touch the real app, adb input safety) for everything here.

## Perf build
- Build type `perf` (`assemblePerf`: R8 like release, not debuggable, debug-signed, `.preview` id)
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
  - `./gradlew :core:shareRead --args="<texts.txt>"`: QR texts (one per line, e.g. read by ZXing from device
    screenshots) through the app's decoding (single code or frames); prints counts and sizes only.
  - `./gradlew :core:shareFile --args="<ics> <out> <name> <16 hex id>"` writes a friend share (`<out>` binary file,
    `<out>.txt` QR text) for preview-app tests; open it with
    `am start -a VIEW -t application/octet-stream -d file:///data/user/0/<pkg>/cache/...`.
  - `./gradlew :core:deviceSeed --args="<dir>"`: fake data for update checks of the `.preview` copy: `data.json` + `me.ics`
    (my share identity, styles, short names with a type part, own changes with notes, a manual course, hidden items,
    three friends with favorite/rename/local settings), `data-v7.json` (before step 7 -> 8, for a migration check) and
    a friend's share file. Copy with `run-as <pkg> cp` into `files/plans/` before the first start (app stopped).
  - Screenshots of the animated code don't read pixel-exact (1 px seams, see the QR notes); downscale them like a
    camera (60 %, Lanczos) before `:core:qrTool read`/`singles`.
- Device testing with fake data: debug build (`<pkg>` = `de.luwify.stundenplanmd.preview`), `adb push` the fixture
  to /data/local/tmp, `run-as` copy into `cache/`,
  `am start -a VIEW -t text/calendar -d file:///data/user/0/<pkg>/cache/...`. Uninstall that copy afterwards (never
  the real app). Several devices may be connected: always `adb -s <serial>`, for Gradle installs `ANDROID_SERIAL`.
  Check `isKeyguardShowing` before screenshots. Rotation for layout checks: `settings put system user_rotation 0|1`
  with `accelerometer_rotation 0`; read the values first and restore them.
