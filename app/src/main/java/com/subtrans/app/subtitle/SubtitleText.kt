package com.subtrans.app.subtitle

/**
 * Turning subtitle bytes into text without silently mangling them.
 *
 * Subtitle files in circulation are encoded inconsistently: UTF-8 with and
 * without a byte order mark, UTF-16 from some Windows tools, and Windows-1252
 * from anything older. Decoding all of them as UTF-8 does not fail loudly — it
 * produces replacement characters or, for UTF-16, text riddled with NULs that
 * still parses into cues. The file looks fine in the queue and is ruined by the
 * time anyone reads it.
 *
 * So the encoding is worked out from the bytes rather than assumed.
 */
object SubtitleText {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    enum class Encoding { UTF8, UTF8_BOM, UTF16_LE, UTF16_BE, WINDOWS_1252 }

    data class Decoded(val text: String, val encoding: Encoding)

    fun decode(bytes: ByteArray): Decoded {
        if (bytes.startsWith(UTF8_BOM)) {
            return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), Encoding.UTF8_BOM)
        }
        if (bytes.startsWith(UTF16_LE_BOM)) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), Encoding.UTF16_LE)
        }
        if (bytes.startsWith(UTF16_BE_BOM)) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), Encoding.UTF16_BE)
        }

        // UTF-16 without a mark still gives itself away: half its bytes are NUL,
        // because subtitle text is overwhelmingly in the low code points.
        val sample = bytes.take(2048)
        val nulls = sample.count { it == 0.toByte() }
        if (sample.isNotEmpty() && nulls > sample.size / 4) {
            val evenNulls = sample.filterIndexed { i, b -> i % 2 == 0 && b == 0.toByte() }.size
            val oddNulls = nulls - evenNulls
            val charset = if (evenNulls > oddNulls) Charsets.UTF_16BE else Charsets.UTF_16LE
            val encoding = if (evenNulls > oddNulls) Encoding.UTF16_BE else Encoding.UTF16_LE
            return Decoded(String(bytes, charset), encoding)
        }

        if (isValidUtf8(bytes)) return Decoded(String(bytes, Charsets.UTF_8), Encoding.UTF8)

        val fallback = runCatching { String(bytes, charset("windows-1252")) }.getOrNull()
            ?: String(bytes, Charsets.ISO_8859_1)
        return Decoded(fallback, Encoding.WINDOWS_1252)
    }

    /**
     * Walks the byte sequence rather than decoding and counting replacement
     * characters, so a file containing a legitimate U+FFFD is not misjudged.
     */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val length = when {
                b <= 0x7F -> 1
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> return false
            }
            if (i + length > bytes.size) return false
            for (k in 1 until length) {
                if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
            }
            i += length
        }
        return true
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
}
