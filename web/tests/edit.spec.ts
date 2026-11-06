import { type Page, expect, test } from '@playwright/test'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { FIX, captureShare, importMyPlan, open, people, pick } from './helpers'

const OUT = join(dirname(fileURLToPath(import.meta.url)), '../android-check')

/** Wednesday 4.11. 13–15 Numerik N4 (VL), weekly. */
const numerik = (page: Page) => page.locator('.wg-day').nth(2).locator('.block', { hasText: /Num/ }).first()
const sheet = (page: Page) => page.getByRole('dialog')
const snackbar = (page: Page) => page.locator('.snackbar')

async function action(page: Page, name: string | RegExp) {
  await sheet(page).getByRole('button', { name }).click()
}

async function menu(page: Page, button: string | RegExp, entry: string | RegExp) {
  await sheet(page).getByRole('button', { name: button }).click()
  await page.getByRole('menuitem', { name: entry }).click()
}

/** A friend's export: the fixture under other course ids (so its courses can be added to mine). */
function friendIcs(): string {
  const f = join(tmpdir(), `kim-${process.pid}.ics`)
  writeFileSync(f, readFileSync(join(FIX, 'fixture.ics'), 'utf8').replaceAll('UID:90', 'UID:70'))
  return f
}

async function settings(page: Page) {
  await page.getByRole('button', { name: 'Einstellungen' }).click()
  return page.getByRole('dialog', { name: 'Einstellungen' })
}

