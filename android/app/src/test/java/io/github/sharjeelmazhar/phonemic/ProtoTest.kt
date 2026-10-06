package io.github.sharjeelmazhar.phonemic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProtoTest {
    @Test fun dhBothSidesAgree() {
        val a = Proto.Dh(); val b = Proto.Dh()
        assertArrayEquals(a.shared(b.public), b.shared(a.public))
    }

    @Test(expected = IllegalArgumentException::class) fun dhRejectsTrivialKeys() { Proto.Dh().shared("1") }

    @Test fun names() {
        for (n in listOf("Redmi Note 11", "smr's desktop", "Ünïcødé 名前", "a%b", ""))
            assertEquals(n.ifEmpty { "-" }, Proto.decodeName(Proto.encodeName(n)))
        assertEquals("Redmi%20Note%2011", Proto.encodeName("Redmi Note 11"))
    }

    @Test fun cipherIsSymmetricAndChunkingDoesNotMatter() {
        val key = ByteArray(32) { it.toByte() }
        val data = ByteArray(1000) { (it * 7).toByte() }
        val whole = data.copyOf().also { Proto.Cipher(key).apply(it) }
        val parts = data.copyOf().also { val c = Proto.Cipher(key); c.apply(it, 0, 33); c.apply(it, 33, 500); c.apply(it, 533, 467) }
        assertArrayEquals(whole, parts)
        assertNotEquals(data.toList(), whole.toList())
        Proto.Cipher(key).apply(whole)
        assertArrayEquals(data, whole)
    }

    // The same values are printed by `bin/phone-mic-daemon --selftest-vectors`: both sides derive identical keys.
    @Test fun knownVectors() {
        val key = Proto.pairKey(ByteArray(32) { 1 }, "aa", "bb")
        assertEquals("48792092ebb4a7032c5c4b6956cbf1ec8d4c06d726c0e180cc72229f5b6c1466", Proto.hex(key))
        assertEquals("200505", Proto.pairCode(key))
        assertEquals("e84534f78c214219ff30eb1acfdab6f2f68f16a01476e04b6c831a2262c558ca", Proto.proof(key, "C", "n1", "n2"))
        assertEquals("d21734fb4e403ba2251338b301e7beab320e9d742b128a958685673527a24c5ecd08a56089a47b3b",
            Proto.hex(ByteArray(40).also { Proto.Cipher(Proto.sessionKey(key, "n1", "n2")).apply(it) }))
    }

    @Test fun parse() {
        assertEquals(listOf("a", "b"), Proto.parse("HELLO a b\r", "HELLO", 2))
    }
}
