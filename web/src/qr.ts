import jsQR from 'jsqr'
import BinaryBitmap from '@zxing/library/esm/core/BinaryBitmap'
import DecodeHintType from '@zxing/library/esm/core/DecodeHintType'
import RGBLuminanceSource from '@zxing/library/esm/core/RGBLuminanceSource'
import GlobalHistogramBinarizer from '@zxing/library/esm/core/common/GlobalHistogramBinarizer'
import HybridBinarizer from '@zxing/library/esm/core/common/HybridBinarizer'
import QRCodeReader from '@zxing/library/esm/core/qrcode/QRCodeReader'
import FinderPatternFinder from '@zxing/library/esm/core/qrcode/detector/FinderPatternFinder'
import NotFoundException from '@zxing/library/esm/core/NotFoundException'
import ErrorCorrectionLevel from '@zxing/library/esm/core/qrcode/decoder/ErrorCorrectionLevel'
import Encoder from '@zxing/library/esm/core/qrcode/encoder/Encoder'
import MaskUtil from '@zxing/library/esm/core/qrcode/encoder/MaskUtil'
import type QRCode from '@zxing/library/esm/core/qrcode/encoder/QRCode'

export interface QrMatrix {
  size: number
  version: number
  mask: number
  dark: (r: number, c: number) => boolean
}

// ZXing-Java 3.5 (the app's) scores penalty rule 3 with the quiet zone out of bounds as not white; the JS port clamps
// the range instead, so it picks other masks for about half the codes. Same rule as Java, so the codes equal the app's.
const masks = MaskUtil as unknown as {
  isWhiteHorizontal: (row: ArrayLike<number>, from: number, to: number) => boolean
  isWhiteVertical: (array: ArrayLike<number>[], col: number, from: number, to: number) => boolean
}
masks.isWhiteHorizontal = (row, from, to) => {
  if (from < 0 || row.length < to) return false
  for (let i = from; i < to; i++) if (row[i] === 1) return false
  return true
}
masks.isWhiteVertical = (array, col, from, to) => {
  if (from < 0 || array.length < to) return false
  for (let i = from; i < to; i++) if (array[i][col] === 1) return false
  return true
}

// Java's willFit divides the input bits into whole bytes ((bits + 7) / 8 in int); the port keeps the fraction and
// picks one version too large when the data ends on a byte boundary. Same rule as Java.
const fit = Encoder as unknown as { willFit: (bits: number, version: { getTotalCodewords(): number; getECBlocksForLevel(l: unknown): { getTotalECCodewords(): number } }, level: unknown) => boolean }
fit.willFit = (bits, version, level) =>
  version.getTotalCodewords() - version.getECBlocksForLevel(level).getTotalECCodewords() >= Math.floor((bits + 7) / 8)

