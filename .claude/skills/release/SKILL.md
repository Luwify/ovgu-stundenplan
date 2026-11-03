---
name: release
description: Stundenplan MD releases - version bump, changelog style, signing, tools/release.sh (dry run,
  backfill), withdraw, updates.json manifest, Forgejo bridge. Use before preparing or checking a release.
---

**Only the user runs the real release.** `tools/release.sh --dry-run` is allowed.

## Releases
- **Version:** `version.properties` (`version=major.minor.patch`) is the only source; `app/build.gradle.kts` derives
  versionName and versionCode = major*10000 + minor*100 + patch (minor, patch < 100). Raise it in a commit before
  releasing (with `changelog/<ver>.de.md` and `.en.md`, short, for users); the script doesn't bump.
  Changelog style (`unreleased.*.md` and release files): impersonal and user-facing, grouped under short headings
  (as in 1.0.1, 1.1.0); describe what the app does now ("Das Freunde-Symbol zeigt jetzt …"), no first person, "du" only
  where needed; no internal details (class names, profiles, timings, test numbers).
- **Signing:** release build type uses `signingConfigs.release` from the Gradle properties `LUWIFY_STORE_FILE`,
  `LUWIFY_STORE_PASSWORD`, `LUWIFY_KEY_ALIAS`, `LUWIFY_KEY_PASSWORD` (in the user's `~/.gradle/gradle.properties`).
  Missing -> `checkReleaseSigning` fails the release build (never debug-key fallback); debug builds don't need them.
  Pinned certificate SHA-256 in `release/signing-cert.sha256` (apksigner or keytool format, normalized by the script;
  it refuses a real release without a valid pin). Current certificate (apksigner): `d717d70f…941549af` (CN=Luwify).
- **GitHub releases** (repo `Luwify/ovgu-stundenplan`): every release has the APK `stundenplan-md-<ver>.apk`, the GPL
  source archive and `updates.json` with the full history. The app reads
  `https://github.com/Luwify/ovgu-stundenplan/releases/latest/download/updates.json`.
  `updates.json`: `:core` `update/UpdateManifest` (`schema` 1, `app`, `releases` newest first: versionName,
  versionCode, date, minSdk, changelog de/en, apkUrl `.../releases/download/v<ver>/stundenplan-md-<ver>.apk`, sha256,
  size, withdrawn), `Manifests.parse` (TooNew/Broken, validation, unknown keys ignored), `updateFor(installed, sdk)`.
  Written only via `./gradlew :core:manifest` (ManifestTool.kt in core/src/test: add/withdraw/check), so the writer
  is the app's parser.
- **tools/release.sh <ver> [--dry-run]** (settings `release/app.conf`, helpers `tools/release-common.sh`, gh CLI):
  preflight (gh login, remote = the GitHub repo, repo public, branch main, clean tree, version.properties == ver,
  changelogs, tag neither local nor on the remote, no such release, no newer release, tests + lint),
  assembleRelease with `-PupdateManifestUrl` set explicitly, apksigner cert == pin, aapt2 package/versionCode/
  versionName/not debuggable/minSdk, manifest URL in the APK, sha256 + size, `git archive` source tarball
  (`.gitattributes` export-ignore: .claude/, CLAUDE.md, notes/, prompt.md, tools/, release/), new updates.json from the
  newest release's one + diff; dry run stops there (output in `build/release/<ver>/`). Real run: tag + push, draft
  release with notes and all three files, check the asset sizes, publish (`--latest`), then an anonymous outsider
  check (https only, at most 3 redirects, final host = the ones the app allows). On failure it prints what was done
  and how to clean up (deletes nothing). **Only the user runs the real release.**
- **Web app** (`WEB_WORKFLOW`/`WEB_URL` in `release/app.conf`): the pushed tag starts `.github/workflows/web.yml`
  (GitHub Pages, version from version.properties == tag). The script checks workflow, Pages and the environment's
  `v*` tag rule in the preflight, builds the web app as a probe (step 6b, also in the dry run), says in the summary
  that the web deploy will happen, and after publishing waits for the run and checks the live version (step 10).
  Not for `--backfill`. Details: `web/CLAUDE.md`.
- `tools/release.sh --backfill <tag> [--dry-run]`: an existing tag rebuilt in a temporary worktree, release with
  `--latest=false`, the tag's date, an "Archiv, neu gebaut" note; only in version order and before any newer release.
- `tools/withdraw.sh <ver>` sets withdrawn=true in updates.json of the latest release (with confirmation).
- `tools/forgejo/`: bridge to the old self-hosted release repo, which installs up to 1.0.2 read (README there;
  settings in the git-ignored `release/forgejo.conf`).
