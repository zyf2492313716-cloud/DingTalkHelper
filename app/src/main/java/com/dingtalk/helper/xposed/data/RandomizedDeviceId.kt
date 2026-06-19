package com.dingtalk.helper.xposed.data

import com.dingtalk.helper.xposed.hooks.EmulatorHooks

object RandomizedDeviceId {

    // IMEI TAC prefixes by brand (Type Allocation Code, first 8 digits)
    private val TAC_PREFIXES = mapOf(
        "samsung" to listOf("352874", "353456", "355892", "356789", "358421", "359123"),
        "xiaomi" to listOf("862145", "861987", "863210", "860123", "864567", "867890"),
        "huawei" to listOf("864512", "867834", "862345", "869012", "863678", "865432"),
        "oppo" to listOf("356789", "862345", "359012", "864567", "351234", "867890"),
        "vivo" to listOf("861234", "354567", "869012", "357890", "863456", "350123"),
        "oneplus" to listOf("352345", "865678", "359012", "862345", "356789", "860123"),
        "honor" to listOf("867123", "354567", "869012", "352345", "866789", "350123"),
        "realme" to listOf("358901", "863456", "356789", "862345", "350123", "865678"),
        "google" to listOf("353456", "862345", "357890", "864567"),
        "motorola" to listOf("355678", "862345", "359012", "865678")
    )

