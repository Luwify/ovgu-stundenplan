import { appStrings } from './generated/strings'

/** Strings only the web app has (the Android app has no install hint, reload banner, backup reminder). */
const webStrings = {
  de: {
    update_available: 'Neue Version verfügbar',
    update_reload: 'Neu laden',
    backup_reminder_never: 'Du hast noch keine Sicherung exportiert. Die Daten liegen nur in diesem Browser.',
    backup_reminder_old: 'Deine letzte Sicherung ist {1} Tage alt.',
    backup_reminder_action: 'Jetzt sichern',
    backup_last: 'Letzte Sicherung: {1}',
    backup_never: 'Noch keine Sicherung exportiert.',
    backup_hint_web:
      'Eine Datei mit deinem Stundenplan, deinen Freunden und Einstellungen, im selben Format wie die Android-App: Du kannst sie dort und hier importieren. Es wird nichts hochgeladen.',
    install_hint:
      'Tipp: Über „Teilen“ → „Zum Home-Bildschirm“ wird Stundenplan MD zur App. Dann bleiben die Daten zuverlässig erhalten (Safari löscht sonst nach 7 Tagen ohne Besuch).',
    storage_kept: 'Daten werden dauerhaft in diesem Browser gespeichert.',
    storage_not_kept: 'Der Browser kann die Daten bei Speichermangel löschen. Exportiere regelmäßig eine Sicherung.',
    language_de: 'Deutsch',
    language_en: 'English',
    scan_camera_failed: 'Die Kamera konnte nicht gestartet werden.',
    scan_file_instead: 'Stattdessen Datei auswählen',
    about_title: 'Über',
    about_text: 'Web-Version von Stundenplan MD. Keine Server, kein Tracking: Alles bleibt in diesem Browser.',
    data_rejected: 'Gespeicherte Daten einer neueren Version konnten nicht gelesen werden. Sie bleiben unverändert erhalten.',
    previous_week: 'Vorherige Woche',
    next_week: 'Nächste Woche',
    previous_day: 'Vorheriger Tag',
    next_day: 'Nächster Tag',
    stand: 'Stand: {1}',
    file_shared_download: 'Datei gespeichert: {1}',
    me: 'Ich',
    detail_type: 'Typ',
    install_dismiss: 'Ausblenden',
    ok: 'OK',
    reorder_handle: 'Ziehen oder Pfeiltasten zum Sortieren',
  },
  en: {
    update_available: 'New version available',
    update_reload: 'Reload',
    backup_reminder_never: "You haven't exported a backup yet. Your data exists only in this browser.",
    backup_reminder_old: 'Your last backup is {1} days old.',
    backup_reminder_action: 'Back up now',
    backup_last: 'Last backup: {1}',
    backup_never: 'No backup exported yet.',
    backup_hint_web:
      'One file with your timetable, friends and settings, in the same format as the Android app: you can import it there and here. Nothing is uploaded.',
    install_hint:
      'Tip: "Share" → "Add to Home Screen" turns Stundenplan MD into an app. Then the data is kept reliably (otherwise Safari deletes it after 7 days without a visit).',
    storage_kept: 'Data is stored persistently in this browser.',
    storage_not_kept: 'The browser may delete the data when space runs low. Export a backup regularly.',
    language_de: 'Deutsch',
    language_en: 'English',
    scan_camera_failed: "The camera couldn't be started.",
    scan_file_instead: 'Pick a file instead',
    about_title: 'About',
    about_text: 'Web version of Stundenplan MD. No servers, no tracking: everything stays in this browser.',
    data_rejected: 'Saved data of a newer version could not be read. It is kept unchanged.',
    previous_week: 'Previous week',
    next_week: 'Next week',
    previous_day: 'Previous day',
    next_day: 'Next day',
    stand: 'As of {1}',
    file_shared_download: 'File saved: {1}',
    me: 'Me',
    detail_type: 'Type',
    install_dismiss: 'Dismiss',
    ok: 'OK',
    reorder_handle: 'Drag or use the arrow keys to reorder',
  },
} as const

