# Web app (web/)

Stundenplan MD as a static PWA for friends without Android (target: iPad, iPadOS Safari; phones work too). Phase 1:
import my .ics, week/day view with subject colors and detail, friends by QR (single + animated) or .stundenplan file,
"Zusammen mit" markers, share my plan (QR / file via Web Share), backup in the Android format, update banner.
Not yet: editing subjects/colors/short names, hiding, own changes, manual courses, adding courses from friends, .ics
friends, rhythms by hand, notifications, the Mensa MD banner, themes other than Soft, the in-app updater (the service
worker is the updater).

## Architecture
- All format logic is the Kotlin/JS build of `:core` (`WebCore` in `core/src/jsMain/.../web/WebCore.kt`, JSON strings
  in and out), synced by `./gradlew :core:webCore` into `src/generated/core` (git-ignored). `src/core.ts` types it.
- Preact 10 + TypeScript + Vite 6. `src/App.tsx` (layout, navigation, flows), `components/` (WeekGrid, DayList,
  Strip, Detail, People, Settings, QrShow, Scanner, ui), `model.ts` (state, undo snapshots, snackbar), `store.ts`
  (IndexedDB key-value: StoredData JSON as in data.json, raw .ics, backup extras, lastBackup, language, qrMode;
  `navigator.storage.persist()` once there is data), `i18n.ts`, `colors.ts` (SubjectColors.block port, Oklab lerp like
  Compose), `qr.ts` (QR codes, see below).
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
- Strings: `scripts/strings.mjs` copies the listed keys from the app's `strings.xml` (de + en) into
  `src/generated/strings.ts` on every build; web-only strings are in `i18n.ts`. Language: German if the browser prefers
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
- Week grid always fits the day range into the height (the app's landscape fit / squash), min 18 px per hour, then it
  scrolls; blocks drop lines as they get lower. Hover styles only under `(hover: hover) and (pointer: fine)`.
- Arrow keys: previous/next week (day in the day view). Touch swipe left/right on the content does the same.
- iOS install hint only where `navigator.standalone === false` (feature detection; Safari's storage is evicted after 7
  days without use unless the app is on the home screen).

## Build and tests
- Node: the one Kotlin/JS downloads (`export PATH=$(ls -d ~/.gradle/nodejs/node-*/ | head -1)bin:$PATH`); no system
  install. `npm ci`, `npm run build` (needs `:core:webCore` first), `npm run dev`.
- `web/scripts/e2e.sh [playwright args]`: core JS build + fixtures from the JVM build (`:core:qrTool write`: a friend's
  share as file, ZXing QR image and animated frames, an Android backup), production build, Playwright in the official
  container (`mcr.microsoft.com/playwright:v<version>-noble`, podman), then the Android decoder (`:core:qrTool read`,
  ZXing as in the app) reads the web app's codes (pixel-exact and screenshot, single and animated) and share files.
- Playwright projects: WebKit iPad (gen 7) and iPad Pro 11 in landscape and portrait, Split View third (375) and half
  (597), iPhone 13; Chromium Pixel 7. The service worker test (offline, update banner) runs in Chromium only.
- Fake camera: getUserMedia returns a canvas stream cycling through ZXing images. Playwright's Linux WebKit doesn't
  play canvas streams in a <video>, so there the test shims the video element (size, readyState, drawImage of the
  canvas); the app's scan pipeline is unchanged.

## Deploy
- `.github/workflows/web.yml`: `:core:test` (JVM + JS compat) + build, then GitHub Pages. Triggers: tags `v*`, pushes to
  `web-pwa` (preview while unmerged; remove that trigger when merging), manual. Pages source = GitHub Actions;
  environment `github-pages` allows `web-pwa` and `v*` tags.
