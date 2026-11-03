// The Android app's themes (ui/theme: Theme.kt, SoftTheme, AmoledTheme, CustomTheme, PlanColors, ColorMath) as CSS
// variables. The theme is stored like the app's backup does it (ThemeBackup in the backup's extras: mode, customBase,
// customAccent, customGlow, customOverrides), so a theme chosen here goes to Android with a backup and back.
import { type RGB, contrast, css, fromInt, hsl, lerp, luminance, setThemePage, toHsl, toInt, withContrast } from './colors'

export type ThemeMode = 'system' | 'soft' | 'amoled' | 'custom'
export type CustomBase = 'system' | 'light' | 'dark' | 'black'
export type ColorRole = 'bg' | 'card' | 'text' | 'border' | 'highlight' | 'favorite'
export const ROLES: ColorRole[] = ['bg', 'card', 'text', 'border', 'highlight', 'favorite']

export interface ThemeSpec {
  mode: ThemeMode
  customBase: CustomBase
  customAccent: number
  customGlow: boolean
  customOverrides: string
}

export const DEFAULT_ACCENT = 0x7839d0

/** The web's look until a theme is chosen (or restored): Soft, as before themes came. */
export const DEFAULT_THEME: ThemeSpec = { mode: 'soft', customBase: 'system', customAccent: DEFAULT_ACCENT, customGlow: false, customOverrides: '' }

/** "bg=101010,card=1A1A1A" (CustomThemeSpec.decodeOverrides): unknown roles and broken entries skipped. */
export function decodeOverrides(text: string | null | undefined): Partial<Record<ColorRole, number>> {
  const out: Partial<Record<ColorRole, number>> = {}
  for (const entry of (text ?? '').split(',')) {
    const [k, v] = entry.split('=')
    if (ROLES.includes(k as ColorRole) && v?.length === 6 && /^[0-9a-fA-F]{6}$/.test(v)) out[k as ColorRole] = parseInt(v, 16)
  }
  return out
}

export function encodeOverrides(o: Partial<Record<ColorRole, number>>): string {
  return ROLES.filter((r) => o[r] !== undefined).map((r) => `${r}=${o[r]!.toString(16).toUpperCase().padStart(6, '0')}`).join(',')
}

interface Scheme {
  primary: RGB; onPrimary: RGB; primaryContainer: RGB; onPrimaryContainer: RGB; inversePrimary: RGB
  secondary: RGB; secondaryContainer: RGB; onSecondaryContainer: RGB
  tertiary: RGB; tertiaryContainer: RGB; onTertiaryContainer: RGB
  background: RGB; onBackground: RGB; surface: RGB; onSurface: RGB; surfaceVariant: RGB; onSurfaceVariant: RGB
  inverseSurface: RGB; inverseOnSurface: RGB; error: RGB; outline: RGB; outlineVariant: RGB
  surfaceContainerLowest: RGB; surfaceContainerLow: RGB; surfaceContainer: RGB; surfaceContainerHigh: RGB; surfaceContainerHighest: RGB
}

type Hex = { [K in keyof Scheme]: number }
const scheme = (h: Hex): Scheme => Object.fromEntries(Object.entries(h).map(([k, v]) => [k, fromInt(v)])) as unknown as Scheme

