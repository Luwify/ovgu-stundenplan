import { type Page, expect } from '@playwright/test'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))

export const FIX = join(here, 'fixtures/generated')
export const meta = () => JSON.parse(readFileSync(join(FIX, 'meta.json'), 'utf8'))

/** A Tuesday in the fixture's semester, 10:30 in Magdeburg. */
export const NOW = new Date('2026-11-03T10:30:00+01:00')

export async function open(page: Page) {
  await page.clock.setFixedTime(NOW)
  await page.goto('/')
}

export async function pick(page: Page, click: () => Promise<void>, file: string) {
  const chooser = page.waitForEvent('filechooser')
  await click()
  await (await chooser).setFiles(file)
}

/** Imports the fixture .ics as my plan from the empty state. */
export async function importMyPlan(page: Page) {
  await pick(page, () => page.getByRole('button', { name: '.ics-Datei importieren' }).first().click(), join(FIX, 'fixture.ics'))
  await expect(page.locator('.snackbar')).toContainText('Importiert')
}

/** Opens the drawer when it isn't a sidebar. */
export async function people(page: Page) {
  const sidebar = page.locator('.sidebar')
  if (await sidebar.isVisible()) return sidebar
  await page.getByRole('button', { name: 'Stundenpläne und Freunde' }).click()
  return page.locator('.drawer')
}

/**
 * A fake camera: getUserMedia returns a canvas stream that shows the given images in turn ([fps]), like a phone
 * showing a code (or an animated one) in front of the camera.
 */
export async function fakeCamera(page: Page, images: string[], fps = 6) {
  // Playwright's Linux WebKit doesn't render a canvas stream in a <video> (readyState stays 0): there the video element
  // reports the canvas's size and drawImage(video) draws the canvas. Chromium plays the stream for real.
  const shimVideo = page.context().browser()?.browserType().name() === 'webkit'
  const urls = images.map((f) => `data:image/png;base64,${readFileSync(f).toString('base64')}`)
  await page.addInitScript(({ urls, fps, shimVideo }) => {
    const fake = async () => {
      const canvas = document.createElement('canvas')
      canvas.width = 1280
      canvas.height = 960
      const g = canvas.getContext('2d')!
      const imgs = await Promise.all(urls.map((u) => new Promise<HTMLImageElement>((ok) => { const i = new Image(); i.onload = () => ok(i); i.src = u })))
      let n = 0
      const draw = () => {
        const img = imgs[n++ % imgs.length]
        g.fillStyle = '#777'
        g.fillRect(0, 0, canvas.width, canvas.height)
        const s = Math.min((canvas.height * 0.9) / img.height, (canvas.width * 0.9) / img.width)
        g.drawImage(img, (canvas.width - img.width * s) / 2, (canvas.height - img.height * s) / 2, img.width * s, img.height * s)
      }
      draw()
      setInterval(draw, 1000 / fps)
      const stream = canvas.captureStream(30)
      ;(stream as unknown as { fakeCanvas: HTMLCanvasElement }).fakeCanvas = canvas
      return stream
    }
    if (shimVideo) {
      const src = (v: HTMLVideoElement) => (v.srcObject as unknown as { fakeCanvas?: HTMLCanvasElement } | null)?.fakeCanvas
      const vp = HTMLVideoElement.prototype
      const desc = (o: object, k: string) => {
        for (let p: object | null = o; p; p = Object.getPrototypeOf(p)) {
          const d = Object.getOwnPropertyDescriptor(p, k)
          if (d) return d
        }
        return undefined
      }
      for (const [k, f] of [
        ['readyState', () => 4],
        ['videoWidth', (c: HTMLCanvasElement) => c.width],
        ['videoHeight', (c: HTMLCanvasElement) => c.height],
      ] as const) {
        const orig = desc(vp, k)!
        Object.defineProperty(vp, k, {
          configurable: true,
          get(this: HTMLVideoElement) { const c = src(this); return c ? f(c) : orig.get!.call(this) },
        })
      }
      const draw = CanvasRenderingContext2D.prototype.drawImage as (...a: unknown[]) => void
      CanvasRenderingContext2D.prototype.drawImage = function (this: CanvasRenderingContext2D, ...a: unknown[]) {
        const c = a[0] instanceof HTMLVideoElement ? src(a[0]) : undefined
        return draw.apply(this, c ? [c, ...a.slice(1)] : a)
      } as typeof CanvasRenderingContext2D.prototype.drawImage
    }
    const proto = (window as unknown as { MediaDevices?: { prototype: MediaDevices } }).MediaDevices?.prototype
    if (proto) Object.defineProperty(proto, 'getUserMedia', { value: fake, configurable: true, writable: true })
    if (navigator.mediaDevices) Object.defineProperty(navigator.mediaDevices, 'getUserMedia', { value: fake, configurable: true, writable: true })
    else Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia: fake }, configurable: true })
  }, { urls, fps, shimVideo })
}

export const frameFiles = () => readdirSync(join(FIX, 'tom-frames')).sort().map((f) => join(FIX, 'tom-frames', f))

/** Captures files given to navigator.share (and blocks the real share sheet): window.__shared. */
export async function captureShare(page: Page) {
  await page.addInitScript(() => {
    const w = window as unknown as { __shared: { name: string; type: string; b64: string }[] }
    w.__shared = []
    navigator.canShare = () => true
    navigator.share = async (data?: ShareData) => {
      for (const f of data?.files ?? []) {
        const bytes = new Uint8Array(await f.arrayBuffer())
        let s = ''
        bytes.forEach((b) => (s += String.fromCharCode(b)))
        w.__shared.push({ name: f.name, type: f.type, b64: btoa(s) })
      }
    }
  })
}
