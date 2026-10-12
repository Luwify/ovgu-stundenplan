#!/usr/bin/env bash
# Withdraw <version>: sets withdrawn=true in the published updates.json (the app then stops offering it). The release
# and its files stay on Forgejo. Only from the home network (API via the NAS). Never run with -x.
#
#   tools/withdraw.sh 1.0.1
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/release-lib.sh"

VERSION="${1:-}"
[[ $# -eq 1 && -n "$VERSION" ]] || die "Aufruf: tools/withdraw.sh <version>"
need_tools curl jq base64 timeout diff
setup_java

require_home_network
load_token
fetch_manifest "$TMP/old.json"
[[ -n "$MANIFEST_SHA" ]] || die "kein updates.json im Release-Repo"
jq -e --arg v "$VERSION" '.releases[] | select(.versionName == $v)' "$TMP/old.json" >/dev/null || die "$VERSION steht nicht in updates.json"
jq -e --arg v "$VERSION" '.releases[] | select(.versionName == $v and .withdrawn)' "$TMP/old.json" >/dev/null && die "$VERSION ist schon zurückgezogen"

gradle_manifest withdraw "$TMP/old.json" "$TMP/new.json" "$VERSION" || die "updates.json konnte nicht geändert werden"
diff -u --label "updates.json (veröffentlicht)" --label "updates.json (neu)" "$TMP/old.json" "$TMP/new.json" || true
newest="$(jq -r '[.releases[] | select(.withdrawn | not)][0].versionName // "keine"' "$TMP/new.json")"
say ""
say "Danach bietet die App als neueste Version an: $newest"
read -r -p "$VERSION zurückziehen? [j/N] " answer
[[ "$answer" == j || "$answer" == J ]] || { say "Nichts geändert."; exit 0; }

put_manifest "$TMP/new.json" "$APP_NAME $VERSION zurückgezogen"
say "updates.json aktualisiert: $VERSION ist zurückgezogen."
