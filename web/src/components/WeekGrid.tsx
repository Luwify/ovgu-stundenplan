import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import type { Day, Item, Week } from '../core'
import { blockColors } from '../colors'
import { dayAndDate, t, weekdayShort } from '../i18n'
import { Icon } from './ui'

const MIN_HOUR_PX = 18
const HEADER_PX = 40

/**
 * The week grid. Fit mode (like the Android app's landscape fit): the hour height fills the available height so the
 * whole day range fits; below [MIN_HOUR_PX] per hour it scrolls instead. Blocks drop lines as they get lower.
 */
export function WeekGrid(props: {
  week: Week
  hours: [number, number]
  today: string
  nowMin: number
  dark: boolean
  onOpen: (item: Item) => void
}) {
  const { week, hours } = props
  const box = useRef<HTMLDivElement>(null)
  const [height, setHeight] = useState(0)
  useLayoutEffect(() => {
    const el = box.current
    if (!el) return
    const ro = new ResizeObserver(() => setHeight(el.clientHeight))
    ro.observe(el)
    setHeight(el.clientHeight)
    return () => ro.disconnect()
  }, [])
  const span = Math.max(1, hours[1] - hours[0])
  const hourPx = Math.max(MIN_HOUR_PX, Math.floor(((height || 600) - HEADER_PX - 4) / span))
  const labels = Array.from({ length: span + 1 }, (_, i) => hours[0] + i)
  const labelEvery = hourPx < 22 ? 2 : 1
  const isThisWeek = week.days.some((d) => d.date === props.today)
  const progress = isThisWeek ? Math.min(Math.max(props.nowMin - hours[0] * 60, 0), span * 60) / 60 : 0

  return (
    <div class="week-grid" ref={box} style={{ '--hour': `${hourPx}px`, '--days': week.days.length } as Record<string, string | number>}>
      <div class="wg-inner" style={{ height: `${HEADER_PX + span * hourPx}px` }}>
        <div class="wg-hours" aria-hidden="true">
          <div class="wg-corner" style={{ height: `${HEADER_PX}px` }} />
          <div class="wg-hour-col" style={{ height: `${span * hourPx}px` }}>
            {progress > 0 && <div class="wg-progress" style={{ height: `${progress * hourPx}px` }} />}
            {labels.map((h, i) =>
              i % labelEvery === 0 && i < span ? (
                <div class="wg-hour-label" style={{ top: `${i * hourPx}px` }}>{h}</div>
              ) : null,
            )}
          </div>
        </div>
        {week.days.map((d) => (
          <DayColumn key={d.date} day={d} {...props} hourPx={hourPx} span={span} />
        ))}
      </div>
    </div>
  )
}

function DayColumn(props: {
  day: Day
  hours: [number, number]
  today: string
  hourPx: number
  span: number
  dark: boolean
  onOpen: (item: Item) => void
}) {
  const { day, hourPx, span } = props
  const isToday = day.date === props.today
  return (
    <div class={`wg-day ${isToday ? 'today' : ''}`}>
      <div class="wg-day-head" style={{ height: `${HEADER_PX}px` }}>
        <span class="wg-weekday">{weekdayShort(day.date)}</span>
        <span class="wg-date">{day.date.slice(8, 10).replace(/^0/, '')}.{day.date.slice(5, 7).replace(/^0/, '')}.</span>
      </div>
      <div class="wg-day-body" style={{ height: `${span * hourPx}px` }}>
        {Array.from({ length: span }, (_, i) => (
          <div class="wg-line" style={{ top: `${i * hourPx}px` }} />
        ))}
        {day.free && <div class="wg-free">{t('lecture_free')}</div>}
        {day.items.map((it) => (
          <Block key={`${it.uid}/${it.start}/${it.status}`} item={it} {...props} />
        ))}
      </div>
    </div>
  )
}

function Block(props: { item: Item; hours: [number, number]; hourPx: number; dark: boolean; onOpen: (item: Item) => void }) {
  const { item: it, hourPx } = props
  const top = ((it.startMin - props.hours[0] * 60) / 60) * hourPx
  const h = Math.max(((it.endMin - it.startMin) / 60) * hourPx - 2, 12)
  const c = blockColors(it.color, props.dark)
  const cancelled = it.status === 'cancelled' || it.status === 'cancelledByMe'
  const highlighted = it.status === 'changed' || it.status === 'extra'
  const lines = h >= 58 ? 3 : h >= 38 ? 2 : 1
  const favorite = it.together.some((x) => x.favorite)
  const label = [it.name, it.typeName, it.room, `${it.start}–${it.end}`, cancelled ? t('cancelled_full') : '', highlighted ? t(it.status === 'changed' ? 'changed' : 'extra') : '']
    .filter(Boolean)
    .join(', ')
  return (
    <button
      type="button"
      class={`block ${cancelled ? 'cancelled' : ''} ${highlighted ? 'highlight' : ''} ${h < 28 ? 'squashed' : ''}`}
      style={{
        top: `${top + 1}px`,
        height: `${h}px`,
        left: `calc(${(it.column / it.columns) * 100}% + 2px)`,
        width: `calc(${100 / it.columns}% - 4px)`,
        background: c.container,
        color: c.content,
        '--stripe': c.stripe,
      } as Record<string, string>}
      aria-label={label}
      title={label}
      onClick={() => props.onOpen(it)}
    >
      <span class="block-badges">
        {it.together.length > 0 && <span class={`badge-together ${favorite ? 'fav' : ''}`}><Icon name="group" size={14} /></span>}
      </span>
      <span class="block-name" style={{ WebkitLineClamp: lines >= 3 ? 2 : 1 }}>{it.name}</span>
      {lines >= 2 && (
        <span class="block-meta">
          {cancelled ? t('cancelled') : [it.typeShort, it.room].filter(Boolean).join(' · ')}
        </span>
      )}
    </button>
  )
}

/** Hover hint for pointer devices: the day's date in long form on the header (title). */
export const dayTitle = (iso: string) => dayAndDate(iso)

export function useNowMinutes(): number {
  const [m, set] = useState(() => { const d = new Date(); return d.getHours() * 60 + d.getMinutes() })
  useEffect(() => {
    const id = setInterval(() => { const d = new Date(); set(d.getHours() * 60 + d.getMinutes()) }, 60000)
    return () => clearInterval(id)
  }, [])
  return m
}
