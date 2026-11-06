import { useId, useMemo, useState } from 'preact/hooks'
import type { DateState, DetailInfo, ManualCourse, ManualRhythm, ManualType, PickDate, SubjectInfo, WeekRhythm } from '../core'
import { blockColors, palette, rgbHex } from '../colors'
import { dayAndDate, t, timeRange, weekdayShort } from '../i18n'
import { timeInput, timeValue, weekRhythmName } from '../texts'
import { MenuButton, type MenuEntry } from './Menu'
import { Button, Dialog, Icon } from './ui'

function Radio(props: { checked: boolean; label: string; onClick: () => void; name: string }) {
  return (
    <label class="radio-row">
      <input type="radio" name={props.name} checked={props.checked} onChange={props.onClick} />
      <span>{props.label}</span>
    </label>
  )
}

export function Switch(props: { checked: boolean; label: string; hint?: string; onChange: (on: boolean) => void }) {
  return (
    <label class="switch-row">
      <span class="switch-text"><span>{props.label}</span>{props.hint && <span class="muted small">{props.hint}</span>}</span>
      <input type="checkbox" role="switch" class="switch" checked={props.checked} onChange={(e) => props.onChange((e.target as HTMLInputElement).checked)} />
    </label>
  )
}

function TextField(props: {
  label: string
  value: string
  onInput: (v: string) => void
  hint?: string
  error?: string | null
  multiline?: boolean
  maxLength?: number
  placeholder?: string
  inputMode?: 'numeric' | 'text'
  type?: 'time'
  autofocus?: boolean
}) {
  const common = {
    value: props.value,
    maxLength: props.maxLength,
    placeholder: props.placeholder,
    'aria-invalid': props.error ? true : undefined,
    onInput: (e: Event) => props.onInput((e.target as HTMLInputElement).value),
  }
  const id = useId()
  const note = (props.error && props.error.trim()) || props.hint
  const described = note ? { 'aria-describedby': id } : {}
  return (
    <div class={`field ${props.error ? 'invalid' : ''}`}>
      <label class="field-inner">
        <span>{props.label}</span>
        {props.multiline ? <textarea rows={3} {...common} {...described} /> : <input type={props.type} inputMode={props.inputMode} autofocus={props.autofocus} {...common} {...described} />}
      </label>
      {note && <span id={id} class={props.error ? 'error small' : 'muted small'}>{note}</span>}
    </div>
  )
}

/**
 * The browser's own time input (iPad: the system's time wheel). Its value is "" or "HH:mm" whatever it shows, so there is
 * nothing to parse; 12 or 24 hours follow the device's region.
 */
function TimeField(props: { label: string; value: string; onInput: (v: string) => void; invalid: boolean }) {
  return <TextField type="time" label={props.label} value={props.value} onInput={props.onInput} error={props.invalid ? ' ' : null} />
}

/** Note, s.t., room and time of one date or (series) of every date from this one on (the app's PersonalEditor). */
export function PersonalEditor(props: {
  editor: DetailInfo['editor']
  onSave: (series: boolean, note: string, st: boolean, room: string, start: string, end: string) => void
  onClose: () => void
}) {
  const e = props.editor
  const [series, setSeries] = useState(false)
  const [note, setNote] = useState(e.note)
  const [st, setSt] = useState(e.st)
  const [room, setRoom] = useState(e.room)
  const [start, setStart] = useState(timeValue(e.start))
  const [end, setEnd] = useState(timeValue(e.end))
  const valid = !!start && !!end && end > start
  return (
    <Dialog
      title={t('action_edit_personal')}
      onClose={props.onClose}
      actions={
        <>
          <Button onClick={props.onClose}>{t('cancel')}</Button>
          <Button variant="filled" disabled={!valid} onClick={() => props.onSave(series, note.trim(), st, room.trim(), start, end)}>{t('save')}</Button>
        </>
      }
    >
      {e.canSeries && (
        <div class="radio-group" role="radiogroup">
          <Radio name="scope" checked={!series} label={t('editor_scope_one', dayAndDate(e.refDate))} onClick={() => setSeries(false)} />
          <Radio name="scope" checked={series} label={t('editor_scope_series', `${weekdayShort(e.seriesDate)} ${timeInput(e.seriesTime)}`, dayAndDate(e.refDate))} onClick={() => setSeries(true)} />
        </div>
      )}
      <TextField label={t('editor_note')} value={note} onInput={setNote} multiline autofocus />
      <Switch label={t('editor_st')} checked={st} onChange={setSt} />
      <TextField label={t('editor_room')} value={room} onInput={setRoom} hint={t('editor_room_hint', e.originalRoom ?? t('no_room'))} />
      <div class="field-pair">
        <TimeField label={t('editor_start')} value={start} onInput={setStart} invalid={!valid} />
        <TimeField label={t('editor_end')} value={end} onInput={setEnd} invalid={!valid} />
      </div>
      {!valid && <p class="error small">{t('editor_time_invalid')}</p>}
    </Dialog>
  )
}

