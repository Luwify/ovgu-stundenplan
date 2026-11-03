// Typed access to the Kotlin/JS build of :core (src/generated/core, see scripts/core.mjs). Everything that touches
// the formats (plan data, .ics, shares, frames, backups) runs there, the same code as in the Android app.
import * as core from './generated/core/stundenplan-core.mjs'

export type NowLine =
  | { kind: 'in'; room: string | null; until: string }
  | { kind: 'freeUntil'; room: string | null; from: string }
  | { kind: 'freeToday' }

export interface PersonInfo {
  id: string
  name: string
  sharedName: string
  localName: string | null
  isMe: boolean
  favorite: boolean
  fromIcs: boolean
  planDate: string | null
  exportedOn: string | null
  hasPlan: boolean
  legacyShare: boolean
  now: NowLine | null
}

export interface Nav {
  weeks: string[]
  open: string
  day: string
  hours: [number, number]
  today: string
}

export interface ChangeLine {
  kind: 'day' | 'time' | 'room' | 'lecturer'
  now: string | null
  before?: string | null
}

export interface TogetherRef {
  id: string
  me?: boolean
  name?: string | null
  favorite?: boolean
}

export interface Item {
  uid: string
  date: string
  start: string
  end: string
  startMin: number
  endMin: number
  column: number
  columns: number
  name: string
  title: string
  summary: string
  subject: string | null
  typeShort: string
  typeName: string
  category: string
  room: string | null
  location: string | null
  lecturers: string[]
  notes: string
  noteHint: 'NONE' | 'SHORTENED' | 'NOT_SENT'
  personalNote: string | null
  st: boolean
  manual: boolean
  color: number
  status: 'regular' | 'changed' | 'extra' | 'cancelled' | 'cancelledByMe'
  changes: ChangeLine[]
  together: TogetherRef[]
  /** The web app's key of this date (UID, start, kind). */
  key: string
  ref: { uid: string; date: string }
  courseKey: string
  subjectKey: string | null
  shortName: string | null
  building: string | null
  alias: string | null
  capacity: number | null
  /** A moved date: the regular slot. */
  original: { date: string; start: string; end: string; room: string | null } | null
  personal: Personal | null
  ownChange: boolean
  rhythm: Rhythm | null
}

export interface Personal {
  cancelled: boolean
  changesPlan: boolean
  overruled: boolean
  roomChanged: boolean
  timeChanged: boolean
  room: string | null
  originalRoom: string | null
  start: string
  end: string
  originalStart: string
  originalEnd: string
  seriesFrom: string | null
  note: string | null
  noteFromSeries: boolean
  st: boolean
}

export interface Rhythm {
  kind: 'even' | 'odd' | '14' | 'weekly' | 'n'
  interval: number
  first: string
  until: string | null
  byMe: boolean
}

export interface Gap {
  start: string
  end: string
  minutes: number
  startMin: number
  endMin: number
  together: TogetherRef[]
}

export type HideKind = 'date' | 'subject' | 'type' | 'group'
export type FriendState = 'ADDABLE' | 'ADDED' | 'IN_PLAN'

export interface DetailInfo {
  hide: { kind: HideKind; type?: string }[]
  friendState: FriendState | null
  added: boolean
  manualId: string | null
  lsfCancelled: boolean
  cancelledForMe: boolean
  editor: {
    canSeries: boolean
    refDate: string
    seriesDate: string
    seriesTime: string
    note: string
    st: boolean
    room: string
    originalRoom: string | null
    start: string
    end: string
  }
}

export interface HiddenItem {
  kind: 'SUBJECT' | 'COURSE' | 'GROUP' | 'DATE'
  title: string
  type: string | null
  day: number | null
  start: string | null
  end: string | null
  date: string | null
  room: string | null
}

export interface HiddenGroup {
  personId: string
  name: string
  isMe: boolean
  items: HiddenItem[]
}

export type DateState = 'ON' | 'CANCELLED' | 'HIDDEN'
export interface PickDate { key: string; date: string; start: string; end: string; room: string | null; state: DateState }

