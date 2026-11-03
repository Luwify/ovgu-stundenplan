# Web parity with the Android app

Resume point for the `web-parity` branch: a new session reads `web/CLAUDE.md`, then this file, then continues with the
first phase that isn't done. Status: **done** / **todo** / **n/a** (not applicable on the web) / **partial**.

## Order (changed from the brief)
The inventory showed that almost all domain logic is in `:core` already (Hiding, Personal, AddedCourses, FriendCourses,
FreeGaps, ManualCourses, Subjects), but the *StoredData edits* that use it live in `:app` (`PlanRepository`,
`MainViewModel.setSubjectStyle/moveCourse/setDates`, `HideTargets`, `ImportRouting`, `ManualEdit.setting`). So:

- **Phase A (new, first):** move those edits into `:core` `store/StoreEdits.kt` (pure `StoredData -> StoredData`),
  `:app` delegates to it, Android unchanged and its tests green; add them to the JS/JVM compat probe.
- Then phases 1–6 as briefed, each = WebCore API + web UI + Playwright tests.

## Inventory

| Feature | Android | Logic in :core | Web | Plan |
|---|---|---|---|---|
| Import my .ics (replace) | Settings, empty state; `StoreOps.importIcs` | yes | done | — |
| .ics routing: partial file asks replace/add | `ImportRouting`, `IcsImportDialog` | **no** (`data/ImportRouting`) | done | move to core (A), dialog (3) |
| .ics from drawer: as friend / add courses / as mine | `IcsImportDialog`, `PlanRepository.importIcsFriend/addCourses` | partial (`AddedCourses`) | done | edits to core (A), UI (3) |
| Week view grid, colours, badges | `WeekView` | yes | done | — |
| Today column tint + day progress in hour column | `WeekView`, `DayProgress` | yes | partial | check/align (4) |
| Weekend columns (Sat/Sun when something is on) | `Schedule.week` | yes | done (core gives the days) | verify with manual weekend course (1) |
| Day view, cancellations near today | `DayScreen` | yes | done | — |
| Day view free blocks "Frei · 13–15 2 h" | `DayScreen` `FreeRow` | yes (`FreeGaps`) | done | (4) |
| "Zusammen frei" in free blocks | `DayScreen`, `FreeGaps.together` | yes | done | WebCore gaps with people (3/4) |
| Strip (week/day chips, current, muted, jump back) | `PlanStrip` | — | done | — |
| Detail sheet: info, changes, together, note hint, rhythm text | `DetailSheet` | yes | done | rhythm text, own-change lines (1) |
| Detail action row + Mehr / Ausblenden menus | `DetailActions` (`SheetActionRow`) | — | done | (4, built with 1/2) |
| Fach bearbeiten: colour, Kürzel all + per type, preview | `SubjectEditor`, `Subjects.setStyle/setTypeShortName` | partial (friend override merge in VM) | done | move to core (A), UI (1) |
| Fach bearbeiten: move course to other/own subject | `SubjectEditor`, `Subjects.move` | partial | done | (A, 1) |
| Fach bearbeiten: rhythm per biweekly series | `SubjectEditor`, `PlanRepository.setRhythm` | partial | done | (A, 1) |
| Fach bearbeiten: course options (hide type, edit/delete manual, remove added) | `SubjectEditor` | partial | done | (1, 2) |
| Own changes editor (note, s.t., room, time; date / series) | `PersonalUi`, `PersonalChanges` | yes | done | (1) |
| Fällt für mich aus + undo | `DetailSheet`, `PersonalChanges.setCancelled` | yes | done | (1) |
| Termine auswählen (cancelled / hidden) | `PersonalUi`, `PlanRepository.setDates` | partial | done | (A, 1) |
| Own-change markers (dashed violet, pencil, note, s.t.) | `PersonalUi` | — | done | (1) |
| Orphaned changes cleanup list (settings) | `SettingsScreen`, `PersonalChanges.orphans` | yes | done | (1) |
| Manual courses (editor, badge, edit/delete, weekend) | `ManualCourseUi`, `ManualCourses`, `PlanRepository.saveManual/deleteManual` | partial | done | (A, 1) |
| Hide: Termin / Fach / Nur Typ / Nur Gruppe | `HiddenUi.hideChoices`, `HideTargets` | **no** (`HideTargets`) | done | move to core (A), UI (2) |
| Ausgeblendet screen with restore | `HiddenScreen` | yes (`Hidden.remove`) | done | (2) |
| Snackbars: undo, swipe, expand, timer rules | `Snackbars.kt` | — | partial | (2) |
| Friends from shares (QR/file), confirm, undo | `FriendConfirmDialog` | yes | done | — |
| .ics friends (new / update), "aus ICS importiert" | `IcsImportDialog`, `importIcsFriend` | **no** | done | (A, 3) |
| Rename friend, details dialog, delete with undo | `FriendDialog` | **no** (repo) | done | (A, 3) |
| Share a friend's plan with name choice | `ShareNameChoiceDialog`, `Forwarding` | yes | done | (3) |
| Favourites (heart in drawer and top bar) | `PlanDrawer` | yes | done | top bar (3) |
| Drag reordering of friends | `ColumnReorder`, `reorderFriends` | **no** | done | (A, 3) |
| "Zu meinem Plan" / Entfernen / In deinem Plan | `DetailSheet`, `FriendCourses`, `addFromFriend/removeAdded` | partial | done | (A, 3) |
| Added courses ("Hinzugefügter Kurs" box, remove group) | `DetailSheet` | partial | done | (A, 3) |
| Drawer now-line, Stand | `PlanDrawer` | yes | done | — |
| My share: name, "Eigene Änderungen teilen" | `SettingsScreen` | partial | done | toggle (A, 5) |
| QR show (single / animated) + scan | `QrScreens` | yes | done | — |
| Backup export/import with undo, reminder | `SettingsScreen` | yes | done | round-trip tests with all new data (each phase) |
| Landscape squash setting | `ThemeSettings.fitLandscape` | — | todo | web always fits now; add the setting (stored in `extras.display`) (4) |
| Themes: System, Soft, AMOLED, Custom + editor | `ui/theme`, `CustomThemeEditor`, `ColorEditorScreen` | no (Android UI) | partial (Soft) | port colour maths to TS (5); stored in `extras.theme` |
| Theme / language dropdown centred on selection | `SettingsDropdown` | — | todo | (4/5) |
| Language System/Deutsch/English | `AppLanguage` | — | done | — |
| Version overview + changelogs | `UpdateScreens` (version list) | — | todo | generated from `changelog/*.md` at build time (5) |
| Licenses / about | NOTICE | — | todo | (5) |
| "Web-App teilen" (QR of the URL) | — (replaces "App teilen") | — | todo | (5) |
| Keyboard shortcuts | — | — | partial (arrows, Esc) | (6) |
| German/English complete, a11y, dark mode | — | — | partial | (6) |
| APK updater, version check | `data/update` | — | **n/a** | service worker banner replaces it |
| Mensa MD banner | `MensaBanner` | — | **n/a** | |
| NFC, notifications, baseline profiles | — | — | **n/a** | |
| "App teilen" as APK | `UpdateScreens` | — | **n/a** | replaced by "Web-App teilen" |

