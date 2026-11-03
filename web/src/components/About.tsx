import { useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks'
import { notice, releases } from '../generated/changelog'
import { drawQr, readableQr } from '../qr'
import { currentLang, t } from '../i18n'
import { Button, Dialog, Icon } from './ui'

/** The installed version without the build suffix ("1.7.0" of "1.7.0-abc1234"). */
const installed = () => __APP_VERSION__.split('-')[0]

/** Version overview (the app's VersionOverviewScreen): every release with its notes, the running one marked. */
export function VersionOverview(props: { onClose: () => void }) {
  const [open, setOpen] = useState<string | null>(installed())
  return (
    <Dialog title={t('version_overview')} onClose={props.onClose} wide>
      <p class="muted small">{t('app_version')}: {__APP_VERSION__}</p>
      {releases.map((r) => {
        const expanded = open === r.version
        const notes = currentLang() === 'de' ? r.de : r.en
        return (
          <div class="release">
            <button type="button" class="settings-row" aria-expanded={expanded} onClick={() => setOpen(expanded ? null : r.version)}>
              <span class="list-text">
                <span class="list-title">{t('version_title', r.version)}</span>
                {r.version === installed() && <span class="muted small">{t('version_installed')}</span>}
              </span>
              <Icon name="chevron_down" class={expanded ? 'flip' : ''} />
            </button>
            {expanded && <div class="release-notes pre">{notes}</div>}
          </div>
        )
      })}
    </Dialog>
  )
}

/** Licenses: the app's licence and the NOTICE file (third-party material, also the web's libraries). */
export function Licenses(props: { onClose: () => void }) {
  return (
    <Dialog title={t('licenses_title')} onClose={props.onClose} wide>
      <p>{t('license_text')}</p>
      <p><a href="https://github.com/Luwify/ovgu-stundenplan" target="_blank" rel="noopener">github.com/Luwify/ovgu-stundenplan</a></p>
      <pre class="notice">{notice}</pre>
    </Dialog>
  )
}

/** "Web-App teilen" (replaces the app's APK sharing): this page's address as a QR code and as a link. */
export function WebAppShare(props: { onClose: () => void; onNotify: (text: string) => void }) {
  const url = useMemo(() => location.origin + location.pathname.replace(/index\.html$/, ''), [])
  const matrix = useMemo(() => readableQr(url), [url])
  const canvas = useRef<HTMLCanvasElement>(null)
  useLayoutEffect(() => {
    if (canvas.current && matrix) drawQr(canvas.current, matrix, Math.min(280, window.innerWidth - 96))
  }, [matrix])
  const share = async () => {
    try {
      if (navigator.share) return await navigator.share({ url, title: t('app_name') })
    } catch (e) {
      if ((e as DOMException)?.name === 'AbortError') return
    }
    try {
      await navigator.clipboard.writeText(url)
      props.onNotify(t('web_share_copied'))
    } catch {
      props.onNotify(url)
    }
  }
  return (
    <Dialog title={t('web_share_title')} onClose={props.onClose}>
      <div class="web-share">
        <canvas ref={canvas} class="qr-canvas" role="img" aria-label={`QR: ${url}`} />
        <p class="muted">{t('web_share_hint')}</p>
        <p class="web-share-url">{url}</p>
        <Button variant="tonal" onClick={() => void share()}>{t('web_share_link')}</Button>
      </div>
    </Dialog>
  )
}
