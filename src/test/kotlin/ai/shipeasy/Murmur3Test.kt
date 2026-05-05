package ai.shipeasy

import kotlin.test.Test
import kotlin.test.assertEquals

class Murmur3Test {
    // Values match the Ruby SDK reference impl across languages.
    @Test fun vectors() {
        assertEquals(0x00000000.toInt(), Murmur3.hash32(""))
        assertEquals(0x3c2569b2.toInt(), Murmur3.hash32("a"))
        assertEquals(0x9bbfd75f.toInt(), Murmur3.hash32("ab"))
        assertEquals(0xb3dd93fa.toInt(), Murmur3.hash32("abc"))
        assertEquals(0x7eeed987.toInt(), Murmur3.hash32("aaaa"))
        assertEquals(0xe9ca302b.toInt(), Murmur3.hash32("aaaaa"))
        assertEquals(0xe2a131eb.toInt(), Murmur3.hash32("Hello, 世界"))
        assertEquals(0x2e4ff723.toInt(), Murmur3.hash32("The quick brown fox jumps over the lazy dog"))
    }
}