// The app's ZXing-Java 3.5 detects finder patterns more strictly than this port (based on 3.3): every candidate
// must also pass a diagonal 1:1:3:1:1 check (the port does that only for pure barcodes), and the three patterns are
// the candidates (seen at least twice, module sizes within 1.4x) that form the best isosceles right triangle (the port
// picks by count and module size). With the port's rules the web's check read codes the app's read-back rejects (a
// finder-like pattern in the data wins in Java). Java's rules here, float math as in Java (Math.fround).
interface Center { getX(): number; getY(): number; getCount(): number; getEstimatedModuleSize(): number }
interface Finder {
  possibleCenters: Center[]
  image: { get(x: number, y: number): boolean; getWidth(): number; getHeight(): number }
  getCrossCheckStateCount(): number[]
  crossCheckDiagonal(i: number, j: number, ...rest: unknown[]): boolean
  handlePossibleCenter(stateCount: number[], i: number, j: number, pure?: boolean): boolean
}
const finder = FinderPatternFinder.prototype as unknown as Finder & { selectBestPatterns(this: Finder): Center[] }
const f32 = Math.fround
function foundPatternDiagonal(s: number[]): boolean {
  let total = 0
  for (let i = 0; i < 5; i++) { if (s[i] === 0) return false; total += s[i] }
  if (total < 7) return false
  const size = f32(total / 7)
  const variance = f32(size / f32(1.333))
  return Math.abs(f32(size - s[0])) < variance && Math.abs(f32(size - s[1])) < variance &&
    Math.abs(f32(f32(3 * size) - s[2])) < f32(3 * variance) && Math.abs(f32(size - s[3])) < variance && Math.abs(f32(size - s[4])) < variance
}
finder.crossCheckDiagonal = function (centerI: number, centerJ: number) {
  const s = this.getCrossCheckStateCount()
  const img = this.image
  let i = 0
  while (centerI >= i && centerJ >= i && img.get(centerJ - i, centerI - i)) { s[2]++; i++ }
  if (s[2] === 0) return false
  while (centerI >= i && centerJ >= i && !img.get(centerJ - i, centerI - i)) { s[1]++; i++ }
  if (s[1] === 0) return false
  while (centerI >= i && centerJ >= i && img.get(centerJ - i, centerI - i)) { s[0]++; i++ }
  if (s[0] === 0) return false
  const maxI = img.getHeight(), maxJ = img.getWidth()
  i = 1
  while (centerI + i < maxI && centerJ + i < maxJ && img.get(centerJ + i, centerI + i)) { s[2]++; i++ }
  while (centerI + i < maxI && centerJ + i < maxJ && !img.get(centerJ + i, centerI + i)) { s[3]++; i++ }
  if (s[3] === 0) return false
  while (centerI + i < maxI && centerJ + i < maxJ && img.get(centerJ + i, centerI + i)) { s[4]++; i++ }
  if (s[4] === 0) return false
  return foundPatternDiagonal(s)
}
// The port's handlePossibleCenter calls crossCheckDiagonal only when `pure` is set: set it always (the patched
// check above ignores the port's extra arguments).
const handle = finder.handlePossibleCenter
finder.handlePossibleCenter = function (stateCount, i, j) { return handle.call(this, stateCount, i, j, true) }
finder.selectBestPatterns = function () {
  if (this.possibleCenters.length < 3) throw new NotFoundException()
  this.possibleCenters = this.possibleCenters.filter((c) => c.getCount() >= 2)
  const centers = this.possibleCenters
  centers.sort((a, b) => a.getEstimatedModuleSize() - b.getEstimatedModuleSize())
  const sq = (a: Center, b: Center) => { const x = a.getX() - b.getX(), y = a.getY() - b.getY(); return x * x + y * y }
  let distortion = Number.MAX_VALUE
  let best: Center[] = []
  for (let i = 0; i < centers.length - 2; i++) {
    const min = centers[i].getEstimatedModuleSize()
    for (let j = i + 1; j < centers.length - 1; j++) {
      const ij = sq(centers[i], centers[j])
      for (let k = j + 1; k < centers.length; k++) {
        if (centers[k].getEstimatedModuleSize() > f32(min * f32(1.4))) continue
        const [a, b, c] = [ij, sq(centers[j], centers[k]), sq(centers[i], centers[k])].sort((x, y) => x - y)
        // a² + b² = c² (right angle) and a = b (isosceles).
        const d = Math.abs(c - 2 * b) + Math.abs(c - 2 * a)
        if (d < distortion) { distortion = d; best = [centers[i], centers[j], centers[k]] }
      }
    }
  }
  if (distortion === Number.MAX_VALUE) throw new NotFoundException()
  return best
}

type MaskChooser = (...args: unknown[]) => number
const encoder = Encoder as unknown as { chooseMaskPattern: MaskChooser }
const chooseBest = encoder.chooseMaskPattern

/**
 * The app's encoder (ZXing, level L, smallest version, alphanumeric for `SPMD:` + Base45), so the web shows the same
 * codes as the app; [mask] forces a mask (ZXing's QR_MASK_PATTERN hint, which this port lacks: its mask choice is
 * swapped for the call, all synchronous). Null if it doesn't fit (version 40).
 */
