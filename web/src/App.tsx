import { useCallback, useEffect, useMemo, useRef, useState } from 'preact/hooks'
import type { Item, PersonInfo, SharePeek } from './core'
import { type Model, useModel } from './model'
import { addDays, dateShort, dayAndDate, isoWeek, mondayOf, plural, t, weekdayShort } from './i18n'
import { save } from './store'
import { WeekGrid, useNowMinutes } from './components/WeekGrid'
import { DayList } from './components/DayList'
import { Strip } from './components/Strip'
import { Detail } from './components/Detail'
import { People } from './components/People'
import { Settings } from './components/Settings'
import { QrShow } from './components/QrShow'
import { Scanner } from './components/Scanner'
import { Button, Dialog, Icon, IconButton, pickFile } from './components/ui'

const ME = 'me'
const MIME = 'application/vnd.luwify.stundenplan'
const DAY_MS = 86400000

function useMedia(query: string): boolean {
  const [on, set] = useState(() => window.matchMedia(query).matches)
  useEffect(() => {
    const m = window.matchMedia(query)
    const f = () => set(m.matches)
    m.addEventListener('change', f)
    return () => m.removeEventListener('change', f)
  }, [query])
  return on
}

/** Shares a file through the share sheet where the browser can, else saves it as a download. */
async function shareOrSave(bytes: Uint8Array | string, name: string, type: string): Promise<'shared' | 'saved' | 'cancelled'> {
  const file = new File([typeof bytes === 'string' ? bytes : new Uint8Array(bytes as Uint8Array)], name, { type })
  try {
    if (navigator.canShare?.({ files: [file] })) {
      await navigator.share({ files: [file] })
      return 'shared'
    }
  } catch (e) {
    if ((e as DOMException)?.name === 'AbortError') return 'cancelled'
    // Some browsers refuse a type: fall back to a download.
  }
  const url = URL.createObjectURL(file)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10000)
  return 'saved'
}

type Pending = { data: Uint8Array | string; peek: Extract<SharePeek, { name: string }> }

