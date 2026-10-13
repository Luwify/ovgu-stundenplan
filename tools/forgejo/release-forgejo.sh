#!/usr/bin/env bash
# Bridge release: publish <version>, already built and released on GitHub by tools/release.sh, also on the old
# Forgejo release repo, so installs up to 1.0.2 (which only read Forgejo) get it. See tools/forgejo/README.md.
#
#   tools/forgejo/release-forgejo.sh 1.0.3 --dry-run   # checks + the new Forgejo updates.json, nothing uploaded
#   tools/forgejo/release-forgejo.sh 1.0.3             # Forgejo release with the same APK, then updates.json
#
# Uses build/release/<version>/ from tools/release.sh (no rebuild: the same APK on both). Only from the home network
# (API via the NAS, see release/forgejo.conf). Never prints secrets; never run with -x.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/forgejo-lib.sh"

VERSION=""
DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        -*) die "unbekannte Option $arg" ;;
        *) [[ -z "$VERSION" ]] || die "nur eine Version angeben"; VERSION="$arg" ;;
    esac
done
[[ -n "$VERSION" ]] || die "Aufruf: tools/forgejo/release-forgejo.sh <version> [--dry-run]"
parse_version "$VERSION"
TAG="v$VERSION"
APK_NAME="$APP_SLUG-$VERSION.apk"
SRC_NAME="$APP_SLUG-$VERSION-src.tar.gz"
APK_URL="$PUBLIC_BASE/releases/download/$TAG/$APK_NAME"
SRC_URL="$PUBLIC_BASE/releases/download/$TAG/$SRC_NAME"
GITHUB_APK_URL="https://github.com/$GH_REPO/releases/download/$TAG/$APK_NAME"
OUT="$ROOT/build/release/$VERSION"
APK="$OUT/$APK_NAME"
FORGEJO_OUT="$OUT/forgejo"
WARNINGS=()

# ---------------------------------------------------------------- 1. preflight
step "1. Vorabprüfung"
need_tools git curl jq base64 sha256sum timeout tar
find_build_tools
setup_java
[[ -f "$APK" && -f "$OUT/$SRC_NAME" ]] || die "$OUT enthält kein $APK_NAME und $SRC_NAME: erst tools/release.sh $VERSION"
[[ -s "$OUT/changelog.de.md" && -s "$OUT/changelog.en.md" ]] || die "Changelog fehlt in $OUT (von tools/release.sh)"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || WARNINGS+=("Tag $TAG gibt es lokal nicht: ist $VERSION schon auf GitHub veröffentlicht?")
read_pin
((PIN_OK)) || ((DRY_RUN)) || die "release/signing-cert.sha256 enthält keinen SHA-256-Fingerabdruck"
((PIN_OK)) || WARNINGS+=("release/signing-cert.sha256 ist nicht gesetzt: ein echtes Release bricht hier ab")

say "APK aus $OUT:"
verify_apk "$APK" "$VERSION" "$VERSION_CODE"
SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$APK")"
say "$SHA  $SIZE Bytes"

# The same APK as on GitHub: download the public GitHub asset and compare.
gh_st="$(curl -sS --max-time 300 -L --proto '=https' --proto-redir '=https' -o "$TMP/github.apk" -w '%{http_code}' "$GITHUB_APK_URL" 2>/dev/null || printf 'curl-error')"
if [[ "$gh_st" == 200 && "$(sha256sum "$TMP/github.apk" | cut -d' ' -f1)" == "$SHA" ]]; then
    say "= APK des GitHub-Releases ($GITHUB_APK_URL)"
else
    ((DRY_RUN)) || die "GitHub-Release-APK nicht abrufbar oder anders (HTTP $gh_st): erst tools/release.sh $VERSION veröffentlichen"
    WARNINGS+=("GitHub-Release-APK nicht abrufbar oder anders (HTTP $gh_st): ein echtes Release bricht hier ab")
fi

require_home_network
load_token
fetch_manifest "$TMP/old.json"
if [[ -n "$MANIFEST_SHA" ]]; then
    read -r newest_code newest_name <<<"$(newest_in_manifest "$TMP/old.json")"
    ((VERSION_CODE > newest_code)) || die "$VERSION ist nicht neuer als $newest_name im Forgejo-updates.json"
    say "Auf Forgejo veröffentlicht: $newest_name"
    MANIFEST_IN="$TMP/old.json"
else
    say "Noch kein updates.json im Forgejo-Release-Repo"
    MANIFEST_IN="-"
    : >"$TMP/old.json"
