import { useEffect, useState } from 'preact/hooks'
import { rgbHex } from '../colors'
import { plural, t } from '../i18n'
import { type ColorRole, type CustomBase, type ThemeSpec, decodeOverrides, effectiveRoles, encodeOverrides, hueOf, hueToAccent, withHue } from '../theme'
import { ColorChoice, Switch } from './Editors'
import { Button, Dialog, Icon } from './ui'

const BASES: [CustomBase, string][] = [
  ['system', 'custom_base_system'], ['light', 'custom_base_light'], ['dark', 'custom_base_dark'], ['black', 'custom_base_black'],
]
const hueGradient = `linear-gradient(to right, ${Array.from({ length: 13 }, (_, i) => rgbHex(hueToAccent(i * 30))).join(', ')})`

/**
 * The editor of the user's own theme (the app's CustomThemeEditor): background (radio list), accent (hue slider that
 * keeps saturation and lightness, hex and R/G/B fields), the card border, and the advanced colour editor.
 * [onChange] gets (spec, commit): commit false only previews (slider moving).
 */
export function CustomThemeEditor(props: { spec: ThemeSpec; primary: number; onChange: (spec: ThemeSpec, commit: boolean) => void; onAdvanced: () => void }) {
  const s = props.spec
  const count = Object.keys(decodeOverrides(s.customOverrides)).length
  return (
    <div class="custom-theme">
      <h3 class="small-heading">{t('custom_base')}</h3>
      <div class="radio-group" role="radiogroup" aria-label={t('custom_base')}>
        {BASES.map(([b, label]) => (
          <label class="radio-row">
            <input type="radio" name="base" checked={s.customBase === b} onChange={() => props.onChange({ ...s, customBase: b }, true)} />
            <span>{t(label as never)}</span>
          </label>
        ))}
      </div>
      <h3 class="small-heading">{t('custom_accent')}</h3>
      <input
        type="range"
        class="hue-slider"
        min={0}
        max={359}
        value={hueOf(s.customAccent)}
        aria-label={t('custom_hue')}
        aria-valuetext={`${hueOf(s.customAccent)}°`}
        style={{ '--track': hueGradient } as Record<string, string>}
        onInput={(e) => props.onChange({ ...s, customAccent: withHue(s.customAccent, Number((e.target as HTMLInputElement).value)) }, false)}
        onChange={(e) => props.onChange({ ...s, customAccent: withHue(s.customAccent, Number((e.target as HTMLInputElement).value)) }, true)}
      />
      <ColorFields value={s.customAccent} onChange={(rgb) => props.onChange({ ...s, customAccent: rgb }, true)} />
      {props.primary !== s.customAccent && <p class="muted small">{t('custom_accent_adjusted')}</p>}
      <Switch label={t('custom_glow')} hint={t('custom_glow_hint')} checked={s.customGlow} onChange={(on) => props.onChange({ ...s, customGlow: on }, true)} />
      <button type="button" class="advanced-row" onClick={props.onAdvanced}>
        <span class="list-text">
          <span>{t('color_advanced')}</span>
          <span class="muted small">{count === 0 ? t('color_advanced_hint') : plural('color_advanced_count', count)}</span>
        </span>
        <Icon name="chevron_right" />
      </button>
    </div>
  )
}

/** The accent as text: hex and R/G/B, kept in sync; a field applies as soon as it is valid (ColorFields). */
function ColorFields(props: { value: number; onChange: (rgb: number) => void }) {
  const fmt = (v: number) => '#' + v.toString(16).toUpperCase().padStart(6, '0')
  const [hex, setHex] = useState(fmt(props.value))
  const channels = [(props.value >> 16) & 255, (props.value >> 8) & 255, props.value & 255]
  const [texts, setTexts] = useState(channels.map(String))
  useEffect(() => {
    if (parseHex(hex) !== props.value) setHex(fmt(props.value))
    setTexts((old) => old.map((x, i) => (Number(x) === channels[i] && /^\d{1,3}$/.test(x) ? x : String(channels[i]))))
  }, [props.value])
  return (
    <>
      <div class="color-fields">
        <span class="color-dot" style={{ background: rgbHex(props.value) }} />
        <label class={`field grow ${parseHex(hex) === null ? 'invalid' : ''}`}>
          <span>{t('custom_hex')}</span>
          <input value={hex} maxLength={7} autocapitalize="characters" autocomplete="off" spellcheck={false}
            onInput={(e) => { const v = (e.target as HTMLInputElement).value.slice(0, 7); setHex(v); const p = parseHex(v); if (p !== null) props.onChange(p) }} />
        </label>
      </div>
      <div class="field-pair">
        {(['custom_red', 'custom_green', 'custom_blue'] as const).map((label, i) => (
          <label class={`field ${/^\d{1,3}$/.test(texts[i]) && Number(texts[i]) <= 255 ? '' : 'invalid'}`}>
            <span>{t(label)}</span>
            <input inputMode="numeric" value={texts[i]} onInput={(e) => {
              const v = (e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 3)
              setTexts(texts.map((x, j) => (j === i ? v : x)))
              if (v !== '' && Number(v) <= 255) {
                const shift = 16 - 8 * i
                props.onChange((props.value & ~(255 << shift) & 0xffffff) | (Number(v) << shift))
              }
            }} />
          </label>
        ))}
      </div>
    </>
  )
}