test.describe('editing my plan', () => {
  test('subject editor: short names per type with preview, colour, rhythm, move', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    await numerik(page).click()
    await menu(page, 'Weitere Aktionen', 'Fach bearbeiten')
    const editor = page.getByRole('dialog', { name: 'Fach bearbeiten' })
    await editor.getByLabel('Kürzel (alle Typen)').fill('Num')
    // Opened from the lecture: its part is edited; the preview shows prefix + part.
    await expect(editor.getByRole('button', { name: 'Vorlesung', exact: true })).toHaveAttribute('aria-pressed', 'true')
    await editor.getByRole('textbox', { name: 'Kürzel (dieser Typ)' }).fill('VL')
    await expect(editor.locator('.preview-chip')).toHaveText('Num VL')
    await editor.locator('.swatch').nth(3).click()
    await editor.getByRole('button', { name: 'OK' }).click()
    await expect(numerik(page)).toHaveAttribute('aria-label', /^Num VL,/)
    // Back in the sheet, which follows its date.
    await expect(sheet(page).locator('.detail-kicker')).toContainText('Num')

    // Rhythm of the biweekly lecture series (Tuesdays): set by hand, applies at once.
    await menu(page, 'Weitere Aktionen', 'Fach bearbeiten')
    const tue = editor.locator('.rhythm-row', { hasText: 'Di' }).first()
    await tue.getByRole('button').click()
    await page.getByRole('menuitem', { name: 'Ungerade KW', exact: true }).click()
    await expect(tue.getByRole('button')).toContainText('Ungerade KW')

    // Move the exercise (HSÜ) to a subject of its own: it leaves this list.
    const rows = editor.locator('.course-row')
    const before = await rows.count()
    const hsue = rows.filter({ hasText: 'HSÜ' })
    await hsue.getByRole('button', { name: 'Optionen' }).click()
    await page.getByRole('menuitem', { name: 'Eigenes Fach' }).click()
    await expect(rows).toHaveCount(before - 1)
    await editor.getByRole('button', { name: 'Abbrechen' }).click()
    // The sheet comes back after the editor; close it.
    await expect(sheet(page).locator('.detail-title')).toBeVisible()
    await page.keyboard.press('Escape')

    // The settings list every subject; the moved course is one of its own now.
    const s = await settings(page)
    await expect(s.locator('.subject-row', { hasText: 'Num' }).first()).toBeVisible()
    expect(await s.locator('.subject-row').count()).toBeGreaterThan(8)
  })

  test('own changes: note and room, cancelled for me with undo, Termine auswählen', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    await numerik(page).click()
    await action(page, 'Notiz & Änderungen')
    const editor = page.getByRole('dialog', { name: 'Notiz & Änderungen' })
    await editor.getByLabel('Notiz').fill('Übungsblatt mitbringen')
    await editor.getByLabel('Raum').fill('K99-001')
    await editor.getByRole('button', { name: 'Speichern' }).click()
    await expect(sheet(page).locator('.info-box.own')).toContainText('Von dir geändert')
    await expect(sheet(page).locator('.info-box.own')).toContainText('K99-001')
    await expect(sheet(page)).toContainText('Übungsblatt mitbringen')
    await page.keyboard.press('Escape')
    await expect(numerik(page)).toHaveClass(/\bown\b/)
    await expect(numerik(page).locator('.badge.own')).toHaveCount(1)

    // Cancelled for me, then undone from the snackbar.
    await numerik(page).click()
    await action(page, /Fällt für mich aus/)
    await expect(snackbar(page)).toContainText('fällt für dich aus')
    await expect(sheet(page).getByRole('button', { name: 'Findet doch statt' })).toHaveAttribute('aria-pressed', 'true')
    await snackbar(page).getByRole('button', { name: 'Rückgängig' }).click()
    await expect(sheet(page).getByRole('button', { name: /Fällt für mich aus/ })).toHaveAttribute('aria-pressed', 'false')

    // Termine auswählen: two dates unchecked and hidden; they are in the Ausgeblendet list and come back from there.
    await menu(page, 'Weitere Aktionen', 'Termine auswählen')
    const pickDialog = page.getByRole('dialog', { name: /Termine von/ })
    const boxes = pickDialog.getByRole('checkbox')
    expect(await boxes.count()).toBeGreaterThan(10)
    await boxes.nth(4).uncheck()
    await boxes.nth(5).uncheck()
    await expect(pickDialog.getByRole('button', { name: 'Speichern' })).toBeDisabled()
    await pickDialog.getByRole('radio', { name: 'Ausblenden' }).check()
    await pickDialog.getByRole('button', { name: 'Speichern' }).click()
    await page.keyboard.press('Escape')
    const s = await settings(page)
    await expect(s.getByRole('button', { name: /Ausgeblendete Fächer und Termine/ })).toContainText('2')
    await s.getByRole('button', { name: /Ausgeblendete Fächer und Termine/ }).click()
    const hidden = page.getByRole('dialog', { name: 'Ausgeblendet' })
    await expect(hidden.locator('.list-row')).toHaveCount(2)
    await hidden.getByRole('button', { name: 'Wiederherstellen' }).first().click()
    await expect(hidden.locator('.list-row')).toHaveCount(1)
  })

  test('hiding: Fach / Termin with undo, Ausgeblendet screen restores', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    const count = await page.locator('.block').count()
    await numerik(page).click()
    await menu(page, 'Ausblenden', 'Fach ausblenden')
    await expect(sheet(page)).toHaveCount(0)
    await expect(snackbar(page)).toContainText('ausgeblendet')
    await expect(page.locator('.block', { hasText: /Numerik/ })).toHaveCount(0)
    await snackbar(page).getByRole('button', { name: 'Rückgängig' }).click()
    await expect(page.locator('.block')).toHaveCount(count)

    // The menu offers only this group when the course has several (the Tuesday lecture is another group).
    await numerik(page).click()
    await sheet(page).getByRole('button', { name: 'Ausblenden' }).click()
    await expect(page.getByRole('menuitem')).toHaveText(['Termin ausblenden', 'Fach ausblenden', 'Nur Vorlesung ausblenden', 'Nur diese Gruppe ausblenden'])
    await page.getByRole('menuitem', { name: 'Termin ausblenden' }).click()
    await expect(page.locator('.block')).toHaveCount(count - 1)
    await page.getByRole('button', { name: 'Einstellungen' }).click()
    await page.getByRole('button', { name: /Ausgeblendete Fächer und Termine/ }).click()
    const hidden = page.getByRole('dialog', { name: 'Ausgeblendet' })
    await expect(hidden).toContainText('Termin')
    await hidden.getByRole('button', { name: 'Wiederherstellen' }).click()
    await expect(hidden.getByText('Nichts ausgeblendet', { exact: false })).toBeVisible()
    await page.keyboard.press('Escape')
    await page.keyboard.press('Escape')
    await expect(page.locator('.block')).toHaveCount(count)
  })

  test('manual course on a Saturday: own column, badge, edit, delete with undo', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    await expect(page.locator('.wg-day')).toHaveCount(5)
    await page.getByRole('button', { name: 'Kurs hinzufügen' }).click()
    const editor = page.getByRole('dialog', { name: 'Kurs hinzufügen' })
    await editor.getByLabel('Titel').fill('Lerngruppe')
    await editor.getByRole('button', { name: 'Seminar' }).click()
    await editor.getByLabel('Erster Termin').fill('2026-11-07')
    const start = editor.getByLabel('Beginn', { exact: true })
    const end = editor.getByLabel('Ende', { exact: true })
    await expect(start).toHaveAttribute('type', 'time')
    await expect(start).toHaveValue('09:15')
    await start.fill('10:00')
    await end.fill('09:00')
    await expect(editor.getByRole('button', { name: 'Speichern' })).toBeDisabled()
    await expect(editor).toContainText('Ende nach Beginn')
    await end.fill('12:00')
    await editor.getByRole('button', { name: 'Speichern' }).click()
    await expect(page.locator('.wg-day')).toHaveCount(6)
    const block = page.locator('.wg-day').nth(5).locator('.block', { hasText: 'Lerngruppe' })
    await expect(block.locator('.badge.manual')).toHaveCount(1)
    await block.click()
    await expect(sheet(page)).toContainText('Eigener Kurs')
    await menu(page, 'Weitere Aktionen', 'Kurs bearbeiten')
    await page.getByRole('dialog', { name: 'Kurs bearbeiten' }).getByLabel('Titel').fill('Lerngruppe Numerik')
    await page.getByRole('dialog', { name: 'Kurs bearbeiten' }).getByRole('button', { name: 'Speichern' }).click()
    await expect(sheet(page).locator('.detail-title')).toHaveText('Lerngruppe Numerik')
    await menu(page, 'Weitere Aktionen', 'Kurs löschen')
    await expect(page.locator('.wg-day')).toHaveCount(5)
    await snackbar(page).getByRole('button', { name: 'Rückgängig' }).click()
    await expect(page.locator('.wg-day')).toHaveCount(6)
  })
})

