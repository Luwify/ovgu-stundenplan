#!/usr/bin/env bash
# Release <version> to the public Forgejo release repo (settings: release/release.conf).
#
#   tools/release.sh 1.0.0 --dry-run   # everything up to the new updates.json, nothing tagged or uploaded
#   tools/release.sh 1.0.0             # the real release (tag, Forgejo release, upload, updates.json)
#
# Only from the home network (API via the NAS, see release/release.conf). Never prints secrets; never run with -x.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/release-lib.sh"

VERSION=""
DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        -*) die "unbekannte Option $arg" ;;
        *) [[ -z "$VERSION" ]] || die "nur eine Version angeben"; VERSION="$arg" ;;
    esac
done
[[ -n "$VERSION" ]] || die "Aufruf: tools/release.sh <version> [--dry-run]"
[[ "$VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] || die "$VERSION ist keine Version major.minor.patch (minor, patch < 100)"
VERSION_CODE=$((BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))
TAG="v$VERSION"
APK_NAME="$APP_SLUG-$VERSION.apk"
SRC_NAME="$APP_SLUG-$VERSION-src.tar.gz"
APK_URL="$PUBLIC_BASE/releases/download/$TAG/$APK_NAME"
SRC_URL="$PUBLIC_BASE/releases/download/$TAG/$SRC_NAME"
OUT="$ROOT/build/release/$VERSION"
CHANGELOG_DE="$ROOT/changelog/$VERSION.de.md"
CHANGELOG_EN="$ROOT/changelog/$VERSION.en.md"
WARNINGS=()

# ---------------------------------------------------------------- 1. preflight
step "1. Vorabprüfung"
need_tools git curl jq base64 sha256sum timeout tar
find_build_tools
setup_java

branch="$(git rev-parse --abbrev-ref HEAD)"
[[ "$branch" == "$WORK_BRANCH" ]] || die "nicht auf $WORK_BRANCH (sondern $branch)"
[[ -z "$(git status --porcelain --untracked-files=normal)" ]] || die "Arbeitsverzeichnis nicht sauber (git status)"
file_version="$(sed -n 's/^version=//p' version.properties)"
[[ "$file_version" == "$VERSION" ]] || die "version.properties sagt $file_version, nicht $VERSION: dort anheben und committen"
[[ -s "$CHANGELOG_DE" && -s "$CHANGELOG_EN" ]] || die "Changelog fehlt: changelog/$VERSION.de.md und .en.md"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "Tag $TAG gibt es lokal schon"
remote_tags="$(GIT_TERMINAL_PROMPT=0 timeout 30 git ls-remote --tags "$WORK_REMOTE" "refs/tags/$TAG")" \
    || die "$WORK_REMOTE nicht erreichbar (git ls-remote): ohne ihn kann der Tag nicht gepusht werden"
[[ -z "$remote_tags" ]] || die "Tag $TAG gibt es auf $WORK_REMOTE schon"
if [[ -z "$(git branch -r --contains HEAD --list "$WORK_REMOTE/$WORK_BRANCH")" ]]; then
    WARNINGS+=("HEAD ist noch nicht auf $WORK_REMOTE/$WORK_BRANCH (laut letztem Fetch/Push): der Tag-Push nimmt die Commits mit, der Branch bleibt aber zurück")
fi
# Accepts apksigner format (lowercase hex) and keytool format (uppercase, colons, optional "SHA256:" label).
pin="$(grep -v '^[[:space:]]*#' release/signing-cert.sha256 | tr -d '[:space:]' | sed -E 's/^[Ss][Hh][Aa]-?256://' | tr -d ':' | tr 'A-F' 'a-f')"
pin_ok=1
[[ "$pin" =~ ^[0-9a-f]{64}$ ]] || pin_ok=0
if ((pin_ok == 0)); then
    ((DRY_RUN)) || die "release/signing-cert.sha256 enthält keinen SHA-256-Fingerabdruck (aus dem Passwortmanager eintragen)"
    WARNINGS+=("release/signing-cert.sha256 ist nicht gesetzt: ein echtes Release bricht hier ab")
fi

require_home_network
load_token
fetch_manifest "$TMP/old.json"
if [[ -n "$MANIFEST_SHA" ]]; then
    newest_code="$(jq -r '.releases[0].versionCode // 0' "$TMP/old.json")"
    newest_name="$(jq -r '.releases[0].versionName // "-"' "$TMP/old.json")"
    ((VERSION_CODE > newest_code)) || die "$VERSION ist nicht neuer als $newest_name im veröffentlichten updates.json"
    say "Veröffentlicht: $newest_name"
    MANIFEST_IN="$TMP/old.json"
else
    say "Noch kein updates.json im Release-Repo (erstes Release)"
    MANIFEST_IN="-"
    : >"$TMP/old.json"
fi
status="$(api GET "$RELEASE_API/releases/tags/$TAG" "$TMP/rel.json")"
case "$status" in
    404) ;;
    200) die "Im Release-Repo gibt es schon ein Release $TAG" ;;
    *) api_fail "Release $TAG prüfen" "$status" ;;