## Phase log
- Phase 0 (inventory): done, this file.
- Phase 1 (editing) + 2 (hiding) + most of 3 (friends): done. Web UI: `components/Detail.tsx` (sheet with the app's
  action row: 3 primaries + "Mehr", `Menu.tsx`), `Editors.tsx` (SubjectEditor, PersonalEditor, PickDates, ManualEditor,
  ColorChoice), `Hidden.tsx`, `IcsDialog.tsx`, `FriendDialogs.tsx`, markers in week/day (`Badges`), settings (subjects,
  share changes, Ausgeblendet, orphans). All app strings are copied now (`strings.mjs`, minus updater/Mensa keys).
  Tests: `tests/edit.spec.ts` (subject editor, own changes, Termine auswählen, hiding + undo + restore, manual course on
  Saturday, .ics friend + Zu meinem Plan, backup round trips). `e2e.sh` reads the web's backups with the JVM
  (`qrTool backup`): an Android backup with every kind of edit (`android-backup-full.json`, from `CompatEdits`) must
  give the same summary after web import/export; a backup made through the web UI must contain every new data type.
  Full run: 119 passed (all projects). Differences: the orphan list's undo is the snackbar (the app shows an inline undo
  row); friend reordering uses a drag handle (pointer or arrow keys) instead of a long-press.
- Phase A (logic into :core): done. `core/store/StoreEdits.kt` (+ `HideTargets`, `ImportRouting`, `IcsEntry/IcsRoute`)
  holds every edit `PlanRepository`/`MainViewModel` made; `:app` calls it (`PlanRepository.edit { }`), Android tests and
  lint unchanged. WebCore exposes it all (keys `uid|start|o/c` for dates): detail, hide/hideSubject/hideCourse,
  hidden/unhide, setCancelledForMe, savePersonal, pickDates/setDates, orphans/deleteChange, subjects, setSubjectStyle,
  moveCourse, setRhythm, manual courses, removeAdded(+Course), addFromFriend, removeFriendGroup, renameFriend,
  reorderFriends, readIcs/icsReplaceMine/icsAddCourses/icsAddFriend, setShareChanges, qrPayloadAs/shareFileAs; day gaps
  carry "together". Compat: `CompatEdits` (19 steps) JVM golden == WebCore in JS, plus share file/QR/backup of the
  result both ways (`StoreEditsTest` on the JVM).