export interface Orphan {
  index: number
  title: string
  date: string
  series: boolean
  until: string | null
  cancelled: boolean
  room: string | null
  start: string | null
  end: string | null
  note: string | null
  st: boolean | null
}

export type WeekRhythm = 'EVEN_WEEKS' | 'ODD_WEEKS' | 'EVERY_14_DAYS'

export interface SubjectInfo {
  key: string
  name: string
  displayName: string
  shortName: string | null
  color: number
  auto: boolean
  hidden: boolean
  typeShortNames: Record<string, string>
  courses: {
    key: string
    title: string
    typeShort: string
    typeName: string
    courseId: string | null
    manualId: string | null
    added: boolean
    series: { uid: string; first: string; start: string; end: string; chosen: WeekRhythm | null; auto: WeekRhythm | null }[]
  }[]
}

export type ManualRhythm = 'ONCE' | 'WEEKLY' | 'EVEN_WEEKS' | 'ODD_WEEKS' | 'BIWEEKLY'
export type ManualType = 'LECTURE' | 'EXERCISE' | 'LAB' | 'SEMINAR' | 'OTHER'

/** A manual course as stored (data.json). */
export interface ManualCourse {
  id: string
  title: string
  type: ManualType
  room: string | null
  lecturer: string | null
  note: string
  st: boolean
  rhythm: ManualRhythm
  startDate: string
  endDate: string | null
  start: string
  end: string
  skipFreeWeeks: boolean
}

export type IcsRead =
  | { route: 'replace' | 'askReplaceOrAdd' | 'askFriendOrAdd'; canBeMine: boolean; currentCourses: number; incomingCourses: number; icsFriends: { id: string; name: string }[] }
  | { error: 'notCalendar' | 'noEvents' }

export interface Day {
  date: string
  free: boolean
  items: Item[]
  gaps: Gap[]
}

export interface Week {
  monday: string
  free: boolean
  days: Day[]
}

export type ShareError = { error: 'notAShare' | 'tooNew' | 'broken' | 'own'; reason?: string }
export type SharePeek = { name: string; planDate: string; exportedOn: string; events: number; known: boolean; own: boolean } | ShareError
export type ShareImport = { ok: true; id: string; name: string; replaced: boolean } | ShareError
export type IcsImport = { ok: true; events: number; dropped: number } | { error: 'notCalendar' | 'noEvents' }
export type Restore =
  | { ok: true; ics: string | null; extras: string; exportedAt: string | null; people: number }
  | { error: 'notABackup' | 'tooNew' | 'broken'; reason?: string }
export type BackupPeek = { ok: true; people: number; exportedAt: string | null; hasPlan: boolean } | Restore

const u8 = (b: Int8Array): Uint8Array => new Uint8Array(b.buffer, b.byteOffset, b.byteLength)
const i8 = (b: Uint8Array): Int8Array => new Int8Array(b.buffer, b.byteOffset, b.byteLength)

/** One plan state (StoredData). Methods that change it return; the caller persists [stateJson]. */
export class Core {
  private readonly c: core.WebCore

  /** Editing (StoreEdits). */
  readonly edit: Edits

  constructor(stateJson: string | null) {
    this.c = new core.WebCore(stateJson)
    this.edit = edits(this.c)
  }

  /** The stored text when it couldn't be read (newer format or broken); the app starts empty and keeps it. */
  get rejected(): string | null {
    return this.c.rejected ?? null
  }

  stateJson(): string {
    return this.c.stateJson()
  }

  people(now = Date.now()): PersonInfo[] {
    return JSON.parse(this.c.people(now))
  }

  navigation(personId: string, now = Date.now()): Nav | null {
    return JSON.parse(this.c.navigation(personId, now))
  }

  week(personId: string, monday: string): Week | null {
    return JSON.parse(this.c.week(personId, monday))
  }

  importIcs(text: string, now = Date.now()): IcsImport {
    return JSON.parse(this.c.importIcs(text, now))
  }

