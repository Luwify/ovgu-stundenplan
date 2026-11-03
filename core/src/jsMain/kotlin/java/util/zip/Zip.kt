package java.util.zip

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

class DataFormatException(message: String? = null) : Exception(message)

class CRC32 {
    private var crc = 0xffffffffL.toInt()
    val value: Long get() = (crc.inv().toLong()) and 0xffffffffL
    fun update(b: Int) { crc = TABLE[(crc xor b) and 0xff] xor (crc ushr 8) }
    fun update(b: ByteArray) = update(b, 0, b.size)
    fun update(b: ByteArray, off: Int, len: Int) {
        var c = crc
        for (i in off until off + len) c = TABLE[(c xor b[i].toInt()) and 0xff] xor (c ushr 8)
        crc = c
    }
    fun reset() { crc = 0xffffffffL.toInt() }

    private companion object {
        val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) -0x12477ce0 xor (c ushr 1) else c ushr 1 }
            c
        }
    }
}

@JsModule("pako")
@JsNonModule
private external object Pako {
    fun deflateRaw(data: Uint8Array, options: dynamic): Uint8Array
    class Inflate(options: dynamic) {
        fun push(data: Uint8Array, flush: Boolean): Boolean
        var onData: (chunk: Uint8Array) -> Unit
        val err: Int
        val msg: String?
        val ended: Boolean
    }
}

private fun ByteArray.u8(): Uint8Array = Int8Array(unsafeCast<Int8Array>().buffer, unsafeCast<Int8Array>().byteOffset, size).let {
    Uint8Array(it.buffer, it.byteOffset, it.length)
}
private fun Uint8Array.bytes(): ByteArray = Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>().copyOf()

/** Whole-buffer raw deflate through pako (a port of zlib), driven like java.util.zip.Deflater by :core. */
class Deflater(private val level: Int = -1, private val nowrap: Boolean = false) {
    private var dictionary: ByteArray? = null
    private var input = ByteArray(0)
    private var output: ByteArray? = null
    private var pos = 0

    fun setDictionary(b: ByteArray) { dictionary = b }
    fun setInput(b: ByteArray) { input = b }
    fun finish() {}
    fun finished(): Boolean = output?.let { pos >= it.size } ?: false
    fun deflate(buf: ByteArray): Int {
        check(nowrap) { "only raw deflate" }
        val out = output ?: run {
            val o: dynamic = js("({})")
            o.level = level
            dictionary?.let { o.dictionary = it.u8() }
            Pako.deflateRaw(input.u8(), o).bytes().also { output = it }
        }
        val n = minOf(buf.size, out.size - pos)
        out.copyInto(buf, 0, pos, pos + n)
        pos += n
        return n
    }
    fun end() {}
}

/** Whole-buffer raw inflate through pako; stops early past [LIMIT] so a hostile share can't fill the memory. */
class Inflater(private val nowrap: Boolean = false) {
    private var dictionary: ByteArray? = null
    private var input = ByteArray(0)
    private var output: ByteArray? = null
    private var complete = false
    private var pos = 0

    fun setDictionary(b: ByteArray) { dictionary = b }
    fun setInput(b: ByteArray, off: Int, len: Int) { input = b.copyOfRange(off, off + len) }
    fun setInput(b: ByteArray) = setInput(b, 0, b.size)
    fun needsDictionary() = false
    fun needsInput() = output != null && !complete && pos >= output!!.size
    fun finished() = complete && output != null && pos >= output!!.size

    private fun run() {
        check(nowrap) { "only raw inflate" }
        val o: dynamic = js("({})")
        o.raw = true
        o.chunkSize = 16384
        dictionary?.let { o.dictionary = it.u8() }
        val inf = Pako.Inflate(o)
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        inf.onData = { c ->
            total += c.length
            if (total > LIMIT) throw DataFormatException("too large")
            chunks += c.bytes()
        }
        inf.push(input.u8(), true)
        if (inf.err != 0) throw DataFormatException(inf.msg)
        complete = inf.ended
        val out = ByteArray(total)
        var at = 0
        for (c in chunks) { c.copyInto(out, at); at += c.size }
        output = out
    }

    fun inflate(buf: ByteArray): Int {
        if (output == null) run()
        val out = output!!
        val n = minOf(buf.size, out.size - pos)
        out.copyInto(buf, 0, pos, pos + n)
        pos += n
        return n
    }
    fun end() {}

    private companion object {
        const val LIMIT = 4 * 1024 * 1024
    }
}
