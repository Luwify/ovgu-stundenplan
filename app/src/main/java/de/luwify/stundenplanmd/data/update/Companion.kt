package de.luwify.stundenplanmd.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import de.luwify.stundenplanmd.core.update.CompanionApp
import de.luwify.stundenplanmd.core.update.CompanionPhase
import de.luwify.stundenplanmd.core.update.CompanionPolicy
import de.luwify.stundenplanmd.core.update.ManifestResult
import de.luwify.stundenplanmd.core.update.Manifests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface CompanionDownload {
    data object Idle : CompanionDownload
    data class Running(val bytes: Long, val size: Long) : CompanionDownload
    data object Verifying : CompanionDownload
    /** Handed to the system installer; waiting for its result (the confirmation dialog). */
    data object Installing : CompanionDownload
    data class Failed(val failure: Failure) : CompanionDownload
}

fun CompanionDownload.phase(): CompanionPhase = when (this) {
    CompanionDownload.Idle -> CompanionPhase.IDLE
    is CompanionDownload.Running -> CompanionPhase.DOWNLOADING
    CompanionDownload.Verifying -> CompanionPhase.VERIFYING
    CompanionDownload.Installing -> CompanionPhase.INSTALLING
    is CompanionDownload.Failed -> CompanionPhase.FAILED
}

/**
 * Installs another app of the author (Mensa MD) from its GitHub releases, with the updater's pieces: the manifest and
 * the checked download of [UpdateHttp], the APK check against the pinned certificate ([CompanionPolicy.problems]),
 * a PackageInstaller session and the "install unknown apps" flow. Also tells whether it is installed. No background
 * job: nothing happens until the user taps the banner. [probe] is the package looked for to tell "installed".
 */
class CompanionInstaller(
    context: Context,
    private val settings: UpdateSettings,
    val companion: CompanionApp = CompanionApp.MENSA,
    private val probe: String = companion.packageName,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dir = File(app.cacheDir, "companion")
    private val source = requireNotNull(companion.source) { "companion manifest is not a GitHub latest-release URL" }
    private val http = UpdateHttp(app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?", source)

    private val _installed = MutableStateFlow(queryInstalled())
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private val _download = MutableStateFlow<CompanionDownload>(CompanionDownload.Idle)
    val download: StateFlow<CompanionDownload> = _download.asStateFlow()
    private var job: Job? = null

    /** "Install unknown apps" isn't granted: true = explain first, false = straight to the system setting. */
    private val _permission = MutableStateFlow<Boolean?>(null)
    val permission: StateFlow<Boolean?> = _permission.asStateFlow()
    private var awaitingGrant = false

    private fun queryInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(probe, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Opens the app; false if it can't be launched (not installed, no launcher activity). */
    fun open(context: Context): Boolean {
        val intent = app.packageManager.getLaunchIntentForPackage(probe) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    /** The settings come back to the foreground (e.g. after the install): look again, go on if "unknown apps" was allowed. */
    fun onResume() {
        _installed.value = queryInstalled()
        if (!awaitingGrant || _permission.value != null) return
        awaitingGrant = false
        if (app.packageManager.canRequestPackageInstalls()) start()
    }

    fun install() {
        if (job?.isActive == true || _installed.value) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            scope.launch {
                awaitingGrant = true
                _permission.value = !settings.current().unknownSourcesExplained
            }
            return
        }
        start()
    }

    /** The UI showed the explanation or opened the system setting. */
    fun permissionHandled(explained: Boolean) {
        _permission.value = null
        if (explained) scope.launch { settings.explainedUnknownSources() }
    }

    fun permissionDeclined() {
        _permission.value = null
        awaitingGrant = false
    }

    private fun start() {
        job = scope.launch {
            val file = File(dir, "${companion.packageName}.apk")
            try {
                withContext(Dispatchers.IO) { dir.mkdirs() }
                _download.value = CompanionDownload.Running(0, 0)
                val manifest = (Manifests.parse(http.manifest(source.manifestUrl)) as? ManifestResult.Ok)?.manifest
                val release = manifest?.let { CompanionPolicy.release(it, companion, source, Build.VERSION.SDK_INT) }
                if (release == null) {
                    _download.value = CompanionDownload.Failed(Failure.Unavailable)
                    return@launch
                }
                var lastPercent = -1L
                val sha = http.download(release.apkUrl, file, release.size) { bytes ->
                    val percent = bytes * 100 / release.size.coerceAtLeast(1)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _download.value = CompanionDownload.Running(bytes, release.size)
                    }
                }
                _download.value = CompanionDownload.Verifying
                val problems = withContext(Dispatchers.IO) {
                    CompanionPolicy.problems(release, sha, file.length(), ApkInspector.archive(app, file), companion)
                }
                if (problems.isNotEmpty()) {
                    _download.value = CompanionDownload.Failed(Failure.Check(problems))
                    return@launch
                }
                _download.value = CompanionDownload.Installing
                withContext(Dispatchers.IO) { ApkInstaller.install(app, file, companion.packageName, companion = true) }
            } catch (e: CancellationException) {
                _download.value = CompanionDownload.Idle
                throw e
            } catch (_: IOException) {
                _download.value = CompanionDownload.Failed(Failure.Network)
            } catch (e: Exception) {
                _download.value = CompanionDownload.Failed(Failure.Install(e.message))
            } finally {
                withContext(Dispatchers.IO + NonCancellable) { file.delete() }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Closes the "installing" state when the system installer doesn't report back (e.g. stopped by Play Protect). */
    fun forgetInstall() {
        if (_download.value is CompanionDownload.Installing) _download.value = CompanionDownload.Idle
    }

    fun dismissFailure() {
        if (_download.value is CompanionDownload.Failed) _download.value = CompanionDownload.Idle
    }

    /** From [InstallResultReceiver]. */
    fun installFinished(success: Boolean, message: String?, aborted: Boolean = false) {
        _installed.value = queryInstalled()
        _download.value = when {
            success || aborted || _download.value !is CompanionDownload.Installing -> CompanionDownload.Idle
            else -> CompanionDownload.Failed(Failure.Install(message))
        }
    }

    /** Where the failure dialog's "Auf GitHub öffnen" goes: the release page in the browser. */
    fun openReleasePage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(companion.releasesPage)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
