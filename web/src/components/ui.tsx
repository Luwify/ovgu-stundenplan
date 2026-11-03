import type { ComponentChildren, JSX } from 'preact'
import { useEffect, useRef } from 'preact/hooks'
import { iconPaths, type IconName as MaterialName } from '../iconPaths'
import { lineIcons, type LineIconName } from '../lineIcons'
import { t } from '../i18n'

export type IconName = MaterialName | LineIconName

export function Icon({ name, size = 24, class: cls }: { name: IconName; size?: number; class?: string }) {
  if (name in lineIcons) {
    const l = lineIcons[name as LineIconName]
    return (
      <svg class={`icon ${cls ?? ''}`} width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
        <path d={l.d} fill="none" stroke="currentColor" stroke-width={l.w} stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    )
  }
  const p = iconPaths[name as MaterialName]
  return (
    <svg class={`icon ${cls ?? ''}`} width={size} height={size} viewBox={`0 0 ${p.vb} ${p.vb}`} aria-hidden="true">
      <path d={p.d} fill="currentColor" />
    </svg>
  )
}

export function IconButton(props: { icon: IconName; label: string; onClick: () => void; disabled?: boolean; class?: string; pressed?: boolean }) {
  return (
    <button
      type="button"
      class={`icon-button ${props.class ?? ''}`}
      aria-label={props.label}
      title={props.label}
      aria-pressed={props.pressed}
      disabled={props.disabled}
      onClick={props.onClick}
    >
      <Icon name={props.icon} />
    </button>
  )
}

/** A modal dialog (centered; a bottom sheet on narrow screens with [sheet]); Escape and the scrim close it. */
export function Dialog(props: {
  title?: string
  onClose: () => void
  children: ComponentChildren
  actions?: ComponentChildren
  sheet?: boolean
  wide?: boolean
  label?: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null
    const first = ref.current?.querySelector<HTMLElement>('[autofocus], button, input, a[href]')
    ;(first ?? ref.current)?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); props.onClose() }
    }
    window.addEventListener('keydown', onKey, true)
    return () => {
      window.removeEventListener('keydown', onKey, true)
      prev?.focus?.()
    }
  }, [])
  return (
    <div class={`scrim ${props.sheet ? 'scrim-sheet' : ''}`} onClick={(e) => e.target === e.currentTarget && props.onClose()}>
      <div
        ref={ref}
        class={`dialog ${props.sheet ? 'sheet' : ''} ${props.wide ? 'dialog-wide' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-label={props.label ?? props.title}
        tabIndex={-1}
      >
        {props.title && (
          <div class="dialog-head">
            <h2>{props.title}</h2>
            <IconButton icon="close" label={t('close')} onClick={props.onClose} />
          </div>
        )}
        <div class="dialog-body">{props.children}</div>
        {props.actions && <div class="dialog-actions">{props.actions}</div>}
      </div>
    </div>
  )
}

export function Button(props: JSX.HTMLAttributes<HTMLButtonElement> & { variant?: 'filled' | 'tonal' | 'text'; disabled?: boolean }) {
  const { variant = 'text', class: cls, ...rest } = props
  return <button type="button" class={`btn btn-${variant} ${cls ?? ''}`} {...rest} />
}

let fileInput: HTMLInputElement | null = null

/**
 * Opens the file picker; [accept] null = any file (iOS greys out unknown extensions like .stundenplan otherwise). One
 * hidden input stays in the page (removing it while the picker is open loses the file in some browsers).
 */
export function pickFile(accept: string | null): Promise<File | null> {
  fileInput?.remove()
  const input = document.createElement('input')
  fileInput = input
  input.type = 'file'
  if (accept) input.accept = accept
  input.style.display = 'none'
  document.body.appendChild(input)
  const result = new Promise<File | null>((resolve) => {
    input.addEventListener('change', () => resolve(input.files?.[0] ?? null), { once: true })
    input.addEventListener('cancel', () => resolve(null), { once: true })
  })
  input.click()
  return result
}
