package de.luwify.stundenplanmd.data.update

import de.luwify.stundenplanmd.core.update.Manifests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The only network code of the app: GET of the manifest and of APKs (the caller checks the URLs against the
 * [de.luwify.stundenplanmd.core.update.UpdateSource]). No cookies, no identifiers, no redirects (the release repo
 * serves both files directly; a redirect would mean a login page). User-Agent: app name and version only.
 */
class UpdateHttp(versionName: String) {
    private val userAgent = "StundenplanMD/$versionName"

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Cache-Control", "no-cache")
        }

    private fun HttpURLConnection.requireOk() {
        val code = responseCode
        if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
    }

    suspend fun manifest(url: String): String = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            c.requireOk()
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > Manifests.MAX_BYTES) throw IOException("manifest too large")
                }
                out.toByteArray()
            }
            bytes.decodeToString()
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads [url] to [dest] (replaced), at most [expectedSize] bytes, reporting progress; returns the SHA-256
     * (lowercase hex). Cancelling the coroutine stops it; the caller deletes the partial file.
     */
    suspend fun download(url: String, dest: File, expectedSize: Long, onProgress: (Long) -> Unit): String = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            c.requireOk()
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > expectedSize) throw IOException("larger than announced")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        onProgress(total)
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            c.disconnect()
        }
    }
}
