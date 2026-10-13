package de.luwify.stundenplanmd.data.update

import android.content.Context
import de.luwify.stundenplanmd.core.update.UpdateSource
import java.io.File

/**
 * DEBUG BUILDS ONLY (src/debug; release builds use src/release, which has no override): the manifest URL can be
 * replaced for tests of the update flow on the .preview copy, by a file in the app's private storage:
 *
 *   adb shell run-as de.luwify.stundenplanmd.preview sh -c 'echo http://127.0.0.1:8765/updates.json > files/debug_update_url'
 *
 * APKs are then allowed from that origin only (plain http only to 127.0.0.1/localhost, see network_security_config).
 * Delete the file to go back to the release repo of the build.
 */
fun buildUpdateSource(context: Context): UpdateSource =
    File(context.filesDir, "debug_update_url").takeIf { it.isFile }?.readText()?.trim()?.let(UpdateSource::override)
        ?: releaseSource