/** Material 3's baseline (Compose lightColorScheme / darkColorScheme defaults) with the app's System fallback accents. */
const SYSTEM_LIGHT = scheme({
  primary: 0x1f5faf, onPrimary: 0xffffff, primaryContainer: 0xeaddff, onPrimaryContainer: 0x4f378b, inversePrimary: 0xd0bcff,
  secondary: 0x535f70, secondaryContainer: 0xe8def8, onSecondaryContainer: 0x4a4458,
  tertiary: 0x6b5778, tertiaryContainer: 0xffd8e4, onTertiaryContainer: 0x633b48,
  background: 0xfef7ff, onBackground: 0x1d1b20, surface: 0xfef7ff, onSurface: 0x1d1b20, surfaceVariant: 0xe7e0ec, onSurfaceVariant: 0x49454f,
  inverseSurface: 0x322f35, inverseOnSurface: 0xf5eff7, error: 0xb3261e, outline: 0x79747e, outlineVariant: 0xcac4d0,
  surfaceContainerLowest: 0xffffff, surfaceContainerLow: 0xf7f2fa, surfaceContainer: 0xf3edf7, surfaceContainerHigh: 0xece6f0, surfaceContainerHighest: 0xe6e0e9,
})
const SYSTEM_DARK = scheme({
  primary: 0xa6c8ff, onPrimary: 0x381e72, primaryContainer: 0x4f378b, onPrimaryContainer: 0xeaddff, inversePrimary: 0x6750a4,
  secondary: 0xbbc7db, secondaryContainer: 0x4a4458, onSecondaryContainer: 0xe8def8,
  tertiary: 0xd7bde4, tertiaryContainer: 0x633b48, onTertiaryContainer: 0xffd8e4,
  background: 0x141218, onBackground: 0xe6e0e9, surface: 0x141218, onSurface: 0xe6e0e9, surfaceVariant: 0x49454f, onSurfaceVariant: 0xcac4d0,
  inverseSurface: 0xe6e0e9, inverseOnSurface: 0x322f35, error: 0xf2b8b5, outline: 0x938f99, outlineVariant: 0x49454f,
  surfaceContainerLowest: 0x0f0d13, surfaceContainerLow: 0x1d1b20, surfaceContainer: 0x211f26, surfaceContainerHigh: 0x2b2930, surfaceContainerHighest: 0x36343b,
})

const SOFT_LIGHT = scheme({
  primary: 0x43654a, onPrimary: 0xffffff, primaryContainer: 0xb9d3b6, onPrimaryContainer: 0x16301c, inversePrimary: 0xa9c9a8,
  secondary: 0x5c6b58, secondaryContainer: 0xe7e3d2, onSecondaryContainer: 0x363c2d,
  tertiary: 0x9a4a2a, tertiaryContainer: 0xfadcc8, onTertiaryContainer: 0x4a2210,
  background: 0xf7f1e6, onBackground: 0x2e2a24, surface: 0xf7f1e6, onSurface: 0x2e2a24, surfaceVariant: 0xe5ddcd, onSurfaceVariant: 0x645c51,
  inverseSurface: 0x3a352e, inverseOnSurface: 0xf4ede0, error: 0xb3261e, outline: 0x8a8174, outlineVariant: 0xddd3c2,
  surfaceContainerLowest: 0xfffcf6, surfaceContainerLow: 0xfbf6ec, surfaceContainer: 0xf3ecdf, surfaceContainerHigh: 0xeee6d8, surfaceContainerHighest: 0xe8dfcf,
})
const SOFT_DARK = scheme({
  primary: 0xa9c9a8, onPrimary: 0x173320, primaryContainer: 0x3c5c43, onPrimaryContainer: 0xd3ebd1, inversePrimary: 0x43654a,
  secondary: 0xb9c7b5, secondaryContainer: 0x2e2b23, onSecondaryContainer: 0xe2decb,
  tertiary: 0xf0ad8e, tertiaryContainer: 0x5b3524, onTertiaryContainer: 0xffdccb,
  background: 0x0d0b09, onBackground: 0xece4d8, surface: 0x0d0b09, onSurface: 0xece4d8, surfaceVariant: 0x3a342c, onSurfaceVariant: 0xb7ae9f,
  inverseSurface: 0xece4d8, inverseOnSurface: 0x2e2a24, error: 0xffb4a9, outline: 0x8f8678, outlineVariant: 0x3f382f,
  surfaceContainerLowest: 0x080706, surfaceContainerLow: 0x15110e, surfaceContainer: 0x1a1612, surfaceContainerHigh: 0x231e18, surfaceContainerHighest: 0x2d2720,
})
const AMOLED = scheme({
  primary: 0x8ab4f8, onPrimary: 0x06234d, primaryContainer: 0x1d3f73, onPrimaryContainer: 0xd6e3ff, inversePrimary: 0x2e5fa8,
  secondary: 0xa8b8d4, secondaryContainer: 0x1e2024, onSecondaryContainer: 0xc9ccd4,
  tertiary: 0xc9b8f2, tertiaryContainer: 0x3a2f5b, onTertiaryContainer: 0xe9deff,
  background: 0x000000, onBackground: 0xe6e7eb, surface: 0x000000, onSurface: 0xe6e7eb, surfaceVariant: 0x2a2c32, onSurfaceVariant: 0xa9acb5,
  inverseSurface: 0xe6e7eb, inverseOnSurface: 0x1b1c20, error: 0xffb4a9, outline: 0x7c7f89, outlineVariant: 0x45474f,
  surfaceContainerLowest: 0x000000, surfaceContainerLow: 0x141518, surfaceContainer: 0x17181b, surfaceContainerHigh: 0x1c1d21, surfaceContainerHighest: 0x232428,
})

