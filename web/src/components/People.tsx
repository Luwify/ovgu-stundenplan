import type { NowLine, PersonInfo } from '../core'
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
}) {
  const me = props.people.find((p) => p.isMe)
  const friends = props.people.filter((p) => !p.isMe)
  const row = (p: PersonInfo) => {
    const now = nowText(p.now)
    return (
      <li class={`person ${p.id === props.selected ? 'selected' : ''}`}>
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
      </li>
    )
  }
  return (
    <nav class="people" aria-label={t('open_drawer')}>
      <div class="people-title"><img src="./icons/icon-192.png" alt="" width="28" height="28" /> {t('app_name')}</div>
      <ul>{me && row(me)}</ul>
      <h3 class="people-heading">{t('friends')}</h3>
      {friends.length === 0 ? <p class="muted people-empty">{t('friends_empty')}</p> : <ul>{friends.map(row)}</ul>}
      <div class="people-actions">
        <button type="button" class="people-action" onClick={props.onAdd}><Icon name="add" /><span>{t('friend_add_action')}</span></button>
        <button type="button" class="people-action" onClick={props.onShareFile}><Icon name="share" /><span>{t('share_action')}</span></button>
        <button type="button" class="people-action" onClick={props.onQr}><Icon name="qr_code_2" /><span>{t('qr_action')}</span></button>
      </div>
    </nav>
  )
}
