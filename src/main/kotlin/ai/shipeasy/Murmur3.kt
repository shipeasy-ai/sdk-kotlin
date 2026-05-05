package ai.shipeasy

import java.nio.charset.StandardCharsets

object Murmur3 {
    private const val C1 = 0xcc9e2d51.toInt()
    private const val C2 = 0x1b873593.toInt()

    fun hash32(key: String): Int {
        val data = key.toByteArray(StandardCharsets.UTF_8)
        val n = data.size
        var h1 = 0
        val nblocks = n / 4

        for (i in 0 until nblocks) {
            val off = i * 4
            var k1 = (data[off].toInt() and 0xff) or
                ((data[off + 1].toInt() and 0xff) shl 8) or
                ((data[off + 2].toInt() and 0xff) shl 16) or
                ((data[off + 3].toInt() and 0xff) shl 24)
            k1 *= C1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= C2
            h1 = h1 xor k1
            h1 = Integer.rotateLeft(h1, 13)
            h1 = h1 * 5 + 0xe6546b64.toInt()
        }

        val tail = nblocks * 4
        var k1 = 0
        when (n and 3) {
            3 -> { k1 = k1 xor ((data[tail + 2].toInt() and 0xff) shl 16); k1 = k1 xor ((data[tail + 1].toInt() and 0xff) shl 8); k1 = k1 xor (data[tail].toInt() and 0xff); k1 *= C1; k1 = Integer.rotateLeft(k1, 15); k1 *= C2; h1 = h1 xor k1 }
            2 -> { k1 = k1 xor ((data[tail + 1].toInt() and 0xff) shl 8); k1 = k1 xor (data[tail].toInt() and 0xff); k1 *= C1; k1 = Integer.rotateLeft(k1, 15); k1 *= C2; h1 = h1 xor k1 }
            1 -> { k1 = k1 xor (data[tail].toInt() and 0xff); k1 *= C1; k1 = Integer.rotateLeft(k1, 15); k1 *= C2; h1 = h1 xor k1 }
        }

        h1 = h1 xor n
        h1 = h1 xor (h1 ushr 16)
        h1 *= 0x85ebca6b.toInt()
        h1 = h1 xor (h1 ushr 13)
        h1 *= 0xc2b2ae35.toInt()
        h1 = h1 xor (h1 ushr 16)
        return h1
    }

    fun bucket(key: String, mod: Int): Int = Integer.remainderUnsigned(hash32(key), mod)
}
