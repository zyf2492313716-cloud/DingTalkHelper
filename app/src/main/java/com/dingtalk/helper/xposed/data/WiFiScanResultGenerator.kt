package com.dingtalk.helper.xposed.data

import android.net.wifi.ScanResult
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * WiFi 扫描结果生成器
 *
 * 基于 GPS 坐标确定性地生成周围 WiFi 环境，模拟真实设备的扫描结果。
 * 同一坐标始终生成相同的 WiFi 列表（确定性哈希）。
 *
 * 特性：
 * - 15-25 个真实 SSID 模式的周围 AP
 * - 2.4GHz (Ch 1/6/11) 和 5GHz (Ch 36/40/44/149/153) 混合
 * - RSSI 基于模拟距离衰减
 * - 常见中国路由器厂商 OUI 前缀
 * - 相邻 AP（相似信号强度）
 */
object WiFiScanResultGenerator {

    private const val MIN_AP_COUNT = 15
    private const val MAX_AP_COUNT = 25
    private const val NEIGHBOR_COUNT = 3
    private const val CONNECTED_RSSI_MIN = -55
    private const val CONNECTED_RSSI_MAX = -40

    private val cache = ConcurrentHashMap<String, List<ScanResult>>()

    /**
     * 常见中国路由器厂商 OUI 前缀
     * 格式: 3 字节，如 0x78, 0x8A, 0x20 -> "78:8A:20"
     */
    private val routerOuis = byteArrayOf(
        0x78.toByte(), 0x8A.toByte(), 0x20.toByte(),  // TP-Link
        0x48.toByte(), 0x5F.toByte(), 0x99.toByte(),  // Huawei
        0x28.toByte(), 0x6C.toByte(), 0x07.toByte(),  // Xiaomi
        0xAC.toByte(), 0x84.toByte(), 0xC6.toByte(),  // TP-Link (alt)
        0x00.toByte(), 0x1A.toByte(), 0x11.toByte(),  // ZTE
        0x14.toByte(), 0x75.toByte(), 0x5B.toByte(),  // ChinaTelecom (天翼)
        0xB0.toByte(), 0xBE.toByte(), 0x76.toByte(),  // TP-Link (v2)
        0x5C.toByte(), 0x7D.toByte(), 0x5E.toByte(),  // Huawei (alt)
        0xF8.toByte(), 0x3D.toByte(), 0xFF.toByte(),  // Huawei (alt2)
        0xCC.toByte(), 0x2D.toByte(), 0x83.toByte(),  // Xiaomi (alt)
        0x60.toByte(), 0xE3.toByte(), 0x27.toByte(),  // ChinaNet
        0x38.toByte(), 0xF8.toByte(), 0x89.toByte(),  // Mercury (水星)
        0x10.toByte(), 0xFE.toByte(), 0xED.toByte(),  // TP-Link (v3)
        0x20.toByte(), 0xDC.toByte(), 0xE6.toByte(),  // Huawei (alt3)
        0xD4.toByte(), 0x3D.toByte(), 0x7E.toByte(),  // Xiaomi (alt2)
        0xE4.toByte(), 0x5F.toByte(), 0x01.toByte(),  // Huawei (alt4)
        0x7C.toByte(), 0x49.toByte(), 0xEB.toByte(),  // Ruijie (锐捷)
        0x58.toByte(), 0x2A.toByte(), 0xF7.toByte(),  // Fast (迅捷)
    )

    private val ouiCount = routerOuis.size / 3

    /**
     * SSID 前缀模式，模拟中国常见 WiFi 命名
     */
    private val ssidPrefixes = listOf(
        "ChinaNet-", "CMCC-", "CMCC-EDU-", "CU_",
        "TP-Link_", "TP-LINK_", "TP-LINK_Fast_",
        "Xiaomi_", "Mi-Fi_",
        "HUAWEI_", "HUAWEI-B315-",
        "Tenda_", "FAST_", "MERCURY_",
        "ZTE_", "ZTE-",
        "ChinaUnicom-", "ChinaTelecom-",
        "Ruijie-",
        "dlink-", "D-Link_",
        "NETCORE_", "C-",
        "Phicomm_", "netis_",
        "B-",
    )

    /**
     * 2.4GHz 频率表（channel -> frequency in MHz）
     */
    private val freq2g4 = intArrayOf(2412, 2437, 2462) // Ch 1, 6, 11

    /**
     * 5GHz 频率表（channel -> frequency in MHz）
     */
    private val freq5g = intArrayOf(
        5180, 5200, 5220, // Ch 36, 40, 44
        5745, 5765        // Ch 149, 153
    )