export function App({ model }: { model: Model }) {
  useModel(model)
  const core = model.core
  const dark = useMedia('(prefers-color-scheme: dark)')
  const wide = useMedia('(min-width: 1000px)')
  const narrow = useMedia('(max-width: 599px)')
  const nowMin = useNowMinutes()

  const people = core.people()
  const [personId, setPersonId] = useState(ME)
  const person: PersonInfo | undefined = people.find((p) => p.id === personId) ?? people.find((p) => p.isMe)
  const pid = person?.id ?? ME
  const nav = useMemo(() => core.navigation(pid), [core, pid, model.version])
  const [tab, setTab] = useState<'week' | 'day'>('week')
  const [monday, setMonday] = useState<string | null>(null)
  const [day, setDay] = useState<string | null>(null)

  // Open this person's week to open (or keep the week while switching people when it is in their range).
  useEffect(() => {
    if (!nav) return
    setMonday((m) => (m && nav.weeks.includes(m) ? m : nav.open))
    setDay((d) => (d && nav.weeks.includes(mondayOf(d)) ? d : nav.day))
  }, [nav])

  const shownMonday = monday && nav?.weeks.includes(monday) ? monday : nav?.open ?? null
  const week = useMemo(() => (shownMonday ? core.week(pid, shownMonday) : null), [core, pid, shownMonday, model.version])
  const dayMonday = day ? mondayOf(day) : null
  const dayWeek = useMemo(() => (dayMonday && nav?.weeks.includes(dayMonday) ? core.week(pid, dayMonday) : null), [core, pid, dayMonday, model.version])
  const shownDay = dayWeek?.days.find((d) => d.date === day) ?? dayWeek?.days[0] ?? null

  const [detail, setDetail] = useState<Item | null>(null)
  const [drawer, setDrawer] = useState(false)
  const [settings, setSettings] = useState(false)
  const [adding, setAdding] = useState(false)
  const [scanning, setScanning] = useState(false)
  const [qrFor, setQrFor] = useState<string | null>(null)
  const [pending, setPending] = useState<Pending | null>(null)
  const [restore, setRestore] = useState<{ text: string; exportedAt: string | null; people: number } | null>(null)
  const [nameFor, setNameFor] = useState<null | (() => void)>(null)
  const [friendMenu, setFriendMenu] = useState(false)
  const [installHidden, setInstallHidden] = useState(() => {
    try { return localStorage.getItem('installHintHidden') === '1' } catch { return false }
  })

  const anyDialog = !!(detail || settings || adding || scanning || qrFor || pending || restore || nameFor || friendMenu || (drawer && !wide))

  // ---- navigation
  const weeks = nav?.weeks ?? []
  const step = useCallback(
    (dir: -1 | 1) => {
      if (!nav) return
      if (tab === 'week') {
        const i = weeks.indexOf(shownMonday ?? nav.open)
        const next = weeks[i + dir]
        if (next) setMonday(next)
      } else {
        const days = dayWeek?.days.map((d) => d.date) ?? []
        const i = days.indexOf(shownDay?.date ?? '')
        if (days[i + dir]) return setDay(days[i + dir])
        const m = weeks[weeks.indexOf(dayMonday ?? '') + dir]
        if (!m) return
        const w = core.week(pid, m)
        const list = w?.days.map((d) => d.date) ?? []
        setDay(dir > 0 ? list[0] : list[list.length - 1])
      }
    },
    [nav, tab, weeks, shownMonday, dayWeek, shownDay, dayMonday, core, pid],
  )

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && drawer && !wide) { setDrawer(false); return }
      if (anyDialog || e.altKey || e.ctrlKey || e.metaKey) return
      const target = e.target as HTMLElement
      if (target?.closest('input, textarea, [role="tablist"]')) return
      if (e.key === 'ArrowLeft') { e.preventDefault(); step(-1) }
      if (e.key === 'ArrowRight') { e.preventDefault(); step(1) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [step, anyDialog, drawer, wide])

  // Horizontal swipe on the content switches week/day.
  const swipe = useRef<{ x: number; y: number; id: number } | null>(null)
  const onPointerDown = (e: PointerEvent) => {
    if (e.pointerType === 'mouse') return
    swipe.current = { x: e.clientX, y: e.clientY, id: e.pointerId }
  }
  const onPointerUp = (e: PointerEvent) => {
    const s = swipe.current
    swipe.current = null
    if (!s || s.id !== e.pointerId) return
    const dx = e.clientX - s.x
    const dy = e.clientY - s.y
    if (Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy) * 1.5) step(dx < 0 ? 1 : -1)
  }

  // ---- actions
  const hasMyPlan = !!people.find((p) => p.isMe)?.hasPlan

  const importIcs = async () => {
    const f = await pickFile('.ics,text/calendar')
    if (!f) return
    let text: string
    try { text = await f.text() } catch { return model.notify(t('import_failed')) }
    const before = model.snapshot()
    const r = core.importIcs(text)
    if ('error' in r) return model.notify(r.error === 'notCalendar' ? t('import_not_calendar') : t('import_no_events'))
    await model.commit({ ics: text })
    setPersonId(ME)
    model.notify(plural('imported', r.events), { label: t('undo'), run: () => model.restoreSnapshot(before) })
  }

  const offerShare = (data: Uint8Array | string) => {
    const peek = core.peekShare(data)
    if ('error' in peek) return model.notify(peek.error === 'tooNew' ? t('share_too_new') : t('share_broken'))
    if (peek.own) return model.notify(t('share_own'))
    setPending({ data, peek })
  }

  const addFromFile = async () => {
    setAdding(false)
    setScanning(false)
    const f = await pickFile(null)
    if (!f) return
    const bytes = new Uint8Array(await f.arrayBuffer())
    const head = new TextDecoder().decode(bytes.slice(0, 64))
    if (/BEGIN:VCALENDAR/i.test(head)) return model.notify(t('friend_ics_not_supported'))
    offerShare(bytes)
  }

  const confirmFriend = async () => {
    const p = pending
    setPending(null)
    if (!p) return
    const before = model.snapshot()
    const r = core.importShare(p.data)
    if ('error' in r) return model.notify(r.error === 'own' ? t('share_own') : r.error === 'tooNew' ? t('share_too_new') : t('share_broken'))
    await model.commit()
    setPersonId(r.id)
    model.notify(t(r.replaced ? 'friend_updated' : 'friend_added', r.name), {
      label: t('undo'),
      run: () => { setPersonId(ME); model.restoreSnapshot(before) },
    })
  }

  const withName = (then: () => void) => {
    if (!hasMyPlan) return model.notify(t('no_plan_to_share'))
    if (core.shareIdentity().name) return then()
    setNameFor(() => then)
  }

  const shareFile = (id: string) => {
    const bytes = core.shareFile(id)
    if (!bytes) return model.notify(t('no_plan_to_share'))
    void shareOrSave(bytes, core.shareFileName(id), MIME).then((r) => {
      if (r === 'saved') model.notify(t('file_shared_download', core.shareFileName(id)))
    }).catch(() => model.notify(t('share_failed')))
  }

  const exportBackup = async () => {
    const text = core.backup(model.stored.ics, model.stored.extras)
    const name = `stundenplan-sicherung-${new Date().toISOString().slice(0, 10)}.json`
    try {
      const r = await shareOrSave(text, name, 'application/json')
      if (r === 'cancelled') return
      model.stored.lastBackup = Date.now()
      await save({ lastBackup: model.stored.lastBackup })
      model.notify(t('backup_exported'))
    } catch {
      model.notify(t('backup_failed'))
    }
  }

  const importBackup = async () => {
    const f = await pickFile('.json,application/json')
    if (!f) return
    const text = await f.text()
    const peek = core.peekBackup(text)
    if ('error' in peek) {
      return model.notify(peek.error === 'notABackup' ? t('backup_not_a_backup') : peek.error === 'tooNew' ? t('backup_too_new') : t('backup_failed'))
    }
    if ('ok' in peek && peek.ok) setRestore({ text, exportedAt: peek.exportedAt, people: peek.people })
  }

  const confirmRestore = async () => {
    const r = restore
    setRestore(null)
    setSettings(false)
    if (!r) return
    let ok = false
    await model.withUndo(() => {
      const res = core.restore(r.text)
      if ('ok' in res) { ok = true; return { ics: res.ics, extras: res.extras } }
    }, t('backup_restored'), t('undo'))
    if (!ok) model.notify(t('backup_failed'))
    setPersonId(ME)
  }

  const deleteFriend = async () => {
    if (!person || person.isMe) return
    const name = person.name
    setFriendMenu(false)
    await model.withUndo(() => core.deleteFriend(person.id), t('friend_deleted', name), t('undo'))
    setPersonId(ME)
  }

  // ---- banners
  const hasData = people.some((p) => p.hasPlan)
  const now = Date.now()
  const backupDue = hasData && (model.stored.lastBackup
    ? now - model.stored.lastBackup > 30 * DAY_MS
    : !!model.stored.firstData && now - model.stored.firstData > 7 * DAY_MS)
  const nav2 = navigator as Navigator & { standalone?: boolean }
  const showInstall = !installHidden && 'standalone' in nav2 && nav2.standalone === false && hasData

  // ---- render
  const title = person?.isMe ? t('my_plan') : person?.name ?? ''
  const subtitle = person?.planDate ? t('stand', dateShort(person.planDate)) : null
  const weekItems = weeks.map((m) => ({
    key: m,
    label: t('week_number', isoWeek(m)),
    title: `${dayAndDate(m)} – ${dayAndDate(addDays(m, 4))}`,
    current: nav ? m === mondayOf(nav.today) : false,
  }))
  const dayItems = (dayWeek?.days ?? []).map((d) => ({
    key: d.date,
    label: `${weekdayShort(d.date)} ${Number(d.date.slice(8))}.`,
    current: d.date === nav?.today,
    muted: d.items.length === 0,
  }))

  const peopleList = (
    <People
      people={people}
      selected={pid}
      onSelect={(id) => { setPersonId(id); setDrawer(false) }}
      onFavorite={(p) => { core.setFavorite(p.id, !p.favorite); void model.commit() }}
      onAdd={() => { setDrawer(false); setAdding(true) }}
      onShareFile={() => { setDrawer(false); withName(() => shareFile(ME)) }}
      onQr={() => { setDrawer(false); withName(() => setQrFor(ME)) }}
    />
  )

  const tabs = (
    <div class="segmented tabs" role="tablist" aria-label="View">
      <button type="button" role="tab" aria-selected={tab === 'week'} onClick={() => setTab('week')}>{t('tab_week')}</button>
      <button type="button" role="tab" aria-selected={tab === 'day'} onClick={() => setTab('day')}>{t('tab_day')}</button>
    </div>
  )

  return (
    <div class={`app ${wide ? 'wide' : ''} ${narrow ? 'narrow' : ''}`}>
      {wide && <aside class="sidebar">{peopleList}</aside>}
      <div class="main">
        <header class="topbar">
          {!wide && <IconButton icon="menu" label={t('open_drawer')} onClick={() => setDrawer(true)} />}
          <div class="titles">
            <h1>{title}</h1>
            {subtitle && <span class="subtitle">{subtitle}{person?.fromIcs ? ` · ${t('from_ics')}` : ''}</span>}
          </div>
          {!narrow && tabs}
          <div class="nav-arrows">
            <IconButton icon="chevron_left" label={t(tab === 'week' ? 'previous_week' : 'previous_day')} onClick={() => step(-1)} />
            <IconButton icon="chevron_right" label={t(tab === 'week' ? 'next_week' : 'next_day')} onClick={() => step(1)} />
          </div>
          {person?.isMe ? (
            <IconButton icon="settings" label={t('settings')} onClick={() => setSettings(true)} />
          ) : (
            <IconButton icon="share" label={t('friend_share', person?.name ?? '')} onClick={() => setFriendMenu(true)} />
          )}
        </header>

        {model.updateReady && (
          <div class="banner" role="status">
            <span>{t('update_available')}</span>
            <Button variant="filled" onClick={() => model.updateReady?.()}>{t('update_reload')}</Button>
          </div>
        )}
        {model.stored.rejected && <div class="banner warn" role="status"><Icon name="warning" /><span>{t('data_rejected')}</span></div>}
        {person?.legacyShare && <div class="banner" role="status"><span>{t('legacy_share_hint')}</span></div>}
        {backupDue && (
          <div class="banner" role="status">
            <span>{model.stored.lastBackup ? t('backup_reminder_old', Math.floor((now - model.stored.lastBackup) / DAY_MS)) : t('backup_reminder_never')}</span>
            <Button variant="tonal" onClick={() => void exportBackup()}>{t('backup_reminder_action')}</Button>
          </div>
        )}
        {showInstall && (
          <div class="banner" role="status">
            <span>{t('install_hint')}</span>
            <Button variant="text" onClick={() => { setInstallHidden(true); try { localStorage.setItem('installHintHidden', '1') } catch { /* private mode */ } }}>{t('install_dismiss')}</Button>
          </div>
        )}

        {person && person.hasPlan && nav ? (
          <>
            {tab === 'week' ? (
              <Strip items={weekItems} selected={shownMonday ?? ''} onSelect={setMonday} label={t('tab_week')} />
            ) : (
              <Strip items={dayItems} selected={shownDay?.date ?? ''} onSelect={setDay} label={t('tab_day')} />
            )}
            <main class="content" onPointerDown={onPointerDown} onPointerUp={onPointerUp} onPointerCancel={() => (swipe.current = null)}>
              {tab === 'week' && week && week.free && <div class="week-free">{t('lecture_free')}</div>}
              {tab === 'week' && week ? (
                <WeekGrid week={week} hours={nav.hours} today={nav.today} nowMin={nowMin} dark={dark} onOpen={setDetail} />
              ) : shownDay ? (
                <DayList day={shownDay} dark={dark} onOpen={setDetail} />
              ) : null}
            </main>
          </>
        ) : (
          <main class="content empty">
            <div class="empty-state">
              <img src="./icons/icon-192.png" alt="" width="96" height="96" />
              <h2>{t('empty_title')}</h2>
              <p class="pre">{t('empty_how_to')}</p>
              <div class="row-buttons">
                <Button variant="filled" onClick={() => void importIcs()}>{t('import_file')}</Button>
                <Button variant="tonal" onClick={importBackup}>{t('backup_import')}</Button>
              </div>
            </div>
          </main>
        )}
        {narrow && person?.hasPlan && <footer class="bottombar">{tabs}</footer>}
      </div>

      {!wide && drawer && (
        <div class="scrim" onClick={(e) => e.target === e.currentTarget && setDrawer(false)}>
          <div class="drawer" role="dialog" aria-modal="true" aria-label={t('open_drawer')}>{peopleList}</div>
        </div>
      )}

      {detail && <Detail item={detail} dark={dark} onClose={() => setDetail(null)} />}

      {settings && (
        <Settings
          model={model}
          onClose={() => setSettings(false)}
          onImportIcs={() => { setSettings(false); void importIcs() }}
          onExportBackup={() => void exportBackup()}
          onImportBackup={() => void importBackup()}
          onName={() => setNameFor(() => () => {})}
        />
      )}

      {adding && (
        <Dialog title={t('friend_add_title')} onClose={() => setAdding(false)}>
          <div class="choice-list">
            <button type="button" class="choice" onClick={() => { setAdding(false); setScanning(true) }}>
              <Icon name="qr_code_scanner" />
              <span><strong>{t('scan_title')}</strong><span class="muted">{t('friend_add_scan_hint')}</span></span>
            </button>
            <button type="button" class="choice" onClick={() => void addFromFile()}>
              <Icon name="file_open" />
              <span><strong>{t('friend_add_file')}</strong><span class="muted">.stundenplan</span></span>
            </button>
          </div>
        </Dialog>
      )}

      {scanning && (
        <Scanner
          onClose={() => setScanning(false)}
          onPickFile={() => void addFromFile()}
          onText={(text) => { setScanning(false); offerShare(text) }}
        />
      )}

      {pending && (
        <Dialog
          title={t(pending.peek.known ? 'friend_confirm_update_title' : 'friend_confirm_add_title', pending.peek.name)}
          onClose={() => setPending(null)}
          actions={
            <>
              <Button onClick={() => setPending(null)}>{t('cancel')}</Button>
              <Button variant="filled" onClick={() => void confirmFriend()}>{t(pending.peek.known ? 'friend_update' : 'friend_add')}</Button>
            </>
          }
        >
          <p>{t('friend_confirm_stand', dateShort(pending.peek.planDate))}</p>
          <p class="muted">{t('friend_shared_on', dateShort(pending.peek.exportedOn))}</p>
        </Dialog>
      )}

      {restore && (
        <Dialog
          title={t('backup_confirm_title')}
          onClose={() => setRestore(null)}
          actions={
            <>
              <Button onClick={() => setRestore(null)}>{t('cancel')}</Button>
              <Button variant="filled" onClick={() => void confirmRestore()}>{t('backup_replace')}</Button>
            </>
          }
        >
          {restore.exportedAt && <p>{t('backup_made_at', dateShort(restore.exportedAt.slice(0, 10)))}</p>}
          <p class="muted">{t('backup_hint_web')}</p>
        </Dialog>
      )}

      {nameFor && <NameDialog current={core.shareIdentity().name} onClose={() => setNameFor(null)} onSave={(name) => {
        core.setShareName(name)
        const next = nameFor
        setNameFor(null)
        void model.commit().then(() => next())
      }} />}

      {friendMenu && person && !person.isMe && (
        <Dialog title={person.name} onClose={() => setFriendMenu(false)}>
          {person.planDate && <p class="muted">{t('stand', dateShort(person.planDate))}{person.exportedOn ? ` · ${t('friend_shared_on', dateShort(person.exportedOn))}` : ''}</p>}
          <div class="choice-list">
            <button type="button" class="choice" onClick={() => { setFriendMenu(false); shareFile(person.id) }}>
              <Icon name="share" /><span><strong>{t('share_as_file')}</strong></span>
            </button>
            <button type="button" class="choice" onClick={() => { setFriendMenu(false); setQrFor(person.id) }}>
              <Icon name="qr_code_2" /><span><strong>{t('friend_share_qr')}</strong></span>
            </button>
            <button type="button" class="choice danger" onClick={() => void deleteFriend()}>
              <Icon name="delete" /><span><strong>{t('friend_delete')}</strong></span>
            </button>
          </div>
        </Dialog>
      )}

      {qrFor && (() => {
        const payload = core.qrPayload(qrFor)
        const p = people.find((x) => x.id === qrFor)
        if (!payload) { setQrFor(null); model.notify(t('no_plan_to_share')); return null }
        const name = qrFor === ME ? core.shareIdentity().name ?? '' : p?.name ?? ''
        const stand = qrFor === ME ? p?.planDate ?? null : p?.planDate ?? null
        return (
          <QrShow
            payload={payload}
            name={name}
            stand={stand ? dateShort(stand) : null}
            mode={model.stored.qrMode}
            onMode={(m) => { model.stored.qrMode = m; model.emit(); void save({ qrMode: m }) }}
            onShareFile={() => shareFile(qrFor)}
            onClose={() => setQrFor(null)}
          />
        )
      })()}

      {model.snack && (
        <div class="snackbar" role="status" key={model.snack.id}>
          <span>{model.snack.text}</span>
          {model.snack.action && (
            <button type="button" class="snack-action" onClick={() => { const a = model.snack?.action; model.dismiss(); a?.run() }}>
              {model.snack.action.label}
            </button>
          )}
          <IconButton icon="close" label={t('close')} onClick={() => model.dismiss()} />
        </div>
      )}
    </div>
  )
}

function NameDialog(props: { current: string | null; onClose: () => void; onSave: (name: string) => void }) {
  const [name, setName] = useState(props.current ?? '')
  return (
    <Dialog
      title={t('share_name_title')}
      onClose={props.onClose}
      actions={
        <>
          <Button onClick={props.onClose}>{t('cancel')}</Button>
          <Button variant="filled" disabled={!name.trim()} onClick={() => props.onSave(name.trim())}>{t('save')}</Button>
        </>
      }
    >
      <p class="muted">{t('share_name_hint')}</p>
      <label class="field">
        <span>{t('share_name_label')}</span>
        <input
          autofocus
          value={name}
          maxLength={40}
          onInput={(e) => setName((e.target as HTMLInputElement).value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && name.trim()) props.onSave(name.trim()) }}
        />
      </label>
    </Dialog>
  )
}
