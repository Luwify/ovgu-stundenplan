package de.luwify.stundenplanmd.data.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import de.luwify.stundenplanmd.StundenplanApp
import de.luwify.stundenplanmd.core.update.ApkFacts
import java.io.File
import java.security.MessageDigest

/** Facts about the installed app and a downloaded APK from PackageManager (package, versionCode, signers). */
object ApkInspector {
    @Suppress("DEPRECATION")
    private val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    fun installed(context: Context): ApkFacts {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return facts(info)
    }

    fun archive(context: Context, apk: File): ApkFacts? {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.path, flags) ?: return null
        return facts(info)
    }

    @Suppress("DEPRECATION")
    private fun facts(info: PackageInfo): ApkFacts {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return ApkFacts(info.packageName, PackageInfoCompat.getLongVersionCode(info), signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Installs a verified APK through a PackageInstaller session; the result comes back to [InstallResultReceiver]. */
object ApkInstaller {
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Mutable: the installer adds the status extras.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}

/** Result of an install session: asks the user to confirm, or reports success/failure to the updater. */
class InstallResultReceiver : BroadcastReceiver() {
    @SuppressLint("UnsafeIntentLaunch") // the confirmation intent comes from the system installer
    override fun onReceive(context: Context, intent: Intent) {
        val updates = (context.applicationContext as StundenplanApp).container.updates
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) updates.installFinished(success = false, message = null)
                else context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> updates.installFinished(success = true, message = null)
            else -> updates.installFinished(
                success = false,
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).takeIf { status != PackageInstaller.STATUS_FAILURE_ABORTED },
                aborted = status == PackageInstaller.STATUS_FAILURE_ABORTED,
            )
        }
    }
}
