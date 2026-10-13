package de.luwify.stundenplanmd.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

/**
 * Walks the screens whose first use is slow: app start, week swipe, Week <-> Day, the detail sheet, swiping between
 * people and the settings page. Needs a plan and friends in the target app (fake data, see README), because the lists
 * and the people pager only exist with data. Run: `./gradlew :app:generatePerfBaselineProfile` (device connected).
 */
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "de.luwify.stundenplanmd.preview") {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text(Pattern.compile("Woche|Week"))), 5_000)
        device.waitForIdle()

        // Week pager: forward, back.
        swipe(device, 0.85f, 0.55f, 0.15f, 0.55f)
        swipe(device, 0.85f, 0.55f, 0.15f, 0.55f)
        swipe(device, 0.15f, 0.55f, 0.85f, 0.55f)
        swipe(device, 0.15f, 0.55f, 0.85f, 0.55f)

        // Week <-> Day, with a day swipe.
        tab(device, "Tag|Day")
        swipe(device, 0.85f, 0.55f, 0.15f, 0.55f)
        swipe(device, 0.15f, 0.55f, 0.85f, 0.55f)
        tab(device, "Woche|Week")

        // Detail sheet of an event in the grid, closed with back.
        tap(device, 0.50f, 0.62f)
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()

        // People pager: to the next person and back, in both views.
        swipe(device, 0.5f, 0.75f, 0.5f, 0.25f)
        swipe(device, 0.5f, 0.25f, 0.5f, 0.75f)
        tab(device, "Tag|Day")
        swipe(device, 0.5f, 0.85f, 0.5f, 0.25f)
        swipe(device, 0.5f, 0.2f, 0.5f, 0.8f)
        tab(device, "Woche|Week")

        // Settings (the gear is in my plan's top bar), scroll, back.
        device.findObject(By.desc(Pattern.compile("Einstellungen|Settings")))?.click()
        device.waitForIdle()
        swipe(device, 0.5f, 0.8f, 0.5f, 0.2f)
        swipe(device, 0.5f, 0.2f, 0.5f, 0.8f)
        device.pressBack()
        device.waitForIdle()
    }

    private fun swipe(device: UiDevice, x1: Float, y1: Float, x2: Float, y2: Float) {
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe((w * x1).toInt(), (h * y1).toInt(), (w * x2).toInt(), (h * y2).toInt(), 24)
        device.waitForIdle()
    }

    private fun tap(device: UiDevice, x: Float, y: Float) {
        device.click((device.displayWidth * x).toInt(), (device.displayHeight * y).toInt())
    }

    private fun tab(device: UiDevice, label: String) {
        device.findObject(By.text(Pattern.compile(label)))?.click()
        device.waitForIdle()
    }
}