esac

say "Tests und Lint ($GRADLE_CHECKS) ..."
# shellcheck disable=SC2086
./gradlew -q $GRADLE_CHECKS >"$TMP/checks.log" 2>&1 || { tail -40 "$TMP/checks.log"; die "Tests oder Lint fehlgeschlagen"; }
say "ok"

# ---------------------------------------------------------------- 2. build
step "2. Release-Build ($GRADLE_ASSEMBLE)"
rm -f "$APK_PATH"
./gradlew -q "$GRADLE_ASSEMBLE" >"$TMP/build.log" 2>&1 || { grep -vE '^w:' "$TMP/build.log" | tail -40; die "Build fehlgeschlagen"; }
[[ -f "$APK_PATH" ]] || die "kein APK unter $APK_PATH"
rm -rf "$OUT"; mkdir -p "$OUT"
cp "$APK_PATH" "$OUT/$APK_NAME"
APK="$OUT/$APK_NAME"
say "ok"

# ---------------------------------------------------------------- 3. verify
step "3. Signatur und Version prüfen"
"$APKSIGNER" verify --print-certs "$APK" >"$TMP/certs.txt" 2>/dev/null || die "apksigner verify: APK nicht gültig signiert"
mapfile -t digests < <(sed -n 's/.*certificate SHA-256 digest: //p' "$TMP/certs.txt" | sort -u)
((${#digests[@]} == 1)) || die "erwartet genau ein Signaturzertifikat, gefunden: ${#digests[@]}"
CERT="${digests[0]}"
if ((pin_ok)); then
    [[ "$CERT" == "$pin" ]] || die "Zertifikat $CERT passt nicht zum gepinnten $pin"
    say "Zertifikat = gepinnter Fingerabdruck"
else
    say "Zertifikat $CERT (kein Pin zum Vergleichen gesetzt)"
fi
"$AAPT2" dump badging "$APK" >"$TMP/badging.txt" 2>/dev/null || die "aapt2 kann das APK nicht lesen"
pkg_line="$(grep '^package:' "$TMP/badging.txt")"
[[ "$pkg_line" == *"name='$APP_ID'"* ]] || die "Paketname im APK ist nicht $APP_ID"
[[ "$pkg_line" == *"versionCode='$VERSION_CODE'"* ]] || die "versionCode im APK ist nicht $VERSION_CODE"
[[ "$pkg_line" == *"versionName='$VERSION'"* ]] || die "versionName im APK ist nicht $VERSION"
grep -q 'application-debuggable' "$TMP/badging.txt" && die "APK ist debuggable"
MIN_SDK="$(sed -n "s/^minSdkVersion:'\([0-9]*\)'/\1/p" "$TMP/badging.txt")"
[[ "$MIN_SDK" =~ ^[0-9]+$ ]] || die "minSdk nicht lesbar"
say "$APP_ID $VERSION ($VERSION_CODE), minSdk $MIN_SDK"

# ---------------------------------------------------------------- 4. checksum
step "4. Prüfsumme"
SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$APK")"
say "$SHA  $SIZE Bytes"

# ---------------------------------------------------------------- 5. source archive
step "5. Quellcode-Archiv (GPL)"
COMMIT="$(git rev-parse HEAD)"
git archive --format=tar.gz --prefix="$APP_SLUG-$VERSION/" -o "$OUT/$SRC_NAME" "$COMMIT"
tar -tzf "$OUT/$SRC_NAME" >"$TMP/src-files.txt"
forbidden="$(grep -E '/(private|notes|tools|release)/|/CLAUDE\.md$|/prompt\.md$|\.(jks|keystore|p12)$|/local\.properties$' "$TMP/src-files.txt" || true)"
[[ -z "$forbidden" ]] || die "Quellarchiv enthält, was nicht hinein soll: $forbidden"
SRC_SIZE="$(stat -c %s "$OUT/$SRC_NAME")"
say "$SRC_NAME: $(wc -l <"$TMP/src-files.txt") Einträge, $SRC_SIZE Bytes (Commit ${COMMIT:0:12})"

# ---------------------------------------------------------------- 6. manifest
step "6. Neues updates.json"
TODAY="$(date +%F)"
gradle_manifest add "$MANIFEST_IN" "$OUT/updates.json" "$APP_ID" "$VERSION" "$TODAY" "$MIN_SDK" \
    "$CHANGELOG_DE" "$CHANGELOG_EN" "$APK_URL" "$SHA" "$SIZE" || die "updates.json konnte nicht gebaut werden"
gradle_manifest check "$OUT/updates.json" >/dev/null || die "neues updates.json ist ungültig"
diff -u --label "updates.json (veröffentlicht)" --label "updates.json (neu)" "$TMP/old.json" "$OUT/updates.json" >"$OUT/updates.diff" || true
{
    printf '## Deutsch\n\n%s\n\n## English\n\n%s\n\n---\n' "$(cat "$CHANGELOG_DE")" "$(cat "$CHANGELOG_EN")"
    printf 'APK SHA-256: `%s`\n\nSignaturzertifikat / signing certificate SHA-256: `%s`\n\n' "$SHA" "$CERT"
    printf 'Quellcode / source code (GPL-3.0-or-later): %s\n' "$SRC_NAME"
} >"$OUT/release-notes.md"

summary() {
    step "Zusammenfassung"
    say "Version:       $VERSION (versionCode $VERSION_CODE, minSdk $MIN_SDK), Tag $TAG auf ${COMMIT:0:12}"
    say "APK:           $OUT/$APK_NAME"
    say "SHA-256:       $SHA"
    say "Größe:         $SIZE Bytes"
    say "Zertifikat:    $CERT"
    say "Quellarchiv:   $OUT/$SRC_NAME ($SRC_SIZE Bytes)"
    say "Hochladen:     $APK_URL"
    say "               $SRC_URL"
    say "Manifest:      $MANIFEST_URL"
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
    step "Außenprüfung (Vorschau, ohne --resolve, ohne Token)"
    read -r st loc <<<"$(public_get "$PUBLIC_BASE/raw/branch/$RELEASE_BRANCH/README.md" "$TMP/readme")"
    if [[ "$st" == 200 ]]; then say "  raw/... ist öffentlich erreichbar (README.md)"; else explain_public "raw/README.md" "$st" "$loc"; fi
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
    for d in "${DONE[@]}"; do say "   - $d"; done
    say "Aufräumen (von Hand, nichts wurde automatisch gelöscht):"
    for d in "${DONE[@]}"; do
        case "$d" in
            tag-local*) say "   git tag -d $TAG" ;;
            tag-pushed*) say "   git push $WORK_REMOTE :refs/tags/$TAG   (nur wenn das Release ganz neu gemacht wird)" ;;
            release-draft*) say "   Entwurf $TAG im Release-Repo löschen: $PUBLIC_BASE/releases (Anhänge gehen mit)" ;;
            release-published*) say "   Release $TAG ist öffentlich, updates.json zeigt aber noch nicht darauf: Release und Tag $TAG im Release-Repo löschen oder den Rest von Hand nachholen" ;;
            manifest*) say "   updates.json ist veröffentlicht: zum Zurücknehmen tools/withdraw.sh $VERSION" ;;
        esac
    done
}
trap 'on_error; rm -rf "$TMP"' EXIT

