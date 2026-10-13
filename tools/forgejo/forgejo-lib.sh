# Helpers for the Forgejo bridge scripts (sourced, not run). Settings: release/app.conf (app) and
# release/forgejo.conf (git-ignored: host, home network, token file; template in tools/forgejo/README.md).
# Secrets: the token is read from $TOKEN_FILE into a header file (mode 600) in a private temp dir and passed to curl as
# -H @file, so it never appears in the process list, in output or in logs. Never `set -x` in these scripts.

source "$(dirname "${BASH_SOURCE[0]}")/../release-common.sh"
[[ -r "$ROOT/release/forgejo.conf" ]] || die "release/forgejo.conf fehlt (Vorlage: tools/forgejo/README.md)"
# shellcheck source=/dev/null
source "$ROOT/release/forgejo.conf"
HDR="$TMP/auth-header"

RELEASE_API="/repos/$RELEASE_OWNER/$RELEASE_REPO"
PUBLIC_BASE="https://$FORGEJO_HOST/$RELEASE_OWNER/$RELEASE_REPO"
MANIFEST_URL="$PUBLIC_BASE/raw/branch/$RELEASE_BRANCH/updates.json"

# The API is only reachable without SSO from the home network: talk to the NAS directly.
require_home_network() {
    timeout 4 bash -c "</dev/tcp/$NAS_IP/443" 2>/dev/null || die "Release nur aus dem Heimnetz möglich ($NAS_IP:443 nicht erreichbar)"
}

load_token() {
    [[ -r "$TOKEN_FILE" ]] || die "Token-Datei $TOKEN_FILE nicht lesbar"
    (umask 077; { printf 'Authorization: token '; tr -d '\r\n' <"$TOKEN_FILE"; printf '\n'; } >"$HDR")
}

# api METHOD PATH OUTFILE [curl args...]: prints the HTTP status; body in OUTFILE. Always via --resolve to the NAS.
api() {
    local method="$1" path="$2" out="$3"; shift 3
    curl -sS --max-time 300 -o "$out" -w '%{http_code}' \
        --resolve "$FORGEJO_HOST:443:$NAS_IP" \
        -H @"$HDR" -H 'Accept: application/json' \
        -X "$method" "https://$FORGEJO_HOST/api/v1$path" "$@" || printf 'curl-error'
}

# Explains a non-2xx API status (no response body: it may echo request data).
api_fail() {
    local what="$1" status="$2"
    case "$status" in
        30[0-9]) die "$what: Weiterleitung (HTTP $status), vermutlich SSO: läuft der Aufruf wirklich über $NAS_IP?" ;;
        401|403) die "$what: keine Berechtigung (HTTP $status): Token ungültig oder ohne Schreibrecht auf $RELEASE_OWNER/$RELEASE_REPO" ;;
        curl-error) die "$what: Verbindung fehlgeschlagen" ;;
        *) die "$what: HTTP $status" ;;
    esac
}

# Live updates.json via the API: writes it to $1 and sets MANIFEST_SHA (git blob sha, needed to update the file);
# MANIFEST_SHA is empty if there is no updates.json yet.
fetch_manifest() {
    local dest="$1" status
    status="$(api GET "$RELEASE_API/contents/updates.json?ref=$RELEASE_BRANCH" "$TMP/contents.json")"
    case "$status" in
        200)
            jq -r '.content' "$TMP/contents.json" | base64 -d >"$dest"
            MANIFEST_SHA="$(jq -r '.sha' "$TMP/contents.json")"
            ;;
        404) MANIFEST_SHA="" ;;
        *) api_fail "updates.json lesen" "$status" ;;
    esac
}

# put_manifest FILE MESSAGE: creates or updates updates.json on the release branch.
put_manifest() {
    local file="$1" message="$2" body status method=POST
    body="$TMP/put.json"
    if [[ -n "$MANIFEST_SHA" ]]; then method=PUT; fi
    jq -n --arg content "$(base64 -w0 "$file")" --arg message "$message" --arg branch "$RELEASE_BRANCH" \
        --arg sha "$MANIFEST_SHA" \
        '{content: $content, message: $message, branch: $branch} + (if $sha == "" then {} else {sha: $sha} end)' >"$body"
    status="$(api "$method" "$RELEASE_API/contents/updates.json" "$TMP/put-out.json" \
        -H 'Content-Type: application/json' --data-binary @"$body")"
    [[ "$status" == 200 || "$status" == 201 ]] || api_fail "updates.json schreiben" "$status"
}

# public_get URL OUT: anonymous, WITHOUT --resolve (the real public path). Prints "<status> <redirect url>".
public_get() {
    curl -sS --max-time 300 -o "$2" -w '%{http_code} %{redirect_url}' "$1" || printf 'curl-error -'
}

# Explains where a public request ended up when it wasn't a 200.
explain_public() {
    local what="$1" status="$2" location="$3"
    case "$status" in
        30[0-9])
            if [[ "$location" == *"/attachments/"* ]]; then
                say "  $what: Weiterleitung auf $location"
                say "  -> /attachments/<uuid> ist nicht von SSO freigegeben. Bypass ergänzen, z. B. ^/attachments/.*\$"
                say "     (oder enger, falls gewünscht) und prüfen, ob die Weiterleitung danach durchgeht."
            elif [[ "$location" == *auth* || "$location" == *login* ]]; then
                say "  $what: Weiterleitung zum Login ($location): die SSO-Bypass-Regel greift nicht."
            else
                say "  $what: Weiterleitung (HTTP $status) nach $location"
            fi ;;
        curl-error) say "  $what: Verbindung fehlgeschlagen" ;;
        *) say "  $what: HTTP $status" ;;
    esac
}