    // MAC OUI prefixes by brand (first 3 bytes, real manufacturer OUIs)
    private val MAC_OUI_PREFIXES = mapOf(
        "samsung" to listOf(
            intArrayOf(0x3C, 0x5A, 0x37), intArrayOf(0x40, 0x4E, 0x36),
            intArrayOf(0x50, 0x01, 0xBB), intArrayOf(0x60, 0x6B, 0xBD),
            intArrayOf(0x84, 0x25, 0xDB), intArrayOf(0xA0, 0x82, 0x1C),
            intArrayOf(0xB4, 0x3A, 0x28), intArrayOf(0xCC, 0x07, 0xAB),
            intArrayOf(0xD8, 0x90, 0xE8), intArrayOf(0xE4, 0x7C, 0xF9)
        ),
        "xiaomi" to listOf(
            intArrayOf(0x28, 0x6C, 0x07), intArrayOf(0x34, 0x80, 0xB3),
            intArrayOf(0x58, 0x44, 0x98), intArrayOf(0x64, 0xB4, 0x73),
            intArrayOf(0x74, 0x23, 0x44), intArrayOf(0x7C, 0x1C, 0x68),
            intArrayOf(0x8C, 0xBE, 0x82), intArrayOf(0xAC, 0xC1, 0xEE),
            intArrayOf(0xD4, 0x97, 0x0B), intArrayOf(0xF8, 0xA4, 0x5F)
        ),
        "huawei" to listOf(
            intArrayOf(0x04, 0x27, 0x28), intArrayOf(0x0C, 0x8A, 0x8B),
            intArrayOf(0x20, 0x08, 0xED), intArrayOf(0x28, 0x6E, 0xD4),
            intArrayOf(0x34, 0xCD, 0xBE), intArrayOf(0x48, 0x46, 0xFB),
            intArrayOf(0x5C, 0x09, 0x4B), intArrayOf(0x70, 0x8A, 0xC3),
            intArrayOf(0x88, 0x3F, 0xD3), intArrayOf(0xCC, 0x53, 0xB5)
        ),
        "oppo" to listOf(
            intArrayOf(0x14, 0x13, 0x5C), intArrayOf(0x20, 0x5D, 0x49),
            intArrayOf(0x28, 0x6C, 0xD7), intArrayOf(0x34, 0x23, 0xBA),
            intArrayOf(0x48, 0x35, 0x43), intArrayOf(0x54, 0x93, 0x20),
            intArrayOf(0x70, 0x32, 0x17), intArrayOf(0x88, 0xC9, 0xD0),
            intArrayOf(0xAC, 0x5F, 0x3E), intArrayOf(0xE8, 0xB4, 0xAE)
        ),
        "vivo" to listOf(
            intArrayOf(0x08, 0x12, 0xA4), intArrayOf(0x10, 0xD5, 0x42),
            intArrayOf(0x1C, 0x63, 0xB7), intArrayOf(0x24, 0x09, 0x95),
            intArrayOf(0x34, 0xCE, 0x00), intArrayOf(0x40, 0x05, 0x5A),
            intArrayOf(0x58, 0x0C, 0xF2), intArrayOf(0x6C, 0x32, 0x2A),
            intArrayOf(0x88, 0xE0, 0xF3), intArrayOf(0xBC, 0x23, 0x92)
        ),
        "oneplus" to listOf(
            intArrayOf(0x04, 0xD1, 0x6E), intArrayOf(0x10, 0x3B, 0x59),
            intArrayOf(0x1C, 0x4D, 0x70), intArrayOf(0x28, 0x7E, 0x80),
            intArrayOf(0x34, 0xE0, 0xCF), intArrayOf(0x48, 0x31, 0xB7),
            intArrayOf(0x5C, 0xA3, 0x9D), intArrayOf(0x78, 0x10, 0xD9),
            intArrayOf(0x94, 0x65, 0x9C), intArrayOf(0xC0, 0xEE, 0x40)
        ),
        "honor" to listOf(
            intArrayOf(0x04, 0xC0, 0x6B), intArrayOf(0x10, 0x44, 0x00),
            intArrayOf(0x1C, 0x52, 0x16), intArrayOf(0x28, 0x6C, 0xE7),
            intArrayOf(0x34, 0x97, 0xF1), intArrayOf(0x48, 0x51, 0xC5),
            intArrayOf(0x5C, 0x7D, 0x5E), intArrayOf(0x70, 0xF9, 0x6D),
            intArrayOf(0x88, 0x57, 0x1D), intArrayOf(0xCC, 0x8C, 0xBF)
        ),
        "realme" to listOf(
            intArrayOf(0x08, 0xD2, 0x9A), intArrayOf(0x14, 0x30, 0x7A),
            intArrayOf(0x20, 0x5E, 0x17), intArrayOf(0x28, 0x98, 0x7B),
            intArrayOf(0x34, 0x2D, 0xBD), intArrayOf(0x44, 0x85, 0x0D),
            intArrayOf(0x54, 0xA5, 0x1B), intArrayOf(0x6C, 0x5C, 0x35),
            intArrayOf(0x8C, 0xAE, 0x29), intArrayOf(0xA0, 0xF3, 0xC1)
        ),
        "google" to listOf(
            intArrayOf(0x3C, 0x28, 0x6D), intArrayOf(0x48, 0xD6, 0x05),
            intArrayOf(0x54, 0x60, 0x09), intArrayOf(0x60, 0x38, 0xE0),
            intArrayOf(0x78, 0x31, 0x2B), intArrayOf(0x90, 0xB1, 0x34),
            intArrayOf(0xA4, 0x77, 0x33), intArrayOf(0xB0, 0xA7, 0xCF)
        ),
        "motorola" to listOf(
            intArrayOf(0x00, 0x1A, 0x3E), intArrayOf(0x08, 0x00, 0x28),
            intArrayOf(0x14, 0x1A, 0xA3), intArrayOf(0x20, 0x0C, 0xC8),
            intArrayOf(0x2C, 0xE4, 0x12), intArrayOf(0x34, 0xE1, 0x2D),
            intArrayOf(0x40, 0x9F, 0x38), intArrayOf(0x54, 0x40, 0xAD),
            intArrayOf(0x60, 0xBE, 0x9B), intArrayOf(0x8C, 0x89, 0xA5)
        )
    )

