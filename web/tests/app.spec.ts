import { expect, test } from '@playwright/test'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { FIX, NOW, captureShare, fakeCamera, frameFiles, importMyPlan, meta, open, people, pick } from './helpers'

const OUT = join(dirname(fileURLToPath(import.meta.url)), '../android-check')

test.describe('my plan', () => {
  test('import, week grid fits, day view, detail', async ({ page }, info) => {
    await open(page)
    await expect(page.getByRole('heading', { name: 'Noch kein Stundenplan' })).toBeVisible()
    await importMyPlan(page)

    const blocks = page.locator('.block')
    await expect(blocks.first()).toBeVisible()
    expect(await blocks.count()).toBeGreaterThan(5)
    // This week (KW 45) is selected and current.
    await expect(page.locator('.chip.selected')).toHaveText('KW 45')

    // No horizontal page scroll at any width.
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)

    const grid = page.locator('.week-grid')
    const { scroll, client, hourPx } = await grid.evaluate((el) => ({
      scroll: el.scrollHeight,
      client: el.clientHeight,
      hourPx: parseFloat(getComputedStyle(el).getPropertyValue('--hour')),
    }))
    const landscape = (page.viewportSize()?.width ?? 0) > (page.viewportSize()?.height ?? 0)
    if (landscape && client > 450) {
      // Squash mode: the whole day range fits without scrolling.
      expect(scroll).toBeLessThanOrEqual(client + 1)
    }
    expect(hourPx).toBeGreaterThanOrEqual(18)

    // Wide windows show the people list as a sidebar, narrow ones a drawer and the tabs at the bottom.
    const width = page.viewportSize()!.width
    await expect(page.locator('.sidebar')).toHaveCount(width >= 1000 ? 1 : 0)
    await expect(page.locator('.bottombar')).toHaveCount(width < 600 ? 1 : 0)

    // Detail of a block.
    await blocks.filter({ hasNot: page.locator('.cancelled') }).first().click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByText('Wann')).toBeVisible()
    await expect(dialog.getByText('Raum', { exact: true })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)

    // Day view: today's cards (Tuesday) with times.
    await page.getByRole('tab', { name: 'Tag' }).click()
    await expect(page.locator('.day-title')).toContainText('Dienstag, 3. November')
    await expect(page.locator('.day-card').first()).toBeVisible()
    await page.screenshot({ path: join(OUT, `layout-${info.project.name}-day.png`) })
    await page.getByRole('tab', { name: 'Woche' }).click()
    await page.screenshot({ path: join(OUT, `layout-${info.project.name}-week.png`) })
  })

  test('arrow keys and the week strip move between weeks', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    await expect(page.locator('.chip.selected')).toHaveText('KW 45')
    await page.locator('.week-grid').click({ position: { x: 5, y: 5 } })
    await page.keyboard.press('ArrowRight')
    await expect(page.locator('.chip.selected')).toHaveText('KW 46')
    await page.keyboard.press('ArrowLeft')
    await page.keyboard.press('ArrowLeft')
    await expect(page.locator('.chip.selected')).toHaveText('KW 44')
    await page.getByRole('button', { name: 'Nächste Woche' }).click()
    await expect(page.locator('.chip.selected')).toHaveText('KW 45')
  })

  test('rotation keeps the plan usable', async ({ page }) => {
    const size = page.viewportSize()!
    test.skip(size.width < 700, 'rotation is checked on the full-size iPad profiles')
    await open(page)
    await importMyPlan(page)
    await page.setViewportSize({ width: size.height, height: size.width })
    await expect(page.locator('.block').first()).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.setViewportSize(size)
    await expect(page.locator('.block').first()).toBeVisible()
  })

  test('data survives a reload (IndexedDB)', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    await page.reload()
    await expect(page.locator('.block').first()).toBeVisible()
  })
})

