# Shared helpers for tools/release.sh, tools/withdraw.sh and tools/forgejo/* (sourced, not run).
# Never `set -x` in these scripts. Secrets are never read here: gh keeps its own login, Gradle reads the signing
# credentials from ~/.gradle/gradle.properties.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=../release/app.conf
source "$ROOT/release/app.conf"

TMP="$(mktemp -d)"
chmod 700 "$TMP"
trap 'rm -rf "$TMP"' EXIT

say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }
die()  { printf 'FEHLER: %s\n' "$*" >&2; exit 1; }

need_tools() {
    local missing=()
    for t in "$@"; do command -v "$t" >/dev/null 2>&1 || missing+=("$t"); done
    ((${#missing[@]} == 0)) || die "Werkzeuge fehlen: ${missing[*]} (bitte selbst installieren, das Skript installiert nichts)"
}

# Newest build-tools of the SDK (ANDROID_HOME, ANDROID_SDK_ROOT or sdk.dir in local.properties).
find_build_tools() {
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -z "$sdk" && -f local.properties ]]; then sdk="$(sed -n 's/^sdk\.dir=//p' local.properties)"; fi
    [[ -n "$sdk" && -d "$sdk/build-tools" ]] || die "Android SDK nicht gefunden (ANDROID_HOME oder sdk.dir in local.properties)"
    local bt
    bt="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
    [[ -x "$bt/apksigner" && -x "$bt/aapt2" ]] || die "apksigner/aapt2 fehlen in $bt (SDK build-tools installieren)"
    APKSIGNER="$bt/apksigner"
    AAPT2="$bt/aapt2"
}

setup_java() {
    if [[ -z "${JAVA_HOME:-}" && -d "$JAVA_HOME_DEFAULT" ]]; then export JAVA_HOME="$JAVA_HOME_DEFAULT"; fi
    [[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME nicht gesetzt (JDK 21)"
}

# parse_version VERSION: dies unless major.minor.patch (minor, patch < 100); sets VERSION_CODE.
parse_version() {
    [[ "$1" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] \
        || die "$1 ist keine Version major.minor.patch (minor, patch < 100)"
    VERSION_CODE=$((BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))
}

# read_pin: PIN = the pinned certificate SHA-256 (apksigner or keytool format, normalized), PIN_OK = 1 if valid.
read_pin() {
    PIN="$(grep -v '^[[:space:]]*#' "$ROOT/release/signing-cert.sha256" 2>/dev/null | tr -d '[:space:]' \
        | sed -E 's/^[Ss][Hh][Aa]-?256://' | tr -d ':' | tr 'A-F' 'a-f')"
    PIN_OK=0
    if [[ "$PIN" =~ ^[0-9a-f]{64}$ ]]; then PIN_OK=1; fi
}

# verify_apk APK VERSION VERSION_CODE: signature (exactly one certificate, = pin if set), package, version, not
# debuggable. Sets CERT and MIN_SDK. Needs find_build_tools and read_pin.
verify_apk() {
    local apk="$1" version="$2" code="$3" digests pkg_line
    "$APKSIGNER" verify --print-certs "$apk" >"$TMP/certs.txt" 2>/dev/null || die "apksigner verify: APK nicht gültig signiert"
    mapfile -t digests < <(sed -n 's/.*certificate SHA-256 digest: //p' "$TMP/certs.txt" | sort -u)
    ((${#digests[@]} == 1)) || die "erwartet genau ein Signaturzertifikat, gefunden: ${#digests[@]}"
    CERT="${digests[0]}"
    if ((PIN_OK)); then
        [[ "$CERT" == "$PIN" ]] || die "Zertifikat $CERT passt nicht zum gepinnten $PIN"
        say "Zertifikat = gepinnter Fingerabdruck"
    else
        say "Zertifikat $CERT (kein Pin zum Vergleichen gesetzt)"
    fi
    "$AAPT2" dump badging "$apk" >"$TMP/badging.txt" 2>/dev/null || die "aapt2 kann das APK nicht lesen"
    pkg_line="$(grep '^package:' "$TMP/badging.txt")"
    [[ "$pkg_line" == *"name='$APP_ID'"* ]] || die "Paketname im APK ist nicht $APP_ID"
    [[ "$pkg_line" == *"versionCode='$code'"* ]] || die "versionCode im APK ist nicht $code"
    [[ "$pkg_line" == *"versionName='$version'"* ]] || die "versionName im APK ist nicht $version"
    grep -q 'application-debuggable' "$TMP/badging.txt" && die "APK ist debuggable"
    MIN_SDK="$(sed -n "s/^minSdkVersion:'\([0-9]*\)'/\1/p" "$TMP/badging.txt")"
    [[ "$MIN_SDK" =~ ^[0-9]+$ ]] || die "minSdk nicht lesbar"
    say "$APP_ID $version ($code), minSdk $MIN_SDK"
}

# source_archive COMMIT VERSION OUT_FILE: the GPL source archive (git archive, export-ignore applies). Sets SRC_SIZE.
source_archive() {
    local commit="$1" version="$2" out="$3" forbidden
    git archive --format=tar.gz --prefix="$APP_SLUG-$version/" -o "$out" "$commit"
    tar -tzf "$out" >"$TMP/src-files.txt"
    forbidden="$(grep -E '/(private|notes|tools|release)/|/CLAUDE(\.local)?\.md$|/prompt\.md$|\.(jks|keystore|p12)$|/local\.properties$' "$TMP/src-files.txt" || true)"
    [[ -z "$forbidden" ]] || die "Quellarchiv enthält, was nicht hinein soll: $forbidden"
    SRC_SIZE="$(stat -c %s "$out")"
    say "$(basename "$out"): $(wc -l <"$TMP/src-files.txt") Einträge, $SRC_SIZE Bytes (Commit ${commit:0:12})"
}

# gradle_manifest ARGS...: the app's own manifest tool (same parser as the app).
gradle_manifest() {
    ./gradlew -q "$MANIFEST_TASK" --args="$*"
}

# newest_in_manifest FILE: prints "<versionCode> <versionName>" of the first (newest) release, "0 -" if none.
newest_in_manifest() {
    jq -r '"\(.releases[0].versionCode // 0) \(.releases[0].versionName // "-")"' "$1"
}