    /**
     * 能力组合
     */
    private val capabilities = listOf(
        "[WPA2-PSK-CCMP][ESS]",
        "[WPA-PSK+TKIP][ESS]",
        "[WPA2-PSK-CCMP][WPS][ESS]",
        "[ESS]",
        "[WPA-PSK+CCMP][WPA2-PSK-CCMP][ESS]",
    )

    /**
     * 根据 GPS 坐标生成完整的 WiFi 扫描结果列表
     * @param lat 纬度
     * @param lng 经度
     * @param connectedSsid 已连接 WiFi 的 SSID
     * @param connectedBssid 已连接 WiFi 的 BSSID
     * @return ScanResult 列表（第一个为已连接的最强信号）
     */
    fun generateScanResults(
        lat: Double,
        lng: Double,
        connectedSsid: String,
        connectedBssid: String
    ): List<ScanResult> {
        val key = "${String.format("%.6f,%.6f", lat, lng)}:$connectedSsid:$connectedBssid"
        return cache.getOrPut(key) {
            buildScanResultList(lat, lng, connectedSsid, connectedBssid)
        }
    }

    /**
     * 构建完整的扫描结果列表
     */
    private fun buildScanResultList(
        lat: Double,
        lng: Double,
        connectedSsid: String,
        connectedBssid: String
    ): List<ScanResult> {
        val hash = coordinateHash(lat, lng)
        val random = SeededRandom(hash)
        val results = mutableListOf<ScanResult>()

        // 1. 已连接的 WiFi（最强信号）
        val connectedRssi = CONNECTED_RSSI_MIN + random.nextInt(
            CONNECTED_RSSI_MAX - CONNECTED_RSSI_MIN + 1
        )
        val connectedFreq = if (random.nextBoolean()) {
            freq2g4[random.nextInt(freq2g4.size)]
        } else {
            freq5g[random.nextInt(freq5g.size)]
        }
        results.add(
            createScanResult(
                ssid = connectedSsid,
                bssid = connectedBssid,
                rssi = connectedRssi,
                frequency = connectedFreq,
                caps = "[WPA2-PSK-CCMP][ESS]",
                random = random
            )
        )

        // 2. 相邻 AP（相似信号强度，来自同一栋楼）
        for (i in 0 until NEIGHBOR_COUNT) {
            val neighborHash = deriveSubHash(hash, i + 100)
            val neighborRandom = SeededRandom(neighborHash)
            val neighborBssid = generateRealisticBssid(neighborRandom)
            val neighborRssi = connectedRssi + (neighborRandom.nextInt(16) - 6) // -6 to +9 relative
            val neighborFreq = if (neighborRandom.nextBoolean()) {
                freq2g4[neighborRandom.nextInt(freq2g4.size)]
            } else {
                freq5g[neighborRandom.nextInt(freq5g.size)]
            }
            val neighborSsid = ssidPrefixes[neighborRandom.nextInt(ssidPrefixes.size)] +
                generateSsidSuffix(neighborRandom)

            results.add(
                createScanResult(
                    ssid = neighborSsid,
                    bssid = neighborBssid,
                    rssi = neighborRssi.coerceIn(-85, -30),
                    frequency = neighborFreq,
                    caps = capabilities[neighborRandom.nextInt(capabilities.size)],
                    random = neighborRandom
                )
            )
        }

        // 3. 周围 AP（不同距离，信号递减）
        val apCount = MIN_AP_COUNT + random.nextInt(MAX_AP_COUNT - MIN_AP_COUNT + 1)
        for (i in 0 until apCount) {
            val apHash = deriveSubHash(hash, i)
            val apRandom = SeededRandom(apHash)

            val ouiIndex = apRandom.nextInt(ouiCount)
            val bssid = generateRealisticBssid(apRandom)

            // 模拟距离：5m ~ 150m，RSSI 按距离衰减
            val distanceMeters = 5.0 + apRandom.nextDouble() * 145.0
            val rssi = calculateRssi(distanceMeters, apRandom.nextBoolean(), apRandom)

            val ssid = ssidPrefixes[apRandom.nextInt(ssidPrefixes.size)] +
                generateSsidSuffix(apRandom)

            val freq = if (apRandom.nextBoolean()) {
                freq2g4[apRandom.nextInt(freq2g4.size)]
            } else {
                freq5g[apRandom.nextInt(freq5g.size)]
            }

            results.add(
                createScanResult(
                    ssid = ssid,
                    bssid = bssid,
                    rssi = rssi,
                    frequency = freq,
                    caps = capabilities[apRandom.nextInt(capabilities.size)],
                    random = apRandom
                )
            )
        }

        // 按信号强度降序排列（真实设备行为）
        results.sortByDescending { it.level }

        return results
    }

