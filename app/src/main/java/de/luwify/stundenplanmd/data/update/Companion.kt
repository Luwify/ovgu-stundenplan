package de.luwify.stundenplanmd.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import de.luwify.stundenplanmd.core.update.CompanionApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Another app of the author (Mensa MD) for the settings banner: whether it is installed, opening it, and its release page
 * in the browser for the download (see [CompanionApp]). No background job: nothing happens until the user taps the banner.
 */
class CompanionLauncher(context: Context, private val companion: CompanionApp = CompanionApp.MENSA) {
    private val app = context.applicationContext

    private val _installed = MutableStateFlow(queryInstalled())
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private fun queryInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(companion.packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Opens the app; false if it can't be launched (not installed, no launcher activity). */
    fun open(context: Context): Boolean {
        val intent = app.packageManager.getLaunchIntentForPackage(companion.packageName) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    /** Back in the foreground (e.g. after installing it from the browser): look again. */
    fun onResume() {
        _installed.value = queryInstalled()
    }

    /** The banner's download button: the release page in the browser. */
    fun openReleasePage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, companion.releasesPage.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
