package de.luwify.stundenplanmd.core

import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.SharedPlan
import java.io.File
import java.time.LocalDate

/**
 * Dev tool: writes a friend's share from an export, for device tests on the preview app, e.g.
 * `./gradlew :core:shareFile --args="core/src/test/resources/lsf_scrubbed.ics /tmp/x.stundenplan Tom 00000000000000aa"`.
 * Also writes `<out>.txt` with the QR text.
 */
fun main(args: Array<String>) {
    val events = LsfImport.parse(File(args[0]).readText()).events
    val plan = SharedPlan(
        personId = args.getOrNull(3) ?: "00000000000000aa",
        name = args.getOrElse(2) { "Test" },
        exportedOn = LocalDate.now(),
        planDate = LocalDate.now().minusDays(1),
        events = events,
        settings = emptyMap(),
        complete = false,
    )
    File(args[1]).writeBytes(ShareCodec.file(plan))
    File(args[1] + ".txt").writeText(ShareCodec.qrText(plan))
    println("${args[1]}: ${File(args[1]).length()} B, QR ${ShareCodec.qrText(plan).length} chars")
}
