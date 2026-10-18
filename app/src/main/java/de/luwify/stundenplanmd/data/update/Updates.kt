package de.luwify.stundenplanmd.data.update

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import de.luwify.stundenplanmd.core.update.ApkCheck
import de.luwify.stundenplanmd.core.update.ApkProblem
import de.luwify.stundenplanmd.core.update.ManifestResult
import de.luwify.stundenplanmd.core.update.Manifests
import de.luwify.stundenplanmd.core.update.Release
import de.luwify.stundenplanmd.core.update.UpdateManifest
import de.luwify.stundenplanmd.core.update.UpdatePolicy
import de.luwify.stundenplanmd.core.update.UpdateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.IOException

data class InstalledVersion(val name: String, val code: Int)

enum class CheckResult { NONE, OK, FAILED, TOO_NEW }

data class UpdateState(
    val installed: InstalledVersion,
    /** The last manifest that was read (kept while offline). */
    val manifest: UpdateManifest? = null,
    val lastCheckMillis: Long? = null,
    val checking: Boolean = false,
    /** Result of the last check in this run (NONE = none yet). */
    val result: CheckResult = CheckResult.NONE,
    val autoCheck: Boolean = true,
    /** The update to point at (badge, settings row), null if none or skipped. */
    val offer: Release? = null,
)

sealed interface Failure {
    data object Network : Failure
    data class Check(val problems: List<ApkProblem>) : Failure
    data class Install(val message: String?) : Failure
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val release: Release, val bytes: Long) : DownloadState
    data class Verifying(val release: Release) : DownloadState
    /** Handed to the system installer; waiting for its result (the confirmation dialog). */
    data class Installing(val release: Release) : DownloadState
    data class Failed(val release: Release, val failure: Failure) : DownloadState
}

/** "Install unknown apps" isn't granted: explain it (first time) or go straight to the system setting. */
data class PermissionAsk(val release: Release, val explain: Boolean)

/**
 * The in-app updater: checks the manifest (automatically when the app comes to the foreground, at most every
 * [UpdatePolicy.CHECK_INTERVAL_MILLIS], or by hand), downloads,
 * verifies and installs an update. No background job: it lives as long as the process.
 */