const parseHex = (text: string): number | null => {
  const m = /^#?([0-9a-fA-F]{6})$/.exec(text.trim())
  return m ? parseInt(m[1], 16) : null
}

type Entry = { role: ColorRole | null; title: string; hint?: string }
const GENERAL: Entry[] = [
  { role: null, title: 'color_accent', hint: 'color_accent_hint' },
  { role: 'bg', title: 'color_background' },
  { role: 'card', title: 'color_card' },
  { role: 'text', title: 'color_text' },
]
const CARDS: Entry[] = [
  { role: 'border', title: 'color_card_border', hint: 'color_card_border_hint' },
  { role: 'highlight', title: 'color_highlight', hint: 'color_highlight_hint' },
  { role: 'favorite', title: 'color_favorite', hint: 'color_favorite_hint' },
]

/**
 * The advanced colour editor (the app's ColorEditorScreen): every colour the theme generates with its live value
 * ("Automatisch", or "angepasst" when a wish was nudged for readability); a row opens the colour choice.
 */
export function ColorEditor(props: { spec: ThemeSpec; systemDark: boolean; onChange: (spec: ThemeSpec) => void; onClose: () => void }) {
  const s = props.spec
  const overrides = decodeOverrides(s.customOverrides)
  const effective = effectiveRoles(s, props.systemDark)
  const [editing, setEditing] = useState<Entry | null>(null)
  const requested = (e: Entry) => (e.role === null ? s.customAccent : overrides[e.role])
  const set = (e: Entry, rgb: number | null) => {
    if (e.role === null) { if (rgb !== null) props.onChange({ ...s, customAccent: rgb }); return }
    const next = { ...overrides }
    if (rgb === null) delete next[e.role]
    else next[e.role] = rgb
    props.onChange({ ...s, customOverrides: encodeOverrides(next) })
  }
  const row = (e: Entry) => {
    const req = requested(e)
    const eff = effective[e.role ?? 'accent']
    const state = req === undefined ? t('color_auto') : req !== eff ? t('color_adjusted', '#' + req.toString(16).toUpperCase().padStart(6, '0')) : null
    return (
      <button type="button" class="settings-row color-row" onClick={() => setEditing(e)}>
        <span class="color-dot" style={{ background: rgbHex(eff) }} />
        <span class="list-text">
          <span>{t(e.title as never)}</span>
          <span class="muted small">{[e.hint ? t(e.hint as never) : null, state].filter(Boolean).join(' · ')}</span>
        </span>
      </button>
    )
  }
  return (
    <Dialog title={t('color_editor_title')} onClose={props.onClose} wide>
      <h3 class="small-heading">{t('color_section_general')}</h3>
      {GENERAL.map(row)}
      <h3 class="small-heading">{t('color_section_cards')}</h3>
      {CARDS.map(row)}
      {Object.keys(overrides).length > 0 && <Button onClick={() => props.onChange({ ...s, customOverrides: '' })}>{t('color_reset_all')}</Button>}
      {editing && (
        <Dialog
          title={t(editing.title as never)}
          onClose={() => setEditing(null)}
          actions={
            <>
              {editing.role !== null && <Button onClick={() => { set(editing, null); setEditing(null) }}>{t('color_auto')}</Button>}
              <Button variant="filled" onClick={() => setEditing(null)}>{t('ok')}</Button>
            </>
          }
        >
          <ColorChoice value={requested(editing) ?? effective[editing.role ?? 'accent']} onChange={(rgb) => set(editing, rgb)} />
        </Dialog>
      )}
    </Dialog>
  )
}
