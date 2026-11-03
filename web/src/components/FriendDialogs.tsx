import { useState } from 'preact/hooks'
import type { PersonInfo } from '../core'
import { dateShort, t } from '../i18n'
import { Button, Dialog, Icon } from './ui'

/** A friend's details (the app's FriendDialog): local name (empty = the shared one), Stand, shared on, remove. */
export function FriendDialog(props: { person: PersonInfo; onRename: (name: string | null) => void; onDelete: () => void; onClose: () => void }) {
  const p = props.person
  const [name, setName] = useState(p.localName ?? '')
  const save = () => props.onRename(name.trim() || null)
  return (
    <Dialog
      title={p.name}
      onClose={props.onClose}
      actions={
        <>
          <Button onClick={props.onClose}>{t('cancel')}</Button>
          <Button variant="filled" onClick={save}>{t('save')}</Button>
        </>
      }
    >
      <label class="field">
        <span>{t('friend_name_label')}</span>
        <input autofocus value={name} maxLength={40} placeholder={p.sharedName} autocapitalize="words"
          onInput={(e) => setName((e.target as HTMLInputElement).value.slice(0, 40))} onKeyDown={(e) => { if (e.key === 'Enter') save() }} />
      </label>
      <div class="friend-facts">
        <p class="small">{p.fromIcs ? t('from_ics_name', p.sharedName) : t('friend_shared_name', p.sharedName)}</p>
        <p>{p.planDate ? t('stand', dateShort(p.planDate)) : ''}</p>
        {!p.fromIcs && <p>{t('friend_shared_on', p.exportedOn ? dateShort(p.exportedOn) : '–')}</p>}
        <p class="muted small">{t(p.fromIcs ? 'friend_update_hint_ics' : 'friend_update_hint')}</p>
      </div>
      <button type="button" class="btn btn-text danger-text" onClick={props.onDelete}>
        <Icon name="delete" size={18} />{t('friend_delete')}
      </button>
    </Dialog>
  )
}

/** Which name goes into a renamed friend's forwarded plan (the app's ShareNameChoiceDialog); the original is preselected. */
export function ShareNameChoice(props: { original: string; local: string; onChoose: (name: string) => void; onClose: () => void }) {
  const [useLocal, setUseLocal] = useState(false)
  return (
    <Dialog
      title={t('share_as_title')}
      onClose={props.onClose}
      actions={
        <>
          <Button onClick={props.onClose}>{t('cancel')}</Button>
          <Button variant="filled" onClick={() => props.onChoose(useLocal ? props.local : props.original)}>{t('share_action')}</Button>
        </>
      }
    >
      <p>{t('share_as_hint')}</p>
      <div class="radio-group" role="radiogroup">
        <label class="radio-row"><input type="radio" name="as" checked={!useLocal} onChange={() => setUseLocal(false)} /><span>{t('share_as_original', props.original)}</span></label>
        <label class="radio-row"><input type="radio" name="as" checked={useLocal} onChange={() => setUseLocal(true)} /><span>{t('share_as_local', props.local)}</span></label>
      </div>
    </Dialog>
  )
}
