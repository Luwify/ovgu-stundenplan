import type { Model } from '../model'
import { rgbHex } from '../colors'
import { dateShort, dayAndDate, plural, t, timeRange } from '../i18n'
import { Switch } from './Editors'
import { Button, Dialog, Icon, IconButton } from './ui'

export function Settings(props: {
  model: Model
  onClose: () => void
  onImportIcs: () => void
  onExportBackup: () => void
  onImportBackup: () => void
  onName: () => void
  onEditSubject: (subjectKey: string) => void
  onShowHidden: () => void
  onDeleteOrphan: (index: number) => void
  children?: preact.ComponentChildren
}) {
  const m = props.model
  const core = m.core
  const me = core.people().find((p) => p.isMe)
  const identity = core.shareIdentity()
  const last = m.stored.lastBackup
  const subjects = core.edit.subjects('me')
  const hiddenCount = core.edit.hidden().reduce((n, g) => n + g.items.length, 0)
  const orphans = core.edit.orphans()
  return (
    <Dialog title={t('settings')} onClose={props.onClose} wide>
      <section class="settings-section">
        <h3>{t('language_title')}</h3>
        <div class="segmented" role="group" aria-label={t('language_title')}>
          {(['system', 'de', 'en'] as const).map((l) => (
            <button type="button" aria-pressed={m.stored.language === l} onClick={() => void m.setLanguage(l)}>
              {l === 'system' ? t('language_system') : l === 'de' ? t('language_de') : t('language_en')}
            </button>
          ))}
        </div>
      </section>
      {props.children}
      {subjects.length > 0 && (
        <section class="settings-section">
          <h3>{t('settings_subjects')}</h3>
          {subjects.map((s) => (
            <button type="button" class="settings-row subject-row" onClick={() => props.onEditSubject(s.key)}>
              <span class="subject-dot" style={{ background: rgbHex(s.color) }} />
              <span class="list-text">
                <span class="list-title">{s.displayName}</span>
                {s.displayName !== s.name && <span class="muted small">{s.name}</span>}
              </span>
              {s.hidden && <span class="hidden-badge">{t('hidden_badge')}</span>}
              <Icon name="edit" size={20} class="muted" />
            </button>
          ))}
        </section>
      )}
      <section class="settings-section">
        <h3>{t('settings_plan')}</h3>
        {me?.planDate && <p class="muted">{t('plan_imported_at', dateShort(me.planDate))}</p>}
        <Button variant="tonal" onClick={props.onImportIcs}>{t('import_file')}</Button>
      </section>
      <section class="settings-section">
        <h3>{t('settings_share')}</h3>
        <button type="button" class="settings-row" onClick={props.onName}>
          <span class="list-text">
            <span>{t('share_name_setting')}</span>
            <span class="muted small">{identity.name ?? t('share_name_unset')}</span>
          </span>
          <Icon name="edit" size={20} class="muted" />
        </button>
        <Switch
          label={t('share_changes')}
          hint={t('share_changes_hint')}
          checked={identity.withChanges}
          onChange={(on) => { core.edit.setShareChanges(on); void m.commit() }}
        />
      </section>
      <section class="settings-section">
        <h3>{t('hidden_title')}</h3>
        <button type="button" class="settings-row" onClick={props.onShowHidden}>
          <span class="list-text">
            <span>{t('hidden_open')}</span>
            <span class="muted small">{hiddenCount === 0 ? t('hidden_none') : plural('hidden_count', hiddenCount)}</span>
          </span>
        </button>
      </section>
      {orphans.length > 0 && (
        <section class="settings-section">
          <h3>{t('orphans_title')}</h3>
          <p class="muted">{t('orphans_hint')}</p>
          {orphans.map((c) => {
            const what = [
              c.note?.trim() || null,
              c.cancelled ? t('personal_cancelled') : null,
              c.room ? `${t('editor_room')} ${c.room}` : null,
              c.start && c.end ? timeRange(c.start, c.end) : null,
              c.st ? t('st_badge') : null,
            ].filter(Boolean).join(' · ')
            const date = dayAndDate(c.date)
            return (
              <div class="list-row">
                <span class="list-text">
                  <span class="list-title">{c.title}</span>
                  <span class="muted small">{(c.series ? t('orphan_series', date) : date) + (what ? ` · ${what}` : '')}</span>
                </span>
                <IconButton icon="delete" label={t('delete')} onClick={() => props.onDeleteOrphan(c.index)} />
              </div>
            )
          })}
        </section>
      )}
      <section class="settings-section">
        <h3>{t('settings_backup')}</h3>
        <p class="muted">{t('backup_hint_web')}</p>
        <p>{last ? t('backup_last', dateShort(new Date(last).toISOString().slice(0, 10))) : t('backup_never')}</p>
        <div class="row-buttons">
          <Button variant="tonal" onClick={props.onExportBackup}>{t('backup_export')}</Button>
          <Button variant="text" onClick={props.onImportBackup}>{t('backup_import')}</Button>
        </div>
        <p class="muted small">{m.persisted ? t('storage_kept') : t('storage_not_kept')}</p>
      </section>
      <section class="settings-section">
        <h3>{t('about_title')}</h3>
        <p class="muted">{t('about_text')}</p>
        <p class="muted small">{t('app_name')} Web {__APP_VERSION__}</p>
      </section>
    </Dialog>
  )
}

declare global {
  const __APP_VERSION__: string
}
