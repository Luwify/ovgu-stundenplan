package de.luwify.stundenplanmd.data.update

import de.luwify.stundenplanmd.core.update.Manifests
import de.luwify.stundenplanmd.core.update.UpdateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The only network code of the app: GET of the manifest and of APKs (the caller checks the APK URL against the
 * [source]). No cookies, no identifiers. Redirects are followed by hand, each hop checked by
 * [UpdateSource.redirect] (GitHub: latest -> tag -> its asset CDN), at most [UpdateSource.MAX_REDIRECTS].
 * User-Agent: app name and version only.
 */
class UpdateHttp(versionName: String, private val source: UpdateSource) {
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

    /** Opens [url] and follows allowed redirects; the returned connection answered 200. */
    private fun connect(url: String): HttpURLConnection {
        var current = url
        repeat(UpdateSource.MAX_REDIRECTS + 1) {
            val c = open(current)
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                c.disconnect()
                throw e
            }
            when (code) {
                HttpURLConnection.HTTP_OK -> return c
                in REDIRECTS -> {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    current = source.redirect(current, location) ?: throw IOException("redirect not allowed")
                }
                else -> {
                    c.disconnect()
                    throw IOException("HTTP $code")
                }
            }
        }
        throw IOException("too many redirects")
    }

    suspend fun manifest(url: String): String = withContext(Dispatchers.IO) {
        val c = connect(url)
        try {
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
        val c = connect(url)
        try {
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

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