/** Color.desaturated: same hue and lightness, saturation scaled by [kept]. */
function desaturated(c: RGB, kept: number): RGB {
  const [h, s, l] = toHsl(c)
  return s === 0 ? c : hsl(h, Math.min(1, s * kept), l)
}

/** ColorScheme.softened (System theme): accents keep 70 % of their saturation, surfaces 50 %. */
function softened(s: Scheme): Scheme {
  const accents: (keyof Scheme)[] = ['primary', 'primaryContainer', 'inversePrimary', 'secondary', 'secondaryContainer', 'tertiary', 'tertiaryContainer']
  const neutral: (keyof Scheme)[] = ['background', 'onBackground', 'surface', 'onSurface', 'surfaceVariant', 'onSurfaceVariant',
    'surfaceContainerLowest', 'surfaceContainerLow', 'surfaceContainer', 'surfaceContainerHigh', 'surfaceContainerHighest', 'outline', 'outlineVariant']
  const out = { ...s }
  for (const k of accents) out[k] = desaturated(s[k], 0.7)
  for (const k of neutral) out[k] = desaturated(s[k], 0.5)
  return out
}

/** ColorScheme.withCardContrast: the System page one step deeper (light surfaceContainer, dark surfaceContainerLowest). */
function withCardContrast(s: Scheme, dark: boolean): Scheme {
  const page = dark ? s.surfaceContainerLowest : s.surfaceContainer
  return { ...s, background: page, surface: page }
}

/** CustomTheme.tone: [s] scaled by the accent's saturation [k]. */
const tone = (h: number, s: number, l: number, k: number) => hsl(h, Math.min(1, Math.max(0, s * k)), l)
const WHITE: RGB = [1, 1, 1]
const BLACK: RGB = [0, 0, 0]

