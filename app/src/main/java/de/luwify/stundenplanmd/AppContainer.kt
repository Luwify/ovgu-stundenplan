package de.luwify.stundenplanmd

import android.content.Context
import de.luwify.stundenplanmd.data.PlanRepository
import java.io.File

/** Manual DI: one instance per process, owned by [StundenplanApp]. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val plans: PlanRepository by lazy { PlanRepository(File(app.filesDir, "plans")) }
}
