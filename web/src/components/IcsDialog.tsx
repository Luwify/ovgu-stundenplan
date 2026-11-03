import { useState } from 'preact/hooks'
import type { IcsRead } from '../core'
import { plural, t } from '../i18n'
import { Button, Dialog, Icon, type IconName } from './ui'

type Read = Extract<IcsRead, { route: string }>

function Choice(props: { icon: IconName; title: string; hint: string; onClick: () => void }) {
  return (
    <button type="button" class="choice" onClick={props.onClick}>
      <Icon name={props.icon} />
      <span><strong>{props.title}</strong><span class="muted">{props.hint}</span></span>
    </button>
  )
}

/**
 * What to do with an .ics (the app's IcsImportDialog): from the settings with far fewer courses than my plan, replace
 * it or add the courses; from anywhere else, add it as a friend (new, or update an .ics friend) or add the courses
 * (+ "Als meinen Plan importieren" while I have none).
 */
export function IcsDialog(props: {
  read: Read
  onReplace: () => void
  onAddCourses: () => void
  onFriend: (name: string, existingId: string | null) => void
  onCancel: () => void
}) {
  const r = props.read
  const [asFriend, setAsFriend] = useState(false)
  const [name, setName] = useState('')
  const courses = plural('ics_courses', r.incomingCourses)
  if (asFriend) {
    return (
      <Dialog
        title={t('ics_friend_title')}
        onClose={props.onCancel}
        actions={
          <>
            <Button onClick={() => setAsFriend(false)}>{t('back')}</Button>
            <Button variant="filled" disabled={!name.trim()} onClick={() => props.onFriend(name.trim(), null)}>{t('ics_friend_new')}</Button>
          </>
        }
      >
        <label class="field">
          <span>{t('ics_friend_name')}</span>
          <input autofocus value={name} maxLength={40} autocapitalize="words" onInput={(e) => setName((e.target as HTMLInputElement).value.slice(0, 40))}
            onKeyDown={(e) => { if (e.key === 'Enter' && name.trim()) props.onFriend(name.trim(), null) }} />
        </label>
        {r.icsFriends.length > 0 && (
          <>
            <h3 class="small-heading">{t('ics_friend_update_heading')}</h3>
            <div class="choice-list">
              {r.icsFriends.map((f) => (
                <Choice icon="edit" title={t('ics_friend_update', f.name)} hint={t('ics_friend_update_hint')} onClick={() => props.onFriend(f.name, f.id)} />
              ))}
            </div>
          </>
        )}
      </Dialog>
    )
  }
  return (
    <Dialog
      title={t(r.route === 'askReplaceOrAdd' ? 'ics_partial_title' : 'ics_choice_title')}
      onClose={props.onCancel}
      actions={<Button onClick={props.onCancel}>{t('cancel')}</Button>}
    >
      {r.route === 'askReplaceOrAdd' ? (
        <>
          <p>{t('ics_partial_body', courses, plural('ics_courses', r.currentCourses))}</p>
          <div class="choice-list">
            <Choice icon="file_open" title={t('ics_replace')} hint={t('ics_replace_hint')} onClick={props.onReplace} />
            <Choice icon="add" title={t('ics_add_courses')} hint={t('ics_add_courses_hint')} onClick={props.onAddCourses} />
          </div>
        </>
      ) : (
        <>
          <p>{t('ics_choice_body', courses)}</p>
          <div class="choice-list">
            <Choice icon="group" title={t('ics_as_friend')} hint={t('ics_as_friend_hint')} onClick={() => setAsFriend(true)} />
            <Choice icon="add" title={t('ics_add_courses')} hint={t('ics_add_courses_hint')} onClick={props.onAddCourses} />
            {r.canBeMine && <Choice icon="file_open" title={t('ics_as_mine')} hint={t('ics_as_mine_hint')} onClick={props.onReplace} />}
          </div>
        </>
      )}
    </Dialog>
  )
}
