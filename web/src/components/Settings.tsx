import type { Model } from '../model'
import { dateShort, t } from '../i18n'
import { Button, Dialog } from './ui'

export function Settings(props: {
  model: Model
  onClose: () => void
  onImportIcs: () => void
  onExportBackup: () => void
  onImportBackup: () => void
  onName: () => void
}) {
  const m = props.model
  const me = m.core.people().find((p) => p.isMe)
  const name = m.core.shareIdentity().name
  const last = m.stored.lastBackup
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
      <section class="settings-section">
        <h3>{t('settings_plan')}</h3>
        {me?.planDate && <p class="muted">{t('plan_imported_at', dateShort(me.planDate))}</p>}
        <Button variant="tonal" onClick={props.onImportIcs}>{t('import_file')}</Button>
      </section>
      <section class="settings-section">
        <h3>{t('settings_share')}</h3>
        <button type="button" class="settings-row" onClick={props.onName}>
          <span>{t('share_name_setting')}</span>
          <span class="muted">{name ?? t('share_name_unset')}</span>
        </button>
      </section>
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