export function encodeQr(text: string, mask?: number): QrMatrix | null {
  let code: QRCode
  try {
    if (mask !== undefined) encoder.chooseMaskPattern = () => mask
    code = Encoder.encode(text, ErrorCorrectionLevel.L)
  } catch {
    return null
  } finally {
    encoder.chooseMaskPattern = chooseBest
  }
  const m = code.getMatrix()
  return { size: m.getWidth(), version: code.getVersion().getVersionNumber(), mask: code.getMaskPattern(), dark: (r, c) => m.get(c, r) === 1 }
}

/** [m] at [cell] px per module with the 4-module quiet zone, as luminance (0 = dark, 255 = light). */
function luminance(m: QrMatrix, cell: number): { lum: Uint8ClampedArray; side: number } {
  const side = (m.size + 8) * cell
  const lum = new Uint8ClampedArray(side * side).fill(255)
  for (let r = 0; r < m.size; r++) for (let c = 0; c < m.size; c++) if (m.dark(r, c)) {
    for (let dy = 0; dy < cell; dy++) lum.fill(0, ((r + 4) * cell + dy) * side + (c + 4) * cell, ((r + 4) * cell + dy) * side + (c + 5) * cell)
  }
  return { lum, side }
}

/** The app's ShareQr.readsBack: rendered at 3 px per module, read by ZXing's camera detector (no pure-image path). */
export function zxingReads(m: QrMatrix, text: string): boolean {
  const { lum, side } = luminance(m, 3)
  const source = new RGBLuminanceSource(lum, side, side)
  const hints = new Map([[DecodeHintType.TRY_HARDER, true]])
  for (const binarizer of [new HybridBinarizer(source), new GlobalHistogramBinarizer(source)]) {
    try {
      if (new QRCodeReader().decode(new BinaryBitmap(binarizer), hints).getText() === text) return true
    } catch {
      // not read with this binarizer
    }
  }
  return false
}

/** The web scanner's decoder (jsQR, as in Scanner.tsx) on the same rendering. */
export function jsqrReads(m: QrMatrix, text: string): boolean {
  const { lum, side } = luminance(m, 3)
  const rgba = new Uint8ClampedArray(side * side * 4)
  for (let i = 0; i < lum.length; i++) { rgba[i * 4] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = lum[i]; rgba[i * 4 + 3] = 255 }
  return jsQR(rgba, side, side, { inversionAttempts: 'dontInvert' })?.data === text
}

/**
 * The app's ShareQr.readable for one code: the default mask if both decoders (the app's ZXing, the web's jsQR) read
 * it back, else the first other mask (0..7) that both read; failing that the first one ZXing reads (Android friends
 * are the usual receivers), else the default. [reads] is for tests.
 */
export function readableQr(text: string, reads: (m: QrMatrix, text: string) => boolean = (m, t) => zxingReads(m, t) && jsqrReads(m, t)): QrMatrix | null {
  const code = encodeQr(text)
  if (!code) return null
  if (reads(code, text)) return code
  const others = [0, 1, 2, 3, 4, 5, 6, 7].filter((k) => k !== code.mask).map((k) => encodeQr(text, k)!)
  return others.find((m) => reads(m, text)) ?? others.find((m) => zxingReads(m, text)) ?? (zxingReads(code, text) ? code : others.find((m) => jsqrReads(m, text)) ?? code)
}

/** Draws [m] dark on white with a 4-module quiet zone, on whole device pixels per module. */
export function drawQr(canvas: HTMLCanvasElement, m: QrMatrix, cssSize: number) {
  const dpr = window.devicePixelRatio || 1
  const modules = m.size + 8
  const scale = Math.max(1, Math.floor((cssSize * dpr) / modules))
  const px = modules * scale
  canvas.width = px
  canvas.height = px
  canvas.style.width = `${px / dpr}px`
  canvas.style.height = `${px / dpr}px`
  const g = canvas.getContext('2d')!
  g.fillStyle = '#fff'
  g.fillRect(0, 0, px, px)
  g.fillStyle = '#000'
  for (let r = 0; r < m.size; r++) for (let c = 0; c < m.size; c++) if (m.dark(r, c)) g.fillRect((c + 4) * scale, (r + 4) * scale, scale, scale)
}