    /**
     * 创建 ScanResult 对象
     */
    private fun createScanResult(
        ssid: String,
        bssid: String,
        rssi: Int,
        frequency: Int,
        caps: String,
        @Suppress("UNUSED_PARAMETER") random: SeededRandom
    ): ScanResult {
        return ScanResult().apply {
            this.SSID = ssid
            this.BSSID = bssid
            this.level = rssi.coerceIn(-100, -20)
            this.frequency = frequency
            this.capabilities = caps
            this.timestamp = System.currentTimeMillis() * 1000
            this.channelWidth = 0 // 20MHz
        }
    }

    /**
     * 基于距离的 RSSI 衰减模型
     *
     * 使用自由空间路径损耗模型的简化版本：
     * RSSI = TxPower - 10 * n * log10(d)
     * 其中 n 为路径损耗指数（室内约 3.0-4.0）
     *
     * @param distanceMeters 距离（米）
     * @param is5g 是否为 5GHz（5GHz 衰减更快）
     * @param random 随机数生成器
     * @return RSSI 值（dBm）
     */
    private fun calculateRssi(distanceMeters: Double, is5g: Boolean, random: SeededRandom): Int {
        val txPower = -25.0
        val pathLossExponent = if (is5g) 3.5 else 3.0

        val baseRssi = txPower - 10 * pathLossExponent * kotlin.math.log10(max(distanceMeters, 1.0))

        // 添加环境噪声（±3dB 随机波动）
        val noise = (random.nextDouble() * 6 - 3)

        return (baseRssi + noise).toInt().coerceIn(-90, -30)
    }

    /**
     * 生成真实感的 BSSID（MAC 地址）
     * 使用常见路由器厂商的 OUI 前缀 + 随机后 3 字节
     */
    fun generateRealisticBssid(random: SeededRandom): String {
        val ouiIndex = random.nextInt(ouiCount)
        val baseOffset = ouiIndex * 3
        val b1 = (routerOuis[baseOffset].toInt() and 0xFF)
        val b2 = (routerOuis[baseOffset + 1].toInt() and 0xFF)
        val b3 = (routerOuis[baseOffset + 2].toInt() and 0xFF)
        val b4 = random.nextInt(256)
        val b5 = random.nextInt(256)
        val b6 = random.nextInt(256)

        return String.format(
            "%02X:%02X:%02X:%02X:%02X:%02X",
            b1, b2, b3, b4, b5, b6
        )
    }

    /**
     * 生成 SSID 后缀（3-4 位十六进制或数字）
     */
    private fun generateSsidSuffix(random: SeededRandom): String {
        val style = random.nextInt(4)
        return when (style) {
            0 -> String.format("%04X", random.nextInt(0x10000))
            1 -> String.format("%06X", random.nextInt(0x1000000))
            2 -> String.format("%04d", random.nextInt(10000))
            else -> String.format("%03d", random.nextInt(1000))
        }
    }

    /**
     * 坐标哈希：同一坐标始终产生相同的 16 字节哈希
     */
    private fun coordinateHash(lat: Double, lng: Double): ByteArray {
        val input = String.format("%.6f,%.6f", lat, lng)
        val digest = MessageDigest.getInstance("SHA-256")
        val fullHash = digest.digest(input.toByteArray())
        return fullHash.copyOf(16)
    }

    /**
     * 从父哈希派生子哈希，保证确定性
     */
    private fun deriveSubHash(parentHash: ByteArray, index: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(parentHash)
        digest.update(byteArrayOf(
            (index shr 24).toByte(),
            (index shr 16).toByte(),
            (index shr 8).toByte(),
            index.toByte()
        ))
        return digest.digest().copyOf(16)
    }

    /**
     * 基于种子的确定性随机数生成器
     * 保证相同种子产生相同序列
     */
    class SeededRandom(seed: ByteArray) {
        private var state: Long

        init {
            var s = 0L
            for (b in seed) {
                s = s * 31 + (b.toInt() and 0xFF)
            }
            state = s xor 0x5DEECE66DL
            if (state == 0L) state = 1L
        }

        fun nextInt(bound: Int): Int {
            if (bound <= 0) return 0
            state = state * 0x5DEECE66DL + 0xBL
            return ((state.toULong() and 0x7FFFFFFFUL) % bound.toULong()).toInt()
        }

        fun nextDouble(): Double {
            return nextInt(1 shl 26).toDouble() / (1 shl 26).toDouble()
        }

        fun nextBoolean(): Boolean {
            return nextInt(2) == 0
        }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        cache.clear()
    }
}
