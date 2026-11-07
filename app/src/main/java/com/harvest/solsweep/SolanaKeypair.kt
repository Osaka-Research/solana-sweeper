package com.harvest.solsweep

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * A Solana keypair is 64 bytes: the first 32 are the ed25519 seed/private
 * key, the last 32 are the matching public key (redundant but that's the
 * standard wire/CLI format - solana-keygen, Phantom export, etc. all use
 * it). Accepts either that as base58, or a CLI-style JSON byte array
 * "[12,34,...]" (also 64 numbers).
 */
class SolanaKeypair private constructor(val secretSeed: ByteArray, val publicKey: ByteArray) {

    private val privateParams = Ed25519PrivateKeyParameters(secretSeed, 0)

    val publicKeyBase58: String get() = Base58.encode(publicKey)

    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    companion object {
        /** Builds a keypair directly from a 32-byte ed25519 seed (e.g. from a mnemonic derivation) - no embedded pubkey to cross-check. */
        fun fromSeed(seed: ByteArray): SolanaKeypair {
            require(seed.size == 32) { "seed must be 32 bytes, got ${seed.size}" }
            val pub = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
            return SolanaKeypair(seed, pub)
        }

        @Throws(IllegalArgumentException::class)
        fun parse(input: String): SolanaKeypair {
            val trimmed = input.trim()

            if (Bip39Slip10.looksLikeMnemonic(trimmed)) {
                val seed = Bip39Slip10.mnemonicToSeed(trimmed)
                val derivedSeed = Bip39Slip10.derivePath(seed, Bip39Slip10.SOLANA_DEFAULT_PATH)
                return fromSeed(derivedSeed)
            }

            val bytes = if (trimmed.startsWith("[")) {
                parseJsonArray(trimmed)
            } else {
                Base58.decode(trimmed)
            }
            require(bytes.size == 64) { "secret key must decode to 64 bytes, got ${bytes.size}" }

            val seed = bytes.copyOfRange(0, 32)
            val embeddedPub = bytes.copyOfRange(32, 64)

            val derivedPub = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
            require(derivedPub.contentEquals(embeddedPub)) {
                "secret key is malformed: embedded public key does not match derived public key"
            }
            return SolanaKeypair(seed, derivedPub)
        }

        private fun parseJsonArray(s: String): ByteArray {
            val inner = s.trim().removePrefix("[").removeSuffix("]")
            val parts = inner.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            return ByteArray(parts.size) { i -> parts[i].toInt().toByte() }
        }
    }
}
