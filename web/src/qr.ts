import qrcode from 'qrcode-generator'

export interface QrMatrix {
  size: number
  version: number
  dark: (r: number, c: number) => boolean
}

/** QR code in alphanumeric mode, ECC L (as the app: `SPMD:` + Base45 is all alphanumeric); null if it doesn't fit. */
export function encodeQr(text: string): QrMatrix | null {
  try {
    const qr = qrcode(0, 'L')
    qr.addData(text, 'Alphanumeric')
    qr.make()
    const size = qr.getModuleCount()
    return { size, version: (size - 17) / 4, dark: (r, c) => qr.isDark(r, c) }
  } catch {
    return null
  }
}

/** Draws [m] dark on white with a 4-module quiet zone, on whole device pixels per module. */
export function drawQr(canvas: HTMLCanvasElement, m: QrMatrix, cssSize: number) {
  const dpr = window.devicePixelRatio || 1
  const modules = m.size + 8
  const scale = Math.max(1, Math.floor((cssSize * dpr) / modules))
  const px = modules * scale
  canvas.width = px
  canvas.height = px
  canvas.style.width = `${px / dpr}px`
  canvas.style.height = `${px / dpr}px`
  const g = canvas.getContext('2d')!
  g.fillStyle = '#fff'
  g.fillRect(0, 0, px, px)
  g.fillStyle = '#000'
  for (let r = 0; r < m.size; r++) for (let c = 0; c < m.size; c++) if (m.dark(r, c)) g.fillRect((c + 4) * scale, (r + 4) * scale, scale, scale)
}