  peekShare(data: Uint8Array | string): SharePeek {
    return JSON.parse(typeof data === 'string' ? this.c.peekShareText(data) : this.c.peekShareBytes(i8(data)))
  }

  importShare(data: Uint8Array | string, now = Date.now()): ShareImport {
    return JSON.parse(typeof data === 'string' ? this.c.importShareText(data, now) : this.c.importShareBytes(i8(data), now))
  }

  deleteFriend(id: string) {
    this.c.deleteFriend(id)
  }

  setFavorite(id: string, favorite: boolean) {
    this.c.setFavorite(id, favorite)
  }

  shareIdentity(): { id: string | null; name: string | null; withChanges: boolean } {
    return JSON.parse(this.c.shareIdentity())
  }

  setShareName(name: string) {
    this.c.setShareName(name, randomHex16())
  }

  qrPayload(personId: string, now = Date.now()): Uint8Array | null {
    const p = this.c.qrPayload(personId, now)
    return p ? u8(p) : null
  }

  shareFile(personId: string, now = Date.now()): Uint8Array | null {
    const p = this.c.shareFile(personId, now)
    return p ? u8(p) : null
  }

  shareFileName(personId: string): string {
    return this.c.shareFileName(personId)
  }

  backup(ics: string | null, extras: string | null, now = Date.now()): string {
    return this.c.backup(ics, extras, now)
  }

  peekBackup(text: string): BackupPeek {
    return JSON.parse(this.c.peekBackup(text))
  }

  restore(text: string): Restore {
    return JSON.parse(this.c.restore(text))
  }
}

/** 16 random hex digits (ids of the share identity, .ics friends, manual courses). */
export function randomHex16(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8))
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

const json = <T>(s: string | null | undefined): T | null => (s == null ? null : JSON.parse(s))

/** The editing part of WebCore (StoreEdits on the Kotlin side); every call changes the state, the caller persists it. */
export interface Edits {
  detail(personId: string, key: string): DetailInfo | null
  hide(personId: string, key: string, kind: HideKind): HiddenItem | null
  hideSubject(personId: string, subjectKey: string): HiddenItem | null
  hideCourse(personId: string, courseKey: string): HiddenItem | null
  hidden(): HiddenGroup[]
  unhide(personId: string, index: number): void
  setCancelledForMe(key: string, cancelled: boolean): { title: string; date: string } | null
  savePersonal(key: string, series: boolean, note: string, st: boolean, room: string, start: string, end: string): boolean
  pickDates(key: string): PickDate[]
  setDates(key: string, states: Record<string, DateState>): void
  orphans(): Orphan[]
  deleteChange(index: number): void
  setShareChanges(on: boolean): void
  subjects(personId: string): SubjectInfo[]
  setSubjectStyle(personId: string, subjectKey: string, shortName: string | null, color: number | null, typed: Record<string, string | null>): void
  moveCourse(personId: string, courseKey: string, target: string): void
  setRhythm(personId: string, uid: string, rhythm: WeekRhythm | null): void
  manualCourse(id: string): ManualCourse | null
  newManualId(): string
  manualDefaultEnd(start: string): string
  saveManual(course: ManualCourse, subjectKey: string | null, shortName: string | null, color: number | null): boolean
  deleteManual(id: string): string | null
  removeAdded(key: string): string | null
  removeAddedCourse(courseKey: string): string | null
  addFromFriend(personId: string, key: string): string | null
  removeFriendGroup(personId: string, key: string): string | null
  renameFriend(id: string, name: string | null): void
  reorderFriends(ids: string[]): void
  readIcs(text: string, fromSettings: boolean): IcsRead
  icsReplaceMine(now?: number): IcsImport & { text?: string }
  icsAddCourses(): { added: number; updated: number; duplicates: { title: string; day: string; start: string; end: string; room: string | null }[] }
  icsAddFriend(name: string, existingId: string | null, now?: number): { id: string; name: string; replaced: boolean }
  cancelIcs(): void
  qrPayloadAs(personId: string, name: string, now?: number): Uint8Array | null
  shareFileAs(personId: string, name: string, now?: number): Uint8Array | null
}

