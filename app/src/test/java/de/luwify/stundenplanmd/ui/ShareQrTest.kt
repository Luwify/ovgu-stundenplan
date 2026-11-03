package de.luwify.stundenplanmd.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.encoder.QRCode
import de.luwify.stundenplanmd.core.share.Base45
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.ShareFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.math.floor
import kotlin.random.Random

class ShareQrTest {
    /** A QR text like a share's: `SPMD:` + Base45 of [n] random bytes (compressed data looks random). */
    private fun text(n: Int, seed: Int = n) = ShareCodec.QR_PREFIX + Base45.encode(Random(seed).nextBytes(n))

    private fun version(n: Int) = ShareQr.code(text(n))!!.version.versionNumber

    @Test fun oneCodeUpToVersion20() {
        // The largest payload that still fits version 20-L (1249 alphanumeric characters), and one byte more.
        val last = (1..2000).last { version(it) <= ShareQr.MAX_SINGLE_VERSION }
        assertEquals(20, version(last))
        assertEquals(21, version(last + 1))
        assertEquals(QrMode.SINGLE, ShareQr.autoMode(ShareQr.code(text(last))))
        assertEquals(QrMode.ANIMATED, ShareQr.autoMode(ShareQr.code(text(last + 1))))
        assertTrue("$last bytes", last in 800..840)
        assertEquals(Mode.ALPHANUMERIC, ShareQr.code(text(last))!!.mode)
    }

    @Test fun theChoiceWinsButNeverATooLargeCode() {
        val big = ShareQr.code(text(1500))
        val small = ShareQr.code(text(200))
        assertEquals(QrMode.ANIMATED, ShareQr.mode(null, big))
        assertEquals(QrMode.SINGLE, ShareQr.mode(QrMode.SINGLE, big))
        assertEquals(QrMode.SINGLE, ShareQr.mode(null, small))
        assertEquals(QrMode.ANIMATED, ShareQr.mode(QrMode.ANIMATED, small))
        // Beyond version 40: no single code at all.
        assertNull(ShareQr.code(text(3000)))
        assertEquals(QrMode.ANIMATED, ShareQr.mode(QrMode.SINGLE, null))
        assertEquals(QrMode.SINGLE, QrMode.fromKey("single"))
        assertNull(QrMode.fromKey(null))
    }

    @Test fun selfCheckGivesACodeTheDetectorReads() {
        for (n in listOf(300, 800, 1400, 1800)) {
            val t = text(n)
            val code = ShareQr.readable(t, ShareQr.code(t)!!)
            assertTrue("$n bytes, mask ${code.maskPattern}", ShareQr.readsBack(code, t))
        }
    }

    private fun read(code: QRCode): String {
        val m = code.matrix
        val cell = 4
        val side = (m.width + 8) * cell
        val px = IntArray(side * side) { -1 }
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y).toInt() == 1) {
            for (dy in 0 until cell) for (dx in 0 until cell) px[((y + 4) * cell + dy) * side + (x + 4) * cell + dx] = 0xFF000000.toInt()
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, px)))).text
    }

    @Test fun animatedFramesAreVersion12AndDecodeFromImages() {
        val payload = Random(3).nextBytes(2600)
        val enc = ShareFrames.Encoder(payload)
        val d = ShareFrames.Decoder()
        var seq = 4
        var missed = 0
        var shown = 0
        while (d.result == null) {
            val code = ShareQr.code(enc.frame(seq))!!
            assertTrue("version ${code.version.versionNumber}", code.version.versionNumber <= 12)
            // A frame the detector misses is just skipped, as by a camera; the fountain code needs no particular one.
            runCatching { d.add(read(code)) }.onFailure { missed++ }
            shown++
            seq += 3
            assertTrue(seq < 300)
        }
        assertArrayEquals(payload, d.result)
        assertTrue("missed $missed of $shown", missed * 4 <= shown)
        assertTrue(ShareQr.code(enc.frame(1 shl 20))!!.version.versionNumber <= 12)
    }

    /**
     * [code] as the screen shows it at 100 %: a [side] px square, white, modules filled at [layout] like the Compose
     * Canvas (float rects, antialiased edges), then read as the app's scanner reads (QR only, TRY_HARDER, hybrid).
     */
    private fun readsOnScreen(text: String, side: Int, layout: ShareQr.Layout): Boolean {
        val code = ShareQr.code(text)!!
        val m = code.matrix
        val img = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Color.WHITE
        g.fillRect(0, 0, side, side)
        g.color = Color.BLACK
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y).toInt() == 1) {
            g.fill(Rectangle2D.Float(layout.origin + x * layout.cell, layout.origin + y * layout.cell, layout.cell, layout.cell))
        }
        g.dispose()
        val px = img.getRGB(0, 0, side, side, null, 0, side)
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        return runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, px))), hints).text }.getOrNull() == text
    }

    @Test fun animatedFramesReadAtFullScaleWithoutSeams() {
        val enc = ShareFrames.Encoder(Random(5).nextBytes(2600))
        val frames = (0 until 4).map { enc.frame(it * 7) }
        // Sides of a phone/tablet code in px (dp x density, rounded by layout): every leftover, odd and even.
        val sides = (600..1000 step 11).toList()
        val width = ShareQr.code(frames[0])!!.matrix.width
        for (side in sides) for ((i, frame) in frames.withIndex()) {
            val layout = ShareQr.layout(side.toFloat(), width)
            assertEquals(floor(layout.origin), layout.origin)
            assertEquals(floor(layout.cell), layout.cell)
            assertTrue("side $side, frame $i", readsOnScreen(frame, side, layout))
        }
        // The check sees seams: with the old origin (half the leftover, a half pixel when it is odd) frames fail.
        val old = sides.count { side ->
            val cell = floor(side.toFloat() / (width + 8))
            val origin = (side - cell * width) / 2
            origin != floor(origin) && !readsOnScreen(frames[0], side, ShareQr.Layout(cell, origin))
        }
        assertTrue("old origin failed at $old sides", old > 0)
    }
}
