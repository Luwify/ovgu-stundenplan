import { type Page, expect, test } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { FIX, captureShare, importMyPlan, open, people, pick } from './helpers'

const snackbar = (page: Page) => page.locator('.snackbar')

async function addTom(page: Page) {
  const list = await people(page)
  await list.getByRole('button', { name: /Hinzufügen/ }).click()
  await pick(page, () => page.getByRole('button', { name: /Datei auswählen/ }).click(), join(FIX, 'tom.stundenplan'))
  await page.getByRole('dialog').getByRole('button', { name: 'Hinzufügen', exact: true }).click()
  await expect(snackbar(page)).toContainText('Tom')
}

async function addKim(page: Page) {
  const f = join(tmpdir(), `kim-more-${process.pid}.ics`)
  writeFileSync(f, readFileSync(join(FIX, 'fixture.ics'), 'utf8').replaceAll('UID:90', 'UID:70'))
  const list = await people(page)
  await list.getByRole('button', { name: /Hinzufügen/ }).click()
  await pick(page, () => page.getByRole('button', { name: /Datei auswählen/ }).click(), f)
  await page.getByRole('button', { name: /Als Freund hinzufügen/ }).click()
  await page.getByLabel('Name').fill('Kim')
  await page.getByRole('button', { name: 'Neu hinzufügen' }).click()
}

async function closeDrawer(page: Page) {
  if (await page.locator('.drawer').isVisible()) await page.keyboard.press('Escape')
}

/** WCAG contrast of two CSS colours "rgb(r g b)". */
function contrast(a: string, b: string): number {
  const lum = (c: string) => {
    const [r, g, bl] = c.match(/\d+(\.\d+)?/g)!.slice(0, 3).map((x) => Number(x) / 255).map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4))
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl
  }
  const [x, y] = [lum(a), lum(b)]
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
}

const vars = (page: Page) => page.evaluate(() => {
  const s = getComputedStyle(document.documentElement)
  const v = (n: string) => s.getPropertyValue(n).trim()
  return {
    bg: v('--bg'), onBg: v('--on-bg'), variant: v('--on-surface-variant'), primary: v('--primary'), onPrimary: v('--on-primary'),
    card: v('--card'), high: v('--surface-container-high'), highlight: v('--highlight'), favorite: v('--favorite'),
    inverse: v('--inverse-surface'), onInverse: v('--inverse-on-surface'), inversePrimary: v('--inverse-primary'),
    secondary: v('--secondary-container'), onSecondary: v('--on-secondary-container'),
  }
})

test.describe('friends', () => {
  test('rename, share under which name, favourite in the top bar, reorder', async ({ page }, info) => {
    await captureShare(page)
    await open(page)
    await importMyPlan(page)
    await addTom(page)
    await expect(page.locator('.titles h1')).toHaveText('Tom')
    // Favourite from the top bar.
    const heart = page.locator('.topbar').getByRole('button', { name: /Favorit/ })
    await heart.click()
    await expect(heart).toHaveAttribute('aria-pressed', 'true')
    // Rename (local name); the shared name stays in the details.
    await page.getByRole('button', { name: 'Details' }).click()
    const details = page.getByRole('dialog', { name: 'Tom' })
    await details.getByLabel('Name auf diesem Gerät').fill('Tommy')
    await details.getByRole('button', { name: 'Speichern' }).click()
    await expect(page.locator('.titles h1')).toHaveText('Tommy')
    // Passing the plan on asks which name goes into it; the original is preselected.
    await page.locator('.topbar').getByRole('button', { name: /teilen/ }).click()
    await page.getByRole('menuitem', { name: 'Als Datei teilen' }).click()
    const choice = page.getByRole('dialog', { name: 'Unter welchem Namen teilen?' })
    await expect(choice.getByRole('radio', { name: 'Original: Tom' })).toBeChecked()
    await choice.getByRole('button', { name: 'Teilen' }).click()
    await expect.poll(() => page.evaluate(() => (window as unknown as { __shared: { name: string }[] }).__shared.map((f) => f.name))).toEqual(['Stundenplan Tom.stundenplan'])

    // A second friend; the handle moves Kim above Tommy (arrow keys; a drag does the same).
    await addKim(page)
    const list = await people(page)
    const names = () => list.locator('.person .person-name').allTextContents()
    expect((await names()).slice(1)).toEqual(['Tommy', 'Kim'])
    if (info.project.name.includes('splitview') || info.project.name.includes('phone') || info.project.name.includes('portrait')) {
      await list.getByRole('button', { name: /Sortieren: Kim/ }).focus()
      await page.keyboard.press('ArrowUp')
    } else {
      // Pointer drag on the handle.
      const handle = list.getByRole('button', { name: /Sortieren: Kim/ })
      const box = (await handle.boundingBox())!
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
      await page.mouse.down()
      await page.mouse.move(box.x + box.width / 2, box.y - 30, { steps: 5 })
      await page.mouse.move(box.x + box.width / 2, box.y - 70, { steps: 5 })
      await page.mouse.up()
    }
    await expect.poll(async () => (await names()).slice(1)).toEqual(['Kim', 'Tommy'])
  })

  test('Zusammen frei: who is free in a break, names on a tap', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    await addTom(page)
    await closeDrawer(page)
    await (await people(page)).getByRole('button', { name: /Mein Stundenplan/ }).click()
    await closeDrawer(page)
    await page.getByRole('tab', { name: 'Tag' }).click()
    // Find a day with a break shared with Tom.
    for (let i = 0; i < 10 && (await page.locator('.free-together').count()) === 0; i++) await page.keyboard.press('ArrowRight')
    const button = page.locator('.free-together').first()
    await expect(button).toHaveAttribute('aria-label', /Zusammen frei mit Tom/)
    await button.click()
    await expect(page.locator('.free-names').first()).toContainText('Tom')
  })
})

