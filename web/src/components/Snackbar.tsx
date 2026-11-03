import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import type { Snack } from '../model'
import { t } from '../i18n'
import { Icon, IconButton } from './ui'

/** Visible time: with an action 10 s (Long), without 4 s (Short); sticky ones stay until closed. */
const timeoutOf = (s: Snack) => (s.sticky ? Infinity : s.action ? 10_000 : 4_000)
/** After a pause (touch, hover, expanded) at least this much is left. */
const MIN_LEFT = 4_000
/** Expanded: the timer stays paused until this long without touch. */
const EXPANDED_IDLE = 5_000

/**
 * The app's snackbar (ui/Snackbars.kt): inverse surface, action and close X beside the text; 2 lines + chevron when
 * cut, a tap expands/collapses; the timer pauses while touched, hovered or expanded and leaves at least 4 s after a
 * pause; a swipe left/right (40 % of the width or a fast fling) dismisses like a time-out.
 */
export function Snackbar(props: { snack: Snack; onDismiss: () => void }) {
  const s = props.snack
  const box = useRef<HTMLDivElement>(null)
  const text = useRef<HTMLSpanElement>(null)
  const [expanded, setExpanded] = useState(false)
  const [cut, setCut] = useState(false)
  const [held, setHeld] = useState(false)
  const [dx, setDx] = useState(0)
  const [leaving, setLeaving] = useState<0 | 1 | -1>(0)
  const left = useRef(timeoutOf(s))
  const lastTouch = useRef(Date.now())
  const drag = useRef<{ x: number; y: number; t: number; id: number; horizontal: boolean | null } | null>(null)

  useLayoutEffect(() => {
    const el = text.current
    if (el) setCut(el.scrollHeight > el.clientHeight + 1)
  }, [s.text])

  // The timer: counts only while nothing holds it.
  useEffect(() => {
    if (!Number.isFinite(left.current)) return
    if (held || leaving) return
    if (expanded) {
      const id = setTimeout(() => { if (Date.now() - lastTouch.current >= EXPANDED_IDLE) setExpanded(false) }, EXPANDED_IDLE)
      return () => clearTimeout(id)
    }
    const started = Date.now()
    const id = setTimeout(props.onDismiss, left.current)
    return () => {
      clearTimeout(id)
      left.current = Math.max(MIN_LEFT, left.current - (Date.now() - started))
    }
  }, [held, expanded, leaving, s.id])

  const reduced = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
  const leave = (dir: 1 | -1) => {
    if (reduced) return props.onDismiss()
    setLeaving(dir)
    setTimeout(props.onDismiss, 180)
  }

  const onDown = (e: PointerEvent) => {
    setHeld(true)
    lastTouch.current = Date.now()
    drag.current = { x: e.clientX, y: e.clientY, t: e.timeStamp, id: e.pointerId, horizontal: null }
  }
  const onMove = (e: PointerEvent) => {
    const d = drag.current
    if (!d || d.id !== e.pointerId) return
    const mx = e.clientX - d.x
    const my = e.clientY - d.y
    if (d.horizontal === null && Math.hypot(mx, my) > 8) {
      d.horizontal = Math.abs(mx) > Math.abs(my)
      if (d.horizontal) box.current?.setPointerCapture(e.pointerId)
    }
    if (d.horizontal) setDx(mx)
  }
  const onUp = (e: PointerEvent) => {
    const d = drag.current
    drag.current = null
    setHeld(false)
    lastTouch.current = Date.now()
    if (!d?.horizontal) return
    const width = box.current?.offsetWidth ?? 400
    const velocity = (e.clientX - d.x) / Math.max(1, e.timeStamp - d.t) * 1000 // px/s
    if (Math.abs(dx) > width * 0.4 || Math.abs(velocity) > 1000) leave(dx < 0 || velocity < -1000 ? -1 : 1)
    else setDx(0)
  }

  const foldable = cut || expanded
  return (
    <div
      ref={box}
      class={`snackbar ${leaving ? 'leaving' : ''} ${dx ? 'dragging' : ''}`}
      role="status"
      aria-live="polite"
      style={{
        '--dx': `${leaving ? leaving * (box.current?.offsetWidth ?? 400) : dx}px`,
        opacity: leaving ? 0 : 1 - Math.min(0.6, Math.abs(dx) / ((box.current?.offsetWidth ?? 400) * 1.2)),
      } as Record<string, string | number>}
      onPointerDown={onDown}
      onPointerMove={onMove}
      onPointerUp={onUp}
      onPointerCancel={() => { drag.current = null; setHeld(false); setDx(0) }}
      onPointerEnter={(e) => { if (e.pointerType === 'mouse') setHeld(true) }}
      onPointerLeave={(e) => { if (e.pointerType === 'mouse' && !drag.current) setHeld(false) }}
      onFocusIn={() => setHeld(true)}
      onFocusOut={() => setHeld(false)}
    >
      <button
        type="button"
        class={`snack-text ${expanded || s.sticky ? 'expanded' : ''}`}
        aria-expanded={foldable ? expanded : undefined}
        disabled={!foldable && !s.sticky}
        onClick={() => { if (foldable) { lastTouch.current = Date.now(); setExpanded(!expanded) } }}
      >
        <span ref={text} class="snack-message">{s.text}</span>
        {foldable && !s.sticky && <Icon name="chevron_down" size={18} class={`snack-chevron ${expanded ? 'up' : ''}`} />}
      </button>
      {s.action && (
        <button type="button" class="snack-action" onClick={() => { const a = s.action; props.onDismiss(); a?.run() }}>
          {s.action.label}
        </button>
      )}
      <IconButton icon="close" label={t('close')} onClick={props.onDismiss} />
    </div>
  )
}
