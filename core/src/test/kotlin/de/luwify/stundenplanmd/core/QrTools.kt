package de.luwify.stundenplanmd.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.ReaderException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.encoder.QRCode
import de.luwify.stundenplanmd.core.compat.CompatProbe
import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.share.Forwarding
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.SharedPlan
import de.luwify.stundenplanmd.core.store.StoreOps
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import de.luwify.stundenplanmd.core.share.ShareFrames
import de.luwify.stundenplanmd.core.share.ShareResult
import de.luwify.stundenplanmd.core.store.BackupFormat
import de.luwify.stundenplanmd.core.store.DisplayBackup
import de.luwify.stundenplanmd.core.store.ThemeBackup
import de.luwify.stundenplanmd.core.store.UpdatesBackup
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

// Dev tools for the web app's tests (web/tests): the Android app's side of QR codes, with ZXing as the app uses it.

/** ZXing's encoder as the app's ShareQr: level L, smallest version; [mask] forces one. */
private fun encode(text: String, mask: Int? = null): QRCode =
    Encoder.encode(text, ErrorCorrectionLevel.L, if (mask == null) emptyMap() else mapOf(EncodeHintType.QR_MASK_PATTERN to mask))

private fun image(code: QRCode, cell: Int = 6): BufferedImage {
    val m = code.matrix
    val side = (m.width + 8) * cell
    val img = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until side) for (x in 0 until side) img.setRGB(x, y, 0xFFFFFF)
    for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y).toInt() == 1) {
        for (dy in 0 until cell) for (dx in 0 until cell) img.setRGB((x + 4) * cell + dx, (y + 4) * cell + dy, 0)
    }
    return img
}

/** The app's scanner: QR only, TRY_HARDER, HybridBinarizer (QrScreens). */
private fun read(img: BufferedImage): String? = try {
    QRCodeReader().decode(
        BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(img))),
        mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true),
    ).text
} catch (e: ReaderException) {
    null
}

/** The app's ShareQr.readsBack: 3 px per module, hybrid then global histogram binarizer, TRY_HARDER. */
private fun readsBack(code: QRCode, text: String): Boolean {
    val img = image(code, 3)
    for (binarizer in listOf(HybridBinarizer(BufferedImageLuminanceSource(img)), GlobalHistogramBinarizer(BufferedImageLuminanceSource(img)))) {
        val read = try { QRCodeReader().decode(BinaryBitmap(binarizer), mapOf(DecodeHintType.TRY_HARDER to true)).text } catch (_: ReaderException) { continue }
        if (read == text) return true
    }
    return false
}

/** The app's ShareQr.readable: a mask ZXing reads back. */
private fun readable(text: String): QRCode {
    val code = encode(text)
    if (readsBack(code, text)) return code
    for (mask in 0..7) {
        if (mask == code.maskPattern) continue
        val other = encode(text, mask)
        if (readsBack(other, text)) return other
    }
    return code
}

/** The matrix as rows of '1'/'0' joined by '/', to compare with the web encoder module by module. */
private fun rows(code: QRCode): String = (0 until code.matrix.height).joinToString("/") { y ->
    (0 until code.matrix.width).joinToString("") { x -> if (code.matrix.get(x, y).toInt() == 1) "1" else "0" }
}

/**
 * Plan variants for the web's QR check: sizes from one event to the largest that fits one code (events repeated
 * with other UIDs), with and without notes, other names (umlauts), my share of the rich state (style, own change,
 * hidden date) and the friend forwarded.
 */
