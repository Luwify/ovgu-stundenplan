import type { Item } from '../core'
import { blockColors } from '../colors'
import { dayAndDate, longDate, t, timeRange } from '../i18n'
import { Dialog, Icon } from './ui'

/** The course detail: time, room, type, lecturers, notes, changes and who attends too. */
export function Detail(props: { item: Item; dark: boolean; onClose: () => void }) {
  const it = props.item
  const c = blockColors(it.color, props.dark)
  const cancelled = it.status === 'cancelled' || it.status === 'cancelledByMe'
  const changeText = (ch: Item['changes'][number]) => {
    const none = t('no_room')
    switch (ch.kind) {
      case 'day': return t('change_day', dayAndDate(ch.now!), dayAndDate(ch.before!))
      case 'time': return t('change_time', ch.now!, ch.before!)
      case 'room': return t('change_room', ch.now ?? none, ch.before ?? none)
      case 'lecturer': return t('change_lecturer', ch.now || '–')
    }
  }
  const together = it.together.map((x) => (x.me ? t('together_you') : x.name ?? '')).filter(Boolean)
  return (
    <Dialog onClose={props.onClose} sheet label={it.name}>
      <div class="detail" style={{ '--stripe': c.stripe } as Record<string, string>}>
        <div class="detail-head">
          <h2>{it.name}</h2>
          {it.typeName && <span class="type-chip" style={{ background: c.container, color: c.content }}>{it.typeName}</span>}
        </div>
        {it.subject && it.subject !== it.name && <p class="muted">{it.subject}</p>}
        {cancelled && <p class="detail-status cancelled">{t('cancelled_full')}</p>}
        {it.status === 'extra' && <p class="detail-status highlight">{t('extra')}</p>}
        {it.status === 'changed' && (
          <ul class="detail-status highlight">{(it.changes.length ? it.changes.map(changeText) : [t('changed')]).map((s) => <li>{s}</li>)}</ul>
        )}
        <dl>
          <dt>{t('detail_when')}</dt>
          <dd>{longDate(it.date)}, {timeRange(it.start, it.end)}{it.st ? ` (${t('st_badge')})` : ''}</dd>
          <dt>{t('detail_room')}</dt>
          <dd>{it.location || it.room || t('no_room')}</dd>
          {it.lecturers.length > 0 && (<><dt>{t('detail_lecturers')}</dt><dd>{it.lecturers.join(', ')}</dd></>)}
          {together.length > 0 && (
            <>
              <dt>{t('detail_together')}</dt>
              <dd class="together-line"><Icon name="group" size={18} /> {together.join(', ')}</dd>
            </>
          )}
          {it.personalNote && (<><dt>{t('personal_note')}</dt><dd class="pre">{it.personalNote}</dd></>)}
          {(it.notes || it.noteHint !== 'NONE') && (
            <>
              <dt>{t('detail_notes')}</dt>
              <dd class="pre">
                {it.notes}
                {it.noteHint === 'SHORTENED' && <span class="muted"> {t('notes_shortened')}</span>}
                {it.noteHint === 'NOT_SENT' && <span class="muted">{t('notes_not_sent')}</span>}
              </dd>
            </>
          )}
          {it.category && (<><dt>{t('detail_type')}</dt><dd>{it.category}</dd></>)}
          {it.summary && it.summary !== it.name && (<><dt>{t('detail_lsf_title')}</dt><dd>{it.summary}</dd></>)}
        </dl>
      </div>
    </Dialog>
  )
}