test.describe('friends', () => {
  test('add by file, switch people, together markers', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    const list = await people(page)
    await list.getByRole('button', { name: 'Hinzufügen' }).click()
    await pick(page, () => page.getByRole('button', { name: /Datei auswählen/ }).click(), join(FIX, 'tom.stundenplan'))
    await expect(page.getByRole('dialog')).toContainText('Stundenplan von Tom hinzufügen?')
    await page.getByRole('dialog').getByRole('button', { name: 'Hinzufügen', exact: true }).click()
    await expect(page.locator('.snackbar')).toContainText('Stundenplan von Tom hinzugefügt.')
    await expect(page.locator('.topbar h1')).toHaveText('Tom')

    // Back to my plan: courses Tom takes too carry the "together" marker, the detail names him.
    const list2 = await people(page)
    await list2.getByRole('button', { name: /Mein Stundenplan/ }).click()
    const together = page.locator('.block .badge-together')
    await expect(together.first()).toBeVisible()
    await together.first().click()
    await expect(page.getByRole('dialog')).toContainText('Zusammen mit')
    await expect(page.getByRole('dialog')).toContainText('Tom')
  })

  test('scan a single QR code (fake camera)', async ({ page }) => {
    await fakeCamera(page, [join(FIX, 'tom-single.png')])
    await open(page)
    await importMyPlan(page)
    const list = await people(page)
    await list.getByRole('button', { name: 'Hinzufügen' }).click()
    await page.getByRole('button', { name: /QR-Code scannen/ }).click()
    await expect(page.getByRole('dialog')).toContainText('Stundenplan von Tom hinzufügen?', { timeout: 20000 })
    await page.getByRole('dialog').getByRole('button', { name: 'Hinzufügen', exact: true }).click()
    await expect(page.locator('.topbar h1')).toHaveText('Tom')
  })

  test('scan an animated QR code with progress (fake camera)', async ({ page }) => {
    // A camera sees the frames in its own rhythm: shuffled start, at the app's 6 fps.
    const frames = frameFiles()
    await fakeCamera(page, [...frames.slice(3), ...frames.slice(0, 3)], 6)
    await open(page)
    await importMyPlan(page)
    const list = await people(page)
    await list.getByRole('button', { name: 'Hinzufügen' }).click()
    await page.getByRole('button', { name: /QR-Code scannen/ }).click()
    await expect(page.getByTestId('scan-status')).toContainText('Animierter Code:', { timeout: 20000 })
    await expect(page.getByRole('dialog')).toContainText('Stundenplan von Tom hinzufügen?', { timeout: 30000 })
  })
})

test.describe('sharing', () => {
  test('my QR code (single and animated) and file, for the Android decoder', async ({ page }, info) => {
    test.skip(info.project.name !== 'ipad-pro11-landscape' && info.project.name !== 'chromium-phone', 'one WebKit and one Chromium run')
    await captureShare(page)
    await open(page)
    await importMyPlan(page)
    const list = await people(page)
    await list.getByRole('button', { name: 'QR-Code' }).click()
    // First share: the name.
    await page.getByRole('textbox', { name: 'Name' }).fill('Alex')
    await page.getByRole('button', { name: 'Speichern' }).click()
    const canvas = page.getByTestId('qr')
    await expect(canvas).toBeVisible()
    const dir = join(OUT, info.project.name)
    mkdirSync(join(dir, 'animated'), { recursive: true })

    // The fixture is larger than version 20: animated by default, like the app.
    await expect(canvas).toHaveAttribute('data-mode', 'animated')
    const seen = new Set<string>()
    for (let i = 0; seen.size < 20 && i < 400; i++) {
      const { seq, url } = await canvas.evaluate((c: HTMLCanvasElement) => ({ seq: c.dataset.drawn!, url: c.toDataURL('image/png') }))
      if (!seen.has(seq)) {
        seen.add(seq)
        writeFileSync(join(dir, 'animated', `frame-${seq.padStart(3, '0')}.png`), Buffer.from(url.split(',')[1], 'base64'))
      }
      await page.waitForTimeout(60)
    }
    expect(seen.size).toBeGreaterThanOrEqual(16)

    await page.getByRole('button', { name: 'Als ein Code' }).click()
    await expect(canvas).toHaveAttribute('data-mode', 'single')
    const single = await canvas.evaluate((c: HTMLCanvasElement) => c.toDataURL('image/png'))
    writeFileSync(join(dir, 'single.png'), Buffer.from(single.split(',')[1], 'base64'))
    // A real screenshot too (scaled by the device pixel ratio, as a camera would see the screen).
    await canvas.screenshot({ path: join(dir, 'single-screenshot.png') })

    await page.getByRole('button', { name: 'Als Datei teilen' }).click()
    await expect.poll(() => page.evaluate(() => (window as unknown as { __shared: unknown[] }).__shared.length)).toBe(1)
    const shared = await page.evaluate(() => (window as unknown as { __shared: { name: string; type: string; b64: string }[] }).__shared[0])
    expect(shared.name).toBe('Stundenplan Alex.stundenplan')
    expect(shared.type).toBe('application/vnd.luwify.stundenplan')
    writeFileSync(join(dir, 'share.stundenplan'), Buffer.from(shared.b64, 'base64'))
  })
})