function customScheme(spec: ThemeSpec, dark: boolean): Scheme {
  const accent = fromInt(spec.customAccent)
  const [h0, s0] = toHsl(accent)
  const h = h0
  const k = Math.min(1.4, Math.max(0, s0 / 0.62))
  const t = (h + 40) % 360
  const o = Object.fromEntries(Object.entries(decodeOverrides(spec.customOverrides)).map(([r, v]) => [r, fromInt(v!)])) as Partial<Record<ColorRole, RGB>>
  const TEXT = 4.5
  const BORDER = 3.0
  if (!dark) {
    const modeText = [tone(h, 0.15, 0.12, k)]
    const card = o.card ? withContrast(o.card, TEXT, modeText) : (() => { const c = tone(h, 0.30, 0.995, k); return o.bg ? withContrast(c, 1.17, [o.bg]) : c })()
    const page = o.bg ? withContrast(o.bg, TEXT, modeText) : withContrast(tone(h, 0.18, 0.93, k), 1.17, [card])
    const sheet = tone(h, 0.20, 0.97, k)
    const surfaces = [page, card, sheet]
    const primary = withContrast(accent, TEXT, surfaces)
    const primaryContainer = tone(h, 0.65, 0.87, k)
    const secondaryContainer = withContrast(tone(h, 0.28, 0.88, k), 1.1, [card])
    const tertiaryContainer = tone(t, 0.60, 0.89, k)
    const text = withContrast(o.text ?? tone(h, 0.15, 0.12, k), TEXT, [...surfaces, tone(h, 0.16, 0.90, k)])
    return {
      primary, onPrimary: withContrast(WHITE, TEXT, [primary]), primaryContainer,
      onPrimaryContainer: withContrast(tone(h, 0.60, 0.16, k), TEXT, [primaryContainer]), inversePrimary: tone(h, 0.60, 0.78, k),
      secondary: withContrast(tone(h, 0.20, 0.36, k), TEXT, surfaces), secondaryContainer,
      onSecondaryContainer: withContrast(tone(h, 0.25, 0.18, k), TEXT, [secondaryContainer]),
      tertiary: withContrast(tone(t, 0.55, 0.36, k), TEXT, surfaces), tertiaryContainer,
      onTertiaryContainer: withContrast(tone(t, 0.60, 0.16, k), TEXT, [tertiaryContainer]),
      background: page, onBackground: text, surface: page, onSurface: text, surfaceVariant: tone(h, 0.16, 0.88, k),
      onSurfaceVariant: withContrast(tone(h, 0.10, 0.34, k), TEXT, [...surfaces, secondaryContainer]),
      inverseSurface: tone(h, 0.12, 0.20, k), inverseOnSurface: tone(h, 0.20, 0.95, k),
      error: withContrast(fromInt(0xb3261e), TEXT, [page]),
      outline: withContrast(tone(h, 0.08, 0.48, k), BORDER, surfaces), outlineVariant: tone(h, 0.14, 0.82, k),
      surfaceContainerLowest: card, surfaceContainerLow: sheet, surfaceContainer: page,
      surfaceContainerHigh: tone(h, 0.16, 0.90, k), surfaceContainerHighest: tone(h, 0.14, 0.87, k),
    }
  }
  const black = spec.customBase === 'black'
  const modeText = [tone(h, 0.12, 0.90, k)]
  const page = o.bg ? withContrast(o.bg, TEXT, modeText) : black ? BLACK : tone(h, 0.15, 0.05, k)
  const card = o.card ? withContrast(o.card, TEXT, modeText)
    : black && !o.bg ? tone(h, 0.08, 0.065, k) : withContrast(tone(h, 0.14, 0.12, k), 1.2, [page])
  const sheet = black ? tone(h, 0.08, 0.08, k) : tone(h, 0.14, 0.09, k)
  const chip = withContrast(black ? tone(h, 0.12, 0.15, k) : tone(h, 0.16, 0.21, k), 1.1, [card])
  const surfaces = [page, card, sheet]
  const primary = withContrast(accent, TEXT, surfaces)
  const primaryContainer = tone(h, 0.45, 0.28, k)
  const tertiaryContainer = tone(t, 0.35, 0.28, k)
  const text = withContrast(o.text ?? tone(h, 0.12, 0.90, k), TEXT, [...surfaces, chip])
  return {
    primary, onPrimary: withContrast(tone(h, 0.70, 0.14, k), TEXT, [primary]), primaryContainer,
    onPrimaryContainer: withContrast(tone(h, 0.70, 0.90, k), TEXT, [primaryContainer]), inversePrimary: tone(h, 0.55, 0.42, k),
    secondary: withContrast(tone(h, 0.25, 0.76, k), TEXT, surfaces), secondaryContainer: chip,
    onSecondaryContainer: withContrast(tone(h, 0.18, 0.84, k), TEXT, [chip]),
    tertiary: withContrast(tone(t, 0.60, 0.79, k), TEXT, surfaces), tertiaryContainer,
    onTertiaryContainer: withContrast(tone(t, 0.70, 0.90, k), TEXT, [tertiaryContainer]),
    background: page, onBackground: text, surface: page, onSurface: text, surfaceVariant: tone(h, 0.10, 0.20, k),
    onSurfaceVariant: withContrast(tone(h, 0.10, 0.72, k), TEXT, [...surfaces, chip]),
    inverseSurface: tone(h, 0.12, 0.90, k), inverseOnSurface: tone(h, 0.12, 0.12, k),
    error: withContrast(fromInt(0xffb4a9), TEXT, [page]),
    outline: withContrast(tone(h, 0.08, 0.55, k), BORDER, surfaces), outlineVariant: tone(h, 0.10, 0.27, k),
    surfaceContainerLowest: black ? BLACK : tone(h, 0.15, 0.03, k), surfaceContainerLow: sheet, surfaceContainer: card,
    surfaceContainerHigh: chip, surfaceContainerHighest: tone(h, 0.12, black ? 0.18 : 0.25, k),
  }
}

