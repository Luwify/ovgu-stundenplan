import type { Day, Gap, Item } from '../core'
import { blockColors } from '../colors'
import { durationText, longDate, t, timeRange } from '../i18n'
import { Badges } from './WeekGrid'
import { useState } from 'preact/hooks'
import { Icon } from './ui'

/** The day view: cards in time order with the free gaps between them (the app's DayScreen). */
export function DayList(props: { day: Day; dark: boolean; onOpen: (item: Item) => void }) {
  const { day } = props
  const rows: ({ kind: 'item'; item: Item } | { kind: 'gap'; gap: Gap })[] = [
    ...day.items.map((item) => ({ kind: 'item' as const, item, at: item.start })),
    ...day.gaps.map((gap) => ({ kind: 'gap' as const, gap, at: gap.start })),
  ].sort((a, b) => (a.at < b.at ? -1 : a.at > b.at ? 1 : a.kind === 'gap' ? 1 : -1))
  return (
    <div class="day-list">
      <h2 class="day-title">{longDate(day.date)}</h2>
      {day.free && <p class="day-free">{t('lecture_free')}</p>}
      {rows.length === 0 && !day.free && <p class="day-empty">{t('no_events_day')}</p>}
      {rows.map((r) =>
        r.kind === 'gap' ? (
          <FreeRow gap={r.gap} />
        ) : (
          <DayCard item={r.item} dark={props.dark} onOpen={props.onOpen} />
        ),
      )}
    </div>
  )
}

function DayCard({ item: it, dark, onOpen }: { item: Item; dark: boolean; onOpen: (i: Item) => void }) {
  const c = blockColors(it.color, dark)
  const cancelled = it.status === 'cancelled' || it.status === 'cancelledByMe'
  const highlighted = it.status === 'changed' || it.status === 'extra'
  return (
    <button
      type="button"
      class={`day-card ${cancelled ? 'cancelled' : ''} ${highlighted ? 'highlight' : ''} ${it.ownChange ? 'own' : ''}`}
      style={{ '--stripe': c.stripe } as Record<string, string>}
      onClick={() => onOpen(it)}
    >
      <span class="dc-time">{timeRange(it.start, it.end)}</span>
      <span class="dc-main">
        <span class="dc-title">
          <span class="dc-name">{it.name}</span>
          {it.typeShort && <span class="type-chip" style={{ background: c.container, color: c.content }}>{it.typeShort}</span>}
          <Badges item={it} size={16} inline />
        </span>
        <span class="dc-sub">
          {cancelled ? t('cancelled_full') : [it.room ?? t('no_room'), it.lecturers.join(', ')].filter(Boolean).join(' · ')}
        </span>
        {highlighted && <span class="dc-change">{it.status === 'extra' ? t('extra') : t('changed')}</span>}
      </span>
    </button>
  )
}

/** A break of 45 min or more; who is free too (favourites first), the names on a tap (the app's FreeRow + tooltip). */
function FreeRow({ gap }: { gap: Gap }) {
  const [open, setOpen] = useState(false)
  const with_ = [...gap.together].sort((a, b) => Number(!!b.favorite) - Number(!!a.favorite))
  const names = with_.map((x) => (x.me ? t('together_you') : x.name ?? '?')).join(', ')
  return (
    <div class="free-row">
      <span class="free-text">{t('free_gap', timeRange(gap.start, gap.end))}  {durationText(gap.minutes)}</span>
      {with_.length > 0 && (
        <button
          type="button"
          class={`free-together ${with_.some((x) => x.favorite) ? 'fav' : ''}`}
          aria-expanded={open}
          aria-label={`${t('free_together', names)} (${t('free_together_show')})`}
          onClick={() => setOpen(!open)}
        >
          <Icon name="group" size={18} />
        </button>
      )}
      {open && <div class="free-names" role="note"><strong>{t('free_together_title')}</strong> {names}</div>}
    </div>
  )
}
