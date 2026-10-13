#!/usr/bin/env bash
# Release <version> on GitHub (settings: release/app.conf), with the gh CLI.
#
#   tools/release.sh 1.1.0 --dry-run          # everything up to the new updates.json, nothing tagged or uploaded
#   tools/release.sh 1.1.0                    # the real release: tag, GitHub release with APK, source, updates.json
#   tools/release.sh --backfill v1.0.1 [--dry-run]
#       an existing tag, rebuilt in a temporary worktree: release with --latest=false, the tag's date as release
#       date and an "Archiv, neu gebaut" note. Only in version order and before any newer release on GitHub
#       (every release carries the full history in its updates.json).
#
# updates.json is an asset of every release; the app reads the one of the latest release
# (https://github.com/<repo>/releases/latest/download/updates.json). The release is created as a draft, all files
# are uploaded, then it is published in one step: the manifest never points to a missing file.
# Never prints secrets; never run with -x.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/release-common.sh"

VERSION=""
BACKFILL=""
DRY_RUN=0
while (($#)); do
    case "$1" in
        --dry-run) DRY_RUN=1 ;;
        --backfill) shift; [[ -n "${1:-}" ]] || die "--backfill braucht einen Tag"; BACKFILL="$1" ;;
        -*) die "unbekannte Option $1" ;;
        *) [[ -z "$VERSION" ]] || die "nur eine Version angeben"; VERSION="$1" ;;
    esac
    shift
done
if [[ -n "$BACKFILL" ]]; then
    [[ -z "$VERSION" ]] || die "--backfill nimmt die Version aus dem Tag: keine Version zusätzlich angeben"
    [[ "$BACKFILL" == v* ]] || die "Tag $BACKFILL beginnt nicht mit v"
    VERSION="${BACKFILL#v}"
fi
[[ -n "$VERSION" ]] || die "Aufruf: tools/release.sh <version> [--dry-run] | --backfill <tag> [--dry-run]"
parse_version "$VERSION"
TAG="v$VERSION"
APK_NAME="$APP_SLUG-$VERSION.apk"
SRC_NAME="$APP_SLUG-$VERSION-src.tar.gz"
PUBLIC_BASE="https://github.com/$GH_REPO"
MANIFEST_URL="$PUBLIC_BASE/releases/latest/download/updates.json"
APK_URL="$PUBLIC_BASE/releases/download/$TAG/$APK_NAME"
TAG_MANIFEST_URL="$PUBLIC_BASE/releases/download/$TAG/updates.json"
# Hosts the app follows redirects to (UpdateSource): github.com within the repo's releases, then GitHub's asset CDN.
ASSET_HOST="release-assets.githubusercontent.com"
OUT="$ROOT/build/release/$VERSION"
WARNINGS=()

# gh_status ARGS...: runs `gh api ARGS` into $TMP/gh.json; prints 200 or 404, dies on anything else.
gh_status() {
    if gh api "$@" >"$TMP/gh.json" 2>"$TMP/gh.err"; then printf 200
    elif grep -q 'HTTP 404' "$TMP/gh.err"; then printf 404
    else die "GitHub-API ($1): $(head -c 300 "$TMP/gh.err")"
    fi
}