export function edits(c: core.WebCore): Edits {
  return {
    detail: (p, k) => json(c.detail(p, k)),
    hide: (p, k, kind) => json(c.hide(p, k, kind)),
    hideSubject: (p, s) => json(c.hideSubject(p, s)),
    hideCourse: (p, k) => json(c.hideCourse(p, k)),
    hidden: () => JSON.parse(c.hidden()),
    unhide: (p, i) => c.unhide(p, i),
    setCancelledForMe: (k, on) => json(c.setCancelledForMe(k, on)),
    savePersonal: (k, series, note, st, room, start, end) => c.savePersonal(k, series, note, st, room, start, end),
    pickDates: (k) => JSON.parse(c.pickDates(k)),
    setDates: (k, states) => c.setDates(k, JSON.stringify(states)),
    orphans: () => JSON.parse(c.orphans()),
    deleteChange: (i) => c.deleteChange(i),
    setShareChanges: (on) => c.setShareChanges(on),
    subjects: (p) => JSON.parse(c.subjects(p)),
    setSubjectStyle: (p, s, short, color, typed) => c.setSubjectStyle(p, s, short, color ?? -1, JSON.stringify(typed)),
    moveCourse: (p, k, target) => c.moveCourse(p, k, target),
    setRhythm: (p, uid, r) => c.setRhythm(p, uid, r),
    manualCourse: (id) => json(c.manualCourse(id)),
    newManualId: () => c.newManualId(randomHex16()),
    manualDefaultEnd: (start) => c.manualDefaultEnd(start),
    saveManual: (course, subjectKey, short, color) => c.saveManual(JSON.stringify(course), subjectKey, short, color ?? -1),
    deleteManual: (id) => c.deleteManual(id) ?? null,
    removeAdded: (k) => c.removeAdded(k) ?? null,
    removeAddedCourse: (k) => c.removeAddedCourse(k) ?? null,
    addFromFriend: (p, k) => c.addFromFriend(p, k) ?? null,
    removeFriendGroup: (p, k) => c.removeFriendGroup(p, k) ?? null,
    renameFriend: (id, name) => c.renameFriend(id, name),
    reorderFriends: (ids) => c.reorderFriends(JSON.stringify(ids)),
    readIcs: (text, fromSettings) => JSON.parse(c.readIcs(text, fromSettings)),
    icsReplaceMine: (now = Date.now()) => JSON.parse(c.icsReplaceMine(now)),
    icsAddCourses: () => JSON.parse(c.icsAddCourses()),
    icsAddFriend: (name, existingId, now = Date.now()) => JSON.parse(c.icsAddFriend(name, existingId, randomHex16(), now)),
    cancelIcs: () => c.cancelIcs(),
    qrPayloadAs: (p, name, now = Date.now()) => { const b = c.qrPayloadAs(p, name, now); return b ? u8(b) : null },
    shareFileAs: (p, name, now = Date.now()) => { const b = c.shareFileAs(p, name, now); return b ? u8(b) : null },
  }
}

export class Frames {
  private readonly e: core.FrameEncoder
  constructor(payload: Uint8Array) {
    this.e = new core.FrameEncoder(i8(payload))
  }
  get blocks(): number {
    return this.e.blocks
  }
  frame(seq: number): string {
    return this.e.frame(seq)
  }
}

export class FrameCollector {
  private d = new core.FrameDecoder()
  /** Progress 0..1, or -1 when [text] is no frame of an animated code. */
  offer(text: string): number {
    return this.d.offer(text)
  }
  result(): string | null {
    return this.d.result() ?? null
  }
  reset() {
    this.d = new core.FrameDecoder()
  }
}

export const qrText = (payload: Uint8Array): string => core.qrText(i8(payload))
export const isFrame = (text: string): boolean => core.isFrame(text)
