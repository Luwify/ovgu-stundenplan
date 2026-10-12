package de.luwify.stundenplanmd.data.update

import android.content.Context
import de.luwify.stundenplanmd.core.update.UpdateSource

/** Release builds: always the public release repo (the debug build has an override, see src/debug). */
@Suppress("UNUSED_PARAMETER")
fun buildUpdateSource(context: Context): UpdateSource = UpdateSource.RELEASES
