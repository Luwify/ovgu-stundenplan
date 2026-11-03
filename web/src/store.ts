// Everything stays in this browser: one IndexedDB key-value store. Nothing is sent anywhere.
const DB = 'stundenplan-md'
const STORE = 'kv'

let dbPromise: Promise<IDBDatabase> | null = null

function db(): Promise<IDBDatabase> {
  dbPromise ??= new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1)
    req.onupgradeneeded = () => req.result.createObjectStore(STORE)
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
  return dbPromise
}

function tx<T>(mode: IDBTransactionMode, run: (s: IDBObjectStore) => IDBRequest<T> | void): Promise<T | undefined> {
  return db().then(
    (d) =>
      new Promise((resolve, reject) => {
        const t = d.transaction(STORE, mode)
        const req = run(t.objectStore(STORE))
        t.oncomplete = () => resolve(req ? req.result : undefined)
        t.onerror = () => reject(t.error)
        t.onabort = () => reject(t.error)
      }),
  )
}

export interface Stored {
  /** StoredData JSON, the same format the Android app keeps in data.json. */
  state: string | null
  /** The raw .ics of my last import (goes into the backup like the app's me.ics). */
  ics: string | null
  /** Theme, language, update and display settings from a restored Android backup, passed on unchanged. */
  extras: string | null
  /** Text the app could not read (newer format): kept, never overwritten. */
  rejected: string | null
  lastBackup: number | null
  firstData: number | null
  language: 'system' | 'de' | 'en'
  qrMode: 'single' | 'animated' | null
}

const defaults: Stored = {
  state: null, ics: null, extras: null, rejected: null, lastBackup: null, firstData: null, language: 'system', qrMode: null,
}

export async function loadAll(): Promise<Stored> {
  const out = { ...defaults }
  for (const k of Object.keys(defaults) as (keyof Stored)[]) {
    const v = await tx<unknown>('readonly', (s) => s.get(k))
    if (v !== undefined) (out as Record<string, unknown>)[k] = v
  }
  return out
}

export async function save(values: Partial<Stored>): Promise<void> {
  await tx('readwrite', (s) => {
    for (const [k, v] of Object.entries(values)) s.put(v, k)
  })
}

/** Asks the browser to keep the data (Safari: granted for home-screen apps); true if granted. */
export async function persist(): Promise<boolean> {
  try {
    if (!navigator.storage?.persist) return false
    if (await navigator.storage.persisted()) return true
    return await navigator.storage.persist()
  } catch {
    return false
  }
}
