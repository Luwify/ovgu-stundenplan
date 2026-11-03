#!/usr/bin/env bash
# End-to-end tests of the web app: Kotlin/JS core, fixtures from the JVM build (ZXing, as the Android app), production
# build, Playwright (WebKit + Chromium) in the official container, then the Android side reads what the web app wrote.
# Usage: web/scripts/e2e.sh [playwright args]   (needs JAVA_HOME = JDK 21, podman or docker)
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
IMAGE=mcr.microsoft.com/playwright:v$(node -p "require('./web/node_modules/@playwright/test/package.json').version")-noble
RUN=$(command -v podman || command -v docker)

./gradlew -q :core:webCore :core:qrTool --args="write $ROOT/web/tests/fixtures/generated"
(cd web && npm run build)
web/scripts/qrcheck.sh
rm -rf web/android-check
"$RUN" run --rm --userns=keep-id --ipc=host -v "$ROOT/web":/web:Z -w /web -e HOME=/tmp "$IMAGE" npx playwright test "$@"

# The Android decoder (ZXing + ShareCodec of the JVM build) on the web app's codes and files.
status=0
for dir in web/android-check/*/; do
  [ -d "$dir" ] || continue
  for set in "$dir/single.png" "$dir/single-screenshot.png" "$dir/animated/"; do
    files=$( [ -d "$set" ] && ls "$set"/*.png || echo "$set" )
    out=$(./gradlew -q :core:qrTool --args="read $(echo $files | tr '\n' ' ')" | tail -1)
    echo "$set: $out"
    echo "$out" | grep -q '"share":{"ok":true,"name":"Alex"' || status=1
  done
  # The plan whose default mask ZXing can't read: the web's other mask must read.
  mask=$(node -p "require('./web/tests/fixtures/generated/meta.json').maskName")
  for f in "$dir/mask.png" "$dir/mask-screenshot.png"; do
    [ -f "$f" ] || continue
    out=$(./gradlew -q :core:qrTool --args="read $f" | tail -1)
    echo "$f: $out"
    echo "$out" | grep -q "\"share\":{\"ok\":true,\"name\":\"$mask\"" || status=1
  done
  out=$(./gradlew -q :core:qrTool --args="file $dir/share.stundenplan" | tail -1)
  echo "$dir file: $out"
  echo "$out" | grep -q '"ok":true,"name":"Alex"' || status=1
done
[ $status -eq 0 ] && echo "Android decoder: all web codes and files read." || { echo "Android decoder: FAILED"; exit 1; }