    // Serial number format patterns by brand
    private val SERIAL_FORMATS = mapOf(
        "samsung" to { rng: java.util.Random -> "R5C" + ('A' + rng.nextInt(26)).toString() + String.format("%09d", rng.nextInt(1000000000)) },
        "xiaomi" to { rng: java.util.Random -> "X" + ('A' + rng.nextInt(26)).toString() + String.format("%03d%08d", rng.nextInt(1000), rng.nextInt(100000000)) },
        "huawei" to { rng: java.util.Random -> String.format("%04X", rng.nextInt(0x10000)) + String.format("%012d", (rng.nextLong() and 0xFFFFFFFFL) % 1000000000000L) },
        "oppo" to { rng: java.util.Random -> "OPP" + String.format("%010d", rng.nextInt(1000000000)) },
        "vivo" to { rng: java.util.Random -> "VV" + String.format("%03d%08d", rng.nextInt(1000), rng.nextInt(100000000)) },
        "oneplus" to { rng: java.util.Random -> "OP" + ('A' + rng.nextInt(26)).toString() + String.format("%09d", rng.nextInt(1000000000)) },
        "honor" to { rng: java.util.Random -> "HNR" + String.format("%010d", rng.nextInt(1000000000)) },
        "realme" to { rng: java.util.Random -> "RM" + String.format("%03d%08d", rng.nextInt(1000), rng.nextInt(100000000)) },
        "google" to { rng: java.util.Random -> "GP" + ('A' + rng.nextInt(26)).toString() + String.format("%09d", rng.nextInt(1000000000)) },
        "motorola" to { rng: java.util.Random -> "MOT" + String.format("%010d", rng.nextInt(1000000000)) }
    )

    private fun calculateLuhnChecksum(digits: String): Int {
        var sum = 0
        var alternate = false
        for (i in digits.reversed()) {
            val d = i - '0'
            sum += if (alternate) {
                val doubled = d * 2
                if (doubled > 9) doubled - 9 else doubled
            } else {
                d
            }
            alternate = !alternate
        }
        return (10 - (sum % 10)) % 10
    }

    fun generateIMEI(seed: Long): String {
        val brand = EmulatorHooks.getDeviceBrand().lowercase()
        val tacPrefixes = TAC_PREFIXES[brand] ?: TAC_PREFIXES["samsung"]!!
        val rng = java.util.Random(seed xor 0x494D4549L)
        val prefix = tacPrefixes[rng.nextInt(tacPrefixes.size)]
        val tac = prefix + String.format("%02d", rng.nextInt(100))
        val body = String.format("%06d", rng.nextInt(1000000))
        val partial = tac + body
        val checksum = calculateLuhnChecksum(partial)
        return partial + checksum
    }

    fun generateIMSI(seed: Long): String {
        val rng = java.util.Random(seed xor 0x494D5349L)
        val operators = listOf(
            "46000" to 15, // China Mobile
            "46002" to 15, // China Mobile
            "46001" to 15, // China Unicom
            "46007" to 15, // China Mobile TD
            "46003" to 15, // China Telecom
            "46005" to 15, // China Telecom
            "46011" to 15  // China Telecom
        )
        val (prefix, totalLen) = operators[rng.nextInt(operators.size)]
        val remaining = totalLen - prefix.length
        val sb = StringBuilder(prefix)
        repeat(remaining) { sb.append(rng.nextInt(10)) }
        return sb.toString()
    }

    fun generateSerial(seed: Long): String {
        val brand = EmulatorHooks.getDeviceBrand().lowercase()
        val formatFn = SERIAL_FORMATS[brand] ?: SERIAL_FORMATS["samsung"]!!
        val rng = java.util.Random(seed xor 0x5345524CL)
        return formatFn(rng)
    }

    fun generateMacAddress(seed: Long): String {
        val brand = EmulatorHooks.getDeviceBrand().lowercase()
        val ouis = MAC_OUI_PREFIXES[brand] ?: MAC_OUI_PREFIXES["samsung"]!!
        val rng = java.util.Random(seed xor 0x4D414340L)
        val oui = ouis[rng.nextInt(ouis.size)]
        return String.format(
            "%02X:%02X:%02X:%02X:%02X:%02X",
            oui[0], oui[1], oui[2],
            rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)
        )
    }

    fun generateAndroidId(seed: Long): String {
        val rng = java.util.Random(seed xor 0x414E4449L)
        val chars = "0123456789abcdef"
        return buildString(16) { repeat(16) { append(chars[rng.nextInt(16)]) } }
    }
}
