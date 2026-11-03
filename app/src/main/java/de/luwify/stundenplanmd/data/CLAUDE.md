# Data (:app)

- The format (`StoredData`, `MyShare`, `Migrations`, backup envelope `BackupFormat`, `StoreOps`) is in `:core` `store/`
  (shared with the web app); `:app` keeps the I/O, `BackupCodec` (theme mapping) and the repository.
- `:app`: `data/PlanRepository` (the edits themselves are `:core` `StoreEdits`; the repository serializes and writes them,
  `edit { }`; one atomic JSON file `files/plans/data.json` + `me.ics`, `StoredData`
  with people/plans/courseSettings/friendSettings/share, import with undo, friend import/rename/delete,
  `shareIdentity`), `data/PersonIds`, `data/IcsSource` (uri -> bytes, text UTF-8/1252),
  `data/settings/ThemeSettings` (DataStore, Mensa MD keys).

## Persistence (user data must survive every update)
- Files in `files/plans/`: `data.json` (`StoredData`: people (list order = drawer order, me first; `Person.favorite`),
  plans, courseSettings, friendSettings, share, personalChanges, manualCourses, rhythms, addedEvents, hidden; field `version`, currently 8; step 2 -> 3 sorts friends
  by shown name, the order v2 displayed; step 3 -> 4 adds `personalChanges`; 4 -> 5 adds `manualCourses`; 5 -> 6 adds `rhythms` and `addedEvents` (.ics friends need no step: `ics-` ids); 6 -> 7 converts friends' plans from shares
  of apps before 1.2.2 (`LegacyShares`, see the share codec) and sets `Plan.legacyShare`; 7 -> 8 adds `hidden`
  (`CourseSetting.typeShortName`, `Plan.sharedHidden` have defaults);
  versions 4 and 5 are on a real device, never change their meaning) and `me.ics` (raw import). Theme: DataStore `settings` (key `schema_version`).
  `data/PlanStore` does the I/O: atomic writes (tmp + fsync + rename, `AtomicFiles`), a leftover `data.json.bak` from
  the old AtomicFile scheme is restored, an unreadable or too-new `data.json` is renamed to `data.json.broken[.n]`
  (never overwritten; the app shows a snackbar), an older version is migrated step by step (`core/store/Migrations`:
  `STEPS[n]` = n -> n+1) and the old file is kept as `data.json.v<n>`.
- **Any change to the stored format: raise `Migrations.CURRENT` (or `ThemeSettings.SCHEMA`), add the step, add a test
  in `PersistenceTest`.** Never drop or rename stored keys without a step.
- Backup (settings page): one versioned JSON (`BackupCodec` version 4: plan data incl. course settings and friends, raw
  .ics, theme, `language` (since 2: "system"/"de"/"en"), `updates` (since 3: autoCheck, skippedCode), `display` (since 4: fitLandscape); `upgrade` steps
  the envelope, missing parts of older files keep the current values on restore; undo restores them too), export via
  CreateDocument, import via OpenDocument with a confirmation dialog and undo (`Snapshot`). `allowBackup` cloud backup
  stays excluded (data_extraction_rules).
