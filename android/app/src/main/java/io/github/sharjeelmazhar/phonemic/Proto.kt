package io.github.sharjeelmazhar.phonemic

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The wire protocol shared with the computer (bin/phone-mic-daemon). Plain JVM, unit-tested.
 *
 * TCP on [TCP_PORT], the phone listens, the computer connects. Text lines first, then audio:
 *   phone:    PHONEMIC 1 <phoneId> <appVersion> <phoneName>
 *   computer: HELLO <computerId> <computerName> <nonceC> <audioSource>
 *   known computer:
 *     phone:    AUTH <nonceP>
 *     computer: PROOF <hmac(K, "C|nonceP|nonceC")>
 *     phone:    OK 48000 1 <hmac(K, "P|nonceP|nonceC")>      then encrypted 16-bit mono PCM until either side closes;
 *     computer: a "\n" every second (keepalive; no keepalive for 6 s = computer gone)
 *   unknown computer (pairing):
 *     phone:    PAIR <nonceP> <dhPublicP>
 *     computer: PAIRKEY <dhPublicC>
 *     both derive K and a 6-digit code; the person compares the code and allows it on the phone
 *     phone:    PAIRED | DENIED     then the connection closes and the computer reconnects with AUTH
 * Phone -> LAN broadcast on [UDP_PORT] every few seconds: PHONEMIC 1 <phoneId> <tcpPort> <phoneName>
 *
 * K = HMAC(sha256(DH shared secret), "K|nonceP|nonceC"), Diffie-Hellman over the 2048-bit MODP group of RFC 3526.
 * Audio is XORed with HMAC(sessionKey, blockNumber) blocks, sessionKey = HMAC(K, "S|nonceP|nonceC").
 * Names are percent-encoded so that they never contain spaces.
 */
object Proto {
    const val VERSION = 1
    const val TCP_PORT = 47630
    const val UDP_PORT = 47631
    const val RATE = 48000

    private const val MODP2048 =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
        "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
        "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
        "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
        "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
        "15728E5A8AACAA68FFFFFFFFFFFFFFFF"
    val P = BigInteger(MODP2048, 16)
    private val G = BigInteger.TWO
    private val rnd = SecureRandom()

    fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(msg) }
    fun hmac(key: ByteArray, msg: String) = hmac(key, msg.toByteArray())

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0 && s.all { it in "0123456789abcdefABCDEF" }) { "not hex" }
        return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
    fun randomHex(bytes: Int = 16) = hex(ByteArray(bytes).also { rnd.nextBytes(it) })

    /** A name as one token: everything but letters, digits and -_. becomes %XX of its UTF-8 bytes. */
    fun encodeName(s: String): String = buildString {
        for (b in s.toByteArray()) {
            val c = b.toInt() and 0xff
            if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-_.") append(c.toChar())
            else append("%%%02X".format(c))
        }
    }.ifEmpty { "-" }
    fun decodeName(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val h = if (s[i] == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (h != null) {
                out.write(h); i += 3
            } else { out.write(s[i].code); i++ }
        }
        return out.toString(Charsets.UTF_8).take(64)
    }

    class Dh {
        private val x = BigInteger(256, rnd).setBit(255)
        val public: String = G.modPow(x, P).toString(16)
        /** sha256 of the shared secret as a 256-byte big-endian number. */
        fun shared(otherHex: String): ByteArray {
            val y = BigInteger(otherHex, 16)
            require(y > BigInteger.ONE && y < P - BigInteger.ONE) { "bad public value" }
            val z = y.modPow(x, P).toByteArray().let { b ->
                ByteArray(256).also { out -> val s = b.takeLast(256).toByteArray(); s.copyInto(out, 256 - s.size) }
            }
            return MessageDigest.getInstance("SHA-256").digest(z)
        }
    }

    fun pairKey(shared: ByteArray, nonceP: String, nonceC: String) = hmac(shared, "K|$nonceP|$nonceC")
    fun pairCode(key: ByteArray): String =
        "%06d".format((ByteBuffer.wrap(hmac(key, "code")).int.toLong() and 0xffffffffL) % 1_000_000)
    fun proof(key: ByteArray, role: String, nonceP: String, nonceC: String) = hex(hmac(key, "$role|$nonceP|$nonceC"))
    fun sessionKey(key: ByteArray, nonceP: String, nonceC: String) = hmac(key, "S|$nonceP|$nonceC")

    fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** Stream cipher: byte n of the stream is XORed with byte n%32 of HMAC(key, n/32 as 8-byte big-endian). */
    class Cipher(key: ByteArray) {
        private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        private var block = 0L
        private var ks = ByteArray(0)
        private var pos = 0
        fun apply(buf: ByteArray, off: Int = 0, len: Int = buf.size) {
            for (i in off until off + len) {
                if (pos == ks.size) {
                    ks = mac.doFinal(ByteBuffer.allocate(8).putLong(block++).array()); pos = 0
                }
                buf[i] = (buf[i].toInt() xor ks[pos++].toInt()).toByte()
            }
        }
    }

    /** Splits a protocol line; the first token must be [cmd] and there must be at least [n] tokens after it. */
    fun parse(line: String?, cmd: String, n: Int): List<String> {
        val t = line?.trim()?.split(' ') ?: throw IllegalStateException("connection closed (expected $cmd)")
        if (t[0] != cmd || t.size < n + 1) throw IllegalStateException("expected $cmd, got: ${line.take(40)}")
        return t.drop(1)
    }
}