summary

# ---------------------------------------------------------------- 7. tag
step "7. Tag $TAG"
git tag -a "$TAG" -m "$APP_NAME $VERSION" "$COMMIT"
DONE+=("tag-local: Tag $TAG lokal angelegt")
GIT_TERMINAL_PROMPT=0 git push "$WORK_REMOTE" "refs/tags/$TAG" || die "Tag-Push fehlgeschlagen"
DONE+=("tag-pushed: Tag $TAG auf $WORK_REMOTE gepusht")

# ---------------------------------------------------------------- 8. Forgejo release
step "8. Release im Release-Repo"
jq -n --arg tag "$TAG" --arg target "$RELEASE_BRANCH" --arg name "$APP_NAME $VERSION" \
    --rawfile body "$OUT/release-notes.md" \
    '{tag_name: $tag, target_commitish: $target, name: $name, body: $body, draft: true, prerelease: false}' >"$TMP/release.json"
status="$(api POST "$RELEASE_API/releases" "$TMP/created.json" -H 'Content-Type: application/json' --data-binary @"$TMP/release.json")"
[[ "$status" == 201 ]] || api_fail "Release-Entwurf anlegen" "$status"
RELEASE_ID="$(jq -r '.id' "$TMP/created.json")"
DONE+=("release-draft: Entwurf $TAG (id $RELEASE_ID)")
say "Entwurf angelegt (id $RELEASE_ID)"