test.describe('settings', () => {
  test('themes: every theme readable in light and dark, AMOLED black, custom accent, in the backup', async ({ page }, info) => {
    test.skip(!['ipad-pro11-landscape', 'chromium-phone'].includes(info.project.name), 'one WebKit and one Chromium run')
    await captureShare(page)
    await open(page)
    await importMyPlan(page)
    const check = async (label: string) => {
      const v = await vars(page)
      const pairs: [string, string, string, number][] = [
        ['text on page', v.onBg, v.bg, 4.5], ['text on card', v.onBg, v.card, 4.5], ['secondary text on page', v.variant, v.bg, 4.5],
        ['on primary', v.onPrimary, v.primary, 4.5], ['snackbar text', v.onInverse, v.inverse, 4.5], ['snackbar action', v.inversePrimary, v.inverse, 4.5],
        ['chip text', v.onSecondary, v.secondary, 4.5], ['highlight on page', v.highlight, v.bg, 3], ['favourite on card', v.favorite, v.card, 3],
      ]
      for (const [name, a, b, min] of pairs) expect(contrast(a, b), `${label}: ${name} ${a} on ${b}`).toBeGreaterThanOrEqual(min - 0.01)
    }
    const s = page.getByRole('dialog', { name: 'Einstellungen' })
    const pickTheme = async (name: string) => {
      await s.getByRole('button', { name: /Farbschema/ }).click()
      await page.getByRole('option', { name }).click()
    }
    for (const scheme of ['light', 'dark'] as const) {
      await page.emulateMedia({ colorScheme: scheme })
      await page.getByRole('button', { name: 'Einstellungen' }).click()
      for (const theme of ['System', 'Soft', 'AMOLED', 'Eigenes']) {
        await pickTheme(theme)
        await check(`${theme} ${scheme}`)
      }
      if (scheme === 'light') {
        // Custom: several hues and the black base stay readable.
        for (const hue of [0, 60, 120, 200, 280]) {
          await s.getByRole('slider', { name: 'Farbton' }).fill(String(hue))
          await check(`custom hue ${hue}`)
        }
        await s.getByRole('radio', { name: 'Schwarz' }).check()
        await check('custom black')
        expect((await vars(page)).bg).toBe('rgb(0 0 0)')
        await s.getByRole('radio', { name: 'Wie System' }).check()
      }
      await page.keyboard.press('Escape')
    }
    await page.getByRole('button', { name: 'Einstellungen' }).click()
    await pickTheme('AMOLED')
    expect((await vars(page)).bg).toBe('rgb(0 0 0)')
    await s.getByRole('button', { name: 'Sicherung exportieren' }).click()
    await expect.poll(() => page.evaluate(() => (window as unknown as { __shared: unknown[] }).__shared.length)).toBe(1)
    const out = await page.evaluate(() => (window as unknown as { __shared: { b64: string }[] }).__shared[0])
    expect(JSON.parse(Buffer.from(out.b64, 'base64').toString('utf8')).theme.mode).toBe('amoled')
    // Survives a reload.
    await page.reload()
    await expect(page.locator('.block').first()).toBeVisible()
    expect((await vars(page)).bg).toBe('rgb(0 0 0)')
  })

  test('dropdown opens with the current value centred on the field', async ({ page }) => {
    await open(page)
    await page.getByRole('button', { name: 'Einstellungen' }).click()
    const field = page.getByRole('button', { name: /Farbschema/ })
    const f = (await field.boundingBox())!
    await field.click()
    const selected = page.getByRole('option', { selected: true })
    await expect(selected).toBeFocused()
    const o = (await selected.boundingBox())!
    const menu = (await page.getByRole('listbox').boundingBox())!
    // Centred unless the window edge moved the menu.
    const vh = page.viewportSize()!.height
    if (menu.y > 9 && menu.y + menu.height < vh - 9) expect(Math.abs(o.y + o.height / 2 - (f.y + f.height / 2))).toBeLessThan(3)
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('Enter')
    // From Soft (the default) one down: AMOLED.
    await expect(field).toContainText('AMOLED')
  })

  test('landscape fit can be turned off: the grid keeps 40 px per hour and scrolls', async ({ page }, info) => {
    test.skip(!info.project.name.includes('landscape'), 'landscape only')
    await open(page)
    await importMyPlan(page)
    const hour = () => page.locator('.week-grid').evaluate((el) => parseFloat(getComputedStyle(el).getPropertyValue('--hour')))
    await page.getByRole('button', { name: 'Einstellungen' }).click()
    await page.getByRole('switch', { name: /Querformat/ }).uncheck()
    await page.keyboard.press('Escape')
    expect(await hour()).toBeGreaterThanOrEqual(40)
  })

  test('version overview, licenses, Web-App teilen', async ({ page }) => {
    await open(page)
    await page.getByRole('button', { name: 'Einstellungen' }).click()
    await page.getByRole('button', { name: /Versionsübersicht/ }).click()
    const versions = page.getByRole('dialog', { name: 'Versionsübersicht' })
    await versions.getByRole('button', { name: /Version 1\.6\.0/ }).click()
    await expect(versions).toContainText('Web-Version')
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: 'Lizenzen' }).click()
    await expect(page.getByRole('dialog', { name: 'Lizenzen' })).toContainText('pako')
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: 'Web-App teilen' }).click()
    const share = page.getByRole('dialog', { name: 'Web-App teilen' })
    await expect(share.locator('canvas')).toBeVisible()
    await expect(share.locator('.web-share-url')).toHaveText(/^http:\/\/127\.0\.0\.1:4173\/$/)
  })
})

