# Web app (web/)

Stundenplan MD as a static PWA for friends without Android (target: iPad, iPadOS Safari; phones work too), at parity
with the app as far as a browser allows (`web/PARITY.md`: the feature table, what is left and why; read it before
working here). Not here: the APK updater (the service worker is the updater), the Mensa MD banner, NFC, notifications,
"App teilen" as APK ("Web-App teilen" = QR of the URL instead).

## Architecture
- All format logic is the Kotlin/JS build of `:core` (`WebCore` in `core/src/jsMain/.../web/WebCore.kt`, JSON strings
  in and out), synced by `./gradlew :core:webCore` into `src/generated/core` (git-ignored). `src/core.ts` types it.
- Preact 10 + TypeScript + Vite 6. `src/App.tsx` (layout, navigation, flows: detail actions, editors, .ics routing,
  forwarding, shortcuts), `components/` (WeekGrid + `Badges`, DayList + free rows, Strip, Detail (the app's action row:
  3 primaries + "Mehr", `Menu.tsx`), Editors (SubjectEditor, PersonalEditor, PickDates, ManualEditor, ColorChoice),
  Hidden, IcsDialog, FriendDialogs, People (drag handle reorder), Settings, ThemeEditor (custom theme + colour editor),
  Dropdown (the app's SettingsDropdown: current row centred on the field), About (versions, licenses, Web-App teilen),
  Snackbar (the app's rules), QrShow, Scanner, ui (Dialog with a layer stack: Escape closes only the topmost dialog,
  menu or dropdown)), `model.ts` (state, undo snapshots `changed(before, message)`, snackbar, theme and display in the
  backup extras), `store.ts` (IndexedDB key-value: StoredData JSON as in data.json, raw .ics, backup extras, lastBackup,
  language, qrMode; `navigator.storage.persist()` once there is data), `i18n.ts`, `texts.ts` (texts built like the
  app's: hidden names, rhythm, room, duplicates), `colors.ts` (SubjectColors.block port, Oklab lerp like Compose),
  `theme.ts` (port of ui/theme: System = M3 baseline + softened, Soft, AMOLED, Custom with the contrast nudges, plan
  colours -> CSS variables), `lineIcons.ts` (the app's own line icons), `qr.ts` (QR codes, see below).
- Times: `TimeField` in `Editors.tsx` = native `<input type="time">` (value "" or "HH:mm", no parsing; 12/24 h follow the
  device's region). Android has its own `TimeField` (digits, fixed colon, picker).
- Editing goes through `core.edit` (= WebCore -> `:core` `StoreEdits`), never TypeScript logic; dates are addressed by
  key `uid|start|o|c`; the detail sheet follows its date by `ref` (uid + original date) through every change.
- Theme and "Querformat an Bildschirm anpassen" live in the backup extras (`theme` = ThemeBackup, `display` =
  DisplayBackup), so they travel with backups both ways; without a choice the web shows Soft and a backup carries Soft.
- Changelogs and NOTICE are bundled at build time (`scripts/changelog.mjs` -> `src/generated/changelog.ts`).
- QR codes (`qr.ts`): ZXing's JS port (@zxing/library 0.21.3, deep ESM imports), patched where it differs from the
  app's ZXing-Java 3.5 (`willFit` integer bytes, mask penalty rule 3 bounds, finder patterns: diagonal check on every
  candidate and the best-triangle selection), so the web's codes equal the app's module by module. One code goes
  through `readableQr` = the app's `ShareQr.readable`: rendered at 3 px/module, read back by ZXing (hybrid, then global
  histogram, TRY_HARDER) and jsQR (the web's scanner); default mask, else masks 0..7; frames use the default mask, as
  in the app. `scripts/qrcheck.sh` (run by e2e.sh): `:core:qrTool texts` makes 34 plans (version 6–40, notes,
  umlauts, my share with changes and hidden dates, a forwarded friend, 5 whose default mask ZXing-Java can't read
  back) with Java's matrix and decision; the web must encode the same matrix, decide the same mask, and ZXing-Java
  (`qrTool singles`) must read every chosen code. The Playwright test "one code whose default mask..." does the same
  in the browser for my plan (`meta.maskName`, share id fixed by stubbing getRandomValues).
- Strings: `scripts/strings.mjs` copies every key of the app's `strings.xml` (de + en; minus updater, Mensa and APK
  sharing keys) into `src/generated/strings.ts` on every build; web-only strings are in `i18n.ts`. Language: German if the browser prefers
  it (or chosen in settings), else English.
- Backup: same file as the app (`BackupFormat`). Theme, language, updates and display of a restored Android backup are
  kept in `extras` and written back unchanged, so Android -> web -> Android keeps them. Reminder banner: no backup 7 days
  after the first data, or the last one older than 30 days.
- Offline: `src/sw.template.js` -> `dist/sw.js` (vite.config.ts fills in the file list and a content hash). Cache first,
  same origin only, `ignoreVary`. A new version waits; the banner's "Neu laden" sends skipWaiting and reloads (only
  then: the first install's clients.claim must not reload the page).
- No external requests at runtime (CSP `default-src 'self'`), no tracking.

## Layout (never detect the device by user agent: iPadOS Safari reports a Mac)
- `min-width: 1000px` -> people list as a sidebar (iPad landscape); narrower -> drawer (menu button, Escape closes).
- `max-width: 599px` -> phone layout (Split View third/half, Slide Over, phones): Week/Day tabs in a bottom bar.
- Week grid fills the height with the day range: in landscape with "Querformat an Bildschirm anpassen" (default on) down
  to 18 px per hour (the app's squash), else down to 40 px, then it scrolls; blocks drop lines as they get lower. Hover styles only under `(hover: hover) and (pointer: fine)`.
- Keys: arrows previous/next week (day in the day view), PageUp/PageDown people, W/D week/day, H this week, N new
  course, "," settings, ? the list. Touch swipe left/right on the content does the same as the arrows.
- iOS install hint only where `navigator.standalone === false` (feature detection; Safari's storage is evicted after 7
  days without use unless the app is on the home screen).

## Build and tests
- Node: the one Kotlin/JS downloads (`export PATH=$(ls -d ~/.gradle/nodejs/node-*/ | head -1)bin:$PATH`); no system
  install. `npm ci`, `npm run build` (needs `:core:webCore` first), `npm run dev`.
- `web/scripts/e2e.sh [playwright args]`: core JS build + fixtures from the JVM build (`:core:qrTool write`: a friend's
  share as file, ZXing QR image and animated frames, an Android backup), production build, Playwright in the official
  container (`mcr.microsoft.com/playwright:v<version>-noble`, podman), then the Android decoder (`:core:qrTool read`,
  ZXing as in the app) reads the web app's codes (pixel-exact and screenshot, single and animated) and share files.
- Specs: `app.spec.ts` (import, layout, friends by QR/file, sharing, backup, service worker), `edit.spec.ts` (subject
  editor, own changes, Termine auswählen, hiding, manual course, .ics friend, backup round trips), `more.spec.ts` (friend
  rename/share name/favourite/reorder, Zusammen frei, themes with a contrast check per theme in light and dark,
  dropdown, landscape fit, about screens, snackbar swipe, shortcuts). After Playwright, `e2e.sh` reads the web's backups
  with the JVM (`qrTool backup`): `full-roundtrip-*.json` must give the summary of `android-backup-full.json`
  (every kind of edit, from `CompatEdits`), `web-edits-*.json` (made through the UI) must hold every new data type.
- Playwright projects: WebKit iPad (gen 7) and iPad Pro 11 in landscape and portrait, Split View third (375) and half
  (597), iPhone 13; Chromium Pixel 7. The service worker test (offline, update banner) runs in Chromium only.
- Fake camera: getUserMedia returns a canvas stream cycling through ZXing images. Playwright's Linux WebKit doesn't
  play canvas streams in a <video>, so there the test shims the video element (size, readyState, drawImage of the
  canvas); the app's scan pipeline is unchanged.

## Deploy
- `.github/workflows/web.yml`: `:core:test` (JVM + JS compat) + build, then GitHub Pages
  (https://luwify.github.io/ovgu-stundenplan/). Triggers: tags `v*` (pushed by `tools/release.sh`; the build takes the
  version from `version.properties` and fails if the tag differs, so the web app shows the Android release's version
  in its settings and in `sw.js`) and manual runs (`<version>-<sha7>`). Pages source = GitHub Actions; environment
  `github-pages` allows `main` (manual runs) and `v*` tags. A tag starts the workflow only while `web.yml` is on the
  default branch.
- `tools/release.sh` (settings `WEB_WORKFLOW`, `WEB_URL` in `release/app.conf`): preflight checks the workflow
  (in the release commit, tag trigger, on GitHub's default branch, active), Pages (source Actions) and the
  environment's tag rule; step 6b builds the web app locally with the release version as a probe (also in
  `--dry-run`, output in `build/release/<ver>/web`); after publishing it waits for the Pages run and checks that the
  live `sw.js` has the version. A failed web deploy doesn't undo the Android release (exit 2, like the outsider check).
