package de.luwify.stundenplanmd.core.share

import java.io.ByteArrayOutputStream
import java.util.BitSet
import java.util.zip.CRC32

/**
 * A QR payload ([ShareCodec.qrPayload]) too large for one comfortable code, as an endless loop of small animated codes,
 * decodable from any frames in any order once enough arrived. Random linear fountain code over GF(2): the payload is
 * cut into K blocks; frame `seq < K` is block `seq` (a clean pass is enough), every later frame the XOR of a
 * pseudo-random subset (each block with probability 1/2, seeded from payload CRC and seq). The decoder runs Gaussian
 * elimination as frames come in and is done at rank K, which random frames reach after about K + 2.
 *
 * Frame text: `SPMD:` + Base45 of [ShareCodec.FRAME_TAG], varint seq, varint K, varint payload length, CRC32 of the
 * payload (4 bytes), one block. Older apps read the tag as a newer version and ask for an update.
 */
object ShareFrames {
    /** Bytes per block: with the header a frame stays within QR version 12-L (535 alphanumeric characters). */
    const val BLOCK = 330
    private const val MAX_BLOCKS = 4096
    private const val MAX_PAYLOAD = 256 * 1024

    class Encoder(private val payload: ByteArray, val blockSize: Int = BLOCK) {
        val k: Int = maxOf(1, (payload.size + blockSize - 1) / blockSize)
        private val crc = CRC32().apply { update(payload) }.value
        private val blocks = List(k) { i -> payload.copyOfRange(minOf(payload.size, i * blockSize), minOf(payload.size, (i + 1) * blockSize)).copyOf(blockSize) }

        init { require(k <= MAX_BLOCKS) { "too many blocks" } }

        fun frame(seq: Int): String = ShareCodec.QR_PREFIX + Base45.encode(bytes(seq))

        internal fun bytes(seq: Int): ByteArray {
            val mix = mix(seq, k, crc)
            val data = ByteArray(blockSize)
            for (i in 0 until k) if (mix.get(i)) xor(data, blocks[i])
            val out = ByteArrayOutputStream()
            out.write(ShareCodec.FRAME_TAG)
            varint(out, seq.toLong()); varint(out, k.toLong()); varint(out, payload.size.toLong())
            for (shift in 24 downTo 0 step 8) out.write((crc shr shift).toInt() and 0xff)
            out.write(data)
            return out.toByteArray()
        }
    }

    /** The frame's bytes if [text] is a frame of an animated share, else null. */
    fun frameBytes(text: String): ByteArray? {
        val t = text.trim()
        if (!t.startsWith(ShareCodec.QR_PREFIX)) return null
        // The tag is in the first two Base45 characters (first two bytes); decode all only for frames.
        val head = Base45.decode(t.substring(ShareCodec.QR_PREFIX.length).take(3)) ?: return null
        if (head.isEmpty() || head[0].toInt() and 0xff != ShareCodec.FRAME_TAG) return null
        return Base45.decode(t.substring(ShareCodec.QR_PREFIX.length))
    }

    fun isFrame(text: String) = frameBytes(text) != null

    /** Collects frames of one payload; a frame of another payload starts over. */
    class Decoder {
        private var k = 0
        private var length = 0
        private var crc = -1L
        private var blockSize = 0
        private var rows = arrayOfNulls<LongArray>(0)
        private var data = arrayOfNulls<ByteArray>(0)
        private val seen = HashSet<Int>()

        /** Independent frames so far; done at [blocks]. */
        var rank = 0; private set
        val blocks: Int get() = k
        val progress: Float get() = if (k == 0) 0f else rank.toFloat() / k

        /** The payload, once complete and its CRC matches. */
        var result: ByteArray? = null; private set

        /** True if [text] was a new frame (of this payload, or the first of another one). */
        fun add(text: String): Boolean = frameBytes(text)?.let { add(it) } ?: false

