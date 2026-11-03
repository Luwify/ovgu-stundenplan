package de.luwify.stundenplanmd.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.WriterException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.encoder.QRCode
import kotlin.math.floor

/** How the share screen shows a plan: one code, or the animated frames ([de.luwify.stundenplanmd.core.share.ShareFrames]). */
enum class QrMode(val key: String) {
    SINGLE("single"),
    ANIMATED("animated");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * Building the codes of the share screen (level L, alphanumeric: the text is `SPMD:` + Base45). One code is shown up
 * to [MAX_SINGLE_VERSION] (denser ones are hard to scan from a phone screen), larger plans are animated unless the
 * user chose otherwise.
 */
object ShareQr {
    const val MAX_SINGLE_VERSION = 20
    /** Frames per second of the animation: about two camera analyses per frame, so few are missed. */
    const val FPS = 6

    /** Module size and origin (px) of a [width]-module code with a 4-module quiet zone in a square of [side] px. */
    data class Layout(val cell: Float, val origin: Float)

    /**
     * Whole pixels for both, so no module edge falls inside a pixel: an edge at x.5 is drawn antialiased from both
     * sides and leaves a light seam between dark modules, which ZXing's binarizer (and jsQR) can't read past. The
     * origin needs it too: half the leftover space is a half pixel whenever the leftover is odd.
     */
    fun layout(side: Float, width: Int): Layout {
        val cell = floor(side / (width + 8))
        return Layout(cell, floor((side - cell * width) / 2))
    }

    /** The smallest code for [text], null if it doesn't fit (version 40). */
    fun code(text: String): QRCode? = try {
        Encoder.encode(text, ErrorCorrectionLevel.L)
    } catch (e: WriterException) {
        null
    }

    /** The default: one code when it fits up to [MAX_SINGLE_VERSION], else animated. */
    fun autoMode(single: QRCode?): QrMode =
        if (single != null && single.version.versionNumber <= MAX_SINGLE_VERSION) QrMode.SINGLE else QrMode.ANIMATED

    /** What is shown: the user's choice, but never one code that doesn't fit at all. */
    fun mode(chosen: QrMode?, single: QRCode?): QrMode = when {
        single == null -> QrMode.ANIMATED
        else -> chosen ?: autoMode(single)
    }

    /**
     * [code] with a mask ZXing's camera detector reads back. Some masks trip its finder/alignment detection even on a
     * perfect image (seen with version 31): the default mask first, then the others; the default if none reads.
     */
    fun readable(text: String, code: QRCode): QRCode {
        if (readsBack(code, text)) return code
        for (mask in 0..7) {
            if (mask == code.maskPattern) continue
            val other = Encoder.encode(text, ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_MASK_PATTERN to mask))
            if (readsBack(other, text)) return other
        }
        return code
    }

    /** Renders [code] (3 px per module, quiet zone) and reads it without the pure-image shortcut, as a camera would. */
    fun readsBack(code: QRCode, text: String): Boolean {
        val m = code.matrix
        val cell = 3
        val side = (m.width + 8) * cell
        val px = IntArray(side * side) { WHITE }
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y).toInt() == 1) {
            for (dy in 0 until cell) for (dx in 0 until cell) px[((y + 4) * cell + dy) * side + (x + 4) * cell + dx] = BLACK
        }
        val source = RGBLuminanceSource(side, side, px)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        for (binarizer in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
            val read = try { QRCodeReader().decode(BinaryBitmap(binarizer), hints).text } catch (_: ReaderException) { continue }
            if (read == text) return true
        }
        return false
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
}