export type Lang = 'de' | 'en'
type AppKey = keyof (typeof appStrings)['en']
type WebKey = keyof (typeof webStrings)['en']
export type Key = AppKey | WebKey

let lang: Lang = 'de'

/** German when the browser prefers it (or by choice), English otherwise; missing German strings fall back to English. */
export function resolveLang(choice: 'system' | 'de' | 'en'): Lang {
  if (choice !== 'system') return choice
  const prefs = navigator.languages?.length ? navigator.languages : [navigator.language]
  for (const p of prefs) {
    if (p.toLowerCase().startsWith('de')) return 'de'
    if (p.toLowerCase().startsWith('en')) return 'en'
  }
  return 'de'
}

export function setLang(l: Lang) {
  lang = l
  document.documentElement.lang = l
}

export const currentLang = () => lang

function fill(s: string, args: (string | number)[]): string {
  return s.replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i) - 1] ?? ''))
}

function raw(key: Key): unknown {
  const a = appStrings[lang] as Record<string, unknown>
  const w = webStrings[lang] as Record<string, unknown>
  return a[key] ?? w[key] ?? (appStrings.en as Record<string, unknown>)[key] ?? (webStrings.en as Record<string, unknown>)[key] ?? key
}

export function t(key: Key, ...args: (string | number)[]): string {
  const v = raw(key)
  return fill(typeof v === 'string' ? v : String((v as Record<string, string>).other ?? key), args)
}

/** Android plurals: "one" for 1, else "other" (both languages). */
export function plural(key: AppKey, n: number, ...args: (string | number)[]): string {
  const v = raw(key) as Record<string, string> | string
  const s = typeof v === 'string' ? v : (n === 1 ? v.one : undefined) ?? v.other
  return fill(s, [n, ...args])
}

const parse = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y, m - 1, d)
}

export const weekdayShort = (iso: string) =>
  new Intl.DateTimeFormat(lang, { weekday: 'short' }).format(parse(iso)).replace(/\.$/, '')

/** "Mo 12.10." like the app. */
export const dayAndDate = (iso: string) => {
  const d = parse(iso)
  return `${weekdayShort(iso)} ${d.getDate()}.${d.getMonth() + 1}.`
}

/** "Montag, 12. Oktober" / "Monday, 12. October" like the app's longDate. */
export const longDate = (iso: string) => {
  const d = parse(iso)
  const weekday = new Intl.DateTimeFormat(lang, { weekday: 'long' }).format(d)
  const month = new Intl.DateTimeFormat(lang, { month: 'long' }).format(d)
  return `${weekday}, ${d.getDate()}. ${month}`
}

export const dateShort = (iso: string) => {
  const d = parse(iso)
  return `${d.getDate()}.${d.getMonth() + 1}.${d.getFullYear()}`
}

/** ISO week number of a Monday (ISO date). */
export function isoWeek(iso: string): number {
  const d = parse(iso)
  const thursday = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 3 - ((d.getDay() + 6) % 7))
  const jan4 = new Date(thursday.getFullYear(), 0, 4)
  return 1 + Math.round(((thursday.getTime() - jan4.getTime()) / 86400000 - 3 + ((jan4.getDay() + 6) % 7)) / 7)
}

/** "13–15" for full hours, else "13:15–14:45" (the app's timeRange). */
export function timeRange(start: string, end: string): string {
  const short = (s: string) => String(Number(s.slice(0, 2)))
  return start.endsWith(':00') && end.endsWith(':00') ? `${short(start)}–${short(end)}` : `${hm(start)}–${hm(end)}`
}

export const hm = (s: string) => `${Number(s.slice(0, 2))}:${s.slice(3, 5)}`

/** "45 min", "2 h", "1 h 45 min". */
export const durationText = (m: number) => (m < 60 ? `${m} min` : m % 60 === 0 ? `${m / 60} h` : `${Math.floor(m / 60)} h ${m % 60} min`)

export function addDays(iso: string, n: number): string {
  const d = parse(iso)
  d.setDate(d.getDate() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export const mondayOf = (iso: string) => addDays(iso, -((parse(iso).getDay() + 6) % 7))