test.describe('friends from .ics', () => {
  test('.ics as a friend, Zu meinem Plan and back', async ({ page }) => {
    await open(page)
    await importMyPlan(page)
    const list = await people(page)
    await list.getByRole('button', { name: /Hinzufügen/ }).click()
    await pick(page, () => page.getByRole('button', { name: /Datei auswählen/ }).click(), friendIcs())
    await page.getByRole('button', { name: /Als Freund hinzufügen/ }).click()
    await page.getByLabel('Name').fill('Kim')
    await page.getByRole('button', { name: 'Neu hinzufügen' }).click()
    await expect(snackbar(page)).toContainText('Kim')
    await expect(page.locator('.titles')).toContainText('aus ICS importiert')
    // A friend's date: "Zu meinem Plan", then it is in my plan (as an added course) and can be removed again.
    const block = page.locator('.wg-day').nth(3).locator('.block').first()
    await block.click()
    await action(page, 'Zu meinem Plan')
    await expect(snackbar(page)).toContainText('zu deinem Plan hinzugefügt')
    await expect(sheet(page).getByRole('button', { name: 'Entfernen' })).toBeVisible()
    await action(page, 'Entfernen')
    await expect(sheet(page).getByRole('button', { name: 'Zu meinem Plan' })).toBeVisible()
  })
})

