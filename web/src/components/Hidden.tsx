import type { HiddenGroup, HiddenItem } from '../core'
import { dayAndDate, t } from '../i18n'
import { slotText } from '../texts'
import { Button, Dialog } from './ui'

/** "Ausgeblendet" (the app's HiddenScreen): per person, me first, each item with "Wiederherstellen". */
export function HiddenScreen(props: { groups: HiddenGroup[]; onRestore: (personId: string, index: number) => void; onClose: () => void }) {
  return (
    <Dialog title={t('hidden_title')} onClose={props.onClose} wide>
      <p class="muted">{t('hidden_hint')}</p>
      {props.groups.length === 0 && <p class="empty-hint">{t('hidden_empty')}</p>}
      {props.groups.map((g) => (
        <section class="settings-section" aria-label={g.isMe ? t('my_plan') : g.name}>
          <h3>{g.isMe ? t('my_plan') : g.name}</h3>
          {g.items.map((h, i) => (
            <div class="list-row">
              <span class="list-text">
                <span class="list-title">{h.title}</span>
                <span class="muted small">{hiddenWhat(h)}</span>
              </span>
              <Button onClick={() => props.onRestore(g.personId, i)}>{t('hidden_restore')}</Button>
            </div>
          ))}
        </section>
      ))}
    </Dialog>
  )
}

function hiddenWhat(h: HiddenItem): string {
  const parts = h.kind === 'SUBJECT' ? [t('hidden_kind_subject')]
    : h.kind === 'COURSE' ? [t('hidden_kind_type'), h.type]
    : h.kind === 'GROUP' ? [t('hidden_kind_group'), h.type, slotText(h), h.room]
    : [t('hidden_kind_date'), h.type, [h.date ? dayAndDate(h.date) : null, slotText({ day: null, start: h.start, end: h.end })].filter(Boolean).join(' '), h.room]
  return parts.filter((x) => x && x.trim()).join(' · ')
}
