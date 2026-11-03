package java.util

/** Minimal BitSet for :core's fountain code (JS stand-in). */
class BitSet(nbits: Int = 64) {
    private var words = LongArray(maxOf(1, (nbits + 63) / 64))

    fun set(i: Int) {
        if (i / 64 >= words.size) words = words.copyOf(maxOf(words.size * 2, i / 64 + 1))
        words[i / 64] = words[i / 64] or (1L shl (i % 64))
    }
    fun clear(i: Int) { if (i / 64 < words.size) words[i / 64] = words[i / 64] and (1L shl (i % 64)).inv() }
    fun get(i: Int): Boolean = i / 64 < words.size && (words[i / 64] and (1L shl (i % 64))) != 0L
    val isEmpty: Boolean get() = words.all { it == 0L }
    fun cardinality(): Int = words.sumOf { it.countOneBits() }
    fun nextSetBit(from: Int): Int {
        var i = from
        while (i < words.size * 64) { if (get(i)) return i; i++ }
        return -1
    }
    fun toLongArray(): LongArray { var n = words.size; while (n > 0 && words[n - 1] == 0L) n--; return words.copyOf(n) }
    override fun equals(other: Any?) = other is BitSet && toLongArray().contentEquals(other.toLongArray())
    override fun hashCode() = toLongArray().contentHashCode()
}