test.describe('backup round trips', () => {
  test('Android backup with every kind of edit: in, shown, out unchanged', async ({ page }, info) => {
    test.skip(info.project.name !== 'ipad-gen7-landscape' && info.project.name !== 'chromium-phone', 'one WebKit and one Chromium run')
    await captureShare(page)
    await open(page)
    await pick(page, () => page.getByRole('button', { name: 'Sicherung importieren' }).click(), join(FIX, 'android-backup-full.json'))
    await page.getByRole('button', { name: 'Ersetzen' }).click()
    await expect(snackbar(page)).toContainText('Sicherung importiert.')
    // The manual course on Saturdays, the .ics friend renamed and favourite, things hidden.
    await expect(page.locator('.wg-day')).toHaveCount(6)
    const list = await people(page)
    await expect(list.getByText('Kimmy')).toBeVisible()
    if (await page.locator('.drawer').isVisible()) await page.keyboard.press('Escape')
    const s = await settings(page)
    await expect(s.getByRole('button', { name: /Ausgeblendete Fächer und Termine/ })).not.toContainText('Nichts')
    await s.getByRole('button', { name: 'Sicherung exportieren' }).click()
    await expect.poll(() => page.evaluate(() => (window as unknown as { __shared: unknown[] }).__shared.length)).toBe(1)
    const out = await page.evaluate(() => (window as unknown as { __shared: { b64: string }[] }).__shared[0])
    const exported = JSON.parse(Buffer.from(out.b64, 'base64').toString('utf8'))
    const original = JSON.parse(readFileSync(join(FIX, 'android-backup-full.json'), 'utf8'))
    expect(exported.data).toEqual(original.data)
    mkdirSync(OUT, { recursive: true })
    writeFileSync(join(OUT, `full-roundtrip-${info.project.name}.json`), JSON.stringify(exported))
  })

  test('a backup made on the web with every new kind of data, for the JVM', async ({ page }, info) => {
    test.skip(info.project.name !== 'ipad-pro11-landscape' && info.project.name !== 'chromium-phone', 'one WebKit and one Chromium run')
    await captureShare(page)
    await open(page)
    await importMyPlan(page)
    // Short names (prefix + type part), a rhythm, a note with another room, a date cancelled for me, a hidden date.
    await numerik(page).click()
    await menu(page, 'Weitere Aktionen', 'Fach bearbeiten')
    const subject = page.getByRole('dialog', { name: 'Fach bearbeiten' })
    await subject.getByLabel('Kürzel (alle Typen)').fill('Num')
    await subject.getByRole('textbox', { name: 'Kürzel (dieser Typ)' }).fill('VL')
    await subject.locator('.rhythm-row').first().getByRole('button').click()
    await page.getByRole('menuitem', { name: 'Gerade KW', exact: true }).click()
    await subject.getByRole('button', { name: 'OK' }).click()
    await action(page, 'Notiz & Änderungen')
    await page.getByRole('textbox', { name: 'Notiz', exact: true }).fill('Blatt 3')
    await page.getByRole('textbox', { name: 'Raum', exact: true }).fill('K99-001')
    await page.getByRole('button', { name: 'Speichern' }).click()
    await page.keyboard.press('Escape')
    const thursday = page.locator('.wg-day').nth(3).locator('.block')
    await thursday.first().click()
    await action(page, /Fällt für mich aus/)
    await page.keyboard.press('Escape')
    await thursday.last().click()
    await menu(page, 'Ausblenden', 'Termin ausblenden')
    // A manual course, an .ics friend and one of their courses in my plan.
    await page.getByRole('button', { name: 'Kurs hinzufügen' }).click()
    await page.getByRole('textbox', { name: 'Titel', exact: true }).fill('Lerngruppe')
    await page.getByRole('button', { name: 'Speichern' }).click()
    const list = await people(page)
    await list.getByRole('button', { name: /Hinzufügen/ }).click()
    await pick(page, () => page.getByRole('button', { name: /Datei auswählen/ }).click(), friendIcs())
    await page.getByRole('button', { name: /Als Freund hinzufügen/ }).click()
    await page.getByLabel('Name').fill('Kim')
    await page.getByRole('button', { name: 'Neu hinzufügen' }).click()
    await page.locator('.wg-day').nth(3).locator('.block').first().click()
    await action(page, 'Zu meinem Plan')
    await page.keyboard.press('Escape')

    const list2 = await people(page)
    await list2.getByRole('button', { name: /Mein Stundenplan/ }).click()
    const s = await settings(page)
    await s.getByRole('button', { name: 'Sicherung exportieren' }).click()
    await expect.poll(() => page.evaluate(() => (window as unknown as { __shared: unknown[] }).__shared.length)).toBe(1)
    const out = await page.evaluate(() => (window as unknown as { __shared: { b64: string }[] }).__shared[0])
    mkdirSync(OUT, { recursive: true })
    writeFileSync(join(OUT, `web-edits-${info.project.name}.json`), Buffer.from(out.b64, 'base64'))
  })
})
