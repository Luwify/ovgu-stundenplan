package de.luwify.stundenplanmd.core

import de.luwify.stundenplanmd.core.share.Base45
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.ShareFrames
import de.luwify.stundenplanmd.core.share.ShareResult
import java.io.File

/**
 * Dev tool: QR texts read from screenshots (one per line, e.g. by ZXing on the dev machine) through the app's own
 * decoding, as the scanner does: `./gradlew :core:shareRead --args="<texts.txt>"`. Prints sizes and counts only.
 */
fun main(args: Array<String>) {
    val frames = ShareFrames.Decoder()
    var single: String? = null
    var frameCount = 0
    for (line in File(args[0]).readLines().map { it.trim() }.filter { it.startsWith(ShareCodec.QR_PREFIX) }) {
        if (ShareFrames.isFrame(line)) { frames.add(line); frameCount++ } else single = line
    }
    val text = frames.result?.let { ShareCodec.QR_PREFIX + Base45.encode(it) } ?: single
    println("frames read $frameCount, independent ${frames.rank}/${frames.blocks}, single code ${single != null}")
    when (val r = text?.let { ShareCodec.parse(it) }) {
        is ShareResult.Ok -> println("ok: version ${Base45.decode(text.substring(5))!![0]}, ${r.plan.events.size} events, ${r.plan.events.count { it.notes.isNotBlank() }} notes, complete=${r.plan.complete}, shortened=${r.plan.events.count { it.notesShortened }}")
        null -> println("incomplete")
        else -> println("result: $r")
    }
}
