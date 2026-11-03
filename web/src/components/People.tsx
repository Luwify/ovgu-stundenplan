import type { NowLine, PersonInfo } from '../core'
import { useRef, useState } from 'preact/hooks'
import { dateShort, t } from '../i18n'
import { Icon, IconButton } from './ui'

export function nowText(n: NowLine | null): string | null {
  if (!n) return null
  switch (n.kind) {
    case 'in': return t('now_in', n.room ?? t('no_room'), n.until)
    case 'freeUntil': return t('now_free_until', n.from, n.room ?? '')
    case 'freeToday': return t('now_free')
  }
}

/** The drawer's content: my plan, friends with "Stand" and now-line, and the add / share / QR actions. */
export function People(props: {
  people: PersonInfo[]
  selected: string
  onSelect: (id: string) => void
  onFavorite: (p: PersonInfo) => void
  onAdd: () => void
  onShareFile: () => void
  onQr: () => void
  /** New order of the friends (ids). */
  onReorder: (ids: string[]) => void
}) {
  const me = props.people.find((p) => p.isMe)
  const friends = props.people.filter((p) => !p.isMe)
  const list = useRef<HTMLUListElement>(null)
  // While dragging: the friend, how far the finger moved, the index it would land on.
  const [drag, setDrag] = useState<{ id: string; dy: number; target: number } | null>(null)
  const start = useRef(0)
  const order = drag ? moved(friends.map((f) => f.id), drag.id, drag.target) : friends.map((f) => f.id)
  const rowHeight = () => (list.current?.querySelector('li') as HTMLElement | null)?.offsetHeight ?? 64

  const handle = (p: PersonInfo, index: number) => (
    <button
      type="button"
      class="drag-handle"
      aria-label={`${t('reorder_handle')}: ${p.name}`}
      title={t('reorder_handle')}
      onPointerDown={(e) => {
        ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
        start.current = e.clientY
        setDrag({ id: p.id, dy: 0, target: index })
      }}
      onPointerMove={(e) => {
        if (!drag || drag.id !== p.id) return
        const dy = e.clientY - start.current
        const target = Math.max(0, Math.min(friends.length - 1, index + Math.round(dy / rowHeight())))
        setDrag({ id: p.id, dy, target })
      }}
      onPointerUp={() => {
        if (drag && drag.target !== index) props.onReorder(moved(friends.map((f) => f.id), p.id, drag.target))
        setDrag(null)
      }}
      onPointerCancel={() => setDrag(null)}
      onKeyDown={(e) => {
        const d = e.key === 'ArrowUp' ? -1 : e.key === 'ArrowDown' ? 1 : 0
        if (!d) return
        e.preventDefault()
        const to = index + d
        if (to < 0 || to >= friends.length) return
        props.onReorder(moved(friends.map((f) => f.id), p.id, to))
        requestAnimationFrame(() => list.current?.querySelectorAll<HTMLElement>('.drag-handle')[to]?.focus())
      }}
    >
      <Icon name="drag" size={20} />
    </button>
  )

  const row = (p: PersonInfo) => {
    const now = nowText(p.now)
    const index = friends.indexOf(p)
    const dragging = drag?.id === p.id
    // The dragged row follows the finger; the others make room.
    const shift = drag && !dragging && index >= 0
      ? (order.indexOf(p.id) - index) * rowHeight()
      : 0
    return (
      <li
        class={`person ${p.id === props.selected ? 'selected' : ''} ${dragging ? 'dragging' : ''}`}
        style={dragging ? { transform: `translateY(${drag!.dy}px)` } : shift ? { transform: `translateY(${shift}px)` } : undefined}
      >
        <button type="button" class="person-main" aria-current={p.id === props.selected} onClick={() => props.onSelect(p.id)}>
          <span class="person-name">{p.isMe ? t('my_plan') : p.name}</span>
          {p.planDate && <span class="person-sub">{t('stand', dateShort(p.planDate))}{p.fromIcs ? ` · ${t('from_ics')}` : ''}</span>}
          {now && <span class="person-sub">{now}</span>}
        </button>
        {!p.isMe && (
          <IconButton
            icon={p.favorite ? 'favorite_filled' : 'favorite'}
            class={p.favorite ? 'fav' : ''}
            label={t('favorite_toggle', p.name)}
            pressed={p.favorite}
            onClick={() => props.onFavorite(p)}
          />
        )}
        {!p.isMe && friends.length > 1 && handle(p, index)}
      </li>
    )
  }
  return (
    <nav class="people" aria-label={t('open_drawer')}>
      <div class="people-title"><img src="./icons/icon-192.png" alt="" width="28" height="28" /> {t('app_name')}</div>
      <ul>{me && row(me)}</ul>
      <h3 class="people-heading">{t('friends')}</h3>
      {friends.length === 0 ? <p class="muted people-empty">{t('friends_empty')}</p> : <ul ref={list}>{friends.map(row)}</ul>}
      <div class="people-actions">
        <button type="button" class="people-action" onClick={props.onAdd}><Icon name="add" /><span>{t('friend_add_action')}</span></button>
        <button type="button" class="people-action" onClick={props.onShareFile}><Icon name="share" /><span>{t('share_action')}</span></button>
        <button type="button" class="people-action" onClick={props.onQr}><Icon name="qr_code_2" /><span>{t('qr_action')}</span></button>
      </div>
    </nav>
  )
}

/** [ids] with [id] moved to [to]. */
function moved(ids: string[], id: string, to: number): string[] {
  const rest = ids.filter((x) => x !== id)
  rest.splice(to, 0, id)
  return rest
}
