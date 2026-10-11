package de.luwify.stundenplanmd

import android.content.Context
import de.luwify.stundenplanmd.data.PlanRepository
import de.luwify.stundenplanmd.data.settings.ThemeSettings
import de.luwify.stundenplanmd.data.settings.themeSettings
import java.io.File

/** Manual DI: one instance per process, owned by [StundenplanApp]. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val plans: PlanRepository by lazy { PlanRepository(File(app.filesDir, "plans")) }

    val theme: ThemeSettings by lazy { themeSettings(app) }
}
