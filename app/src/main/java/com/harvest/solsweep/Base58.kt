package com.harvest.solsweep

import java.math.BigInteger

/** Standard Bitcoin/Solana Base58 alphabet, encode/decode. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also {
        for (i in ALPHABET.indices) it[ALPHABET[i].code] = i
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++

        var num = BigInteger(1, input)
        val base = BigInteger.valueOf(58)
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val (div, rem) = num.divideAndRemainder(base)
            sb.append(ALPHABET[rem.toInt()])
            num = div
        }
        repeat(zeros) { sb.append(ALPHABET[0]) }
        return sb.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var num = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (c in input) {
            val digit = if (c.code < 128) INDEX[c.code] else -1
            require(digit >= 0) { "invalid base58 character: $c" }
            num = num.multiply(base).add(BigInteger.valueOf(digit.toLong()))
        }
        var bytes = num.toByteArray()
        // BigInteger may prepend a sign byte - strip it
        if (bytes.size > 1 && bytes[0].toInt() == 0) bytes = bytes.copyOfRange(1, bytes.size)

        var zeros = 0
        while (zeros < input.length && input[zeros] == ALPHABET[0]) zeros++

        val out = ByteArray(zeros + bytes.size)
        System.arraycopy(bytes, 0, out, zeros, bytes.size)
        return out
    }
}