# version_code_of TAG: versionCode of a tag v<major.minor.patch>, empty if it isn't one.
version_code_of() {
    [[ "$1" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] || return 0
    printf '%s' $((BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))
}

# ---------------------------------------------------------------- 1. preflight
step "1. Vorabprüfung"
need_tools git gh curl jq sha256sum timeout tar unzip strings
find_build_tools
setup_java
gh auth status --hostname github.com >/dev/null 2>&1 || die "gh ist nicht bei github.com angemeldet (gh auth login)"

remote_url="$(git remote get-url "$WORK_REMOTE" 2>/dev/null)" || die "Remote $WORK_REMOTE fehlt"
[[ "$remote_url" =~ github\.com[:/]${GH_REPO}(\.git)?/?$ ]] || die "Remote $WORK_REMOTE zeigt nicht auf github.com/$GH_REPO ($remote_url)"
[[ "$(gh_status "repos/$GH_REPO")" == 200 ]] || die "Repo $GH_REPO nicht gefunden"
if [[ "$(jq -r '.visibility' "$TMP/gh.json")" != public ]]; then
    ((DRY_RUN)) || die "Repo $GH_REPO ist nicht öffentlich: die App könnte nichts herunterladen"
    WARNINGS+=("Repo $GH_REPO ist nicht öffentlich: ein echtes Release bricht hier ab")
fi

read_pin
if ((PIN_OK == 0)); then
    ((DRY_RUN)) || die "release/signing-cert.sha256 enthält keinen SHA-256-Fingerabdruck (aus dem Passwortmanager eintragen)"
    WARNINGS+=("release/signing-cert.sha256 ist nicht gesetzt: ein echtes Release bricht hier ab")
fi

remote_tags="$(GIT_TERMINAL_PROMPT=0 timeout 30 git ls-remote --tags "$WORK_REMOTE" "refs/tags/$TAG")" \
    || die "$WORK_REMOTE nicht erreichbar (git ls-remote)"

# All releases on GitHub (drafts included), as "<versionCode> <tag> <draft>".
gh api "repos/$GH_REPO/releases" --paginate -q '.[] | "\(.tag_name) \(.draft)"' >"$TMP/releases.raw" 2>"$TMP/gh.err" \
    || die "Releases auf GitHub nicht lesbar: $(head -c 300 "$TMP/gh.err")"
: >"$TMP/releases.txt"
while read -r t draft; do
    code="$(version_code_of "$t")"
    [[ -n "$code" ]] || { WARNINGS+=("Release $t auf GitHub ist kein v<major.minor.patch>: übergangen"); continue; }
    printf '%s %s %s\n' "$code" "$t" "$draft" >>"$TMP/releases.txt"
done <"$TMP/releases.raw"
sort -n -o "$TMP/releases.txt" "$TMP/releases.txt"
grep -q " $TAG " "$TMP/releases.txt" && die "Auf GitHub gibt es schon ein Release $TAG (evtl. als Entwurf)"
grep -q ' true$' "$TMP/releases.txt" && WARNINGS+=("Auf GitHub liegen Release-Entwürfe: $(awk '$3 == "true" {print $2}' "$TMP/releases.txt" | tr '\n' ' ')")
newer="$(awk -v c="$VERSION_CODE" '$1 > c && $3 == "false" {print $2}' "$TMP/releases.txt" | tr '\n' ' ')"
[[ -z "$newer" ]] || die "Auf GitHub gibt es schon neuere Releases ($newer): $VERSION käme in deren updates.json nicht mehr vor"

# The manifest to build on: updates.json of the newest published release (older than this one, see above).
prev_tag="$(awk '$3 == "false" {t = $2} END {print t}' "$TMP/releases.txt")"
if [[ -n "$prev_tag" ]]; then
    gh release download "$prev_tag" --repo "$GH_REPO" --pattern updates.json --dir "$TMP/prev" >/dev/null 2>"$TMP/gh.err" \
        || die "updates.json von $prev_tag nicht ladbar: $(head -c 300 "$TMP/gh.err")"
    MANIFEST_IN="$TMP/prev/updates.json"
    cp "$MANIFEST_IN" "$TMP/old.json"
    read -r newest_code newest_name <<<"$(newest_in_manifest "$MANIFEST_IN")"
    ((VERSION_CODE > newest_code)) || die "$VERSION ist nicht neuer als $newest_name in updates.json von $prev_tag"
    say "Neuestes Release: $prev_tag (updates.json bis $newest_name)"
    if [[ -z "$BACKFILL" && "$(gh_status "repos/$GH_REPO/releases/latest")" == 200 ]]; then
        latest_tag="$(jq -r '.tag_name' "$TMP/gh.json")"
        [[ "$latest_tag" == "$prev_tag" ]] || WARNINGS+=("GitHub markiert $latest_tag als latest, neuestes Release ist $prev_tag")
    fi
else
    say "Noch kein Release auf GitHub (erstes Release)"
    MANIFEST_IN="-"
    : >"$TMP/old.json"
fi

if [[ -z "$BACKFILL" ]]; then
    BUILD_DIR="$ROOT"
    branch="$(git rev-parse --abbrev-ref HEAD)"
    [[ "$branch" == "$WORK_BRANCH" ]] || die "nicht auf $WORK_BRANCH (sondern $branch)"
    [[ -z "$(git status --porcelain --untracked-files=normal)" ]] || die "Arbeitsverzeichnis nicht sauber (git status)"
    git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "Tag $TAG gibt es lokal schon (für alte Versionen: --backfill $TAG)"
    [[ -z "$remote_tags" ]] || die "Tag $TAG gibt es auf $WORK_REMOTE schon"
    if [[ -z "$(git branch -r --contains HEAD --list "$WORK_REMOTE/$WORK_BRANCH")" ]]; then
        WARNINGS+=("HEAD ist noch nicht auf $WORK_REMOTE/$WORK_BRANCH (laut letztem Fetch/Push): der Tag-Push nimmt die Commits mit, der Branch bleibt aber zurück")
    fi
    COMMIT="$(git rev-parse HEAD)"
    RELEASE_DATE="$(date +%F)"
else
    git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "Tag $TAG gibt es lokal nicht"
    [[ "$(git cat-file -t "refs/tags/$TAG")" == tag ]] || WARNINGS+=("$TAG ist kein annotierter Tag")
    COMMIT="$(git rev-parse "refs/tags/$TAG^{commit}")"
    [[ -n "$remote_tags" ]] || die "Tag $TAG ist nicht auf $WORK_REMOTE: erst pushen (git push $WORK_REMOTE refs/tags/$TAG)"
    [[ "$(awk '{print $1}' <<<"$remote_tags" | head -1)" == "$(git rev-parse "refs/tags/$TAG")" ]] \
        || die "Tag $TAG auf $WORK_REMOTE zeigt woanders hin als lokal"
    RELEASE_DATE="$(git for-each-ref --format='%(creatordate:short)' "refs/tags/$TAG")"
    BUILD_DIR="$TMP/worktree"
    git worktree add --quiet --detach "$BUILD_DIR" "$COMMIT"
    trap 'git -C "$ROOT" worktree remove --force "$BUILD_DIR" 2>/dev/null; rm -rf "$TMP"' EXIT
    if [[ -f "$ROOT/local.properties" ]]; then cp "$ROOT/local.properties" "$BUILD_DIR/"; fi
    say "Archiv: $TAG (Commit ${COMMIT:0:12}, ursprünglich vom $RELEASE_DATE), Worktree in $BUILD_DIR"
fi

file_version="$(sed -n 's/^version=//p' "$BUILD_DIR/version.properties")"
[[ "$file_version" == "$VERSION" ]] || die "version.properties sagt $file_version, nicht $VERSION: dort anheben und committen"
CHANGELOG_DE="$BUILD_DIR/changelog/$VERSION.de.md"
CHANGELOG_EN="$BUILD_DIR/changelog/$VERSION.en.md"
[[ -s "$CHANGELOG_DE" && -s "$CHANGELOG_EN" ]] || die "Changelog fehlt: changelog/$VERSION.de.md und .en.md"

say "Tests und Lint ($GRADLE_CHECKS) ..."
# shellcheck disable=SC2086
(cd "$BUILD_DIR" && ./gradlew -q $GRADLE_CHECKS) >"$TMP/checks.log" 2>&1 || { tail -40 "$TMP/checks.log"; die "Tests oder Lint fehlgeschlagen"; }
say "ok"

# ---------------------------------------------------------------- 2. build
step "2. Release-Build ($GRADLE_ASSEMBLE)"
rm -f "$BUILD_DIR/$APK_PATH"
# The manifest URL explicitly, so no Gradle property elsewhere can point the release at another place.
(cd "$BUILD_DIR" && ./gradlew -q "$GRADLE_ASSEMBLE" -PupdateManifestUrl="$MANIFEST_URL") >"$TMP/build.log" 2>&1 \
    || { grep -vE '^w:' "$TMP/build.log" | tail -40; die "Build fehlgeschlagen"; }
[[ -f "$BUILD_DIR/$APK_PATH" ]] || die "kein APK unter $APK_PATH"
rm -rf "$OUT"; mkdir -p "$OUT"
cp "$BUILD_DIR/$APK_PATH" "$OUT/$APK_NAME"
APK="$OUT/$APK_NAME"
say "ok"

# ---------------------------------------------------------------- 3. verify
step "3. Signatur und Version prüfen"
verify_apk "$APK" "$VERSION" "$VERSION_CODE"
if [[ -z "$BACKFILL" ]]; then
    grep -qF "$MANIFEST_URL" <(unzip -p "$APK" 'classes*.dex' 2>/dev/null | strings -n 20) \
        || die "Manifest-URL $MANIFEST_URL steht nicht im APK"
    say "Update-URL im APK: $MANIFEST_URL"
fi

# ---------------------------------------------------------------- 4. checksum
step "4. Prüfsumme"
SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$APK")"
say "$SHA  $SIZE Bytes"

# ---------------------------------------------------------------- 5. source archive
step "5. Quellcode-Archiv (GPL)"
source_archive "$COMMIT" "$VERSION" "$OUT/$SRC_NAME"

# ---------------------------------------------------------------- 6. manifest and notes
step "6. Neues updates.json"
cp "$CHANGELOG_DE" "$OUT/changelog.de.md"
cp "$CHANGELOG_EN" "$OUT/changelog.en.md"
gradle_manifest add "$MANIFEST_IN" "$OUT/updates.json" "$APP_ID" "$VERSION" "$RELEASE_DATE" "$MIN_SDK" \
    "$OUT/changelog.de.md" "$OUT/changelog.en.md" "$APK_URL" "$SHA" "$SIZE" || die "updates.json konnte nicht gebaut werden"
gradle_manifest check "$OUT/updates.json" >/dev/null || die "neues updates.json ist ungültig"
diff -u --label "updates.json (bisher)" --label "updates.json (neu)" "$TMP/old.json" "$OUT/updates.json" >"$OUT/updates.diff" || true
{
    if [[ -n "$BACKFILL" ]]; then
        printf '> **Archiv, neu gebaut.** Ursprünglich veröffentlicht am %s. Dieses APK wurde am %s aus dem Tag %s neu gebaut\n' "$RELEASE_DATE" "$(date +%F)" "$TAG"
        printf '> (gleicher Schlüssel, aber nicht bitgleich mit dem damaligen APK). Falls diese Version eine Update-Suche hat:\n'
        printf '> sie funktioniert mit GitHub nicht, neuere Versionen bitte von der Release-Seite installieren.\n>\n'
        printf '> **Archive, rebuilt.** Originally released on %s. This APK was rebuilt from the tag %s on %s (same key,\n' "$RELEASE_DATE" "$TAG" "$(date +%F)"
        printf '> but not bit-identical to the original APK). If this version has an update check, it does not work with\n'
        printf '> GitHub: install newer versions from the releases page.\n\n'
    fi
    printf '## Deutsch\n\n%s\n\n## English\n\n%s\n\n---\n' "$(cat "$CHANGELOG_DE")" "$(cat "$CHANGELOG_EN")"
    printf 'APK SHA-256: `%s`\n\nSignaturzertifikat / signing certificate SHA-256: `%s`\n\n' "$SHA" "$CERT"
    printf 'Quellcode / source code (GPL-3.0-or-later): %s\n' "$SRC_NAME"
} >"$OUT/release-notes.md"

summary() {
    step "Zusammenfassung"
    say "Version:       $VERSION (versionCode $VERSION_CODE, minSdk $MIN_SDK), Tag $TAG auf ${COMMIT:0:12}"
    if [[ -n "$BACKFILL" ]]; then say "Art:           Archiv (--latest=false), Datum $RELEASE_DATE"; else say "Art:           neues Release (latest), Datum $RELEASE_DATE"; fi
    say "APK:           $APK"
    say "SHA-256:       $SHA"
    say "Größe:         $SIZE Bytes"
    say "Zertifikat:    $CERT"
    say "Quellarchiv:   $OUT/$SRC_NAME ($SRC_SIZE Bytes)"
    say "Release-Text:  $OUT/release-notes.md"
    say "APK-URL:       $APK_URL"
    say "Manifest:      $TAG_MANIFEST_URL"
    [[ -n "$BACKFILL" ]] || say "               $MANIFEST_URL (nach dem Veröffentlichen)"
    say ""
    cat "$OUT/updates.diff"
    if ((${#WARNINGS[@]})); then
        say ""
        for w in "${WARNINGS[@]}"; do say "WARNUNG: $w"; done
    fi
}

if ((DRY_RUN)); then
    summary
    say ""
    say "Probelauf: nichts getaggt, nichts hochgeladen. Dateien liegen in $OUT"
    exit 0
fi

# ---------------------------------------------------------------- from here on: real changes
DONE=()
on_error() {
    local code=$?
    ((code == 0)) && return
    say ""
    say "!! Abgebrochen. Bisher erledigt:"
    if ((${#DONE[@]} == 0)); then say "   (nichts)"; fi
    for d in "${DONE[@]}"; do say "   - ${d#*: }"; done
    say "Aufräumen (von Hand, nichts wurde automatisch gelöscht):"
    for d in "${DONE[@]}"; do
        case "$d" in
            tag-local*) say "   git tag -d $TAG" ;;
            tag-pushed*) say "   git push $WORK_REMOTE :refs/tags/$TAG   (nur wenn das Release ganz neu gemacht wird)" ;;
            release-draft*) say "   gh release delete $TAG --repo $GH_REPO   (Entwurf samt Anhängen; der Tag bleibt)" ;;
            release-published*) say "   Release $TAG ist öffentlich: prüfen ($PUBLIC_BASE/releases/tag/$TAG), zum Zurücknehmen tools/withdraw.sh $VERSION" ;;
        esac
    done
}
trap 'on_error; [[ -z "$BACKFILL" ]] || git -C "$ROOT" worktree remove --force "$BUILD_DIR" 2>/dev/null; rm -rf "$TMP"' EXIT

summary

# ---------------------------------------------------------------- 7. tag
if [[ -z "$BACKFILL" ]]; then
    step "7. Tag $TAG"
    git tag -a "$TAG" -m "$APP_NAME $VERSION" "$COMMIT"
    DONE+=("tag-local: Tag $TAG lokal angelegt")
    GIT_TERMINAL_PROMPT=0 git push "$WORK_REMOTE" "refs/tags/$TAG" || die "Tag-Push fehlgeschlagen"
    DONE+=("tag-pushed: Tag $TAG auf $WORK_REMOTE gepusht")
else
    step "7. Tag $TAG (vorhanden)"
fi

# ---------------------------------------------------------------- 8. GitHub release
step "8. GitHub-Release"
LATEST_FLAG="--latest"
[[ -z "$BACKFILL" ]] || LATEST_FLAG="--latest=false"
gh release create "$TAG" --repo "$GH_REPO" --verify-tag --draft "$LATEST_FLAG" \
    --title "$APP_NAME $VERSION" --notes-file "$OUT/release-notes.md" \
    "$APK" "$OUT/$SRC_NAME" "$OUT/updates.json" >/dev/null 2>"$TMP/gh.err" \
    || { DONE+=("release-draft: Entwurf $TAG (evtl. unvollständig)"); die "Release-Entwurf anlegen/hochladen: $(head -c 300 "$TMP/gh.err")"; }
DONE+=("release-draft: Entwurf $TAG mit Anhängen")
gh release view "$TAG" --repo "$GH_REPO" --json assets -q '.assets[] | "\(.name) \(.size)"' >"$TMP/assets.txt" \
    || die "Anhänge des Entwurfs nicht lesbar"
for f in "$APK" "$OUT/$SRC_NAME" "$OUT/updates.json"; do
    grep -qx "$(basename "$f") $(stat -c %s "$f")" "$TMP/assets.txt" || die "$(basename "$f"): fehlt im Entwurf oder falsche Größe"
done
say "Entwurf mit APK, Quellarchiv und updates.json angelegt"

gh release edit "$TAG" --repo "$GH_REPO" --draft=false "$LATEST_FLAG" >/dev/null 2>"$TMP/gh.err" \
    || die "Release veröffentlichen: $(head -c 300 "$TMP/gh.err")"
DONE+=("release-published: Release $TAG veröffentlicht")
say "veröffentlicht ($LATEST_FLAG)"

# ---------------------------------------------------------------- 9. outsider check
step "9. Außenprüfung (anonym, wie die App: https, höchstens 3 Weiterleitungen)"
outside_ok=1
# public_get URL OUT: prints "<status> <redirects> <final url without query>".
public_get() {
    curl -sS --max-time 300 -L --max-redirs 3 --proto '=https' --proto-redir '=https' -o "$2" \
        -w '%{http_code} %{num_redirects} %{url_effective}' "$1" 2>/dev/null | sed -E 's/\?.*//' || printf 'curl-error 0 -'
}
host_ok() { [[ "$1" == "https://$ASSET_HOST/"* || "$1" == "$PUBLIC_BASE/releases/download/"* ]]; }
check_manifest() {
    local url="$1" st n final
    read -r st n final <<<"$(public_get "$url" "$TMP/public.json")"
    if [[ "$st" == 200 ]] && cmp -s "$TMP/public.json" "$OUT/updates.json" && host_ok "$final"; then
        say "  $url: aktuell ($n Weiterleitungen, zuletzt $final)"
    else
        say "  $url: HTTP $st, $n Weiterleitungen, $final – nicht der neue Stand (Cache? später erneut prüfen)"; outside_ok=0
    fi
}
check_manifest "$TAG_MANIFEST_URL"
[[ -n "$BACKFILL" ]] || check_manifest "$MANIFEST_URL"
read -r st n final <<<"$(public_get "$APK_URL" "$TMP/public.apk")"
if [[ "$st" == 200 && "$(sha256sum "$TMP/public.apk" | cut -d' ' -f1)" == "$SHA" ]] && host_ok "$final"; then
    say "  APK: öffentlich erreichbar, SHA-256 stimmt (über $final)"
else
    say "  APK: HTTP $st, $n Weiterleitungen, $final – nicht wie erwartet (Host außerhalb der Freigabe der App?)"; outside_ok=0
fi

trap '[[ -z "$BACKFILL" ]] || git -C "$ROOT" worktree remove --force "$BUILD_DIR" 2>/dev/null; rm -rf "$TMP"' EXIT
say ""
if ((outside_ok)); then
    say "Fertig: $APP_NAME $VERSION ist veröffentlicht."
else
    say "Veröffentlicht, aber die Außenprüfung passt nicht: siehe oben."
    exit 2
fi
