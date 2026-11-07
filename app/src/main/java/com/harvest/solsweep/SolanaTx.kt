package com.harvest.solsweep

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Base64

/**
 * Hand-rolled legacy Solana transaction builder for exactly one case: a
 * System Program transfer from one account to another. Deliberately not
 * using a third-party Solana SDK here - this signs and moves real funds, so
 * the wire format is small enough to implement directly and fully verify,
 * rather than trust an unfamiliar dependency with that job.
 *
 * Wire format: https://docs.solana.com/developing/programming-model/transactions
 */
object SolanaTx {

    private val SYSTEM_PROGRAM_ID = ByteArray(32) // all-zero pubkey

    private fun writeCompactU16(out: ByteArrayOutputStream, valueIn: Int) {
        var value = valueIn
        while (true) {
            var elem = value and 0x7f
            value = value ushr 7
            if (value == 0) {
                out.write(elem)
                break
            } else {
                out.write(elem or 0x80)
                }
        }
    }

    private fun buildMessage(fromPubkey: ByteArray, toPubkey: ByteArray, lamports: Long, recentBlockhash: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()

        // header: numRequiredSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts
        out.write(1)
        out.write(0)
        out.write(1) // only the system program is readonly+unsigned

        // account_keys: [from(signer,writable), to(writable), systemProgram(readonly)]
        writeCompactU16(out, 3)
        out.write(fromPubkey)
        out.write(toPubkey)
        out.write(SYSTEM_PROGRAM_ID)

        // recent blockhash
        out.write(recentBlockhash)

        // instructions: compact array of 1
        writeCompactU16(out, 1)
        out.write(2) // programIdIndex -> index 2 (system program) in account_keys

        // instruction accounts: [0 (from), 1 (to)]
        writeCompactU16(out, 2)
        out.write(0)
        out.write(1)

        // instruction data: u32 LE instruction type (2 = Transfer) + u64 LE lamports
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(2)
        data.putLong(lamports)
        val dataBytes = data.array()
        writeCompactU16(out, dataBytes.size)
        out.write(dataBytes)

        return out.toByteArray()
    }

    /** Builds, signs, and wire-serializes a transfer transaction; returns base64 ready for sendTransaction. */
    fun buildSignedTransferBase64(
        keypair: SolanaKeypair,
        toPubkey: ByteArray,
        lamports: Long,
        recentBlockhash: ByteArray
    ): String {
        val message = buildMessage(keypair.publicKey, toPubkey, lamports, recentBlockhash)
        val signature = keypair.sign(message)

        val out = ByteArrayOutputStream()
        writeCompactU16(out, 1) // signature count
        out.write(signature)
        out.write(message)

        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
