package de.luwify.stundenplanmd

import android.content.Context
import de.luwify.stundenplanmd.data.PlanRepository
import de.luwify.stundenplanmd.data.settings.ThemeSettings
import de.luwify.stundenplanmd.data.settings.themeSettings
import de.luwify.stundenplanmd.data.update.Updates
import de.luwify.stundenplanmd.data.update.buildUpdateSource
import de.luwify.stundenplanmd.data.update.updateSettings
import java.io.File

/** Manual DI: one instance per process, owned by [StundenplanApp]. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val plans: PlanRepository by lazy { PlanRepository(File(app.filesDir, "plans")) }

    val theme: ThemeSettings by lazy { themeSettings(app) }

    val updates: Updates by lazy { Updates(app, updateSettings(app), buildUpdateSource(app)) }
}
