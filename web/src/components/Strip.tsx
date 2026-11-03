import { useEffect, useRef } from 'preact/hooks'

/** Scrolling chips (weeks or days): selected = filled, current = outlined; keeps the selected one in view. */
export function Strip(props: {
  items: { key: string; label: string; title?: string; current?: boolean; muted?: boolean }[]
  selected: string
  onSelect: (key: string) => void
  label: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = ref.current?.querySelector<HTMLElement>('[aria-current="true"], .chip.selected')
    el?.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' })
  }, [props.selected])
  return (
    <div class="strip" ref={ref} role="tablist" aria-label={props.label}>
      {props.items.map((i) => (
        <button
          type="button"
          role="tab"
          aria-selected={i.key === props.selected}
          class={`chip ${i.key === props.selected ? 'selected' : ''} ${i.current ? 'current' : ''} ${i.muted ? 'muted' : ''}`}
          title={i.title}
          onClick={() => props.onSelect(i.key)}
        >
          {i.label}
        </button>
      ))}
    </div>
  )
}
