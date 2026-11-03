package java.io

/** JS stand-in with the methods :core uses. */
class ByteArrayOutputStream(initial: Int = 32) {
    private var buf = ByteArray(maxOf(1, initial))
    private var count = 0

    private fun ensure(extra: Int) {
        if (count + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, count + extra))
    }

    fun write(b: Int) { ensure(1); buf[count++] = b.toByte() }
    fun write(b: ByteArray) = write(b, 0, b.size)
    fun write(b: ByteArray, off: Int, len: Int) { ensure(len); b.copyInto(buf, count, off, off + len); count += len }
    fun size(): Int = count
    fun reset() { count = 0 }
    fun toByteArray(): ByteArray = buf.copyOf(count)
}