/**
 * "Termine auswählen" (the app's PickDatesDialog): every date of the group, checked = takes place; for the unchecked
 * ones it asks each time: shown as cancelled or hidden (preselected only when they share one state already).
 */
export function PickDates(props: { name: string; dates: PickDate[]; onSave: (states: Record<string, DateState>) => void; onClose: () => void }) {
  const [checked, setChecked] = useState<Record<string, boolean>>({})
  const isOn = (d: PickDate) => checked[d.key] ?? d.state === 'ON'
  const initialOff = Array.from(new Set(props.dates.filter((d) => d.state !== 'ON').map((d) => d.state)))
  const [choice, setChoice] = useState<DateState | null>(initialOff.length === 1 ? initialOff[0] : null)
  const anyOff = props.dates.some((d) => !isOn(d))
  const save = () => {
    const out: Record<string, DateState> = {}
    for (const d of props.dates) {
      const target: DateState = isOn(d) ? 'ON' : choice ?? d.state
      if (target !== d.state) out[d.key] = target
    }
    props.onSave(out)
  }
  return (
    <Dialog
      title={t('pick_dates_title', props.name)}
      onClose={props.onClose}
      actions={
        <>
          <Button onClick={props.onClose}>{t('cancel')}</Button>
          <Button variant="filled" disabled={anyOff && !choice} onClick={save}>{t('save')}</Button>
        </>
      }
    >
      <p>{t('pick_dates_hint')}</p>
      <div class="check-list">
        {props.dates.map((d) => (
          <label class={`check-row ${isOn(d) ? '' : 'off'}`}>
            <input type="checkbox" checked={isOn(d)} onChange={() => setChecked({ ...checked, [d.key]: !isOn(d) })} />
            <span>
              {`${dayAndDate(d.date)}  ${timeRange(d.start, d.end)}` + (d.room ? ` · ${d.room}` : '') + (d.state === 'HIDDEN' ? ` · ${t('pick_dates_hidden_now')}` : '')}
            </span>
          </label>
        ))}
      </div>
      {anyOff && (
        <div class="radio-group" role="radiogroup" aria-label={t('pick_dates_unselected')}>
          <h3 class="small-heading">{t('pick_dates_unselected')}</h3>
          <Radio name="off" checked={choice === 'CANCELLED'} label={t('pick_dates_as_cancelled')} onClick={() => setChoice('CANCELLED')} />
          <Radio name="off" checked={choice === 'HIDDEN'} label={t('pick_dates_hide')} onClick={() => setChoice('HIDDEN')} />
        </div>
      )}
    </Dialog>
  )
}

