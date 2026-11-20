package com.android.sim.a.sim

import com.android.sim.a.root.RootShell

data class SimStatusInfo(
    val simState: String,
    val operatorNumeric: String,
    val operatorAlpha: String,
    val countryIso: String,
    val isRoaming: String,
    val iccid: String,
    val imsi: String
)

object SimHardwareController {

    private const val EF_ICCID = 12258  // 0x2FE2
    private const val EF_IMSI = 28423   // 0x6F07
    private const val EF_MSISDN = 28480 // 0x6F40
    private const val EF_FPLMN = 28539  // 0x6F7B

    private const val CMD_READ_BINARY = 176
    private const val CMD_READ_RECORD = 178
    private const val CMD_UPDATE_BINARY = 214
    private const val CMD_UPDATE_RECORD = 220

    fun readSystemSimStatus(atPort: String?): SimStatusInfo {
        val state = RootShell.exec("getprop gsm.sim.state").ifBlank { "UNKNOWN" }
        val numeric = RootShell.exec("getprop gsm.sim.operator.numeric").ifBlank { "-" }
        val alpha = RootShell.exec("getprop gsm.sim.operator.alpha").ifBlank { "-" }
        val iso = RootShell.exec("getprop gsm.sim.operator.iso-country").ifBlank { "-" }
        val roaming = RootShell.exec("getprop gsm.operator.isroaming").ifBlank { "false" }

        var iccid = "-"
        var imsi = "-"

        if (!atPort.isNullOrBlank()) {
            val iccidRaw = RootShell.sendAtCommand(atPort, "AT+CRSM=$CMD_READ_BINARY,$EF_ICCID,0,0,10")
            SimHexUtils.parseCrsmResponse(iccidRaw)?.takeIf { it.isSuccess && it.dataHex.isNotEmpty() }?.let {
                iccid = SimHexUtils.decodeIccid(it.dataHex)
            }

            val imsiRaw = RootShell.sendAtCommand(atPort, "AT+CRSM=$CMD_READ_BINARY,$EF_IMSI,0,0,9")
            SimHexUtils.parseCrsmResponse(imsiRaw)?.takeIf { it.isSuccess && it.dataHex.isNotEmpty() }?.let {
                imsi = SimHexUtils.decodeImsi(it.dataHex)
            }
        }

        return SimStatusInfo(state, numeric, alpha, iso, roaming, iccid, imsi)
    }

    fun readMsisdn(atPort: String): Pair<MsisdnRecord?, String> {
        val raw = RootShell.sendAtCommand(atPort, "AT+CRSM=$CMD_READ_RECORD,$EF_MSISDN,1,4,0")
        val parsed = SimHexUtils.parseCrsmResponse(raw)
        if (parsed != null && parsed.isSuccess && parsed.dataHex.length >= 28) {
            return SimHexUtils.decodeMsisdn(parsed.dataHex) to raw
        }

        val rawFallback = RootShell.sendAtCommand(atPort, "AT+CRSM=$CMD_READ_RECORD,$EF_MSISDN,1,4,28")
        val parsedFallback = SimHexUtils.parseCrsmResponse(rawFallback)
        if (parsedFallback != null && parsedFallback.isSuccess && parsedFallback.dataHex.length >= 28) {
            return SimHexUtils.decodeMsisdn(parsedFallback.dataHex) to rawFallback
        }

        return null to raw
    }

    fun writeMsisdn(
        atPort: String,
        alphaTag: String,
        phoneNumber: String,
        recordLength: Int
    ): Pair<Boolean, String> {
        val len = recordLength.coerceAtLeast(14)
        val payloadHex = SimHexUtils.encodeMsisdn(alphaTag, phoneNumber, len)
        val cmd = "AT+CRSM=$CMD_UPDATE_RECORD,$EF_MSISDN,1,4,$len,\"$payloadHex\""
        val raw = RootShell.sendAtCommand(atPort, cmd)
        val parsed = SimHexUtils.parseCrsmResponse(raw)
        val ok = parsed?.isSuccess == true
        return ok to "CMD: $cmd\nRES: $raw"
    }

    fun readFplmn(atPort: String): Triple<List<String>, Int, String> {
        val raw = RootShell.sendAtCommand(atPort, "AT+CRSM=$CMD_READ_BINARY,$EF_FPLMN,0,0,12")
        val parsed = SimHexUtils.parseCrsmResponse(raw)
        if (parsed != null && parsed.isSuccess && parsed.dataHex.isNotEmpty()) {
            val list = SimHexUtils.decodeFplmn(parsed.dataHex)
            return Triple(list, parsed.dataHex.length / 2, raw)
        }
        return Triple(emptyList(), 12, raw)
    }

    fun writeFplmn(atPort: String, plmns: List<String>, byteLength: Int = 12): Pair<Boolean, String> {
        val len = byteLength.coerceAtLeast(12)
        val payloadHex = SimHexUtils.encodeFplmn(plmns, len)
        val cmd = "AT+CRSM=$CMD_UPDATE_BINARY,$EF_FPLMN,0,0,$len,\"$payloadHex\""
        val raw = RootShell.sendAtCommand(atPort, cmd)
        val parsed = SimHexUtils.parseCrsmResponse(raw)
        val ok = parsed?.isSuccess == true
        return ok to "CMD: $cmd\nRES: $raw"
    }

    fun refreshRadio(atPort: String): String {
        val off = RootShell.sendAtCommand(atPort, "AT+CFUN=0", 1200)
        val on = RootShell.sendAtCommand(atPort, "AT+CFUN=1", 1500)
        return "CFUN=0 -> $off\nCFUN=1 -> $on"
    }
}