/** CustomTheme.isDark: a background set by hand decides by its brightness, else the base (and the system). */
export function isDark(spec: ThemeSpec, systemDark: boolean): boolean {
  if (spec.mode === 'amoled') return true
  if (spec.mode !== 'custom') return systemDark
  const bg = decodeOverrides(spec.customOverrides).bg
  if (bg !== undefined) return luminance(fromInt(bg)) < 0.18
  return spec.customBase === 'system' ? systemDark : spec.customBase !== 'light'
}

interface Plan { card: RGB; border: RGB; borderWidth: string; highlight: RGB; onHighlight: RGB; highlightText: RGB; favorite: RGB; own: RGB }

/** PlanColors.planColors: highlight amber, favourite red, own violet, each nudged to 3:1 (text 4.5:1). */
function planColors(page: RGB, card: RGB, border: RGB, borderWidth: string, surfaces: RGB[], highlightWish?: RGB, favoriteWish?: RGB): Plan {
  const dark = luminance(page) < 0.18
  const base = highlightWish ?? fromInt(dark ? 0xffb74d : 0xe65100)
  const highlight = withContrast(base, 3, [page, card])
  const onHighlight = withContrast(luminance(highlight) > 0.4 ? fromInt(0x2b1700) : WHITE, 4.5, [highlight])
  const all = [page, card, ...surfaces]
  const favorite = withContrast(favoriteWish ?? fromInt(dark ? 0xff5c6c : 0xd3203a), 3, all)
  const own = withContrast(fromInt(dark ? 0xd59cf0 : 0x8e24aa), 3, all)
  return { card, border, borderWidth, highlight, onHighlight, highlightText: withContrast(base, 4.5, [page, card]), favorite, own }
}

export interface Resolved { dark: boolean; vars: Record<string, string>; primary: number; page: number }