private fun variants(ics: String): List<Pair<String, SharedPlan>> {
    val events = LsfImport.parse(ics).events
    val base = CompatProbe.friendShare(ics)
    val out = mutableListOf<Pair<String, SharedPlan>>()
    val d = CompatProbe.rich(ics)
    out += "mine-rich" to StoreOps.mySharedPlan(d, d.share, LocalDate.of(2026, 11, 3), CompatProbe.NOW)!!
    out += "forwarded-friend" to Forwarding.of(d.person(CompatProbe.FRIEND_ID)!!, d.planOf(CompatProbe.FRIEND_ID)!!, LocalDate.of(2026, 11, 3), PlanZone)!!
    val many = (0 until 4).flatMap { k -> events.map { e -> e.copy(uid = if (k == 0) e.uid else "${e.uid.take(6)}${k}${e.uid.drop(7)}") } }
    for (n in listOf(1, 2, 4, 8, 12, 18, 25, 35, events.size, events.size * 2, events.size * 3, events.size * 4)) {
        val sub = many.take(n)
        out += "n$n-notes" to base.copy(events = sub)
        out += "n$n-plain" to base.copy(events = sub.map { it.copy(notes = "") }, name = "Jürgen Groß-Öztürk")
    }
    // Notes that don't compress (one per copy) to reach the large versions up to 40.
    for (k in 1..4) out += "unique-notes-x$k" to base.copy(
        events = many.take(events.size * k).mapIndexed { i, e -> e.copy(notes = "${e.notes} Gruppe $i: ${(i * 7919L * 104729L).toString(36)}") },
        name = "Notizen $k",
    )
    return out.filter { encodeOrNull(ShareCodec.qrText(it.second)) != null }
}

private fun encodeOrNull(text: String): QRCode? = try { encode(text) } catch (_: com.google.zxing.WriterException) { null }

/**
 * `texts <out.json>`: QR texts of [variants] with ZXing's default mask and version, the mask the app's readable
 * picks and the default matrix; plus `search`: texts of the fixture friend under other names whose default mask
 * ZXing doesn't read back (the case the mask switch exists for), up to [limit].
 */
fun qrTexts(out: File, search: Int) {
    val ics = Fixture.text
    val items = variants(ics).map { (id, plan) -> Triple(id, ShareCodec.qrText(plan), plan) }.toMutableList()
    val base = CompatProbe.friendShare(ics)
    var found = 0
    var i = 0
    while (found < 5 && i < search) {
        val plan = base.copy(name = "Tom $i")
        val text = ShareCodec.qrText(plan)
        if (!readsBack(encode(text), text)) { items += Triple("default-fails-$i", text, plan); found++ }
        i++
    }
    if (found == 0) println("qrTexts: no default-mask failure in $search names")
    val json = items.joinToString(",\n", "[\n", "\n]") { (id, text, plan) ->
        val code = encode(text)
        val pick = readable(text)
        """{"id":"$id","name":${Json.encodeToString(String.serializer(), plan.name)},"text":"$text","version":${code.version.versionNumber},""" +
            """"defaultMask":${code.maskPattern},"defaultReads":${readsBack(code, text)},"readableMask":${pick.maskPattern},"matrix":"${rows(code)}"}"""
    }
    out.writeText(json)
    println("qrTexts: $out (${items.size} texts, searched $i names)")
}

/** My share as the web app makes it from the fixture with share id [CompatProbe.MY_ID] on 2026-11-03 under [name]. */
private fun myShareText(ics: String, name: String): String {
    val d = CompatProbe.imported(ics).copy(share = de.luwify.stundenplanmd.core.store.MyShare(CompatProbe.MY_ID, name))
    return ShareCodec.qrText(StoreOps.mySharedPlan(d, d.share, LocalDate.of(2026, 11, 3), CompatProbe.NOW)!!)
}

/** The first name "Alex <i>" whose share of my fixture plan ZXing doesn't read back with the default mask. */
private fun maskCase(ics: String): Pair<String, String> {
    for (i in 0 until 5000) {
        val name = "Alex $i"
        val text = myShareText(ics, name)
        if (!readsBack(encode(text), text)) return name to text
    }
    error("no default-mask failure found")
}

/**
 * `./gradlew :core:qrWrite --args="<outDir>"`: a friend's share made by the JVM build from the fixture (as file, as one
 * QR image and as animated frames, all with ZXing), plus an Android backup and the fixture .ics, and meta.json.
 */