test.describe('backup', () => {
  test('Android backup in, same format out, extras kept', async ({ page }, info) => {
    test.skip(info.project.name !== 'ipad-gen7-landscape' && info.project.name !== 'chromium-phone', 'one WebKit and one Chromium run')
    await captureShare(page)
    await open(page)
    await pick(page, () => page.getByRole('button', { name: 'Sicherung importieren' }).click(), join(FIX, 'android-backup.json'))
    await expect(page.getByRole('dialog')).toContainText('Sicherung importieren?')
    await page.getByRole('button', { name: 'Ersetzen' }).click()
    await expect(page.locator('.snackbar')).toContainText('Sicherung importiert.')
    await expect(page.locator('.block').first()).toBeVisible()
    const list = await people(page)
    await expect(list.getByText('Tom')).toBeVisible()
    if (await page.locator('.drawer').isVisible()) await page.keyboard.press('Escape')
    await expect(page.locator('.drawer')).toHaveCount(0)

    await page.getByRole('button', { name: 'Einstellungen' }).click()
    await page.getByRole('button', { name: 'Sicherung exportieren' }).click()
    await expect.poll(() => page.evaluate(() => (window as unknown as { __shared: unknown[] }).__shared.length)).toBe(1)
    const out = await page.evaluate(() => (window as unknown as { __shared: { name: string; b64: string }[] }).__shared[0])
    expect(out.name).toMatch(/^stundenplan-sicherung-\d{4}-\d{2}-\d{2}\.json$/)
    const exported = JSON.parse(Buffer.from(out.b64, 'base64').toString('utf8'))
    const original = JSON.parse(readFileSync(join(FIX, 'android-backup.json'), 'utf8'))
    expect(exported.format).toBe(original.format)
    expect(exported.version).toBe(original.version)
    expect(exported.data).toEqual(original.data)
    expect(exported.ics).toBe(original.ics)
    expect(exported.theme).toEqual(original.theme)
    expect(exported.language).toBe(original.language)
    expect(exported.updates).toEqual(original.updates)
    expect(exported.display).toEqual(original.display)
    mkdirSync(OUT, { recursive: true })
    writeFileSync(join(OUT, `backup-${info.project.name}.json`), JSON.stringify(exported))
  })

  test('reminder when no backup was made for a week', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    await expect(page.getByText('Du hast noch keine Sicherung exportiert')).toHaveCount(0)
    // Ten days later.
    await page.clock.setFixedTime(new Date(NOW.getTime() + 10 * 86400000))
    await page.reload()
    await expect(page.getByText('Du hast noch keine Sicherung exportiert')).toBeVisible()
  })
})

test.describe('language', () => {
  test.use({ locale: 'en-US' })
  test('English when the browser prefers it', async ({ page }) => {
    await open(page)
    await expect(page.getByRole('heading', { name: 'No timetable yet' })).toBeVisible()
    await pick(page, () => page.getByRole('button', { name: 'Import .ics file' }).first().click(), join(FIX, 'fixture.ics'))
    await expect(page.locator('.chip.selected')).toHaveText('Week 45')
  })
})

test.describe('service worker', () => {
  test('offline and "new version" banner', async ({ page, context, browserName }) => {
    test.skip(browserName !== 'chromium', 'offline mode with a service worker is only reliable in Playwright\'s Chromium')
    await open(page)
    await page.evaluate(() => navigator.serviceWorker.ready)
    await page.reload()
    await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true)

    await context.setOffline(true)
    await page.reload()
    await expect(page.getByRole('heading', { name: 'Noch kein Stundenplan' })).toBeVisible()
    await context.setOffline(false)

    // A new build: sw.js with another version (same length: the preview server keeps the file size it saw at start).
    const file = join(dirname(fileURLToPath(import.meta.url)), '../dist/sw.js')
    const sw = readFileSync(file, 'utf8')
    const next = sw.replace(/const VERSION = "([^"]+)"/, (_, v: string) => `const VERSION = "${v.slice(0, -4)}next"`)
    expect(next).not.toBe(sw)
    writeFileSync(file, next)
    try {
      await page.evaluate(() => navigator.serviceWorker.getRegistration().then((r) => r?.update()))
      await expect(page.getByText('Neue Version verfügbar')).toBeVisible({ timeout: 15000 })
    } finally {
      writeFileSync(file, sw)
    }
    await page.getByRole('button', { name: 'Neu laden' }).click()
    await page.waitForLoadState('load')
    await expect(page.getByText('Neue Version verfügbar')).toHaveCount(0)
  })
})

void NOW
void meta
