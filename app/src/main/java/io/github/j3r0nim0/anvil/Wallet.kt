package io.github.j3r0nim0.anvil

import org.bouncycastle.jcajce.provider.digest.Keccak
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Monero wallet address validation.
 *
 * Monero addresses use Cryptonote **block-based Base58** (NOT plain big-endian):
 *   - binary blob: [varint network_tag][spend_key 32B][view_key 32B][payment_id 8B?][keccak256 checksum 4B]
 *   - standard / subaddress: 69 bytes  →  95 Base58 chars  (8×11 + 7)
 *   - integrated:            77 bytes  →  106 Base58 chars (9×11 + 7)
 *   - data split into 8-byte blocks, each encoded to 11 chars; last block 1..7 bytes → 2,3,5,6,7,9,10 chars
 *
 * Checksum = first 4 bytes of Keccak-256(tag + keys [+ payment_id]).
 * Network tag is a little-endian base-128 varint: 18 mainnet, 19 integrated, 42 subaddress.
 */
object Wallet {

    private const val NETWORK_MAINNET = 18L     // 0x12
    private const val NETWORK_INTEGRATED = 19L  // 0x13
    private const val NETWORK_SUBADDRESS = 42L  // 0x2A

    private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** encoded char count per data block size (index 1..8, index 0 unused) */
    private val ENC_SIZES = intArrayOf(0, 2, 3, 5, 6, 7, 9, 10, 11)

    /** decoded data block size per encoded char count (0..11, -1 = invalid) */
    private val DEC_SIZES: IntArray = run {
        val r = IntArray(12) { -1 }
        for (i in 1..8) r[ENC_SIZES[i]] = i
        r
    }

    private const val FULL_BLOCK = 8
    private const val FULL_ENC = 11
    private const val CHECKSUM_SIZE = 4

    private val junk = Regex("[\\s\\u200B\\u200C\\u200D\\uFEFF\\u00AD]+")
    private val keccak256: MessageDigest by lazy { Keccak.Digest256() }

    /** Quick shape pre-filter: 95 chars starting 4/8, or 106 chars starting 4. */
    private val pattern = Regex("^(?:[48][$B58]{94}|4[$B58]{105})$")

    /**
     * Full validation: shape pre-filter + block Base58 decode + Keccak-256 checksum
     * + mainnet network tag check.
     */
    fun isValid(address: String): Boolean {
        val clean = parse(address)
        if (!pattern.matches(clean)) return false
        return try {
            val raw = b58decode(clean) ?: return false
            verifyChecksum(raw) && verifyNetworkTag(raw)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Strips URI scheme, zero-width characters, and other junk.
     * Does NOT validate — use [isValid] for that.
     */
    fun parse(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("monero:", ignoreCase = true)) {
            s = s.substring("monero:".length)
            val q = s.indexOf('?')
            if (q >= 0) s = s.substring(0, q)
        }
        return junk.replace(s, "")
    }

    /**
     * Masked display only: first 6 + last 4 characters, for a valid address.
     * Never return the full address from this function. "Not set" otherwise.
     */
    fun mask(address: String): String {
        val clean = parse(address)
        if (!isValid(clean)) return "Not set"
        return clean.take(6) + " … " + clean.takeLast(4)
    }

    // ── Cryptonote block Base58 ─────────────────────────────────────────────

    /** Decodes block-Base58 to bytes. Returns null on any structural error. */
    private fun b58decode(enc: String): ByteArray? {
        if (enc.isEmpty()) return ByteArray(0)
        val fullCount = enc.length / FULL_ENC
        val lastSize = enc.length % FULL_ENC
        val lastDecoded = DEC_SIZES[lastSize]
        if (lastDecoded < 0) return null
        val out = ByteArray(fullCount * FULL_BLOCK + lastDecoded)
        for (i in 0 until fullCount) {
            val block = decodeBlock(
                enc.substring(i * FULL_ENC, (i + 1) * FULL_ENC), FULL_BLOCK,
            ) ?: return null
            System.arraycopy(block, 0, out, i * FULL_BLOCK, FULL_BLOCK)
        }
        if (lastSize > 0) {
            val block = decodeBlock(enc.substring(fullCount * FULL_ENC), lastDecoded) ?: return null
            System.arraycopy(block, 0, out, fullCount * FULL_BLOCK, lastDecoded)
        }
        return out
    }

    /** Decodes one Base58 block into [resSize] big-endian bytes. Null on invalid symbol / overflow. */
    private fun decodeBlock(block: String, resSize: Int): ByteArray? {
        var num = BigInteger.ZERO
        for (c in block) {
            val digit = B58.indexOf(c)
            if (digit < 0) return null
            num = num.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        // Must fit in uint64
        if (num.bitLength() > 64) return null
        // Non-final blocks are always 8 bytes; the last block must fit in its byte count
        if (resSize < FULL_BLOCK && BigInteger.ONE.shiftLeft(8 * resSize) <= num) return null
        val bytes = num.toByteArray()
        val start = if (bytes.size > 1 && bytes[0].toInt() == 0) 1 else 0
        val len = bytes.size - start
        if (len > resSize) return null
        val out = ByteArray(resSize)
        System.arraycopy(bytes, start, out, resSize - len, len)
        return out
    }

    // ── Checksum + network tag ──────────────────────────────────────────────

    /** Verifies last 4 bytes == first 4 bytes of Keccak-256(rest). */
    private fun verifyChecksum(raw: ByteArray): Boolean {
        if (raw.size <= CHECKSUM_SIZE) return false
        val dataLen = raw.size - CHECKSUM_SIZE
        val hash = keccak256.digest(raw.copyOfRange(0, dataLen))
        for (i in 0 until CHECKSUM_SIZE) {
            if (hash[i] != raw[dataLen + i]) return false
        }
        return true
    }

    /** Decodes the leading varint and checks it is a mainnet tag. */
    private fun verifyNetworkTag(raw: ByteArray): Boolean {
        val dataLen = raw.size - CHECKSUM_SIZE
        var value = 0L
        var shift = 0
        var i = 0
        while (i < dataLen && shift < 64) {
            val b = raw[i].toInt() and 0xFF
            value = value or ((b.toLong() and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return when (value) {
            NETWORK_MAINNET, NETWORK_INTEGRATED, NETWORK_SUBADDRESS -> true
            else -> false
        }
    }
}
