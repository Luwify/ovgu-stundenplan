import type { DetailInfo, HideKind, Item } from '../core'
import { blockColors } from '../colors'
import { dayAndDate, longDate, t, timeRange } from '../i18n'
import { changeLines, rhythmText, roomText } from '../texts'
import { ActionRow, type MenuEntry, type SheetAction } from './Menu'
import { Dialog, Icon } from './ui'

/** What the detail sheet can do; every action is the app's (MainScreen + MainViewModel). */
export interface DetailActions {
  editSubject: () => void
  editPersonal: () => void
  cancelForMe: (cancel: boolean) => void
  pickDates: () => void
  editManual: (id: string) => void
  deleteManual: (id: string) => void
  removeAdded: () => void
  hide: (kind: HideKind) => void
  addToMine: () => void
  removeFromMine: () => void
}

/**
 * The detail sheet (the app's DetailSheet): title, LSF status, own-course / added / own-change boxes, the fields and
 * the action row. [who]: null in my plan, else the friend's name.
 */
export function Detail(props: {
  item: Item
  info: DetailInfo | null
  who: string | null
  dark: boolean
  act: DetailActions
  onClose: () => void
}) {
  const it = props.item
  const info = props.info
  const c = blockColors(it.color, props.dark)
  const mine = props.who === null
  const hideLabels: Record<HideKind, (type?: string) => string> = {
    date: () => t('hide_date'),
    subject: () => t('hide_subject'),
    type: (type) => t('hide_type', type ?? ''),
    group: () => t('hide_group'),
  }
  const hide: SheetAction = {
    icon: 'hide',
    label: t('hide'),
    description: t('hide'),
    menu: (info?.hide ?? [{ kind: 'date' as HideKind }]).map((h) => ({ label: hideLabels[h.kind](h.type), onClick: () => props.act.hide(h.kind) })),
  }
  const editSubject: SheetAction | null = it.subjectKey
    ? { icon: 'edit', label: t('action_short_subject'), description: t('edit_subject'), onClick: props.act.editSubject }
    : null
  let primary: SheetAction[]
  let more: MenuEntry[] = []
  if (mine) {
    const cancelled = !!info?.cancelledForMe
    const cancel: SheetAction | null = info?.lsfCancelled ? null : {
      icon: 'cancel_date',
      label: t(cancelled ? 'action_short_uncancel' : 'action_short_cancel'),
      description: t(cancelled ? 'action_uncancel' : 'action_cancel_for_me'),
      selected: cancelled,
      onClick: () => props.act.cancelForMe(!cancelled),
    }
    const note: SheetAction = { icon: 'note', label: t('action_short_note'), description: t('action_edit_personal'), onClick: props.act.editPersonal }
    primary = [note, cancel, hide, editSubject].filter((a): a is SheetAction => !!a)
    more = [
      { label: t('action_pick_dates'), onClick: props.act.pickDates },
      ...(info?.manualId ? [
        { label: t('manual_edit'), onClick: () => props.act.editManual(info.manualId!) },
        { label: t('manual_delete'), onClick: () => props.act.deleteManual(info.manualId!) },
      ] : []),
      ...(info?.added ? [{ label: t('added_remove'), onClick: props.act.removeAdded }] : []),
    ]
  } else {
    const toMine: SheetAction | null = info?.friendState === 'ADDABLE'
      ? { icon: 'add_date', label: t('add_to_plan_short'), description: t('add_to_plan'), onClick: props.act.addToMine }
      : info?.friendState === 'ADDED'
        ? { icon: 'remove_date', label: t('remove_short'), description: t('added_remove'), onClick: props.act.removeFromMine }
        : info?.friendState === 'IN_PLAN'
          ? { icon: 'check_date', label: t('in_your_plan'), description: t('in_your_plan'), enabled: false }
          : null
    primary = [editSubject, hide, toMine].filter((a): a is SheetAction => !!a)
  }

  const together = [...it.together].sort((a, b) => Number(!!b.favorite) - Number(!!a.favorite))
  const hint = it.noteHint === 'SHORTENED' ? t('notes_shortened') : it.noteHint === 'NOT_SENT' ? t('notes_not_sent') : null
  const when = [longDate(it.date), timeRange(it.start, it.end), rhythmText(it.rhythm)].filter(Boolean).join('\n')

  return (
    <Dialog onClose={props.onClose} sheet label={it.title}>
      <div class="detail">
        <div class="detail-kicker">
          <span class="detail-dot" style={{ background: c.stripe }} />
          <span>{[it.shortName, it.typeName || null].filter(Boolean).join(' · ')}</span>
        </div>
        <h2 class="detail-title">{it.title}</h2>

        <StatusBox item={it} />
        {it.manual && (
          <div class="info-box">
            <div class="info-box-head"><span class="manual-badge"><Icon name="add" size={18} /></span>
              {mine ? t('manual_own_course') : t('manual_own_course_of', props.who!)}</div>
          </div>
        )}
        {mine && info?.added && (
          <div class="info-box">
            <div class="info-box-head"><Icon name="add" size={18} class="primary" />{t('added_course')}</div>
            <p class="muted small">{t('added_course_hint')}</p>
          </div>
        )}
        <PersonalBox item={it} who={props.who} />

        <Field label={t('detail_when')} text={when} />
        <Field label={t('detail_room')} text={roomText(it)} />
        {together.length > 0 && (
          <div class="field-block">
            <div class="field-label">{t('detail_together')}</div>
            <div class="together-names">
              {together.map((x) => (
                <span class={x.favorite ? 'fav' : ''}>{x.favorite && <Icon name="favorite_filled" size={16} />}{x.me ? t('together_you') : x.name}</span>
              ))}
            </div>
          </div>
        )}
        {it.lecturers.length > 0 && <Field label={t('detail_lecturers')} text={it.lecturers.join(', ')} />}
        {(it.notes.trim() || hint) && <Field label={t('detail_notes')} text={[it.notes.trim() || null, hint].filter(Boolean).join('\n\n')} />}
        {it.summary !== it.title && <Field label={t('detail_lsf_title')} text={it.summary} />}

        <ActionRow primary={primary} more={more} moreLabel={t('action_more')} moreDescription={t('action_more_long')} />
      </div>
    </Dialog>
  )
}

