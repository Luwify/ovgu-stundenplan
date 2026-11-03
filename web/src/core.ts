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
}

export interface Gap {
  start: string
  end: string
  minutes: number
}

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

  constructor(stateJson: string | null) {
    this.c = new core.WebCore(stateJson)
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

  shareIdentity(): { id: string | null; name: string | null } {
    return JSON.parse(this.c.shareIdentity())
  }

  setShareName(name: string) {
    const bytes = crypto.getRandomValues(new Uint8Array(8))
    this.c.setShareName(name, Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join(''))
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