/** A colour swatch; [label] "+" opens the picker. */
function Swatch(props: { rgb: number; selected: boolean; label?: string; onClick: () => void }) {
  return (
    <button
      type="button"
      class={`swatch ${props.selected ? 'selected' : ''}`}
      style={{ background: rgbHex(props.rgb) }}
      aria-label={props.label ? t('color') : rgbHex(props.rgb).toUpperCase()}
      aria-pressed={props.selected}
      onClick={props.onClick}
    >
      {props.label && <span class="swatch-plus">{props.label}</span>}
    </button>
  )
}

/** Palette swatches + "+" (a free colour through the browser's colour input, or a hex code). */
export function ColorChoice(props: { value: number; onChange: (rgb: number) => void }) {
  const [picking, setPicking] = useState(false)
  const [hex, setHex] = useState(rgbHex(props.value).slice(1).toUpperCase())
  const custom = !palette.includes(props.value)
  return (
    <>
      <div class="swatches">
        {palette.map((rgb) => <Swatch rgb={rgb} selected={props.value === rgb} onClick={() => props.onChange(rgb)} />)}
        <Swatch rgb={props.value} selected={custom} label="+" onClick={() => { setHex(rgbHex(props.value).slice(1).toUpperCase()); setPicking(!picking) }} />
      </div>
      {picking && (
        <div class="color-custom">
          <input type="color" aria-label={t('color')} value={rgbHex(props.value)} onInput={(e) => {
            const v = (e.target as HTMLInputElement).value
            setHex(v.slice(1).toUpperCase())
            props.onChange(parseInt(v.slice(1), 16))
          }} />
          <label class="field hex">
            <span>{t('custom_hex')}</span>
            <input value={hex} maxLength={6} onInput={(e) => {
              const v = (e.target as HTMLInputElement).value.replace(/[^0-9a-fA-F]/g, '').toUpperCase()
              setHex(v)
              if (v.length === 6) props.onChange(parseInt(v, 16))
            }} />
          </label>
        </div>
      )}
    </>
  )
}

/**
 * Short name (prefix for all types + a part per type, live preview) and colour of a subject (saved on OK), and its
 * courses: move (at once), hide, rhythm per biweekly series, and in my plan edit/delete manual courses or remove added
 * ones (the app's SubjectEditor).
 */