/** The scheme and plan colours of [spec], as CSS variables (styles.css uses them). */
export function resolve(spec: ThemeSpec, systemDark: boolean): Resolved {
  const dark = isDark(spec, systemDark)
  let s: Scheme
  let p: Plan
  const favSurfaces = (x: Scheme) => [x.surface, x.surfaceContainerLow]
  switch (spec.mode) {
    case 'soft':
      s = dark ? SOFT_DARK : SOFT_LIGHT
      p = planColors(s.background, dark ? s.surfaceContainer : s.surfaceContainerLowest, s.outlineVariant, '1px', favSurfaces(s))
      break
    case 'amoled':
      s = AMOLED
      p = planColors(s.background, fromInt(0x0f0f11), fromInt(0x4f7fd9), '1px', favSurfaces(s))
      break
    case 'custom': {
      s = customScheme(spec, dark)
      const card = dark ? s.surfaceContainer : s.surfaceContainerLowest
      const o = Object.fromEntries(Object.entries(decodeOverrides(spec.customOverrides)).map(([r, v]) => [r, fromInt(v!)])) as Partial<Record<ColorRole, RGB>>
      const edge = withContrast(o.border ?? fromInt(spec.customAccent), 3, [s.background, card])
      p = planColors(s.background, card, spec.customGlow ? edge : o.border ?? s.outlineVariant, spec.customGlow ? '1px' : '1px', favSurfaces(s), o.highlight, o.favorite)
      break
    }
    default:
      s = withCardContrast(softened(dark ? SYSTEM_DARK : SYSTEM_LIGHT), dark)
      p = planColors(s.background, dark ? s.surfaceContainer : s.surfaceContainerLowest, s.outlineVariant, '1px', favSurfaces(s))
  }
  // withReadableSnackbar: the snackbar's action 4.5:1 on its container.
  const inversePrimary = withContrast(s.inversePrimary, 4.5, [s.inverseSurface])
  const c = css
  const vars: Record<string, string> = {
    '--primary': c(s.primary), '--on-primary': c(s.onPrimary), '--primary-container': c(s.primaryContainer),
    '--on-primary-container': c(s.onPrimaryContainer), '--secondary-container': c(s.secondaryContainer),
    '--on-secondary-container': c(s.onSecondaryContainer), '--tertiary': c(s.tertiary),
    '--bg': c(s.background), '--on-bg': c(s.onBackground), '--surface-variant': c(s.surfaceVariant),
    '--on-surface-variant': c(s.onSurfaceVariant), '--outline': c(s.outline), '--outline-variant': c(s.outlineVariant),
    '--inverse-surface': c(s.inverseSurface), '--inverse-on-surface': c(s.inverseOnSurface), '--inverse-primary': c(inversePrimary),
    '--surface-container-lowest': c(s.surfaceContainerLowest), '--surface-container-low': c(s.surfaceContainerLow),
    '--surface-container': c(s.surfaceContainer), '--surface-container-high': c(s.surfaceContainerHigh),
    '--surface-container-highest': c(s.surfaceContainerHighest), '--card': c(p.card), '--card-border': c(p.border),
    '--highlight': c(p.highlight), '--on-highlight': c(p.onHighlight), '--highlight-text': c(p.highlightText),
    '--favorite': c(p.favorite), '--own': c(p.own), '--error': c(s.error),
    '--scrim': dark ? 'rgb(0 0 0 / 0.55)' : 'rgb(0 0 0 / 0.32)',
  }
  return { dark, vars, primary: toInt(s.primary), page: toInt(s.background) }
}

/** Applies [spec] to the document: CSS variables, color-scheme, the theme-color meta, the block page. */
export function applyTheme(spec: ThemeSpec, systemDark: boolean): Resolved {
  const r = resolve(spec, systemDark)
  const root = document.documentElement
  for (const [k, v] of Object.entries(r.vars)) root.style.setProperty(k, v)
  root.style.colorScheme = r.dark ? 'dark' : 'light'
  root.dataset.theme = r.dark ? 'dark' : 'light'
  document.querySelectorAll('meta[name="theme-color"]').forEach((m) => m.setAttribute('content', r.vars['--bg']))
  setThemePage(r.page)
  return r
}

/** The effective colours the advanced editor shows per role (what the theme made of the wishes). */
export function effectiveRoles(spec: ThemeSpec, systemDark: boolean): Record<ColorRole | 'accent', number> {
  const v = resolve(spec, systemDark).vars
  const n = (s: string) => toInt(s.match(/\d+/g)!.slice(0, 3).map((x) => Number(x) / 255) as RGB)
  return {
    accent: n(v['--primary']), bg: n(v['--bg']), card: n(v['--card']), text: n(v['--on-bg']),
    border: n(v['--card-border']), highlight: n(v['--highlight']), favorite: n(v['--favorite']),
  }
}

/** ColorInput.hue / withHue / hueToAccent. */
export const hueOf = (rgb: number) => Math.floor(((toHsl(fromInt(rgb))[0] % 360) + 360) % 360)
export function withHue(rgb: number, hue: number): number {
  const [, s, l] = toHsl(fromInt(rgb))
  return toInt(s < 0.08 ? hsl(hue, 0.62, 0.52) : hsl(hue, s, l))
}
export const hueToAccent = (hue: number) => toInt(hsl(hue, 0.62, 0.52))

export { contrast, lerp }
