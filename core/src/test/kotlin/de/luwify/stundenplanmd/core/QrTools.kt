package de.luwify.stundenplanmd.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.ReaderException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.encoder.QRCode
import de.luwify.stundenplanmd.core.compat.CompatProbe
import de.luwify.stundenplanmd.core.share.ShareCodec
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

/** The app's ShareQr.readable: a mask ZXing reads back. */
private fun readable(text: String): QRCode {
    val code = encode(text)
    if (read(image(code, 3)) == text) return code
    for (mask in 0..7) {
        if (mask == code.maskPattern) continue
        val other = encode(text, mask)
        if (read(image(other, 3)) == text) return other
    }
    return code
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
    File(out, "meta.json").writeText(
        """{"name":"${tom.name}","events":${tom.events.size},"qrTextLength":${text.length},"singleVersion":${single.version.versionNumber},""" +
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
        "read" -> qrRead(args.drop(1).map(::File))
        "file" -> args.drop(1).forEach { path ->
            when (val r = ShareCodec.parse(File(path).readBytes())) {
                is ShareResult.Ok -> println("""{"file":"${File(path).name}","ok":true,"name":"${r.plan.name}","events":${r.plan.events.size}}""")
                else -> println("""{"file":"${File(path).name}","ok":false,"result":"$r"}""")
            }
        }
        else -> error("usage: write <dir> | read <image>... | file <share file>...")
    }
}
