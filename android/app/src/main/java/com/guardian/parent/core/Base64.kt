package com.guardian.parent.core

/**
 * Minimal, dependency free Base64 codec.
 *
 * `android.util.Base64` would work as well, but keeping the implementation
 * here makes the encoding behaviour explicit and lets the same code run in
 * plain JVM unit tests without the Android framework.
 */
object Base64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    /** Encodes [bytes] using the standard alphabet, with `=` padding. */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index + 2 < bytes.size) {
            val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                (bytes[index + 2].toInt() and 0xFF)
            out.append(ALPHABET[(chunk shr 18) and 0x3F])
            out.append(ALPHABET[(chunk shr 12) and 0x3F])
            out.append(ALPHABET[(chunk shr 6) and 0x3F])
            out.append(ALPHABET[chunk and 0x3F])
            index += 3
        }
        val remaining = bytes.size - index
        if (remaining == 1) {
            val chunk = (bytes[index].toInt() and 0xFF) shl 16
            out.append(ALPHABET[(chunk shr 18) and 0x3F])
            out.append(ALPHABET[(chunk shr 12) and 0x3F])
            out.append("==")
        } else if (remaining == 2) {
            val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8)
            out.append(ALPHABET[(chunk shr 18) and 0x3F])
            out.append(ALPHABET[(chunk shr 12) and 0x3F])
            out.append(ALPHABET[(chunk shr 6) and 0x3F])
            out.append('=')
        }
        return out.toString()
    }

    /** Decodes a standard (padded or unpadded) Base64 string. */
    fun decode(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        val clean = StringBuilder(text.length)
        for (char in text) {
            if (char == '=' || char == '\n' || char == '\r' || char == ' ') continue
            clean.append(char)
        }
        val out = ByteArray(clean.length * 3 / 4)
        var outIndex = 0
        var index = 0
        while (index < clean.length) {
            val c0 = valueOf(clean[index])
            val c1 = if (index + 1 < clean.length) valueOf(clean[index + 1]) else 0
            val c2 = if (index + 2 < clean.length) valueOf(clean[index + 2]) else 0
            val c3 = if (index + 3 < clean.length) valueOf(clean[index + 3]) else 0
            val chunk = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            if (outIndex < out.size) out[outIndex++] = ((chunk shr 16) and 0xFF).toByte()
            if (outIndex < out.size) out[outIndex++] = ((chunk shr 8) and 0xFF).toByte()
            if (outIndex < out.size) out[outIndex++] = (chunk and 0xFF).toByte()
            index += 4
        }
        return out
    }

    private fun valueOf(char: Char): Int {
        val code = char.code
        if (code >= DECODE_TABLE.size) return 0
        val value = DECODE_TABLE[code]
        return if (value < 0) 0 else value
    }
}
