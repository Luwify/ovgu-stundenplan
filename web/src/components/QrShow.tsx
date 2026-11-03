import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks'
import { Frames, qrText } from '../core'
import { drawQr, encodeQr, readableQr } from '../qr'
import { t } from '../i18n'
import { Button, Dialog } from './ui'

/** The app's rules (ui/ShareQr): one code up to version 20, else the animation (version 12 frames at 6 fps). */
export const MAX_SINGLE_VERSION = 20
export const FPS = 6

export function QrShow(props: {
  payload: Uint8Array
  name: string
  stand: string | null
  mode: 'single' | 'animated' | null
  onMode: (m: 'single' | 'animated') => void
  onShareFile: () => void
  onClose: () => void
}) {
  // One code: read back first and switch the mask if needed (ShareQr.readable); frames as the app: default mask.
  const text = useMemo(() => qrText(props.payload), [props.payload])
  const fits = useMemo(() => encodeQr(text), [text])
  const auto = fits && fits.version <= MAX_SINGLE_VERSION ? 'single' : 'animated'
  const mode = !fits ? 'animated' : props.mode ?? auto
  const single = useMemo(() => (mode === 'single' ? readableQr(text) : null), [text, mode])
  const frames = useMemo(() => new Frames(props.payload), [props.payload])
  const canvas = useRef<HTMLCanvasElement>(null)
  const box = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState(300)
  const [seq, setSeq] = useState(0)

  useLayoutEffect(() => {
    const el = box.current
    if (!el) return
    const fit = () => setSize(Math.max(160, Math.min(el.clientWidth, window.innerHeight - 260, 640)))
    const ro = new ResizeObserver(fit)
    ro.observe(el)
    fit()
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    if (mode !== 'animated') return
    const id = setInterval(() => setSeq((s) => s + 1), 1000 / FPS)
    return () => clearInterval(id)
  }, [mode])

  useEffect(() => {
    const m = mode === 'single' ? single : encodeQr(frames.frame(seq))
    if (m && canvas.current) {
      drawQr(canvas.current, m, size)
      canvas.current.dataset.drawn = mode === 'single' ? 'single' : String(seq)
      canvas.current.dataset.mask = String(m.mask)
    }
  }, [mode, seq, size, single])

  // Keep the screen on while the code is shown (where the browser supports it).
  useEffect(() => {
    let lock: { release: () => Promise<void> } | null = null
    const nav = navigator as Navigator & { wakeLock?: { request: (t: 'screen') => Promise<{ release: () => Promise<void> }> } }
    nav.wakeLock?.request('screen').then((l) => (lock = l)).catch(() => {})
    return () => { void lock?.release().catch(() => {}) }
  }, [])

  return (
    <Dialog title={t('qr_title')} onClose={props.onClose} wide>
      <div class="qr-layout">
        <div class="qr-box" ref={box}>
          <canvas ref={canvas} class="qr-canvas" data-testid="qr" data-mode={mode} role="img" aria-label={t('qr_title')} />
        </div>
        <div class="qr-text">
          <h3>{props.name}</h3>
          {props.stand && <p class="muted">{t('stand', props.stand)}</p>}
          <p>{t('qr_how_to')}</p>
          {mode === 'animated' && <p class="muted">{t('qr_animated_hint')}</p>}
          {fits && (
            <div class="segmented" role="group">
              <button type="button" aria-pressed={mode === 'single'} onClick={() => props.onMode('single')}>{t('qr_mode_single')}</button>
              <button type="button" aria-pressed={mode === 'animated'} onClick={() => props.onMode('animated')}>{t('qr_mode_animated')}</button>
            </div>
          )}
          <Button variant="tonal" onClick={props.onShareFile}>{t('share_as_file')}</Button>
        </div>
      </div>
    </Dialog>
  )
}
