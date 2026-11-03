#!/usr/bin/env bash
# The web's QR encoder against the app's (no browser): `:core:qrTool texts` makes QR texts of many plans (sizes from
# version 6 to 40, notes, umlauts, my share with changes and hidden dates, a forwarded friend, codes whose default
# mask ZXing doesn't read back) with ZXing-Java's matrix; tests/tools/qrcheck.ts checks that the web encodes the same
# matrix, runs the mask self-check and writes the chosen codes as PNG; ZXing-Java (as the app's scanner) reads them.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=web/test-results/qrcheck
rm -rf "$OUT" && mkdir -p "$OUT"
./gradlew -q :core:qrTool --args="texts $PWD/$OUT/texts.json"
web/node_modules/.bin/esbuild web/tests/tools/qrcheck.ts --bundle --platform=node --format=esm --outfile="$OUT/qrcheck.mjs" --log-level=warning
node "$OUT/qrcheck.mjs" "$OUT/texts.json" "$OUT/png" | tail -1
./gradlew -q :core:qrTool --args="singles $(ls "$OUT"/png/*.png | tr '\n' ' ')" > "$OUT/singles.json"
node -e '
const fs = require("fs"); const [texts, singles] = process.argv.slice(1).map((f) => JSON.parse(fs.readFileSync(f, "utf8")))
const want = Object.fromEntries(texts.map((t) => [t.id + ".png", t.text]))
const bad = singles.filter((s) => s.text !== want[s.file])
console.log(bad.length ? `Android decoder: ${bad.map((b) => b.file).join(", ")} NOT read` : `Android decoder: all ${singles.length} web codes read`)
process.exit(bad.length ? 1 : 0)' "$OUT/texts.json" "$OUT/singles.json"
