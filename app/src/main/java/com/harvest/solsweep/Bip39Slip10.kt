package com.harvest.solsweep

import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BIP39 mnemonic -> seed, and SLIP-0010 ed25519 hardened derivation, enough
 * to recreate the keypair a wallet app (Phantom, Solflare, ...) derives from
 * a seed phrase. Implemented by hand against the RFC2898/SLIP-0010 specs
 * (HMAC primitives only, no third-party crypto-wallet library) since this
 * derives a key that moves real funds.
 *
 * Note: this does NOT validate the mnemonic's BIP39 checksum (would need the
 * full 2048-word list embedded) - only that the word count is plausible.
 * A typo'd mnemonic silently derives a *different* wallet rather than
 * erroring; the UI's "derived address" display is the safety net - check it
 * matches the address you expect before relying on it.
 */
object Bip39Slip10 {

    private const val PBKDF2_ITERATIONS = 2048
    private val VALID_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

    fun looksLikeMnemonic(input: String): Boolean {
        val words = input.trim().split(Regex("\\s+"))
        return words.size in VALID_WORD_COUNTS && words.all { it.isNotBlank() }
    }

    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normalizedMnemonic = Normalizer.normalize(mnemonic.trim(), Normalizer.Form.NFKD)
        val normalizedSalt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD)
        return pbkdf2HmacSha512(normalizedMnemonic.toByteArray(Charsets.UTF_8), normalizedSalt.toByteArray(Charsets.UTF_8), PBKDF2_ITERATIONS, 64)
    }

    private fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLenBytes: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hLen = mac.macLength
        val numBlocks = (dkLenBytes + hLen - 1) / hLen
        val out = ByteArray(numBlocks * hLen)

        for (blockIndex in 1..numBlocks) {
            mac.reset()
            mac.update(salt)
            mac.update(intToBigEndianBytes(blockIndex))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 2..iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (blockIndex - 1) * hLen, hLen)
        }
        return out.copyOf(dkLenBytes)
    }

    private fun intToBigEndianBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** SLIP-0010 ed25519 hardened derivation along e.g. "m/44'/501'/0'/0'". Returns the 32-byte private key (seed). */
    fun derivePath(seed: ByteArray, path: String): ByteArray {
        var mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec("ed25519 seed".toByteArray(Charsets.UTF_8), "HmacSHA512"))
        var i = mac.doFinal(seed)
        var key = i.copyOfRange(0, 32)
        var chainCode = i.copyOfRange(32, 64)

        val segments = path.removePrefix("m/").split("/").filter { it.isNotBlank() }
        for (segment in segments) {
            val hardened = segment.endsWith("'") || segment.endsWith("h")
            val indexNum = segment.trimEnd('\'', 'h').toInt()
            require(hardened) { "SLIP-0010 ed25519 only supports hardened derivation - all path segments need a trailing '" }
            val index = indexNum or 0x80000000.toInt()

            val data = ByteArray(1 + 32 + 4)
            data[0] = 0x00
            System.arraycopy(key, 0, data, 1, 32)
            data[33] = (index ushr 24).toByte()
            data[34] = (index ushr 16).toByte()
            data[35] = (index ushr 8).toByte()
            data[36] = index.toByte()

            mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(chainCode, "HmacSHA512"))
            i = mac.doFinal(data)
            key = i.copyOfRange(0, 32)
            chainCode = i.copyOfRange(32, 64)
        }
        return key
    }

    /** Standard Phantom/Solflare-style Solana derivation path. */
    const val SOLANA_DEFAULT_PATH = "m/44'/501'/0'/0'"
}
