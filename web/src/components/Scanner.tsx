import jsQR from 'jsqr'
import { useEffect, useRef, useState } from 'preact/hooks'
import { FrameCollector } from '../core'
import { t } from '../i18n'
import { Button, Dialog } from './ui'

/**
 * Camera scanner: frames stay in memory (a canvas), nothing is stored or sent. Single codes go straight to [onText];
 * frames of an animated code are collected (progress in %) and the finished share goes to [onText] too.
 */
export function Scanner(props: { onText: (text: string) => void; onClose: () => void; onPickFile: () => void }) {
  const video = useRef<HTMLVideoElement>(null)
  const [error, setError] = useState<string | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [hint, setHint] = useState<string | null>(null)

  useEffect(() => {
    let stream: MediaStream | null = null
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const frames = new FrameCollector()
    const canvas = document.createElement('canvas')
    const g = canvas.getContext('2d', { willReadFrequently: true })!
    let lastText = ''

    const tick = () => {
      if (stopped) return
      const v = video.current
      if (v && v.readyState >= 2 && v.videoWidth > 0) {
        const scale = Math.min(1, 1000 / Math.max(v.videoWidth, v.videoHeight))
        canvas.width = Math.round(v.videoWidth * scale)
        canvas.height = Math.round(v.videoHeight * scale)
        g.drawImage(v, 0, 0, canvas.width, canvas.height)
        const img = g.getImageData(0, 0, canvas.width, canvas.height)
        const code = jsQR(img.data, img.width, img.height, { inversionAttempts: 'attemptBoth' })
        const text = code?.data
        if (text && text !== lastText) {
          lastText = text
          const p = frames.offer(text)
          if (p >= 0) {
            setProgress(Math.floor(p * 100))
            const done = frames.result()
            if (done) { stopped = true; props.onText(done); return }
          } else if (text.trim().startsWith('SPMD:')) {
            stopped = true
            props.onText(text)
            return
          } else {
            setHint(t('scan_other_code'))
          }
        }
      }
      timer = setTimeout(tick, 80)
    }

    navigator.mediaDevices
      ?.getUserMedia({ video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false })
      .then((s) => {
        if (stopped) { s.getTracks().forEach((tr) => tr.stop()); return }
        stream = s
        const v = video.current!
        v.srcObject = s
        v.setAttribute('playsinline', '')
        v.muted = true
        void v.play().catch(() => {})
        tick()
      })
      .catch((e: DOMException) => setError(e?.name === 'NotAllowedError' ? t('scan_no_permission') : t('scan_camera_failed')))
    if (!navigator.mediaDevices?.getUserMedia) setError(t('scan_camera_failed'))

    return () => {
      stopped = true
      clearTimeout(timer)
      stream?.getTracks().forEach((tr) => tr.stop())
    }
  }, [])

  return (
    <Dialog title={t('scan_title')} onClose={props.onClose} wide>
      <div class="scanner">
        {error ? (
          <p class="error">{error}</p>
        ) : (
          <video ref={video} class="scan-video" playsInline muted autoPlay data-testid="scan-video" />
        )}
        <p class="scan-status" data-testid="scan-status" aria-live="polite">
          {progress !== null ? t('scan_progress', progress) : hint ?? t('scan_hint')}
        </p>
        <Button variant="text" onClick={props.onPickFile}>{t('scan_file_instead')}</Button>
      </div>
    </Dialog>
  )
}
