# UI (:app)

- `MainScreen` (Week/Day tabs, `VerticalPager` over
  `vm.pages` = me + friends in drawer order, synced both ways with `vm.selected`; shared `week`/`day` state so switching
  people keeps the week/day; detail sheet, subject editor, settings overlay; top bar title and actions follow the
  pager's live position, not the settled page (`PagerLayers`: the two pages around it, each laid out with its own
  content and centered on its own in a block of fixed height `TopBarBlock`, so an .ics friend's subtitle never moves the
  bar or a name; slide/fade by the offset in graphicsLayer, no recomposition per frame; only the nearer one takes taps and
  semantics; without animations the nearer one at once); detail sheet keyed by `SheetKey` (person, `ref`, UID) and looked
  up in the shown plan on every change: editors/dialogs opened from it hide it while open and bring it back on any close,
  with the new state; hiding/removing close it (gone from the plan); the snackbar host sits above the overlays), `PeoplePager` (`edgeTouches` on the pager itself: gesture start/end and the finger's velocity measured in the
  pager's coordinates (content measures on its page, which moves with the finger: a grid that fits the screen reported 0,
  so flicks only switched past halfway); the settle uses that velocity; `EdgeOnlyConnection`:
  content scrolls first, the pager moves only for drags that start at the content's edge, the connection moves and
  settles the pager itself and is its only touch input: the pager has `userScrollEnabled = false` (its own drag grabbed
  touches while it settled and swallowed swipes); one connection for all pages (a fast second swipe lands on the next
  person's page and must cancel the first one's settle); every gesture end settles the pager, and `keepSettled`
  (LaunchedEffect in MainScreen) snaps it to the nearer person when it sits between two with no finger down and nothing
  moving it for 300 ms; `Modifier.edgeSwitching` = touch-down/up tracking + nestedScroll + a `draggable` for areas
  without scrolling content; `FollowSharedPage`; `PersonIndicator` name pill while switching), `WeekView` (hour column
  background fills in primaryContainer from the top of the day range down to now (hour labels on it in
  onPrimaryContainer, 2dp primary edge at now), `DayProgress` in `:core`: this week only, 0 before / 1 after the day
  range and on the weekend; no red now line), `DayScreen` (day pager, Mon–Fri, cancellations only near today),
  `PlanStrip` (`ChipStrip`: scrolling week/day chips; selected = primary fill, current = primary outline, muted = no
  events; while the current chip is less than half visible a round tonal chevron button at that edge jumps back
  (selects it and scrolls the strip); no "Heute" button any more; animations off when the system's animator scale is
  0), `DetailSheet` (note hint from `Event.noteHint`; the "Ausblenden" menu button has a trailing `MenuArrow`; "Zusammen mit" lists favorites first with a heart),
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
  `EventBlock` badges (manual, together, own change, note) stack at the top right; `BlockLines` narrows only the lines
  that start beside them (title, and more while the column is taller; 3 dp of overlap allowed, the badges are round),
  the lines below get the full width (title width as before: 17 dp end). Day cards keep badges in the title row.
- Snackbars: one implementation for the whole app, `ui/Snackbars.kt`: `AppSnackbarHost` (M3 `Snackbar`: inverseSurface /
  inverseOnSurface / inversePrimary action, theme shape, 12 dp margin, max 600 dp centered; own row layout, action
  and close X centered vertically beside the text) and `SnackbarHostState.notify(message,
  actionLabel, sticky, onAction)` (`snackbarDuration`: action = Long 10 s, plain = Short, sticky = Indefinite with the
  close X; `onAction` only on a tap of the action). The host always gets Indefinite (`AppSnackbarVisuals.timeout` holds
  the real one): `SnackbarTimer` counts itself, paused while touched or expanded (expanded: until 5 s without touch),
  at least 4 s left after a pause, accessibility-recommended times. `SnackbarFold`: 2 lines + "…" + chevron when cut
  (`hasVisualOverflow`), a tap expands/collapses (animateContentSize, snap without animations); sticky ones in full.
  Texts checked against 2 lines on the phone at 1.0×/1.3× (Roboto, 328 dp, less with "Rückgängig"); those with course
  titles or names rely on expanding. Swipe left/right
  dismisses like a time-out (`SnackbarSwipe`: 40 % of the width or a 1000 dp/s fling in the drag's direction; fades out;
  snap back otherwise; no animation with "remove animations"); touches beside the snackbar reach the pagers.
  `withReadableSnackbar` (Theme.kt) nudges inversePrimary to 4.5:1 on inverseSurface in every theme. Host in
  `MainScreen` above the overlays: safeDrawing insets, + 80 dp over the bottom bar (not with the rail). While the detail sheet
  is open (its own window), `DetailSheet` hosts the same `SnackbarHostState` at its bottom and MainScreen's host is left out
  (`sheetOpen`), so a snackbar is never under the sheet.
  **Mensa MD should get the same snackbar later** (it still uses the plain `SnackbarHost`), to keep the two apps in sync.
