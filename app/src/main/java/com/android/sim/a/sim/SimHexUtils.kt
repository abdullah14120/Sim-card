package com.android.sim.a.sim

import java.util.Locale

data class MsisdnRecord(
    val alphaTag: String,
    val phoneNumber: String,
    val recordLength: Int,
    val rawHex: String
)

object SimHexUtils {

    data class CrsmResponse(
        val sw1: Int,
        val sw2: Int,
        val dataHex: String,
        val raw: String
    ) {
        val isSuccess: Boolean get() = sw1 == 144 && sw2 == 0 // 0x9000
    }

    fun parseCrsmResponse(rawResponse: String): CrsmResponse? {
        val regex = Regex("""\+CRSM:\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*"?([0-9A-Fa-f]*)"?)?""")
        val match = regex.find(rawResponse) ?: return null
        val sw1 = match.groupValues[1].toIntOrNull() ?: return null
        val sw2 = match.groupValues[2].toIntOrNull() ?: return null
        val hex = match.groupValues[3].uppercase(Locale.US)
        return CrsmResponse(sw1, sw2, hex, rawResponse)
    }

    /**
     * فك تشفير سجل EF_MSISDN (6F40) حسب معيار 3GPP TS 31.102
     * الطول الكلي = X (Alpha Tag) + 14 بايت ثابتة في النهاية
     */
    fun decodeMsisdn(hex: String): MsisdnRecord? {
        val bytes = hexToBytes(hex)
        if (bytes.size < 14) return null

        val alphaLen = bytes.size - 14
        val alphaBytes = bytes.copyOfRange(0, alphaLen)
        val alphaTag = alphaBytes
            .takeWhile { (it.toInt() and 0xFF) != 0xFF }
            .map { it.toInt().toChar() }
            .joinToString("")
            .trim()

        val bcdLen = bytes[alphaLen].toInt() and 0xFF
        val tonNpi = bytes[alphaLen + 1].toInt() and 0xFF

        if (bcdLen == 0xFF || bcdLen < 2) {
            return MsisdnRecord(alphaTag, "", bytes.size, hex)
        }

        val digitBytesCount = (bcdLen - 1).coerceAtMost(10)
        val digitBytes = bytes.copyOfRange(alphaLen + 2, alphaLen + 2 + digitBytesCount)
        val numberDigits = decodeSwappedBcd(digitBytes)
        val prefix = if (tonNpi == 0x91) "+" else ""

        return MsisdnRecord(
            alphaTag = alphaTag,
            phoneNumber = prefix + numberDigits,
            recordLength = bytes.size,
            rawHex = hex
        )
    }

    /**
     * تشفير الاسم ورقم الهاتف إلى سجل EF_MSISDN (6F40) بنفس طول السجل الأصلي للشريحة
     */
    fun encodeMsisdn(alphaTag: String, phoneNumber: String, totalRecordLength: Int): String {
        val recordLen = totalRecordLength.coerceAtLeast(14)
        val alphaLen = recordLen - 14
        val result = ByteArray(recordLen) { 0xFF.toByte() }

        // 1. Alpha Tag (ASCII)
        val tagBytes = alphaTag.toByteArray(Charsets.US_ASCII)
        val copyLen = tagBytes.size.coerceAtMost(alphaLen)
        System.arraycopy(tagBytes, 0, result, 0, copyLen)

        // 2. Phone number (TON/NPI + Swapped BCD)
        val trimmedPhone = phoneNumber.trim()
        if (trimmedPhone.isNotEmpty()) {
            val isInternational = trimmedPhone.startsWith("+")
            val digitsOnly = trimmedPhone.removePrefix("+").filter { it.isDigit() || it == '*' || it == '#' }
            val bcdBytes = encodeSwappedBcd(digitsOnly)
            val maxDigitsBytes = bcdBytes.size.coerceAtMost(10)

            // Length byte = TON/NPI (1 byte) + BCD bytes
            result[alphaLen] = (maxDigitsBytes + 1).toByte()
            result[alphaLen + 1] = (if (isInternational) 0x91 else 0x81).toByte()

            for (i in 0 until maxDigitsBytes) {
                result[alphaLen + 2 + i] = bcdBytes[i]
            }
        }

        return bytesToHex(result)
    }

