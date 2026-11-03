import type { Day, Gap, Item } from '../core'
import { blockColors } from '../colors'
import { durationText, longDate, t, timeRange } from '../i18n'
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
          <div class="free-row">{t('free_gap', timeRange(r.gap.start, r.gap.end))}  {durationText(r.gap.minutes)}</div>
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
      class={`day-card ${cancelled ? 'cancelled' : ''} ${highlighted ? 'highlight' : ''}`}
      style={{ '--stripe': c.stripe } as Record<string, string>}
      onClick={() => onOpen(it)}
    >
      <span class="dc-time">{timeRange(it.start, it.end)}</span>
      <span class="dc-main">
        <span class="dc-title">
          <span class="dc-name">{it.name}</span>
          {it.typeShort && <span class="type-chip" style={{ background: c.container, color: c.content }}>{it.typeShort}</span>}
          {it.st && <span class="type-chip">{t('st_badge')}</span>}
          {it.together.length > 0 && (
            <span class={`badge-together ${it.together.some((x) => x.favorite) ? 'fav' : ''}`}><Icon name="group" size={16} /></span>
          )}
        </span>
        <span class="dc-sub">
          {cancelled ? t('cancelled_full') : [it.room ?? t('no_room'), it.lecturers.join(', ')].filter(Boolean).join(' · ')}
        </span>
        {highlighted && <span class="dc-change">{it.status === 'extra' ? t('extra') : t('changed')}</span>}
      </span>
    </button>
  )
}
