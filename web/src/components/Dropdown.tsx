import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import { Icon, layer } from './ui'

const ROW = 48
const PAD = 8

/**
 * The app's SettingsDropdown: a field with its value; the menu opens over it with the current value's row centred on
 * the field, the others above and below, clamped to the window (a list taller than the window fills it and scrolls to
 * the current row). Arrow keys, Home/End, Enter and Escape work as in a listbox.
 */
export function Dropdown<T extends string | null>(props: { label: string; options: [T, string][]; value: T; onChange: (v: T) => void }) {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const field = useRef<HTMLButtonElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const [place, setPlace] = useState<{ top: number; left: number; width: number; height: number; scroll: number } | null>(null)
  const index = Math.max(0, props.options.findIndex(([v]) => v === props.value))
  const current = props.options[index]?.[1] ?? ''

  useLayoutEffect(() => {
    if (!open || !field.current) return
    const r = field.current.getBoundingClientRect()
    const n = props.options.length
    const full = n * ROW + 2 * PAD
    const minTop = 8
    const maxBottom = window.innerHeight - 8
    const height = Math.min(full, maxBottom - minTop)
    // The current row's centre on the field's centre, then moved into the window.
    let top = r.top + r.height / 2 - (PAD + index * ROW + ROW / 2)
    top = Math.max(minTop, Math.min(top, maxBottom - height))
    const scroll = full > height ? Math.max(0, PAD + index * ROW + ROW / 2 - height / 2) : 0
    setPlace({ top, left: r.left, width: Math.max(r.width, 200), height, scroll })
    setActive(index)
  }, [open])

  // Escape (and the dialog under it): the open list is the top layer.
  useEffect(() => {
    if (!open) return
    const l = layer()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && l.isTop()) { e.preventDefault(); e.stopPropagation(); close() } }
    window.addEventListener('keydown', onKey, true)
    return () => { window.removeEventListener('keydown', onKey, true); l.remove() }
  }, [open])

  useEffect(() => {
    if (!open || !place) return
    if (list.current) list.current.scrollTop = place.scroll
    list.current?.querySelectorAll<HTMLElement>('[role="option"]')[index]?.focus()
  }, [place])

  const close = (focus = true) => { setOpen(false); setPlace(null); if (focus) field.current?.focus() }
  const choose = (i: number) => { props.onChange(props.options[i][0]); close() }
  const onKey = (e: KeyboardEvent) => {
    const n = props.options.length
    const move = (i: number) => { e.preventDefault(); setActive(i); list.current?.querySelectorAll<HTMLElement>('[role="option"]')[i]?.focus() }
    if (e.key === 'ArrowDown') move(Math.min(n - 1, active + 1))
    else if (e.key === 'ArrowUp') move(Math.max(0, active - 1))
    else if (e.key === 'Home') move(0)
    else if (e.key === 'End') move(n - 1)
    else if (e.key === 'Tab') close(false)
  }
  return (
    <div class="dropdown">
      <button
        ref={field}
        type="button"
        class="dropdown-field"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen(true)}
      >
        <span class="dropdown-label">{props.label}</span>
        <span class="dropdown-value">{current}</span>
        <Icon name="chevron_down" size={20} />
      </button>
      {open && (
        <div class="dropdown-scrim" onPointerDown={(e) => { if (e.target === e.currentTarget) close() }}>
          {place && (
            <div
              ref={list}
              class="dropdown-menu"
              role="listbox"
              aria-label={props.label}
              tabIndex={-1}
              onKeyDown={onKey}
              style={{ top: `${place.top}px`, left: `${place.left}px`, width: `${place.width}px`, maxHeight: `${place.height}px` }}
            >
              {props.options.map(([v, label], i) => (
                <div
                  role="option"
                  tabIndex={i === active ? 0 : -1}
                  aria-selected={v === props.value}
                  class={`dropdown-option ${v === props.value ? 'selected' : ''}`}
                  onClick={() => choose(i)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(i) } }}
                >
                  <span>{label}</span>
                  {v === props.value && <Icon name="check" size={18} />}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
