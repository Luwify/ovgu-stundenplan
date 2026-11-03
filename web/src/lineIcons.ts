// The Android app's own line icons (ui/Icons.kt, drawn from scratch, 24 grid, round stroke): same paths as SVG.

const rr = (l: number, t: number, r: number, b: number, rad: number) =>
  `M${l + rad} ${t}H${r - rad}C${r - rad / 2} ${t} ${r} ${t + rad / 2} ${r} ${t + rad}V${b - rad}` +
  `C${r} ${b - rad / 2} ${r - rad / 2} ${b} ${r - rad} ${b}H${l + rad}C${l + rad / 2} ${b} ${l} ${b - rad / 2} ${l} ${b - rad}` +
  `V${t + rad}C${l} ${t + rad / 2} ${l + rad / 2} ${t} ${l + rad} ${t}Z`

const page = rr(3.5, 4.5, 20.5, 20, 2) + 'M8 3V6M16 3V6'

export const lineIcons = {
  edit: { w: 1.8, d: 'M4 20L4.5 16L15.5 5C16.3 4.2 17.7 4.2 18.5 5L19 5.5C19.8 6.3 19.8 7.7 19 8.5L8 19.5ZM13.5 7L17 10.5' },
  cancel_date: { w: 2, d: page + 'M9.5 11L14.5 16M14.5 11L9.5 16' },
  add_date: { w: 2, d: page + 'M12 10.5V16.5M9 13.5H15' },
  check_date: { w: 2, d: page + 'M8.5 13.5L11 16L15.5 11' },
  remove_date: { w: 2, d: page + 'M9 13.5H15' },
  hide: {
    w: 2,
    d: 'M2.5 12C4.5 8 8 5.5 12 5.5C16 5.5 19.5 8 21.5 12C19.5 16 16 18.5 12 18.5C8 18.5 4.5 16 2.5 12Z' +
      'M15 12C15 13.7 13.7 15 12 15C10.3 15 9 13.7 9 12C9 10.3 10.3 9 12 9C13.7 9 15 10.3 15 12ZM4 4L20 20',
  },
  more: { w: 3, d: 'M5.5 12L5.5 12M12 12L12 12M18.5 12L18.5 12' },
  note: {
    w: 2,
    d: 'M14 20H6C4.9 20 4 19.1 4 18V6C4 4.9 4.9 4 6 4H18C19.1 4 20 4.9 20 6V14L14 20V14H20M8 8.5H16M8 12H12',
  },
  check: { w: 2, d: 'M5 12.5L10 17.5L19 7' },
  chevron_down: { w: 2, d: 'M6 9.5L12 15.5L18 9.5' },
  back: { w: 2, d: 'M19 12H5M11 6L5 12L11 18' },
  drag: { w: 2, d: 'M5 9H19M5 15H19' },
  eye: {
    w: 2,
    d: 'M2.5 12C4.5 8 8 5.5 12 5.5C16 5.5 19.5 8 21.5 12C19.5 16 16 18.5 12 18.5C8 18.5 4.5 16 2.5 12Z' +
      'M15 12C15 13.7 13.7 15 12 15C10.3 15 9 13.7 9 12C9 10.3 10.3 9 12 9C13.7 9 15 10.3 15 12Z',
  },
} as const

export type LineIconName = keyof typeof lineIcons
