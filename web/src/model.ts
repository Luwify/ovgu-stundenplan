import { useEffect, useState } from 'preact/hooks'
import { Core } from './core'
import { persist, save, type Stored } from './store'
import { resolveLang, setLang, t } from './i18n'
import { DEFAULT_THEME, type ThemeSpec } from './theme'

export interface Snapshot {
  state: string
  ics: string | null
  extras: string | null
}

export interface Snack {
  id: number
  text: string
  action?: { label: string; run: () => void }
}

/** App state around one [Core]: persistence after every change, undo snapshots, snackbars. */
export class Model {
  core: Core
  stored: Stored
  version = 0
  snack: Snack | null = null
  updateReady: (() => void) | null = null
  persisted: boolean | null = null
  private listeners = new Set<() => void>()
  private snackTimer: ReturnType<typeof setTimeout> | undefined

  constructor(stored: Stored) {
    this.stored = stored
    this.core = new Core(stored.state)
    if (this.core.rejected && !stored.rejected) {
      // A newer app version's data: keep it untouched, start empty.
      this.stored.rejected = this.core.rejected
      void save({ rejected: this.core.rejected })
    }
    setLang(resolveLang(stored.language))
    void navigator.storage?.persisted?.().then((p) => { this.persisted = p; this.emit() }).catch(() => {})
  }

  subscribe(f: () => void) {
    this.listeners.add(f)
    return () => this.listeners.delete(f)
  }

  emit() {
    this.version++
    this.listeners.forEach((f) => f())
  }

  /** Stores the plan state (and [extra] keys) after a change; asks for persistent storage once there is data. */
  async commit(extra: Partial<Stored> = {}) {
    const state = this.core.stateJson()
    const values: Partial<Stored> = { state, ...extra }
    if (!this.stored.firstData) values.firstData = Date.now()
    Object.assign(this.stored, values)
    this.emit()
    await save(values)
    if (!this.persisted) {
      this.persisted = await persist()
      this.emit()
    }
  }

  snapshot(): Snapshot {
    return { state: this.core.stateJson(), ics: this.stored.ics, extras: this.stored.extras }
  }

  restoreSnapshot(s: Snapshot) {
    this.core = new Core(s.state)
    void this.commit({ ics: s.ics, extras: s.extras })
  }

  /** Runs [change]; the snackbar's undo puts the previous state (and ics/extras) back. */
  async withUndo(change: () => Partial<Stored> | void, message: string, undoLabel: string) {
    const before = this.snapshot()
    const extra = change() || {}
    await this.commit(extra)
    this.notify(message, { label: undoLabel, run: () => this.restoreSnapshot(before) })
  }

  /** After a change made since [before]: stores it and shows [message] with "Rückgängig" (back to [before]). */
  async changed(before: Snapshot, message: string, onUndo?: () => void) {
    await this.commit()
    this.notify(message, { label: t('undo'), run: () => { this.restoreSnapshot(before); onUndo?.() } })
  }

  notify(text: string, action?: Snack['action']) {
    clearTimeout(this.snackTimer)
    this.snack = { id: Date.now(), text, action }
    this.emit()
    this.snackTimer = setTimeout(() => this.dismiss(), action ? 10000 : 4000)
  }

  dismiss() {
    this.snack = null
    this.emit()
  }

  /** The backup extras (theme, language, updates, display) as an object; what the web doesn't use is kept. */
  private extras(): Record<string, unknown> {
    try { return this.stored.extras ? JSON.parse(this.stored.extras) : {} } catch { return {} }
  }

  private async setExtras(next: Record<string, unknown>) {
    const extras = JSON.stringify(next)
    this.stored.extras = extras
    this.emit()
    await save({ extras })
  }

  /** The theme as the app's backup stores it (ThemeBackup); Soft until one is chosen or restored. */
  get theme(): ThemeSpec {
    const t = this.extras().theme as Partial<ThemeSpec> | undefined
    return t && typeof t.mode === 'string' ? { ...DEFAULT_THEME, ...t } as ThemeSpec : DEFAULT_THEME
  }

  async setTheme(spec: ThemeSpec) {
    await this.setExtras({ ...this.extras(), theme: spec })
  }

  /** "Querformat an Bildschirm anpassen" (DisplayBackup.fitLandscape, default on). */
  get fitLandscape(): boolean {
    const d = this.extras().display as { fitLandscape?: boolean } | undefined
    return d?.fitLandscape ?? true
  }

  async setFitLandscape(on: boolean) {
    const e = this.extras()
    await this.setExtras({ ...e, display: { mensaBannerHidden: null, ...(e.display as object | undefined), fitLandscape: on } })
  }

  /** The extras for a backup: the theme shown here goes with it (Soft when none was chosen). */
  backupExtras(): string {
    return JSON.stringify({ ...this.extras(), theme: this.theme })
  }

  async setLanguage(choice: Stored['language']) {
    setLang(resolveLang(choice))
    this.stored.language = choice
    this.emit()
    await save({ language: choice })
  }
}

/** Re-renders on every model change. */
export function useModel(model: Model): number {
  const [, set] = useState(0)
  useEffect(() => {
    const off = model.subscribe(() => set((v) => v + 1))
    return () => { off() }
  }, [model])
  return model.version
}