fi
status="$(api GET "$RELEASE_API/releases/tags/$TAG" "$TMP/rel.json")"
case "$status" in
    404) ;;
    200) die "Im Forgejo-Release-Repo gibt es schon ein Release $TAG" ;;
    *) api_fail "Release $TAG prüfen" "$status" ;;
esac

# ---------------------------------------------------------------- 2. manifest
step "2. Neues Forgejo-updates.json (APK-URL auf Forgejo)"
rm -rf "$FORGEJO_OUT"; mkdir -p "$FORGEJO_OUT"
TODAY="$(date +%F)"
gradle_manifest add "$MANIFEST_IN" "$FORGEJO_OUT/updates.json" "$APP_ID" "$VERSION" "$TODAY" "$MIN_SDK" \
    "$OUT/changelog.de.md" "$OUT/changelog.en.md" "$APK_URL" "$SHA" "$SIZE" || die "updates.json konnte nicht gebaut werden"
gradle_manifest check "$FORGEJO_OUT/updates.json" >/dev/null || die "neues updates.json ist ungültig"
diff -u --label "updates.json (Forgejo)" --label "updates.json (neu)" "$TMP/old.json" "$FORGEJO_OUT/updates.json" >"$FORGEJO_OUT/updates.diff" || true
{
    printf '## Deutsch\n\n%s\n\n## English\n\n%s\n\n---\n' "$(cat "$OUT/changelog.de.md")" "$(cat "$OUT/changelog.en.md")"
    printf 'APK SHA-256: `%s`\n\nSignaturzertifikat / signing certificate SHA-256: `%s`\n\n' "$SHA" "$CERT"
    printf 'Quellcode / source code (GPL-3.0-or-later): %s\n\n' "$SRC_NAME"
    printf 'Neue Versionen erscheinen auf / new versions are published on https://github.com/%s/releases\n' "$GH_REPO"
} >"$FORGEJO_OUT/release-notes.md"

summary() {
    step "Zusammenfassung"
    say "Version:       $VERSION (versionCode $VERSION_CODE, minSdk $MIN_SDK)"
    say "APK:           $APK (= GitHub-Release)"
    say "SHA-256:       $SHA"
    say "Zertifikat:    $CERT"
    say "Hochladen:     $APK_URL"
    say "               $SRC_URL"
    say "Manifest:      $MANIFEST_URL"
    say ""
    cat "$FORGEJO_OUT/updates.diff"
    if ((${#WARNINGS[@]})); then
        say ""
        for w in "${WARNINGS[@]}"; do say "WARNUNG: $w"; done
    fi
}

if ((DRY_RUN)); then
    summary
    say ""
    say "Probelauf: nichts hochgeladen. Dateien liegen in $FORGEJO_OUT"
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
            release-draft*) say "   Entwurf $TAG im Release-Repo löschen: $PUBLIC_BASE/releases (Anhänge gehen mit)" ;;
            release-published*) say "   Release $TAG ist öffentlich, updates.json zeigt aber noch nicht darauf: Release und Tag $TAG im Release-Repo löschen oder den Rest von Hand nachholen" ;;
            manifest*) say "   updates.json ist veröffentlicht: zum Zurücknehmen tools/forgejo/withdraw-forgejo.sh $VERSION" ;;
        esac
    done
}
trap 'on_error; rm -rf "$TMP"' EXIT

summary

# ---------------------------------------------------------------- 3. Forgejo release
step "3. Release im Forgejo-Release-Repo"
jq -n --arg tag "$TAG" --arg target "$RELEASE_BRANCH" --arg name "$APP_NAME $VERSION" \
    --rawfile body "$FORGEJO_OUT/release-notes.md" \
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

put_manifest "$FORGEJO_OUT/updates.json" "$APP_NAME $VERSION"
DONE+=("manifest: updates.json zeigt auf $VERSION")
say "updates.json veröffentlicht"

# ---------------------------------------------------------------- 4. outsider check
step "4. Außenprüfung (ohne --resolve, ohne Token)"
outside_ok=1
read -r st loc <<<"$(public_get "$MANIFEST_URL" "$TMP/public.json")"
if [[ "$st" == 200 ]] && cmp -s "$TMP/public.json" "$FORGEJO_OUT/updates.json"; then
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
    say "Fertig: $APP_NAME $VERSION ist auch auf Forgejo veröffentlicht."
else
    say "Veröffentlicht, aber von außen nicht (ganz) erreichbar: siehe oben."
    exit 2
fi