function Field(props: { label: string; text: string }) {
  return (
    <div class="field-block">
      <div class="field-label">{props.label}</div>
      <div class="field-text pre">{props.text}</div>
    </div>
  )
}

function StatusBox({ item: it }: { item: Item }) {
  let lines: string[]
  if (it.status === 'cancelled') lines = [t('cancelled_on', longDate(it.date))]
  else if (it.status === 'changed' && it.original) {
    const o = it.original
    lines = [...changeLines(it), t('regular_slot', `${dayAndDate(o.date)} ${timeRange(o.start, o.end)}`, o.room ?? t('no_room'))]
  } else if (it.status === 'extra') lines = [t('extra_long')]
  else return null
  return (
    <div class="info-box status">
      {lines.map((l, i) => <p class={i === 0 ? 'status-first' : ''}>{l}</p>)}
    </div>
  )
}

/** Own changes: what was changed against LSF's values, s.t., the note (the app's PersonalBox). */
function PersonalBox({ item: it, who }: { item: Item; who: string | null }) {
  const p = it.personal
  const st = <div class="st-line"><span class="st-badge">{t('st_badge')}</span>{t('st_long')}</div>
  if (!p) return it.st ? st : null
  const lines: string[] = []
  if (p.overruled) lines.push(t('personal_overruled'))
  if (p.cancelled) lines.push(who === null ? t('personal_cancelled') : t('personal_cancelled_by', who))
  if (p.roomChanged) lines.push(t('personal_room', p.room ?? '–', p.originalRoom ?? t('no_room')))
  if (p.timeChanged) lines.push(t('personal_time', timeRange(p.start, p.end), timeRange(p.originalStart, p.originalEnd)))
  if (p.seriesFrom) lines.push(t('personal_series', dayAndDate(p.seriesFrom)))
  return (
    <>
      {p.changesPlan && (
        <div class="info-box own">
          <div class="info-box-head"><Icon name="edit" size={18} class="own" />
            <strong>{who === null ? t('personal_changed_by_you') : t('personal_changed_by', who)}</strong></div>
          {lines.map((l) => <p>{l}</p>)}
        </div>
      )}
      {p.st && st}
      {p.note && (
        <div class="field-block">
          <div class="field-label with-icon"><Icon name="note" size={16} />{t(p.noteFromSeries ? 'personal_note_series' : 'personal_note')}</div>
          <div class="field-text pre">{p.note}</div>
        </div>
      )}
    </>
  )
}