    /**
     * فك تشفير EF_FPLMN (6F7B) — كل شبكة محظورة 3 بايت (MCC/MNC)
     */
    fun decodeFplmn(hex: String): List<String> {
        val bytes = hexToBytes(hex)
        val list = mutableListOf<String>()
        var i = 0
        while (i + 2 < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() and 0xFF
            if (!(b0 == 0xFF && b1 == 0xFF && b2 == 0xFF)) {
                val mcc1 = b0 and 0x0F
                val mcc2 = (b0 shr 4) and 0x0F
                val mcc3 = b1 and 0x0F
                val mnc3 = (b1 shr 4) and 0x0F
                val mnc1 = b2 and 0x0F
                val mnc2 = (b2 shr 4) and 0x0F

                val mcc = "$mcc1$mcc2$mcc3"
                val mnc = if (mnc3 == 0x0F) "$mnc1$mnc2" else "$mnc1$mnc2$mnc3"
                list.add("$mcc-$mnc")
            }
            i += 3
        }
        return list
    }

    /**
     * تشفير قائمة MCC-MNC إلى بايتات EF_FPLMN
     */
    fun encodeFplmn(plmns: List<String>, totalBytes: Int = 12): String {
        val result = ByteArray(totalBytes) { 0xFF.toByte() }
        val maxEntries = totalBytes / 3
        for ((idx, entry) in plmns.take(maxEntries).withIndex()) {
            val clean = entry.replace("-", "").replace(" ", "").trim()
            if (clean.length != 5 && clean.length != 6) continue
            val mcc1 = clean[0].digitToIntOrNull() ?: continue
            val mcc2 = clean[1].digitToIntOrNull() ?: continue
            val mcc3 = clean[2].digitToIntOrNull() ?: continue
            val mnc1 = clean[3].digitToIntOrNull() ?: continue
            val mnc2 = clean[4].digitToIntOrNull() ?: continue
            val mnc3 = if (clean.length == 6) (clean[5].digitToIntOrNull() ?: 0x0F) else 0x0F

            val offset = idx * 3
            result[offset] = ((mcc2 shl 4) or mcc1).toByte()
            result[offset + 1] = ((mnc3 shl 4) or mcc3).toByte()
            result[offset + 2] = ((mnc2 shl 4) or mnc1).toByte()
        }
        return bytesToHex(result)
    }

    fun decodeIccid(hex: String): String {
        return decodeSwappedBcd(hexToBytes(hex))
    }

    fun decodeImsi(hex: String): String {
        val bytes = hexToBytes(hex)
        if (bytes.size < 2) return ""
        val sb = StringBuilder()
        sb.append((bytes[1].toInt() shr 4) and 0x0F)
        for (i in 2 until bytes.size) {
            val low = bytes[i].toInt() and 0x0F
            val high = (bytes[i].toInt() shr 4) and 0x0F
            if (low <= 9) sb.append(low)
            if (high <= 9) sb.append(high)
        }
        return sb.toString()
    }

    private fun decodeSwappedBcd(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val low = b.toInt() and 0x0F
            val high = (b.toInt() shr 4) and 0x0F
            if (low == 0x0F) break
            sb.append(nibbleToChar(low))
            if (high == 0x0F) break
            sb.append(nibbleToChar(high))
        }
        return sb.toString()
    }

    private fun encodeSwappedBcd(digits: String): ByteArray {
        val len = (digits.length + 1) / 2
        val out = ByteArray(len) { 0xFF.toByte() }
        for (i in digits.indices) {
            val nibble = charToNibble(digits[i])
            val byteIdx = i / 2
            if (i % 2 == 0) {
                out[byteIdx] = ((out[byteIdx].toInt() and 0xF0) or nibble).toByte()
            } else {
                out[byteIdx] = ((out[byteIdx].toInt() and 0x0F) or (nibble shl 4)).toByte()
            }
        }
        return out
    }

    private fun nibbleToChar(n: Int): Char = when (n) {
        in 0..9 -> ('0'.code + n).toChar()
        10 -> '*'
        11 -> '#'
        else -> '?'
    }

    private fun charToNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c.code - '0'.code
        '*' -> 10
        '#' -> 11
        else -> 0x0F
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").trim()
        val len = clean.length / 2
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
    }
}
