package de.luwify.stundenplanmd.data

import android.annotation.SuppressLint
import java.security.SecureRandom

/** Random share ids: 8 bytes as 16 hex digits, made once per installation (kept in [MyShare]). */
object PersonIds {
    // TrulyRandom concerns Android 4.3 and older; minSdk is 26.
    @SuppressLint("TrulyRandom")
    fun new(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