upload() {
    local file="$1" name; name="$(basename "$1")"
    status="$(api POST "$RELEASE_API/releases/$RELEASE_ID/assets?name=$name" "$TMP/asset.json" -F "attachment=@$file")"
    [[ "$status" == 201 ]] || api_fail "$name hochladen" "$status"
    [[ "$(jq -r '.size' "$TMP/asset.json")" == "$(stat -c %s "$file")" ]] || die "$name: hochgeladene Größe stimmt nicht"
    say "hochgeladen: $name"
}
upload "$APK"
upload "$OUT/$SRC_NAME"

status="$(api PATCH "$RELEASE_API/releases/$RELEASE_ID" "$TMP/published.json" -H 'Content-Type: application/json' --data-binary '{"draft": false}')"
[[ "$status" == 200 ]] || api_fail "Release veröffentlichen" "$status"
DONE+=("release-published: Release $TAG veröffentlicht")
say "veröffentlicht"

# The manifest must never point to a missing file: fetch the APK from the release (home network) before publishing it.
dl_status="$(curl -sS --max-time 300 -L -o "$TMP/check.apk" -w '%{http_code}' --resolve "$FORGEJO_HOST:443:$NAS_IP" "$APK_URL" || printf 'curl-error')"
[[ "$dl_status" == 200 && "$(sha256sum "$TMP/check.apk" | cut -d' ' -f1)" == "$SHA" ]] \
    || die "APK unter $APK_URL nicht abrufbar oder anders (HTTP $dl_status): updates.json wird nicht geschrieben"

put_manifest "$OUT/updates.json" "$APP_NAME $VERSION"
DONE+=("manifest: updates.json zeigt auf $VERSION")
say "updates.json veröffentlicht"

# ---------------------------------------------------------------- 10. outsider check
step "10. Außenprüfung (ohne --resolve, ohne Token)"
outside_ok=1
read -r st loc <<<"$(public_get "$MANIFEST_URL" "$TMP/public.json")"
if [[ "$st" == 200 ]] && cmp -s "$TMP/public.json" "$OUT/updates.json"; then
    say "  updates.json: öffentlich erreichbar und aktuell"
elif [[ "$st" == 200 ]]; then
    say "  updates.json: erreichbar, aber noch nicht der neue Stand (Cache?) – später erneut prüfen: $MANIFEST_URL"; outside_ok=0
else
    explain_public "updates.json" "$st" "$loc"; outside_ok=0
fi
read -r st loc <<<"$(public_get "$APK_URL" "$TMP/public.apk")"
if [[ "$st" == 200 && "$(sha256sum "$TMP/public.apk" | cut -d' ' -f1)" == "$SHA" ]]; then
    say "  APK: öffentlich erreichbar, SHA-256 stimmt"
elif [[ "$st" == 200 ]]; then
    say "  APK: HTTP 200, aber andere Prüfsumme (Login-Seite statt APK?)"; outside_ok=0
else
    explain_public "APK" "$st" "$loc"; outside_ok=0
fi

trap 'rm -rf "$TMP"' EXIT
say ""
if ((outside_ok)); then
    say "Fertig: $APP_NAME $VERSION ist veröffentlicht."
else
    say "Veröffentlicht, aber von außen nicht (ganz) erreichbar: siehe oben. Freunde bekommen das Update erst, wenn das behoben ist."
    exit 2
fi