export function SubjectEditor(props: {
  subjects: SubjectInfo[]
  subjectKey: string
  courseKey: string | null
  isMe: boolean
  dark: boolean
  onSave: (shortName: string | null, color: number | null, typed: Record<string, string | null>) => void
  onMove: (courseKey: string, target: string) => void
  onRhythm: (uid: string, rhythm: WeekRhythm | null) => void
  onHideSubject: () => void
  onHideCourse: (courseKey: string) => void
  onEditManual: (id: string) => void
  onDeleteManual: (id: string) => void
  onRemoveAdded: (courseKey: string) => void
  onClose: () => void
}) {
  const subject = props.subjects.find((s) => s.key === props.subjectKey)
  const [shortName, setShortName] = useState(subject?.shortName ?? '')
  const [parts, setParts] = useState<Record<string, string>>(() =>
    Object.fromEntries((subject?.courses ?? []).map((c) => [c.key, subject?.typeShortNames[c.key] ?? ''])))
  const [focus, setFocus] = useState(() => (subject?.courses.some((c) => c.key === props.courseKey) ? props.courseKey! : subject?.courses[0]?.key ?? ''))
  const [color, setColor] = useState<number | null>(subject?.auto ? null : subject?.color ?? null)
  const [fullTitle, setFullTitle] = useState(false)
  if (!subject) { queueMicrotask(props.onClose); return null }
  const focused = subject.courses.find((c) => c.key === focus) ?? subject.courses[0]
  const shown = color ?? (subject.auto ? subject.color : subject.color)
  const preview = composeName(shortName.trim() || subject.name, parts[focused.key])
  const tint = blockColors(shown, props.dark)
  return (
    <Dialog
      title={t('edit_subject')}
      onClose={props.onClose}
      actions={
        <>
          <Button onClick={props.onClose}>{t('cancel')}</Button>
          <Button variant="filled" onClick={() => props.onSave(
            shortName.trim() || null, color,
            Object.fromEntries(Object.entries(parts).map(([k, v]) => [k, v.trim() || null])),
          )}>{t('ok')}</Button>
        </>
      }
    >
      <p class="muted">{subject.name}</p>
      <TextField label={t('short_name_all')} value={shortName} onInput={(v) => setShortName(v.slice(0, 24))} placeholder={subject.name} />
      {subject.courses.length > 1 && (
        <div class="chips" role="group" aria-label={t('short_name_type')}>
          {subject.courses.map((c) => (
            <button type="button" class={`filter-chip ${c.key === focused.key ? 'selected' : ''}`} aria-pressed={c.key === focused.key} onClick={() => setFocus(c.key)}>
              {c.key === focused.key && <Icon name="check" size={16} />}{c.typeName}
            </button>
          ))}
        </div>
      )}
      <div class="field">
        <label class="field-inner">
          <span>{t('short_name_type')}</span>
          <input value={parts[focused.key] ?? ''} maxLength={16} onInput={(e) => setParts({ ...parts, [focused.key]: (e.target as HTMLInputElement).value.slice(0, 16) })} />
        </label>
        <button type="button" class={`field-hint ${fullTitle ? '' : 'one-line'}`} aria-expanded={fullTitle} onClick={() => setFullTitle(!fullTitle)}>
          {focused.typeName} · {focused.title}
        </button>
      </div>
      <div class="preview-row">
        <span class="muted">{t('short_name_preview')}</span>
        <span class="preview-chip" style={{ background: tint.container, color: tint.content }}>{preview}</span>
      </div>
      <h3 class="small-heading">{t('color')}</h3>
      <ColorChoice value={shown} onChange={setColor} />
      {color !== null && <Button onClick={() => setColor(null)}>{t('color_automatic')}</Button>}
      <hr />
      <h3 class="small-heading">{t('courses_of_subject')}</h3>
      {subject.courses.map((course, i) => {
        const alone = subject.courses.length === 1
        const entries: MenuEntry[] = [
          ...(props.isMe && course.manualId ? [
            { label: t('manual_edit'), onClick: () => props.onEditManual(course.manualId!) },
            { label: t('manual_delete'), onClick: () => props.onDeleteManual(course.manualId!) },
          ] : []),
          ...(props.isMe && course.added ? [{ label: t('added_remove'), onClick: () => props.onRemoveAdded(course.key) }] : []),
          ...(!alone ? [{ label: t('hide_type', course.typeName), onClick: () => props.onHideCourse(course.key) }] : []),
          { label: t('move'), heading: true },
          ...(!alone ? [{ label: t('move_to_own'), onClick: () => props.onMove(course.key, 'own:' + course.key) }] : []),
          ...props.subjects.filter((s) => s.key !== subject.key).map((s) => ({ label: s.displayName, onClick: () => props.onMove(course.key, s.key) })),
        ]
        return (
          <div class={`course-group ${i > 0 ? 'divided' : ''}`}>
            <div class="course-row">
              <span class="type-label">{course.typeShort}</span>
              <span class="course-text">
                <span class="course-title">{course.title}</span>
                {course.courseId && <span class="muted small">{t('course_id', course.courseId)}</span>}
              </span>
              <MenuButton entries={entries} align="end" label={t('course_options')} class="text-menu">
                {() => <span class="btn btn-text">{t('course_options')}</span>}
              </MenuButton>
            </div>
            {course.series.map((s) => {
              const auto = t('rhythm_auto', weekRhythmName(s.auto))
              return (
                <div class="rhythm-row">
                  <span class="muted small">{weekdayShort(s.first)} {timeRange(s.start, s.end)}</span>
                  <MenuButton
                    align="end"
                    label={`${weekdayShort(s.first)} ${timeRange(s.start, s.end)}: ${s.chosen ? weekRhythmName(s.chosen) : auto}`}
                    class="text-menu"
                    entries={[
                      { label: auto, onClick: () => props.onRhythm(s.uid, null) },
                      ...(['EVEN_WEEKS', 'ODD_WEEKS', 'EVERY_14_DAYS'] as WeekRhythm[]).map((r) => ({ label: weekRhythmName(r), onClick: () => props.onRhythm(s.uid, r) })),
                    ]}
                  >
                    {() => <span class="btn btn-text small">{s.chosen ? weekRhythmName(s.chosen) : auto}</span>}
                  </MenuButton>
                </div>
              )
            })}
          </div>
        )
      })}
      <hr />
      <Button onClick={props.onHideSubject}>{t('hide_subject')}</Button>
    </Dialog>
  )
}

