// Texts built from core data the same way the Android app builds them (HiddenUi.hiddenName, DetailSheet.rhythm,
// MainScreen.duplicateText, Format.changeLines).
import type { HiddenItem, Item, Rhythm, WeekRhythm } from './core'
import { dayAndDate, plural, t, timeRange, weekdayShort } from './i18n'

/** "Mo" for ISO day 1..7. */
const weekdayOfIso = (day: number) => weekdayShort(`2024-01-0${day}`) // 2024-01-01 is a Monday

/** "Mo 9–11" */
export function slotText(h: { day: number | null; start: string | null; end: string | null }): string {
  return [h.day ? weekdayOfIso(h.day) : null, h.start && h.end ? timeRange(h.start, h.end) : null].filter(Boolean).join(' ')
}

/** What a hidden item is called ("Numerik (Übung)", "Numerik, Gruppe Mo 9–11", ...). */
export function hiddenName(h: HiddenItem): string {
  switch (h.kind) {
    case 'SUBJECT': return h.title
    case 'COURSE': return t('hidden_named_type', h.title, h.type ?? '')
    case 'GROUP': return t('hidden_named_group', h.title, slotText(h))
    case 'DATE': return t('hidden_named_date', h.title, h.date ? dayAndDate(h.date) : '')
  }
}

/** "In geraden KW am Mo bis Mi 27.1." etc. */
export function rhythmText(r: Rhythm | null): string | null {
  if (!r) return null
  const day = weekdayShort(r.first)
  const base = (r.kind === 'even' ? t('rhythm_even_weeks', day)
    : r.kind === 'odd' ? t('rhythm_odd_weeks', day)
    : r.kind === '14' ? t('rhythm_14_days', day)
    : r.kind === 'weekly' ? t('rhythm_weekly', day)
    : t('rhythm_n_weeks', r.interval, day)) + (r.byMe ? ' ' + t('rhythm_by_me') : '')
  return r.until ? t('rhythm_until', base, dayAndDate(r.until)) : base
}

export function weekRhythmName(r: WeekRhythm | null): string {
  return r === 'EVEN_WEEKS' ? t('manual_even_weeks') : r === 'ODD_WEEKS' ? t('manual_odd_weeks') : t('manual_biweekly')
}

/** The room line of the detail: "K18-012 (K18-013) · Gebäude K18 · 60 Plätze". */
export function roomText(it: Item): string {
  if (!it.location && !it.room) return t('no_room')
  if (it.personal?.roomChanged && !it.personal.overruled) return it.room ?? t('no_room')
  const room = it.location ? (it.room ?? '') : it.room ?? ''
  return [
    room + (it.alias ? ` (${it.alias})` : ''),
    it.building ? t('building', it.building) : null,
    it.capacity != null ? plural('seats', it.capacity) : null,
  ].filter(Boolean).join(' · ')
}

/** "Raum geändert: ..." lines of a moved date. */
export function changeLines(it: Item): string[] {
  const none = t('no_room')
  return it.changes.map((ch) => {
    switch (ch.kind) {
      case 'day': return t('change_day', dayAndDate(ch.now!), dayAndDate(ch.before!))
      case 'time': return t('change_time', ch.now!, ch.before!)
      case 'room': return t('change_room', ch.now ?? none, ch.before ?? none)
      case 'lecturer': return t('change_lecturer', ch.now || '–')
    }
  })
}

/** "Schon in deinem Plan: Mi 13–15, K09B-110" (at most two). */
export function duplicateText(d: { day: string; start: string; end: string; room: string | null }[]): string {
  const slots = [...d].sort((a, b) => (a.day + a.start < b.day + b.start ? -1 : 1)).slice(0, 2)
    .map((x) => `${weekdayShort(x.day)} ${timeRange(x.start, x.end)}` + (x.room ? `, ${x.room}` : '')).join('; ') + (d.length > 2 ? ' …' : '')
  return t('already_in_plan', slots)
}

/** "9:15" from "09:15" or "09:15:00". */
export const timeInput = (s: string) => `${Number(s.slice(0, 2))}:${s.slice(3, 5)}`

/** The app's parseTime: "9", "9:15", "9.15" -> "09:15", else null. */
export function parseTime(text: string): string | null {
  const m = /^(\d{1,2})(?::(\d{2}))?$/.exec(text.trim().replace('.', ':'))
  if (!m) return null
  const h = Number(m[1])
  const min = Number(m[2] ?? '0')
  return h <= 23 && min <= 59 ? `${String(h).padStart(2, '0')}:${String(min).padStart(2, '0')}` : null
}
