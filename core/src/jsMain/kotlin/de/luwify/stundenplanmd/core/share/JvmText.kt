package de.luwify.stundenplanmd.core.share

/**
 * `String.toByteArray()` as on the JVM (UTF-8, an unpaired surrogate becomes '?'), so share bytes match the Android
 * app's exactly. Kotlin/JS has no such function; this same-package one is only compiled for JS.
 */
internal fun String.toByteArray(): ByteArray {
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            sb.append(c).append(this[i + 1]); i += 2
        } else {
            sb.append(if (c.isSurrogate()) '?' else c); i++
        }
    }
    return sb.toString().encodeToByteArray()
}
