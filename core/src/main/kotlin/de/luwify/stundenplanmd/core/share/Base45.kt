package de.luwify.stundenplanmd.core.share

/** RFC 9285: bytes as text from the QR alphanumeric set, so a QR code can carry them in alphanumeric mode. */
object Base45 {
    const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    fun encode(bytes: ByteArray): String = buildString((bytes.size + 1) / 2 * 3) {
        var i = 0
        while (i + 1 < bytes.size) {
            var n = (bytes[i].toInt() and 0xff) * 256 + (bytes[i + 1].toInt() and 0xff)
            repeat(3) { append(ALPHABET[n % 45]); n /= 45 }
            i += 2
        }
        if (i < bytes.size) {
            val n = bytes[i].toInt() and 0xff
            append(ALPHABET[n % 45]); append(ALPHABET[n / 45])
        }
    }

    /** Null for characters outside the alphabet, a dangling single character or a value out of range. */
    fun decode(text: String): ByteArray? {
        if (text.length % 3 == 1) return null
        val out = ByteArray(text.length / 3 * 2 + if (text.length % 3 == 2) 1 else 0)
        var o = 0
        var i = 0
        while (i < text.length) {
            val chunk = minOf(3, text.length - i)
            var n = 0
            for (k in chunk - 1 downTo 0) {
                val v = ALPHABET.indexOf(text[i + k])
                if (v < 0) return null
                n = n * 45 + v
            }
            if (chunk == 3) {
                if (n > 0xffff) return null
                out[o++] = (n shr 8).toByte(); out[o++] = n.toByte()
            } else {
                if (n > 0xff) return null
                out[o++] = n.toByte()
            }
            i += chunk
        }
        return out
    }
}