class Updates(context: Context, private val settings: UpdateSettings, private val source: UpdateSource) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dir = File(app.cacheDir, "updates")
    private val sdk = Build.VERSION.SDK_INT

    val installed: InstalledVersion = app.packageManager.getPackageInfo(app.packageName, 0).let { info ->
        InstalledVersion(info.versionName ?: "?", PackageInfoCompat.getLongVersionCode(info).toInt())
    }
    private val http = UpdateHttp(installed.name, source)

    private val checking = MutableStateFlow(false)
    private val result = MutableStateFlow(CheckResult.NONE)

    val state: StateFlow<UpdateState> = combine(settings.prefs, checking, result) { p, c, r ->
        val manifest = p.manifest?.let { (Manifests.parse(it) as? ManifestResult.Ok)?.manifest }
        UpdateState(
            installed = installed,
            manifest = manifest,
            lastCheckMillis = p.lastCheckMillis,
            checking = c,
            result = r,
            autoCheck = p.autoCheck,
            offer = manifest?.let { UpdatePolicy.offer(it, installed.code, sdk, p.skippedCode) },
        )
    }.stateIn(scope, SharingStarted.Eagerly, UpdateState(installed))

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()
    private var job: Job? = null

    /** A newer version found by a check, for the one-time snackbar; the UI clears it. */
    val announcement = MutableStateFlow<Release?>(null)

    private val _permission = MutableStateFlow<PermissionAsk?>(null)
    val permission: StateFlow<PermissionAsk?> = _permission.asStateFlow()
    private var awaitingGrant: Release? = null

    fun canInstall(release: Release) = UpdatePolicy.canInstall(release, installed.code, sdk) && source.allowsApk(release.apkUrl)

    /** Once per process, before the first check: removes APKs left from an earlier run, migrates the settings. */
    private val startup: Job by lazy {
        scope.launch {
            withContext(Dispatchers.IO) { cleanUp() }
            settings.migrate()
        }
    }

    /**
     * Each time the app comes to the foreground (process ON_START: cold start, back from the background, restored
     * after Android killed the process; not on rotation): checks if it's due, after the next frame so it never
     * delays the UI.
     */
    fun onForeground() {
        scope.launch {
            startup.join()
            awaitFrame()
            yield() // awaitFrame resumes inside the frame callback, before that frame is drawn
            val p = settings.current()
            if (UpdatePolicy.shouldAutoCheck(p.autoCheck, p.lastCheckMillis, System.currentTimeMillis())) check()
        }
    }

    fun checkNow() {
        scope.launch { check() }
    }

    private suspend fun check() {
        if (checking.value) return
        checking.value = true
        try {
            val text = http.manifest(source.manifestUrl)
            when (Manifests.parse(text)) {
                is ManifestResult.Ok -> {
                    settings.checked(System.currentTimeMillis(), text)
                    result.value = CheckResult.OK
                }
                is ManifestResult.TooNew -> result.value = CheckResult.TOO_NEW
                is ManifestResult.Broken -> result.value = CheckResult.FAILED
            }
        } catch (_: IOException) {
            // Offline or server unreachable: quietly keep the last result.
            result.value = CheckResult.FAILED
        } finally {
            checking.value = false
        }
        val p = settings.current()
        val manifest = p.manifest?.let { (Manifests.parse(it) as? ManifestResult.Ok)?.manifest } ?: return
        val offer = UpdatePolicy.offer(manifest, installed.code, sdk, p.skippedCode)
        if (UpdatePolicy.shouldAnnounce(offer, p.announcedCode) && offer != null) {
            settings.announced(offer.versionCode)
            announcement.value = offer
        }
    }

    /** For the backup. */
    suspend fun backupState(): de.luwify.stundenplanmd.data.UpdatesBackup =
        settings.current().let { de.luwify.stundenplanmd.data.UpdatesBackup(it.autoCheck, it.skippedCode) }

    suspend fun restore(backup: de.luwify.stundenplanmd.data.UpdatesBackup) = settings.restore(backup.autoCheck, backup.skippedCode)

    fun setAutoCheck(on: Boolean) {
        scope.launch { settings.setAutoCheck(on) }
    }

    /** "Don't show again" for this version: no badge and no snackbar for it (it stays in the version list). */
    fun skip(release: Release) {
        scope.launch { settings.skip(release.versionCode) }
    }

    /** Installieren: asks for "install unknown apps" first if needed, then downloads, verifies and installs. */
    fun install(release: Release) {
        if (job?.isActive == true || !canInstall(release)) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            scope.launch {
                awaitingGrant = release
                _permission.value = PermissionAsk(release, explain = !settings.current().unknownSourcesExplained)
            }
            return
        }
        start(release)
    }

    /** The UI showed the explanation or opened the system setting. */
    fun permissionHandled(explained: Boolean) {
        _permission.value = null
        if (explained) scope.launch { settings.explainedUnknownSources() }
    }

    fun permissionDeclined() {
        _permission.value = null
        awaitingGrant = null
    }

    /** Back from the system setting: go on if it was granted. */
    fun onResume() {
        val release = awaitingGrant ?: return
        if (_permission.value != null) return
        awaitingGrant = null
        if (app.packageManager.canRequestPackageInstalls()) start(release)
    }

    private fun start(release: Release) {
        job = scope.launch {
            val file = File(dir, "update-${release.versionName}.apk")
            try {
                withContext(Dispatchers.IO) { dir.mkdirs() }
                _download.value = DownloadState.Running(release, 0)
                var lastPercent = -1L
                val sha = http.download(release.apkUrl, file, release.size) { bytes ->
                    val percent = bytes * 100 / release.size.coerceAtLeast(1)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _download.value = DownloadState.Running(release, bytes)
                    }
                }
                _download.value = DownloadState.Verifying(release)
                val problems = withContext(Dispatchers.IO) {
                    ApkCheck.problems(release, sha, file.length(), ApkInspector.archive(app, file), ApkInspector.installed(app))
                }
                if (problems.isNotEmpty()) {
                    _download.value = DownloadState.Failed(release, Failure.Check(problems))
                    return@launch
                }
                _download.value = DownloadState.Installing(release)
                // The session keeps its own copy: the download can go right away.
                withContext(Dispatchers.IO) { ApkInstaller.install(app, file) }
            } catch (e: CancellationException) {
                _download.value = DownloadState.Idle
                throw e
            } catch (_: IOException) {
                _download.value = DownloadState.Failed(release, Failure.Network)
            } catch (e: Exception) {
                _download.value = DownloadState.Failed(release, Failure.Install(e.message))
            } finally {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { file.delete() }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Closes the "installing" state when the system installer doesn't report back (e.g. stopped by Play Protect). */
    fun forgetInstall() {
        if (_download.value is DownloadState.Installing) _download.value = DownloadState.Idle
    }

    fun dismissFailure() {
        if (_download.value is DownloadState.Failed) _download.value = DownloadState.Idle
    }

    /** From [InstallResultReceiver]. On success Android replaces (and stops) this process anyway. */
    fun installFinished(success: Boolean, message: String?, aborted: Boolean = false) {
        val current = _download.value as? DownloadState.Installing
        _download.value = when {
            success || aborted || current == null -> DownloadState.Idle
            else -> DownloadState.Failed(current.release, Failure.Install(message))
        }
    }

    /** The installed APK as a file to share: cache/share/stundenplan-md-<version>.apk. */
    suspend fun shareableApk(): File = withContext(Dispatchers.IO) {
        val share = File(app.cacheDir, "share").apply { mkdirs() }
        share.listFiles { f -> f.name.endsWith(".apk") }?.forEach { it.delete() }
        val out = File(share, "stundenplan-md-${installed.name}.apk")
        File(app.applicationInfo.sourceDir).copyTo(out, overwrite = true)
        out
    }

    /** Link for a first install (QR code): the newest version's APK, else the installed one's. */
    fun shareLink(): Pair<String, String> {
        val newest = state.value.manifest?.newest?.takeIf { source.allowsApk(it.apkUrl) }
        return newest?.let { it.versionName to it.apkUrl } ?: (installed.name to source.apkUrl(installed.name))
    }

    private fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
        File(app.cacheDir, "share").listFiles { f -> f.name.endsWith(".apk") }?.forEach { it.delete() }
    }
}