/** Subject.compose: prefix + space + part; no part = only the prefix. */
export const composeName = (prefix: string, part: string | undefined) => (part?.trim() ? `${prefix} ${part.trim()}` : prefix)

const TYPES: [ManualType, string][] = [
  ['LECTURE', 'manual_type_lecture'], ['EXERCISE', 'manual_type_exercise'], ['LAB', 'manual_type_lab'],
  ['SEMINAR', 'manual_type_seminar'], ['OTHER', 'manual_type_other'],
]
const RHYTHMS: [ManualRhythm, string][] = [
  ['ONCE', 'manual_once'], ['WEEKLY', 'manual_weekly'], ['EVEN_WEEKS', 'manual_even_weeks'], ['ODD_WEEKS', 'manual_odd_weeks'], ['BIWEEKLY', 'manual_biweekly'],
]

/** Add or edit a manual course (the app's ManualCourseEditor). */
export function ManualEditor(props: {
  existing: ManualCourse | null
  /** The course's subject now (null = its own) and its style. */
  existingSubject: string | null
  existingShort: string | null
  existingColor: number | null
  subjects: SubjectInfo[]
  newId: () => string
  defaultEnd: (start: string) => string
  today: string
  autoColor: number
  onSave: (course: ManualCourse, subjectKey: string | null, shortName: string | null, color: number) => void
  onClose: () => void
}) {
  const ex = props.existing
  const ownKey = ex ? `own:manual:${ex.id}` : null
  const [title, setTitle] = useState(ex?.title ?? '')
  const [touched, setTouched] = useState(false)
  const [type, setType] = useState<ManualType>(ex?.type ?? 'LECTURE')
  const [subjectKey, setSubjectKey] = useState<string | null>(props.existingSubject && props.existingSubject !== ownKey ? props.existingSubject : null)
  const [shortName, setShortName] = useState(props.existingShort ?? '')
  const [color, setColor] = useState(props.existingColor ?? props.autoColor)
  const [room, setRoom] = useState(ex?.room ?? '')
  const [lecturer, setLecturer] = useState(ex?.lecturer ?? '')
  const [note, setNote] = useState(ex?.note ?? '')
  const [st, setSt] = useState(ex?.st ?? false)
  const [rhythm, setRhythm] = useState<ManualRhythm>(ex?.rhythm ?? 'WEEKLY')
  const [startDate, setStartDate] = useState(ex?.startDate ?? props.today)
  const [endDate, setEndDate] = useState(ex?.endDate ?? props.defaultEnd(ex?.startDate ?? props.today))
  const [startText, setStartText] = useState(ex ? timeValue(ex.start) : '09:15')
  const [endText, setEndText] = useState(ex ? timeValue(ex.end) : '10:45')
  const [skipFree, setSkipFree] = useState(ex?.skipFreeWeeks ?? true)
  const timesOk = !!startText && !!endText && endText > startText
  const once = rhythm === 'ONCE'
  const datesOk = once || endDate >= startDate
  const titleOk = title.trim().length > 0
  const valid = titleOk && timesOk && datesOk
  const subjects = useMemo(() => props.subjects.filter((x) => x.key !== ownKey), [props.subjects, ownKey])
  const save = () => props.onSave({
    id: ex?.id ?? props.newId(),
    title: title.trim(),
    type,
    room: room.trim() || null,
    lecturer: lecturer.trim() || null,
    note: note.trim(),
    st,
    rhythm,
    startDate,
    endDate: once ? null : endDate,
    start: startText,
    end: endText,
    skipFreeWeeks: skipFree,
  }, subjectKey, shortName.trim() || null, color)
  return (
    <Dialog
      title={t(ex ? 'manual_edit' : 'manual_add')}
      onClose={props.onClose}
      wide
      actions={
        <>
          <Button onClick={props.onClose}>{t('cancel')}</Button>
          <Button variant="filled" disabled={!valid} onClick={save}>{t('save')}</Button>
        </>
      }
    >
      <TextField label={t('manual_title')} value={title} autofocus onInput={(v) => { setTitle(v); setTouched(true) }} error={touched && !titleOk ? t('manual_title_required') : null} />
      <h3 class="small-heading">{t('manual_type')}</h3>
      <div class="chips">
        {TYPES.map(([v, label]) => (
          <button type="button" class={`filter-chip ${type === v ? 'selected' : ''}`} aria-pressed={type === v} onClick={() => setType(v)}>
            {type === v && <Icon name="check" size={16} />}{t(label as never)}
          </button>
        ))}
      </div>
      <label class="field">
        <span>{t('manual_subject')}</span>
        <select value={subjectKey ?? ''} onChange={(ev) => setSubjectKey((ev.target as HTMLSelectElement).value || null)}>
          <option value="">{t('manual_subject_new')}</option>
          {subjects.map((x) => <option value={x.key}>{x.displayName}</option>)}
        </select>
      </label>
      {subjectKey === null && (
        <>
          <TextField label={t('short_name')} value={shortName} onInput={(v) => setShortName(v.slice(0, 24))} />
          <ColorChoice value={color} onChange={setColor} />
        </>
      )}
      <TextField label={t('editor_room')} value={room} onInput={setRoom} />
      <TextField label={t('manual_lecturer')} value={lecturer} onInput={setLecturer} />
      <TextField label={t('editor_note')} value={note} onInput={setNote} multiline />
      <Switch label={t('editor_st')} checked={st} onChange={setSt} />
      <h3 class="small-heading">{t('manual_rhythm')}</h3>
      <div class="chips">
        {RHYTHMS.map(([v, label]) => (
          <button type="button" class={`filter-chip ${rhythm === v ? 'selected' : ''}`} aria-pressed={rhythm === v} onClick={() => setRhythm(v)}>
            {rhythm === v && <Icon name="check" size={16} />}{t(label as never)}
          </button>
        ))}
      </div>
      <div class="field-pair">
        <label class="field">
          <span>{t(once ? 'manual_date' : 'manual_start_date')}</span>
          <input type="date" value={startDate} required onInput={(ev) => {
            const d = (ev.target as HTMLInputElement).value
            if (!d) return
            setStartDate(d)
            // A new first date moves a default end along only while it would end up before it.
            if (endDate < d) setEndDate(props.defaultEnd(d))
          }} />
        </label>
        {!once && (
          <label class="field">
            <span>{t('manual_end_date')}</span>
            <input type="date" value={endDate} required onInput={(ev) => { const d = (ev.target as HTMLInputElement).value; if (d) setEndDate(d) }} />
          </label>
        )}
      </div>
      {!datesOk && <p class="error small">{t('manual_end_before_start')}</p>}
      <div class="field-pair">
        <TimeField label={t('editor_start')} value={startText} onInput={setStartText} invalid={!timesOk} />
        <TimeField label={t('editor_end')} value={endText} onInput={setEndText} invalid={!timesOk} />
      </div>
      {!timesOk && <p class="error small">{t('editor_time_invalid')}</p>}
      {!once && <Switch label={t('manual_skip_free')} hint={t('manual_skip_free_hint')} checked={skipFree} onChange={setSkipFree} />}
    </Dialog>
  )
}
