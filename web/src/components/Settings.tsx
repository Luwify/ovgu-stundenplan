import { useState } from 'preact/hooks'
import type { Model } from '../model'
import type { ThemeMode, ThemeSpec } from '../theme'
import { Dropdown } from './Dropdown'
import { Licenses, VersionOverview, WebAppShare } from './About'
import { ColorEditor, CustomThemeEditor } from './ThemeEditor'
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
  systemDark: boolean
  /** The accent the theme shows (after the readability nudge). */
  primary: number
  /** The custom theme while the hue slider moves (shown, not saved yet). */
  draft: ThemeSpec | null
  onDraft: (spec: ThemeSpec | null) => void
  children?: preact.ComponentChildren
}) {
  const m = props.model
  const theme = m.theme
  const [colors, setColors] = useState(false)
  const [about, setAbout] = useState<null | 'versions' | 'licenses' | 'share'>(null)
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
        <Dropdown
          label={t('language_label')}
          options={[['system', t('language_system')], ['de', 'Deutsch'], ['en', 'English']]}
          value={m.stored.language}
          onChange={(l) => void m.setLanguage(l)}
        />
        <Switch label={t('fit_landscape')} hint={t('fit_landscape_hint')} checked={m.fitLandscape} onChange={(on) => void m.setFitLandscape(on)} />
      </section>
      <section class="settings-section">
        <h3>{t('theme_title')}</h3>
        <Dropdown
          label={t('theme_label')}
          options={[['system', t('theme_system')], ['soft', t('theme_soft')], ['amoled', t('theme_amoled')], ['custom', t('theme_custom')]]}
          value={theme.mode}
          onChange={(mode) => void m.setTheme({ ...theme, mode: mode as ThemeMode })}
        />
        {theme.mode === 'custom' && (
          <CustomThemeEditor
            spec={props.draft ?? theme}
            primary={props.primary}
            onChange={(spec, commit) => (commit ? void m.setTheme(spec).then(() => props.onDraft(null)) : props.onDraft(spec))}
            onAdvanced={() => setColors(true)}
          />
        )}
        {colors && <ColorEditor spec={theme} systemDark={props.systemDark} onChange={(spec) => void m.setTheme(spec)} onClose={() => setColors(false)} />}
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
        <button type="button" class="settings-row" onClick={() => setAbout('versions')}>
          <span class="list-text"><span>{t('version_overview')}</span><span class="muted small">{t('app_name')} Web {__APP_VERSION__}</span></span>
          <Icon name="chevron_right" />
        </button>
        <button type="button" class="settings-row" onClick={() => setAbout('share')}>
          <span class="list-text"><span>{t('web_share_title')}</span></span>
          <Icon name="qr_code_2" />
        </button>
        <button type="button" class="settings-row" onClick={() => setAbout('licenses')}>
          <span class="list-text"><span>{t('licenses_title')}</span></span>
          <Icon name="chevron_right" />
        </button>
        {about === 'versions' && <VersionOverview onClose={() => setAbout(null)} />}
        {about === 'licenses' && <Licenses onClose={() => setAbout(null)} />}
        {about === 'share' && <WebAppShare onClose={() => setAbout(null)} onNotify={(text) => m.notify(text)} />}
      </section>
    </Dialog>
  )
}

declare global {
  const __APP_VERSION__: string
}