fun qrWrite(out: File) {
    out.mkdirs()
    val ics = Fixture.text
    val tom = CompatProbe.friendShare(ics)
    File(out, "tom.stundenplan").writeBytes(ShareCodec.file(tom))
    val payload = ShareCodec.qrPayload(tom)
    val text = ShareCodec.qrText(tom)
    val single = readable(text)
    ImageIO.write(image(single), "png", File(out, "tom-single.png"))
    val enc = ShareFrames.Encoder(payload)
    val frames = File(out, "tom-frames").apply { deleteRecursively(); mkdirs() }
    val count = enc.k + 6
    for (seq in 0 until count) ImageIO.write(image(readable(enc.frame(seq))), "png", File(frames, "frame-%03d.png".format(seq)))
    File(out, "fixture.ics").writeText(ics)
    val backup = BackupFormat.encode(
        CompatProbe.rich(ics), ics, ThemeBackup("soft", "dark", 0x1E88E5, true, "bg=101010"), "de",
        UpdatesBackup(autoCheck = false, skippedCode = 10503), DisplayBackup(fitLandscape = false, mensaBannerHidden = true), CompatProbe.NOW,
    )
    File(out, "android-backup.json").writeText(backup)
    val (maskName, maskText) = maskCase(ics)
    val maskDefault = encode(maskText)
    File(out, "meta.json").writeText(
        """{"maskName":"$maskName","maskText":"$maskText","maskDefault":${maskDefault.maskPattern},"maskReadable":${readable(maskText).maskPattern},""" +
            """"maskVersion":${maskDefault.version.versionNumber},"name":"${tom.name}","events":${tom.events.size},"qrTextLength":${text.length},"singleVersion":${single.version.versionNumber},""" +
            """"blocks":${enc.k},"frames":$count,"friendId":"${tom.personId}","myName":"Alex"}""",
    )
    println("qrWrite: $out (QR text ${text.length} chars, single version ${single.version.versionNumber}, ${enc.k} blocks)")
}

/**
 * `./gradlew :core:qrRead --args="<image>..."`: reads screenshots as the app's scanner would (ZXing), collects frames
 * of an animated code, and prints JSON: per image whether it read, and the share it gives (name, events, version).
 */
fun qrRead(files: List<File>) {
    val decoder = ShareFrames.Decoder()
    var single: String? = null
    val results = files.map { f ->
        val text = read(ImageIO.read(f))
        when {
            text == null -> """{"file":"${f.name}","read":false}"""
            ShareFrames.isFrame(text) -> {
                decoder.add(text)
                val b = ShareFrames.frameBytes(text)!!
                var seq = 0L; var shift = 0; var i = 1
                while (true) { val x = b[i++].toInt() and 0xff; seq = seq or ((x and 0x7f).toLong() shl shift); if (x < 0x80) break; shift += 7 }
                """{"file":"${f.name}","read":true,"frame":true,"seq":$seq}"""
            }
            else -> { single = text; """{"file":"${f.name}","read":true,"frame":false}""" }
        }
    }
    val share = single ?: decoder.result?.let { ShareCodec.QR_PREFIX + de.luwify.stundenplanmd.core.share.Base45.encode(it) }
    val parsed = share?.let { ShareCodec.parse(it) }
    val summary = when (parsed) {
        is ShareResult.Ok -> """{"ok":true,"name":"${parsed.plan.name}","events":${parsed.plan.events.size},"personId":"${parsed.plan.personId}","length":${share.length}}"""
        null -> """{"ok":false,"progress":${decoder.progress}}"""
        else -> """{"ok":false,"result":"$parsed"}"""
    }
    println("""{"images":[${results.joinToString(",")}],"share":$summary}""")
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "write" -> qrWrite(File(args[1]))
        "texts" -> qrTexts(File(args[1]), args.getOrNull(2)?.toInt() ?: 3000)
        // One single code per image, read as the app's scanner does: the text per file (null = not read).
        "singles" -> println(args.drop(1).joinToString(",\n", "[\n", "\n]") { path ->
            val text = read(ImageIO.read(File(path)))
            """{"file":"${File(path).name}","text":${if (text == null) "null" else "\"$text\""}}"""
        })
        "read" -> qrRead(args.drop(1).map(::File))
        "file" -> args.drop(1).forEach { path ->
            when (val r = ShareCodec.parse(File(path).readBytes())) {
                is ShareResult.Ok -> println("""{"file":"${File(path).name}","ok":true,"name":"${r.plan.name}","events":${r.plan.events.size}}""")
                else -> println("""{"file":"${File(path).name}","ok":false,"result":"$r"}""")
            }
        }
        else -> error("usage: write <dir> | texts <out.json> [names to search] | singles <png>... | read <image>... | file <share file>...")
    }
}
