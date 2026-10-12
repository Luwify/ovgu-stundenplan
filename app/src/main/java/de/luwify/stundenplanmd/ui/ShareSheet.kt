package de.luwify.stundenplanmd.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.share.ShareCodec
import java.io.File

/** Opens the system share sheet for my plan's share file (own MIME type; read access granted to the target only). */
fun openShareSheet(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(ShareCodec.MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)))
}