        internal fun add(frame: ByteArray): Boolean {
            if (frame.isEmpty() || frame[0].toInt() and 0xff != ShareCodec.FRAME_TAG) return false
            var pos = 1
            fun read(): Long {
                var v = 0L; var shift = 0
                while (true) {
                    val b = frame[pos++].toInt() and 0xff
                    v = v or ((b and 0x7f).toLong() shl shift)
                    if (b < 0x80) return v
                    shift += 7
                    require(shift <= 35) { "varint" }
                }
            }
            val seq: Long; val fk: Long; val flen: Long; var fcrc = 0L
            try {
                seq = read(); fk = read(); flen = read()
                repeat(4) { fcrc = (fcrc shl 8) or (frame[pos++].toLong() and 0xff) }
            } catch (e: RuntimeException) {
                return false
            }
            val size = frame.size - pos
            if (seq > Int.MAX_VALUE || fk !in 1..MAX_BLOCKS || flen !in 0..MAX_PAYLOAD || size <= 0) return false
            if (fk.toInt() != maxOf(1, ((flen + size - 1) / size).toInt())) return false
            if (fcrc != crc || fk.toInt() != k || flen.toInt() != length || size != blockSize) reset(fk.toInt(), flen.toInt(), fcrc, size)
            if (result != null || !seen.add(seq.toInt())) return false
            val row = LongArray((k + 63) / 64)
            val mix = mix(seq.toInt(), k, crc)
            for (i in 0 until k) if (mix.get(i)) row[i / 64] = row[i / 64] or (1L shl (i % 64))
            insert(row, frame.copyOfRange(pos, frame.size))
            if (rank == k) solve()
            return true
        }

        private fun reset(newK: Int, newLength: Int, newCrc: Long, size: Int) {
            k = newK; length = newLength; crc = newCrc; blockSize = size
            rows = arrayOfNulls(k); data = arrayOfNulls(k)
            seen.clear(); rank = 0; result = null
        }

        private fun bit(row: LongArray, i: Int) = (row[i / 64] ushr (i % 64)) and 1L == 1L

        /** Reduces [row] by the pivots (each pivot's lowest bit is its index); keeps what is left as a new pivot. */
        private fun insert(row: LongArray, block: ByteArray) {
            for (i in 0 until k) {
                if (!bit(row, i)) continue
                val pivot = rows[i]
                if (pivot == null) {
                    rows[i] = row; data[i] = block; rank++
                    return
                }
                for (w in row.indices) row[w] = row[w] xor pivot[w]
                xor(block, data[i]!!)
            }
        }

        private fun solve() {
            for (i in k - 1 downTo 0) {
                val row = rows[i]!!
                for (j in i + 1 until k) if (bit(row, j)) xor(data[i]!!, data[j]!!)
            }
            val out = ByteArray(length)
            for (i in 0 until k) {
                val from = i * blockSize
                if (from >= length) break
                System.arraycopy(data[i]!!, 0, out, from, minOf(blockSize, length - from))
            }
            if (CRC32().apply { update(out) }.value == crc) {
                result = out
            } else {
                // A corrupt frame got in (the QR's own error correction let it through): start over.
                reset(k, length, crc, blockSize)
            }
        }
    }

    /** Which blocks frame [seq] holds. */
    internal fun mix(seq: Int, k: Int, crc: Long): BitSet {
        val set = BitSet(k)
        if (seq < k) { set.set(seq); return set }
        var state = (crc shl 32) xor seq.toLong() xor -0x61c8864680b583ebL
        fun next(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        var bits = 0L; var left = 0
        for (i in 0 until k) {
            if (left == 0) { bits = next(); left = 64 }
            if (bits and 1L == 1L) set.set(i)
            bits = bits ushr 1; left--
        }
        if (set.isEmpty) set.set(((next() ushr 1) % k).toInt())
        return set
    }

    private fun xor(into: ByteArray, other: ByteArray) {
        for (i in into.indices) into[i] = (into[i].toInt() xor other[i].toInt()).toByte()
    }

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) { out.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }
}