- `SettingsDropdown` (language, theme, manual course subject): spinner-style menu. Popup over the whole window
  (`WholeWindow`, screen coordinates: a popup's window may start beside a cutout), current value's row centered on the
  field, others above/below, clamped to the window's safe area (`menuPlacement`, `MenuPlacementTest`; a list taller than
  the space fills it and scrolls to the current row). Opens like `GrowingMenu`: the field's rect grows into the panel
  on the bouncy spring, other rows fade in, the current one stays; closes at once; instant with animations off.
- `TimeField` (`TimeField.kt`): the only time input (own changes, manual course). State = `TimeDigits` (up to 4 digits, no
  colon; "915" = 09:15, fewer than 3 digits are no time, nothing is corrected), colon drawn by a `VisualTransformation`,
  clock button -> M3 `TimePickerDialog` (24 h; keyboard input only when the window is too low for the dial).
- Day view free blocks: `DayScreen` merges `DayPlan.gaps` into the list as dashed, non-clickable `FreeRow`s ("Frei · 13–15  2 h").
- Landscape fit (setting "Querformat an Bildschirm anpassen", `ThemeSettings.fitLandscape`, default on, in the backup as
  `display` since version 4): `WeekPage` scales the hour height over the whole plan's `Schedule.hours` (`HourScale`, min
  16 dp, 4 dp margin; scrolls below that); `EventBlock(squashed)` drops the type line, then the room, then title lines, and
  shows only the badges that fit; hour labels thin out when lines are closer than a label. On a low screen (phone
  landscape: `compactHeight`, `LocalCompactHeight`: height < 480 dp) the chrome is slimmed: 48 dp top bar, tabs in a
  `NavigationRail` on the left, one-line chips and day headers, so 12 h fit without scrolling.
- Performance: `MainScreen` keeps every person composed (`composedAround`, one more per idle moment after the start,
  max 8): otherwise the pager's prefetch composes the page beyond the next one at the start of a drag (a whole week
  grid, 80–150 ms). Build type `perf` (`assemblePerf`: R8 like release, not debuggable, debug-signed, `.preview` id)
  for frame times: `dumpsys gfxinfo <pkg>` / `atrace -a <pkg> view gfx`; it installs over the debug copy and keeps
  its test data. A fresh `adb install` is only verified (no AOT); compare runs with the same compile state.

## Language
- `AppLanguage` (copied from Mensa MD): System / Deutsch / English dropdown at the top of the settings. Android 13+:
  the system's per-app language (`LocaleManager`, in sync with the system setting; the system recreates the
  activity). Before 13: SharedPreferences `language` + `attachBaseContext` wrap + `recreate()`. State survives the
  recreate (saveable / ViewModel). LSF content stays German.

## QR screens
- **QR:** scan with CameraX 1.6.2 (core, camera2, lifecycle, compose `CameraXViewfinder`) + ZXing core 3.5.4 (both
  Apache 2.0; no ML Kit, no Play Services), 1080p analysis frames, QR only. CAMERA permission requested only when
  scanning; `uses-feature camera required=false`. Show: ZXing writer, dark on white whatever the theme, quiet zone,
  max brightness, screen kept on. Code size = the smaller of available width and height (portrait keeps ~260 dp
  for the texts below), quiet zone inside, never larger than the screen; landscape: code left, name/Stand/hints
  right. `ui/ShareQr`: one code up to version 20 (`MAX_SINGLE_VERSION`), else the animation (version 12 at 6 fps);
  toggle "Als ein Code" / "Animiert" below, remembered in `ThemeSettings.qrMode` (key `qr_mode`, null = by size; not
  in the backup); one code only when it fits at all (v40). Before showing one code `ShareQr.readable` renders it and
  reads it back with ZXing's camera detector, switching the mask if needed (some masks fail even on perfect
  images). `QrCode` draws on whole pixels, module size and origin (`ShareQr.layout`; a half-pixel origin left 1 px seams
  that broke ZXing's hybrid binarizer and jsQR, seen on version-12 frames until 1.6.0; `ShareQrTest` reads frames
  rasterized at 100 % for many sizes).
  Scanner: frames go to a `ShareFrames.Decoder` (progress "Animierter Code: 62 %"), the finished payload into the
  normal import as `SPMD:` + Base45; the analyzer reports each new text once and keeps going for frames.
  Share file via FileProvider (`${applicationId}.files`, cache/share only).
