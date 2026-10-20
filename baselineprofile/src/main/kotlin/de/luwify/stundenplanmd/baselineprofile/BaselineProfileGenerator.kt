package de.luwify.stundenplanmd.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

/** The copy the walk runs in (never the real app). */
private const val TARGET = "de.luwify.stundenplanmd.preview"

/**
 * Walks the screens whose first use is slow: app start, week swipe, Week <-> Day, the detail sheet with its "Mehr"
 * menu, swiping between people and the settings page. Needs a plan and friends in the target app (fake data, see
 * README), because the lists and the people pager only exist with data. Run: `./gradlew :app:generateBaselineProfile`
 * (device connected).
 *
 * Every lookup is limited to the target app, Back is only pressed where a sheet, menu or page is open, and each section
 * checks that the app is still in front: a step that misses (another day's plan) must never send input elsewhere.
 */
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(inApp().text(Pattern.compile("Woche|Week"))), 5_000)
        device.waitForIdle()
        inFront(device)
        tab(device, "Woche|Week")

        // Week pager: forward, back.
        swipe(device, 0.85f, 0.55f, 0.15f, 0.55f)
        swipe(device, 0.85f, 0.55f, 0.15f, 0.55f)
        swipe(device, 0.15f, 0.55f, 0.85f, 0.55f)
        swipe(device, 0.15f, 0.55f, 0.85f, 0.55f)
        inFront(device)

        // Week <-> Day, with a day swipe.
        tab(device, "Tag|Day")
        swipe(device, 0.85f, 0.55f, 0.15f, 0.55f)
        swipe(device, 0.15f, 0.55f, 0.85f, 0.55f)
        inFront(device)

        // Detail sheet of a course in the day list (its start time), with its "Mehr" menu (grows out of the button).
        // A course is found wherever it is today; without one (a free day) this part is skipped.
        device.findObject(inApp().text(Pattern.compile("\\d{1,2}:\\d{2}")))?.let { course ->
            course.click()
            val moreActions = inApp().desc(Pattern.compile("Weitere Aktionen|More actions"))
            device.wait(Until.findObject(moreActions), 3_000)?.let { more ->
                more.click()
                device.waitForIdle()
                // Back closes the menu, then the sheet: the second only while the sheet is still there.
                device.pressBack()
                device.waitForIdle()
                if (device.hasObject(moreActions)) {
                    device.pressBack()
                    device.waitForIdle()
                }
            }
        }
        inFront(device)
        tab(device, "Woche|Week")

        // People pager: to the next person and back, in both views.
        swipe(device, 0.5f, 0.75f, 0.5f, 0.25f)
        swipe(device, 0.5f, 0.25f, 0.5f, 0.75f)
        tab(device, "Tag|Day")
        swipe(device, 0.5f, 0.85f, 0.5f, 0.25f)
        swipe(device, 0.5f, 0.2f, 0.5f, 0.8f)
        tab(device, "Woche|Week")
        inFront(device)

        // Settings (the gear is in my plan's top bar), scroll, back.
        device.findObject(inApp().desc(Pattern.compile("Einstellungen|Settings")))?.let { gear ->
            gear.click()
            device.waitForIdle()
            inFront(device)
            swipe(device, 0.5f, 0.8f, 0.5f, 0.2f)
            swipe(device, 0.5f, 0.2f, 0.5f, 0.8f)
            device.pressBack()
            device.waitForIdle()
        }
        inFront(device)
    }

    private fun inApp(): BySelector = By.pkg(TARGET)

    /** Stops the walk (the test fails) when the target app is no longer in front. */
    private fun inFront(device: UiDevice) {
        check(device.currentPackageName == TARGET) { "Left $TARGET (now ${device.currentPackageName}); stopped" }
    }

    private fun swipe(device: UiDevice, x1: Float, y1: Float, x2: Float, y2: Float) {
        inFront(device)
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe((w * x1).toInt(), (h * y1).toInt(), (w * x2).toInt(), (h * y2).toInt(), 24)
        device.waitForIdle()
    }

    private fun tab(device: UiDevice, label: String) {
        device.findObject(inApp().text(Pattern.compile(label)))?.click()
        device.waitForIdle()
    }
}
