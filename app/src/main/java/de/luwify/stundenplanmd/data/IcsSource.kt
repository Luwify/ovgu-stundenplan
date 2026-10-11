package de.luwify.stundenplanmd.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Reading an .ics file the user picked, opened or shared. */
object IcsSource {
    private const val MAX_BYTES = 4 * 1024 * 1024

    /** The file of a VIEW or SEND intent, or null if the intent carries none. */
    fun uriOf(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> null
    }

    /** UTF-8 (what LSF writes), falling back to Windows-1252. Null if unreadable or too big. */
    fun read(resolver: ContentResolver, uri: Uri): String? = runCatching {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) return null
            }
            out.toByteArray()
        } ?: return null
        try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }.getOrNull()
}
