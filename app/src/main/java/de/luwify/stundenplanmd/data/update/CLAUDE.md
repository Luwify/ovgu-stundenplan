# Updates

## In-app updater
- `:core` `update/UpdatePolicy.kt`: `UpdateSource` (manifest URL, APK prefix `.../releases/download/`, redirect
  rules, `override` for debug; `forManifestUrl` accepts only `https://github.com/<owner>/<repo>/releases/latest/
  download/<file>`), `UpdatePolicy` (auto check at most every 6 h since the last successful one, overview hides withdrawn except the installed
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
- `:app` `data/update/`: `Updates` (process-wide in `AppContainer`: state, check whenever the app comes to the
  foreground (`ProcessLifecycleOwner` ON_START registered in `StundenplanApp`, after the next frame; APK cleanup and
  settings migration once per process before it), download to `cacheDir/updates` with progress/cancel, verify, PackageInstaller session,
  "install unknown apps" explanation once then `ACTION_MANAGE_UNKNOWN_APP_SOURCES`, continues in `onResume`; APKs
  deleted after the session write and once per process start), `UpdateHttp` (HttpURLConnection, checked redirects),
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

## Mensa MD banner (settings, top)
- `core` `update/Companion.kt`: `CompanionApp.MENSA` (package `de.luwify.mensamd`, manifest of `Luwify/ovgu-mensa`, release page,
  pinned certificate = `release/signing-cert.sha256`, checked by `CompanionTest`), `CompanionPolicy` (`banner` state, `release`,
  `problems`: signer must be exactly the pin, not this app's own, so debug builds work). App: `data/update/CompanionInstaller`
  (installed check via `<queries>`, download with the updater's `UpdateHttp`, `ApkInstaller` with the `companion` extra so
  `InstallResultReceiver` routes the result, same "unknown apps" flow via `UnknownSourcesDialog`), `ui/MensaBanner`.
  Choice: `ThemeSettings.mensaBannerHidden` (Boolean?, null = never chosen): shown only while Mensa MD isn't installed
  (`CompanionPolicy.wanted`), an explicit choice wins; in the backup's `display` part (null = keep current; the undo
  snapshot resets it). Switch "Hinweis auf Mensa MD anzeigen" in the "Version" section.
