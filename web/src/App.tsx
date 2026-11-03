import { useCallback, useEffect, useMemo, useRef, useState } from 'preact/hooks'
import type { DateState, IcsRead, Item, ManualCourse, PersonInfo, PickDate, SharePeek } from './core'
import { type Model, useModel } from './model'
import { addDays, currentLang, dateShort, dayAndDate, isoWeek, mondayOf, plural, t, weekdayShort } from './i18n'
import { save } from './store'
import { WeekGrid, useNowMinutes } from './components/WeekGrid'
import { DayList } from './components/DayList'
import { Strip } from './components/Strip'
import { Detail, type DetailActions } from './components/Detail'
import { ManualEditor, PersonalEditor, PickDates, SubjectEditor } from './components/Editors'
import { HiddenScreen } from './components/Hidden'
import { IcsDialog } from './components/IcsDialog'
import { FriendDialog, ShareNameChoice } from './components/FriendDialogs'
import { MenuButton } from './components/Menu'
import { Snackbar } from './components/Snackbar'
import { dayAndDate as dayAndDateText } from './i18n'
import { duplicateText, hiddenName } from './texts'
import { People } from './components/People'
import { Settings } from './components/Settings'
import { QrShow } from './components/QrShow'
import { Scanner } from './components/Scanner'
import { Button, Dialog, Icon, IconButton, pickFile } from './components/ui'
import { palette } from './colors'
import { applyTheme, type ThemeSpec } from './theme'

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
  const systemDark = useMedia('(prefers-color-scheme: dark)')
  const landscape = useMedia('(orientation: landscape)')
  // The theme (as the app's backup stores it); a custom theme previews while its hue slider moves.
  const [themeDraft, setThemeDraft] = useState<ThemeSpec | null>(null)
  const shownTheme = themeDraft ?? model.theme
  const resolved = useMemo(() => applyTheme(shownTheme, systemDark), [JSON.stringify(shownTheme), systemDark])
  const dark = resolved.dark
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

  // The detail sheet follows its date through every change (looked up by the date's ref in its week).
  const [detailRef, setDetailRef] = useState<{ pid: string; uid: string; date: string; monday: string } | null>(null)
  const detail: Item | null = useMemo(() => {
    if (!detailRef) return null
    const w = core.week(detailRef.pid, detailRef.monday)
    for (const d of w?.days ?? []) for (const it of d.items) if (it.ref.uid === detailRef.uid && it.ref.date === detailRef.date) return it
    return null
  }, [detailRef, core, model.version])
  const openDetail = (it: Item) => setDetailRef({ pid, uid: it.ref.uid, date: it.ref.date, monday: mondayOf(it.date) })
  const [subjectEdit, setSubjectEdit] = useState<{ pid: string; subjectKey: string; courseKey: string | null } | null>(null)
  const [personalEdit, setPersonalEdit] = useState<{ key: string } | null>(null)
  const [pickEdit, setPickEdit] = useState<{ key: string; name: string; dates: PickDate[] } | null>(null)
  const [manualEdit, setManualEdit] = useState<{ id: string | null } | null>(null)
  const [showHidden, setShowHidden] = useState(false)
  const [icsRead, setIcsRead] = useState<Extract<IcsRead, { route: string }> | null>(null)
  /** An editor opened from the sheet hides it while open; it comes back when the editor closes. */
  const editorOpen = !!(subjectEdit || personalEdit || pickEdit || manualEdit)
  const [drawer, setDrawer] = useState(false)
  const [settings, setSettings] = useState(false)
  const [adding, setAdding] = useState(false)
  const [scanning, setScanning] = useState(false)
  const [qrFor, setQrFor] = useState<string | null>(null)
  /** A friend's plan shown as QR under this name (their shared one or my local one). */
  const [qrName, setQrName] = useState<string | null>(null)
  const [shareAs, setShareAs] = useState<{ person: PersonInfo; how: 'file' | 'qr' } | null>(null)
  const [pending, setPending] = useState<Pending | null>(null)
  const [restore, setRestore] = useState<{ text: string; exportedAt: string | null; people: number } | null>(null)
  const [nameFor, setNameFor] = useState<null | (() => void)>(null)
  const [friendMenu, setFriendMenu] = useState(false)
  const [shortcuts, setShortcuts] = useState(false)
  const [installHidden, setInstallHidden] = useState(() => {
    try { return localStorage.getItem('installHintHidden') === '1' } catch { return false }
  })

  const anyDialog = !!(detail || editorOpen || showHidden || icsRead || settings || adding || scanning || qrFor || pending || restore || nameFor || friendMenu || shareAs || shortcuts || (drawer && !wide))

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
      if (target?.closest('button, a, [role="option"], [role="menuitem"]') && (e.key === ' ' || e.key === 'Enter')) return
      const ids = people.map((p) => p.id)
      const go = (f: () => void) => { e.preventDefault(); f() }
      switch (e.key) {
        case 'ArrowLeft': return go(() => step(-1))
        case 'ArrowRight': return go(() => step(1))
        // People: the app's vertical pager.
        case 'PageUp': return go(() => setPersonId(ids[Math.max(0, ids.indexOf(pid) - 1)] ?? ME))
        case 'PageDown': return go(() => setPersonId(ids[Math.min(ids.length - 1, ids.indexOf(pid) + 1)] ?? ME))
        case 'w': case 'W': return go(() => setTab('week'))
        case 'd': case 'D': case 't': case 'T': return go(() => setTab('day'))
        case 'h': case 'H': case 'Home': return go(() => { if (nav) { setMonday(nav.open); setDay(nav.day) } })
        case 'n': case 'N': return go(() => { if (pid === ME && people.find((p) => p.isMe)?.hasPlan) setManualEdit({ id: null }) })
        case ',': return go(() => setSettings(true))
        case '?': return go(() => setShortcuts(true))
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [step, anyDialog, drawer, wide, people, pid, nav])

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

  /** An .ics from the settings or the empty plan ([fromSettings]) or from the drawer's "+": routed like the app. */
  const handleIcs = async (text: string, fromSettings: boolean) => {
    const r = core.edit.readIcs(text, fromSettings)
    if ('error' in r) return model.notify(r.error === 'notCalendar' ? t('import_not_calendar') : t('import_no_events'))
    if (r.route === 'replace') return replaceMine()
    setIcsRead(r)
  }

  const replaceMine = async () => {
    setIcsRead(null)
    const before = model.snapshot()
    const r = core.edit.icsReplaceMine()
    if ('error' in r) return model.notify(t('import_no_events'))
    await model.commit({ ics: r.text ?? null })
    setPersonId(ME)
    const text = plural('imported', r.events) + (r.dropped > 0 ? ' ' + plural('added_dropped', r.dropped) : '')
    model.notify(text, { label: t('undo'), run: () => model.restoreSnapshot(before) })
  }

  const icsAddCourses = async () => {
    setIcsRead(null)
    const before = model.snapshot()
    const r = core.edit.icsAddCourses()
    const parts = [
      r.added > 0 ? plural('courses_added', r.added) : null,
      r.updated > 0 ? plural('courses_updated', r.updated) : null,
      r.duplicates.length ? duplicateText(r.duplicates) : null,
    ].filter(Boolean).join(' ')
    setPersonId(ME)
    if (r.added + r.updated === 0) { await model.commit(); return model.notify(parts) }
    await model.changed(before, parts)
  }

  const icsAddFriend = async (name: string, existingId: string | null) => {
    setIcsRead(null)
    const before = model.snapshot()
    const r = core.edit.icsAddFriend(name, existingId)
    setPersonId(r.id)
    await model.changed(before, t(r.replaced ? 'friend_updated' : 'friend_added', r.name), () => setPersonId(ME))
  }

  const importIcs = async () => {
    const f = await pickFile('.ics,text/calendar')
    if (!f) return
    let text: string
    try { text = await f.text() } catch { return model.notify(t('import_failed')) }
    await handleIcs(text, true)
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
    if (/BEGIN:VCALENDAR/i.test(head)) return handleIcs(new TextDecoder().decode(bytes), false)
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

  /** Passes a friend's plan on as they shared it; a renamed friend asks which name goes into the share first. */
  const forward = (p: PersonInfo, how: 'file' | 'qr') => {
    if (p.localName && p.localName !== p.sharedName) return setShareAs({ person: p, how })
    sendForward(p, how, p.sharedName)
  }
  const sendForward = (p: PersonInfo, how: 'file' | 'qr', name: string) => {
    if (how === 'qr') { setQrName(name); setQrFor(p.id); return }
    const bytes = core.edit.shareFileAs(p.id, name)
    if (!bytes) return model.notify(t('no_plan_to_share'))
    const fileName = `Stundenplan ${name.replace(/[^\p{L}\p{N} _-]/gu, '').trim() || 'Stundenplan'}.stundenplan`
    void shareOrSave(bytes, fileName, MIME).then((r) => {
      if (r === 'saved') model.notify(t('file_shared_download', fileName))
    }).catch(() => model.notify(t('share_failed')))
  }

  const exportBackup = async () => {
    const text = core.backup(model.stored.ics, model.backupExtras())
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

  // ---- detail and editors (the app's MainScreen/MainViewModel flows)
  const isMe = !!person?.isMe
  const detailInfo = useMemo(() => (detail && detailRef ? core.edit.detail(detailRef.pid, detail.key) : null), [detail, detailRef, core, model.version])
  const closeDetail = () => setDetailRef(null)
  const act: DetailActions = {
    editSubject: () => detail?.subjectKey && setSubjectEdit({ pid, subjectKey: detail.subjectKey, courseKey: detail.courseKey }),
    editPersonal: () => detail && setPersonalEdit({ key: detail.key }),
    cancelForMe: (on) => {
      if (!detail) return
      const before = model.snapshot()
      const r = core.edit.setCancelledForMe(detail.key, on)
      if (r) void model.changed(before, t(on ? 'cancelled_for_me' : 'uncancelled_for_me', r.title, dayAndDateText(r.date)))
    },
    pickDates: () => detail && setPickEdit({ key: detail.key, name: detail.name, dates: core.edit.pickDates(detail.key) }),
    editManual: (id) => setManualEdit({ id }),
    deleteManual: (id) => deleteManual(id),
    removeAdded: () => {
      if (!detail) return
      const before = model.snapshot()
      const title = core.edit.removeAdded(detail.key)
      if (title) { closeDetail(); void model.changed(before, t('added_removed', title)) }
    },
    hide: (kind) => {
      if (!detail || !detailRef) return
      const before = model.snapshot()
      const h = core.edit.hide(detailRef.pid, detail.key, kind)
      if (h) { closeDetail(); void model.changed(before, t('hidden_snackbar', hiddenName(h))) }
    },
    addToMine: () => {
      if (!detail || !detailRef) return
      const before = model.snapshot()
      const title = core.edit.addFromFriend(detailRef.pid, detail.key)
      if (title) void model.changed(before, t('added_from_friend', title))
    },
    removeFromMine: () => {
      if (!detail || !detailRef) return
      const before = model.snapshot()
      const title = core.edit.removeFriendGroup(detailRef.pid, detail.key)
      if (title) void model.changed(before, t('added_removed', title))
    },
  }
  const deleteManual = (id: string) => {
    const before = model.snapshot()
    const title = core.edit.deleteManual(id)
    if (title) { closeDetail(); void model.changed(before, t('manual_deleted', title)) }
  }
  const hideFromEditor = (h: ReturnType<typeof core.edit.hideSubject>, before: ReturnType<typeof model.snapshot>) => {
    if (!h) return
    setSubjectEdit(null)
    closeDetail()
    void model.changed(before, t('hidden_snackbar', hiddenName(h)))
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
      onReorder={(ids) => { core.edit.reorderFriends(ids); void model.commit() }}
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
          {person?.isMe && hasMyPlan && <IconButton icon="add" label={t('manual_add')} onClick={() => setManualEdit({ id: null })} />}
          {person?.isMe ? (
            <IconButton icon="settings" label={t('settings')} onClick={() => setSettings(true)} />
          ) : person && (
            <>
              <IconButton
                icon={person.favorite ? 'favorite_filled' : 'favorite'}
                class={person.favorite ? 'fav' : ''}
                label={t('favorite_toggle', person.name)}
                pressed={person.favorite}
                onClick={() => { core.setFavorite(person.id, !person.favorite); void model.commit() }}
              />
              <MenuButton
                class="icon-menu"
                align="end"
                label={t('friend_share', person.name)}
                entries={[
                  { label: t('share_as_file'), onClick: () => forward(person, 'file') },
                  { label: t('friend_share_qr'), onClick: () => forward(person, 'qr') },
                ]}
              >
                {() => <span class="icon-button"><Icon name="share" /></span>}
              </MenuButton>
              <IconButton icon="edit" label={t('friend_details')} onClick={() => setFriendMenu(true)} />
            </>
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
                <WeekGrid week={week} fit={model.fitLandscape && landscape} hours={nav.hours} today={nav.today} nowMin={nowMin} dark={dark} onOpen={openDetail} />
              ) : shownDay ? (
                <DayList day={shownDay} dark={dark} onOpen={openDetail} />
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

      {settings && (
        <Settings
          model={model}
          onClose={() => setSettings(false)}
          onImportIcs={() => { setSettings(false); void importIcs() }}
          onExportBackup={() => void exportBackup()}
          onImportBackup={() => void importBackup()}
          onName={() => setNameFor(() => () => {})}
          onEditSubject={(k) => setSubjectEdit({ pid: ME, subjectKey: k, courseKey: null })}
          onShowHidden={() => setShowHidden(true)}
          systemDark={systemDark}
          primary={resolved.primary}
          draft={themeDraft}
          onDraft={setThemeDraft}
          onDeleteOrphan={(i) => { const b = model.snapshot(); core.edit.deleteChange(i); void model.changed(b, t('orphan_deleted')) }}
        />
      )}

      {detail && !editorOpen && (
        <Detail item={detail} info={detailInfo} who={detailRef?.pid === ME ? null : people.find((p) => p.id === detailRef?.pid)?.name ?? ''} dark={dark} act={act} onClose={closeDetail} />
      )}

      {subjectEdit && (
        <SubjectEditor
          key={subjectEdit.subjectKey}
          subjects={core.edit.subjects(subjectEdit.pid)}
          subjectKey={subjectEdit.subjectKey}
          courseKey={subjectEdit.courseKey}
          isMe={subjectEdit.pid === ME}
          dark={dark}
          onSave={(short, color, typed) => {
            core.edit.setSubjectStyle(subjectEdit.pid, subjectEdit.subjectKey, short, color, typed)
            setSubjectEdit(null)
            void model.commit()
          }}
          onMove={(courseKey, target) => {
            core.edit.moveCourse(subjectEdit.pid, courseKey, target)
            void model.commit()
            // The subject may be gone (its last course moved): follow the course.
            const s = core.edit.subjects(subjectEdit.pid).find((x) => x.courses.some((c) => c.key === courseKey))
            if (!core.edit.subjects(subjectEdit.pid).some((x) => x.key === subjectEdit.subjectKey)) setSubjectEdit(s ? { ...subjectEdit, subjectKey: s.key, courseKey } : null)
          }}
          onRhythm={(uid, r) => { core.edit.setRhythm(subjectEdit.pid, uid, r); void model.commit() }}
          onHideSubject={() => { const b = model.snapshot(); hideFromEditor(core.edit.hideSubject(subjectEdit.pid, subjectEdit.subjectKey), b) }}
          onHideCourse={(k) => { const b = model.snapshot(); hideFromEditor(core.edit.hideCourse(subjectEdit.pid, k), b) }}
          onEditManual={(id) => { setSubjectEdit(null); setManualEdit({ id }) }}
          onDeleteManual={(id) => { setSubjectEdit(null); deleteManual(id) }}
          onRemoveAdded={(k) => {
            setSubjectEdit(null)
            const before = model.snapshot()
            const title = core.edit.removeAddedCourse(k)
            if (title) { closeDetail(); void model.changed(before, t('added_removed', title)) }
          }}
          onClose={() => setSubjectEdit(null)}
        />
      )}

      {personalEdit && detailInfo && (
        <PersonalEditor
          editor={detailInfo.editor}
          onSave={(series, note, st, room, start, end) => {
            core.edit.savePersonal(personalEdit.key, series, note, st, room, start, end)
            setPersonalEdit(null)
            void model.commit()
          }}
          onClose={() => setPersonalEdit(null)}
        />
      )}

      {pickEdit && (
        <PickDates
          name={pickEdit.name}
          dates={pickEdit.dates}
          onSave={(states: Record<string, DateState>) => {
            if (Object.keys(states).length) { core.edit.setDates(pickEdit.key, states); void model.commit() }
            setPickEdit(null)
          }}
          onClose={() => setPickEdit(null)}
        />
      )}

      {manualEdit && (() => {
        const subjects = core.edit.subjects(ME)
        const existing: ManualCourse | null = manualEdit.id ? core.edit.manualCourse(manualEdit.id) : null
        const subject = existing ? subjects.find((x) => x.courses.some((c) => c.key === `manual:${existing.id}`)) ?? null : null
        return (
          <ManualEditor
            existing={existing}
            existingSubject={subject?.key ?? null}
            existingShort={subject?.shortName ?? null}
            existingColor={subject && !subject.auto ? subject.color : null}
            subjects={subjects}
            newId={() => core.edit.newManualId()}
            defaultEnd={(d) => core.edit.manualDefaultEnd(d)}
            today={nav?.today ?? new Date().toISOString().slice(0, 10)}
            autoColor={palette[subjects.length % palette.length]}
            onSave={(course, subjectKey, short, color) => {
              if (core.edit.saveManual(course, subjectKey, short, color)) void model.commit()
              setManualEdit(null)
            }}
            onClose={() => setManualEdit(null)}
          />
        )
      })()}

      {showHidden && (
        <HiddenScreen
          groups={core.edit.hidden()}
          onRestore={(p, i) => { core.edit.unhide(p, i); void model.commit() }}
          onClose={() => setShowHidden(false)}
        />
      )}

      {icsRead && (
        <IcsDialog
          read={icsRead}
          onReplace={() => void replaceMine()}
          onAddCourses={() => void icsAddCourses()}
          onFriend={(name, id) => void icsAddFriend(name, id)}
          onCancel={() => { core.edit.cancelIcs(); setIcsRead(null) }}
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
        <FriendDialog
          person={person}
          onRename={(name) => { core.edit.renameFriend(person.id, name); setFriendMenu(false); void model.commit() }}
          onDelete={() => void deleteFriend()}
          onClose={() => setFriendMenu(false)}
        />
      )}

      {shareAs && (
        <ShareNameChoice
          original={shareAs.person.sharedName}
          local={shareAs.person.localName ?? ''}
          onChoose={(name) => { const s = shareAs; setShareAs(null); sendForward(s.person, s.how, name) }}
          onClose={() => setShareAs(null)}
        />
      )}

      {qrFor && (() => {
        const payload = qrFor !== ME && qrName ? core.edit.qrPayloadAs(qrFor, qrName) : core.qrPayload(qrFor)
        const p = people.find((x) => x.id === qrFor)
        if (!payload) { setQrFor(null); model.notify(t('no_plan_to_share')); return null }
        const name = qrFor === ME ? core.shareIdentity().name ?? '' : qrName ?? p?.sharedName ?? ''
        const stand = qrFor === ME ? p?.planDate ?? null : p?.planDate ?? null
        return (
          <QrShow
            payload={payload}
            name={name}
            stand={stand ? dateShort(stand) : null}
            mode={model.stored.qrMode}
            onMode={(m) => { model.stored.qrMode = m; model.emit(); void save({ qrMode: m }) }}
            onShareFile={() => { const p = people.find((x) => x.id === qrFor); if (qrFor !== ME && p) sendForward(p, 'file', qrName ?? p.sharedName); else shareFile(qrFor) }}
            onClose={() => { setQrFor(null); setQrName(null) }}
          />
        )
      })()}

      {shortcuts && (
        <Dialog title={t('shortcuts_title')} onClose={() => setShortcuts(false)}>
          <dl class="shortcuts">
            {([
              ['← →', `${t('previous_week')} / ${t('next_week')}`],
              ['W / D', `${t('tab_week')} / ${t('tab_day')}`],
              ['H', t('back_to_this_week')],
              [currentLang() === 'de' ? 'Bild ↑ / Bild ↓' : 'PgUp / PgDn', t('shortcut_people')],
              ['N', t('manual_add')],
              [',', t('settings')],
              ['Esc', t('close')],
              ['?', t('shortcuts_title')],
            ] as const).map(([k, v]) => <><dt><kbd>{k}</kbd></dt><dd>{v}</dd></>)}
          </dl>
        </Dialog>
      )}

      {model.snack && <Snackbar key={model.snack.id} snack={model.snack} onDismiss={() => model.dismiss()} />}
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