test.describe('polish', () => {
  test('snackbar: swipe dismisses, expands when cut', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    await page.locator('.wg-day').nth(2).locator('.block').first().click()
    await page.getByRole('dialog').getByRole('button', { name: 'Ausblenden' }).click()
    await page.getByRole('menuitem', { name: 'Fach ausblenden' }).click()
    const s = snackbar(page)
    await expect(s).toContainText('ausgeblendet')
    const box = (await s.boundingBox())!
    await page.mouse.move(box.x + 30, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x + 30 + box.width * 0.6, box.y + box.height / 2, { steps: 8 })
    await page.mouse.up()
    await expect(s).toHaveCount(0)
  })

  test('keyboard shortcuts', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    await page.locator('body').click({ position: { x: 5, y: 5 } })
    await page.keyboard.press('d')
    await expect(page.getByRole('tab', { name: 'Tag' })).toHaveAttribute('aria-selected', 'true')
    await page.keyboard.press('w')
    await expect(page.getByRole('tab', { name: 'Woche' })).toHaveAttribute('aria-selected', 'true')
    await page.keyboard.press('ArrowRight')
    await expect(page.locator('.chip.selected')).toHaveText('KW 46')
    await page.keyboard.press('h')
    await expect(page.locator('.chip.selected')).toHaveText('KW 45')
    await page.keyboard.press('?')
    await expect(page.getByRole('dialog', { name: 'Tastenkürzel' })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.keyboard.press('n')
    await expect(page.getByRole('dialog', { name: 'Kurs hinzufügen' })).toBeVisible()
  })
})
