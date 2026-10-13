#!/usr/bin/env bash
# Withdraw <version>: sets withdrawn=true in updates.json of the latest GitHub release (the one the app reads), so
# the app stops offering it. The release and its files stay. Never run with -x.
#
#   tools/withdraw.sh 1.0.1
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/release-common.sh"

VERSION="${1:-}"
[[ $# -eq 1 && -n "$VERSION" ]] || die "Aufruf: tools/withdraw.sh <version>"
need_tools gh jq diff
setup_java
gh auth status --hostname github.com >/dev/null 2>&1 || die "gh ist nicht bei github.com angemeldet (gh auth login)"

latest="$(gh api "repos/$GH_REPO/releases/latest" -q .tag_name 2>/dev/null)" || die "kein (latest) Release auf GitHub"
gh release download "$latest" --repo "$GH_REPO" --pattern updates.json --dir "$TMP/latest" >/dev/null 2>&1 \
    || die "updates.json von $latest nicht ladbar"
OLD="$TMP/latest/updates.json"
jq -e --arg v "$VERSION" '.releases[] | select(.versionName == $v)' "$OLD" >/dev/null || die "$VERSION steht nicht in updates.json"
jq -e --arg v "$VERSION" '.releases[] | select(.versionName == $v and .withdrawn)' "$OLD" >/dev/null && die "$VERSION ist schon zurückgezogen"

mkdir -p "$TMP/new"
gradle_manifest withdraw "$OLD" "$TMP/new/updates.json" "$VERSION" || die "updates.json konnte nicht geändert werden"
diff -u --label "updates.json ($latest)" --label "updates.json (neu)" "$OLD" "$TMP/new/updates.json" || true
newest="$(jq -r '[.releases[] | select(.withdrawn | not)][0].versionName // "keine"' "$TMP/new/updates.json")"
say ""
say "Danach bietet die App als neueste Version an: $newest"
say "(Nur updates.json von $latest wird ersetzt; ältere Releases behalten ihre Kopie, die App liest sie nicht.)"
read -r -p "$VERSION zurückziehen? [j/N] " answer
[[ "$answer" == j || "$answer" == J ]] || { say "Nichts geändert."; exit 0; }

gh release upload "$latest" --repo "$GH_REPO" --clobber "$TMP/new/updates.json" >/dev/null || die "Hochladen fehlgeschlagen"
say "updates.json von $latest aktualisiert: $VERSION ist zurückgezogen."
