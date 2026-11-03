import { subjectPalette } from './generated/core/stundenplan-core.mjs'
// The subject block colors of the Android app (ui/theme/SubjectColors + ColorMath): a tint of the subject's hue as
// fill, the color itself as a stripe, nudged for contrast against the Soft theme's page. Compose's lerp works in
// Oklab, so does this port.

export type RGB = [number, number, number] // 0..1 sRGB

export const fromInt = (rgb: number): RGB => [((rgb >> 16) & 255) / 255, ((rgb >> 8) & 255) / 255, (rgb & 255) / 255]
export const css = (c: RGB) => `rgb(${c.map((v) => Math.round(Math.min(1, Math.max(0, v)) * 255)).join(' ')})`

const lin = (c: number) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
const unlin = (c: number) => (c <= 0.0031308 ? c * 12.92 : 1.055 * c ** (1 / 2.4) - 0.055)
export const luminance = (c: RGB) => 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2])
export const contrast = (a: RGB, b: RGB) => {
  const la = luminance(a)
  const lb = luminance(b)
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

function toOklab(c: RGB): RGB {
  const [r, g, b] = c.map(lin)
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
  return [0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s, 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s, 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s]
}

function fromOklab([L, a, b]: RGB): RGB {
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ].map((v) => Math.min(1, Math.max(0, unlin(v)))) as RGB
}

export const lerp = (a: RGB, b: RGB, f: number): RGB => {
  const x = toOklab(a)
  const y = toOklab(b)
  return fromOklab([x[0] + (y[0] - x[0]) * f, x[1] + (y[1] - x[1]) * f, x[2] + (y[2] - x[2]) * f])
}

export function toHsl([r, g, b]: RGB): RGB {
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const l = (max + min) / 2
  const d = max - min
  if (d === 0) return [0, 0, l]
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
  const h = max === r ? ((g - b) / d + (g < b ? 6 : 0)) * 60 : max === g ? ((b - r) / d + 2) * 60 : ((r - g) / d + 4) * 60
  return [h, s, l]
}

export function hsl(h: number, s: number, l: number): RGB {
  const c = (1 - Math.abs(2 * l - 1)) * s
  const hp = (((h % 360) + 360) % 360) / 60
  const x = c * (1 - Math.abs((hp % 2) - 1))
  const [r, g, b] = [[c, x, 0], [x, c, 0], [0, c, x], [0, x, c], [x, 0, c], [c, 0, x]][Math.min(5, Math.max(0, Math.floor(hp)))]
  const m = l - c / 2
  return [r + m, g + m, b + m].map((v) => Math.min(1, Math.max(0, v))) as RGB
}

export function withContrast(c: RGB, min: number, backgrounds: RGB[]): RGB {
  if (backgrounds.every((b) => contrast(c, b) >= min)) return c
  const towardWhite = backgrounds.reduce((s, b) => s + luminance(b), 0) / backgrounds.length < 0.18
  const target: RGB = towardWhite ? [1, 1, 1] : [0, 0, 0]
  for (let step = 1; step <= 50; step++) {
    const x = lerp(c, target, step / 50)
    if (backgrounds.every((b) => contrast(x, b) >= min)) return x
  }
  return target
}

export interface BlockColors {
  container: string
  content: string
  stripe: string
}

/** The page the blocks sit on: the theme's background (theme.ts sets it). Soft light by default. */
let themePage = 0xf7f1e6

export function setThemePage(rgb: number) {
  themePage = rgb
}

const cache = new Map<string, BlockColors>()

/** SubjectColors.block on the current theme's page, light or dark. */
export function blockColors(rgb: number, dark: boolean): BlockColors {
  const key = `${rgb}/${dark}/${themePage}`
  const hit = cache.get(key)
  if (hit) return hit
  const page = fromInt(themePage)
  const base = fromInt(rgb)
  const [h, s] = toHsl(base)
  const fill = dark ? hsl(h, s * 0.45, 0.22) : hsl(h, s * 0.55, 0.88)
  const container = withContrast(fill, 1.15, [page])
  const content = withContrast(dark ? hsl(h, s * 0.3, 0.92) : hsl(h, s * 0.6, 0.15), 4.5, [container])
  const stripe = withContrast(base, 3.0, [container])
  const out = { container: css(container), content: css(content), stripe: css(stripe) }
  cache.set(key, out)
  return out
}

/** The app's subject palette (SubjectPalette, from :core). */
export const palette: number[] = Array.from(subjectPalette())

/** "#rrggbb" of 0xRRGGBB. */
export const rgbHex = (rgb: number) => '#' + (rgb & 0xffffff).toString(16).padStart(6, '0')

/** 0..1 sRGB -> 0xRRGGBB. */
export const toInt = (c: RGB) => c.reduce((v, x) => (v << 8) | Math.round(Math.min(1, Math.max(0, x)) * 255), 0)
