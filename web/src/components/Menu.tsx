import type { ComponentChildren } from 'preact'
import { useEffect, useRef, useState } from 'preact/hooks'
import { Icon, type IconName, layer } from './ui'

export interface MenuEntry {
  label: string
  /** A heading row (not clickable), like "Verschieben" in the course options. */
  heading?: boolean
  onClick?: () => void
}

/**
 * A menu that opens from its button (above it with [up], else below), closes on Escape, a tap outside or a choice.
 * Arrow keys move between entries. [align]: which edge of the button the panel lines up with.
 */
export function MenuButton(props: {
  entries: MenuEntry[]
  up?: boolean
  align?: 'start' | 'center' | 'end'
  class?: string
  label: string
  children: (open: boolean) => ComponentChildren
  disabled?: boolean
}) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const l = layer()
    const onDown = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => {
      if (!l.isTop()) return
      if (e.key === 'Escape') { e.stopPropagation(); setOpen(false); box.current?.querySelector<HTMLElement>('.menu-trigger')?.focus() }
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        const items = Array.from(box.current?.querySelectorAll<HTMLElement>('.menu-item') ?? [])
        const i = items.indexOf(document.activeElement as HTMLElement)
        items[(i + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus()
      }
    }
    document.addEventListener('pointerdown', onDown, true)
    window.addEventListener('keydown', onKey, true)
    box.current?.querySelector<HTMLElement>('.menu-item')?.focus()
    return () => {
      document.removeEventListener('pointerdown', onDown, true)
      window.removeEventListener('keydown', onKey, true)
      l.remove()
    }
  }, [open])
  return (
    <div class={`menu-box ${props.class ?? ''}`} ref={box}>
      <button
        type="button"
        class="menu-trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={props.label}
        disabled={props.disabled}
        onClick={() => setOpen(!open)}
      >
        {props.children(open)}
      </button>
      {open && (
        <div class={`menu-panel ${props.up ? 'up' : 'down'} align-${props.align ?? 'center'}`} role="menu">
          {props.entries.map((e) =>
            e.heading ? (
              <div class="menu-heading" role="presentation">{e.label}</div>
            ) : (
              <button type="button" role="menuitem" class="menu-item" onClick={() => { setOpen(false); e.onClick?.() }}>
                {e.label}
              </button>
            ),
          )}
        </div>
      )}
    </div>
  )
}

/** One slot of the detail sheet's action row: icon pill + short label; [description] is the full name. */
export interface SheetAction {
  icon: IconName
  label: string
  description: string
  selected?: boolean
  menu?: MenuEntry[]
  enabled?: boolean
  onClick?: () => void
}

const SLOTS = 4

/**
 * The detail sheet's actions in one row of equal columns (the app's SheetActionRow): at most three primaries, the rest
 * and [more] under "Mehr" (left out when empty); fewer columns are centred at the same width.
 */
export function ActionRow(props: { primary: SheetAction[]; more: MenuEntry[]; moreLabel: string; moreDescription: string }) {
  const overflow: MenuEntry[] = [...props.primary.slice(SLOTS - 1).map((a) => ({ label: a.description, onClick: a.onClick })), ...props.more]
  const slots: SheetAction[] = [
    ...props.primary.slice(0, SLOTS - 1),
    ...(overflow.length ? [{ icon: 'more' as IconName, label: props.moreLabel, description: props.moreDescription, menu: overflow }] : []),
  ]
  return (
    <div class="action-row" style={{ '--slots': SLOTS } as Record<string, number>}>
      {slots.map((a, i) => {
        const align = slots.length > 2 && i === 0 ? 'start' : slots.length > 2 && i === slots.length - 1 ? 'end' : 'center'
        const pill = (lit: boolean) => (
          <>
            <span class={`action-pill ${lit ? 'lit' : ''}`}><Icon name={a.icon} size={22} /></span>
            <span class="action-label">{a.label}</span>
          </>
        )
        return a.menu ? (
          <MenuButton class="action-slot" entries={a.menu} up align={align} label={a.description}>
            {(open) => pill(open || !!a.selected)}
          </MenuButton>
        ) : (
          <div class="action-slot">
            <button
              type="button"
              class="menu-trigger"
              aria-label={a.description}
              aria-pressed={a.selected === undefined ? undefined : a.selected}
              disabled={a.enabled === false}
              onClick={a.onClick}
            >
              {pill(!!a.selected)}
            </button>
          </div>
        )
      })}
    </div>
  )
}
