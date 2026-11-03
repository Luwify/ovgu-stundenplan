// Node check of the web's QR encoder against the app's (bundled with esbuild by scripts/qrcheck.sh):
//   node qrcheck.mjs <texts.json from `:core:qrTool texts`> <out dir>
// For every plan variant: the web's default code must equal ZXing-Java's module by module, the mask self-check
// (readableQr) must decide as the app's ShareQr.readable and give a code both decoders read, and the chosen code is
// written as PNG for `:core:qrTool singles`.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import pako from 'pako'
import { encodeQr, jsqrReads, readableQr, zxingReads, type QrMatrix } from '../../src/qr'

interface JvmText { id: string; text: string; version: number; defaultMask: number; defaultReads: boolean; readableMask: number; matrix: string }

const [textsPath, out] = process.argv.slice(2)
const texts: JvmText[] = JSON.parse(readFileSync(textsPath, 'utf8'))
mkdirSync(out, { recursive: true })

function rows(m: QrMatrix): string {
  const r: string[] = []
  for (let y = 0; y < m.size; y++) { let s = ''; for (let x = 0; x < m.size; x++) s += m.dark(y, x) ? '1' : '0'; r.push(s) }
  return r.join('/')
}

const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0 })
function crc(b: Uint8Array): number { let c = 0xffffffff; for (const x of b) c = crcTable[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }
function chunk(type: string, data: Uint8Array): Buffer {
  const head = Buffer.alloc(8); head.writeUInt32BE(data.length, 0); head.write(type, 4, 'ascii')
  const body = Buffer.concat([head.subarray(4), Buffer.from(data)])
  const tail = Buffer.alloc(4); tail.writeUInt32BE(crc(body), 0)
  return Buffer.concat([head.subarray(0, 4), body, tail])
}
/** Grey PNG, [cell] px per module, 4-module quiet zone (as drawQr). */
function png(m: QrMatrix, cell = 6): Buffer {
  const side = (m.size + 8) * cell
  const raw = new Uint8Array(side * (side + 1)).fill(255)
  for (let y = 0; y < side; y++) {
    raw[y * (side + 1)] = 0
    const r = Math.floor(y / cell) - 4
    for (let x = 0; x < side; x++) { const c = Math.floor(x / cell) - 4; if (r >= 0 && c >= 0 && r < m.size && c < m.size && m.dark(r, c)) raw[y * (side + 1) + 1 + x] = 0 }
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(side, 0); ihdr.writeUInt32BE(side, 4); ihdr[8] = 8; ihdr[9] = 0
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', pako.deflate(raw)), chunk('IEND', new Uint8Array())])
}

let failed = 0
const report = texts.map((t) => {
  const plain = encodeQr(t.text)!
  const same = rows(plain) === t.matrix && plain.mask === t.defaultMask && plain.version === t.version
  const started = performance.now()
  const pick = readableQr(t.text)!
  const ms = Math.round(performance.now() - started)
  const zx = zxingReads(pick, t.text)
  const jq = jsqrReads(pick, t.text)
  writeFileSync(join(out, `${t.id}.png`), png(pick))
  // The same decision as the app's ShareQr.readable (ZXing-Java read-back), unless jsQR (the web's scanner) rejects
  // the app's choice: then the web takes the next mask both read.
  const appPick = encodeQr(t.text, t.readableMask)!
  const sameChoice = pick.mask === t.readableMask || !jsqrReads(appPick, t.text)
  const ok = same && zx && jq && sameChoice && zxingReads(plain, t.text) === t.defaultReads
  if (!ok) failed++
  return { id: t.id, version: t.version, sameAsZxingJava: same, defaultMask: plain.mask, defaultReadsJava: t.defaultReads, webDefaultZxing: zxingReads(plain, t.text), webDefaultJsqr: jsqrReads(plain, t.text), mask: pick.mask, javaMask: t.readableMask, sameChoice, zxing: zx, jsqr: jq, ms }
})
writeFileSync(join(out, 'report.json'), JSON.stringify(report, null, 1))
for (const r of report) console.log(JSON.stringify(r))
console.log(failed ? `qrcheck: ${failed} of ${report.length} FAILED` : `qrcheck: all ${report.length} ok`)
process.exit(failed ? 1 : 0)
