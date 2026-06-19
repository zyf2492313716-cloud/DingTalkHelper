package com.dingtalk.helper.xposed.data

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 地理关联管理器
 *
 * 根据 GPS 坐标生成地理一致的 WiFi/基站信息，解决三者独立配置导致的地理矛盾。
 * 同一坐标总是生成相同的 WiFi/基站信息（确定性哈希）。
 *
 * 生成规则：
 * - 中国地区 MCC=460，MNC 根据区域推断（00=移动，01=联通，03=电信）
 * - LAC/CID 基于坐标哈希生成，保持一致性
 * - WiFi SSID：基于坐标的"区域名+编号"格式
 * - WiFi BSSID：基于坐标哈希的 MAC 地址
 */
object GeoCorrelationManager {

    // 缓存：避免重复计算
    private val wifiCache = ConcurrentHashMap<String, CorrelatedWifiInfo>()
    private val cellCache = ConcurrentHashMap<String, CorrelatedCellInfo>()

    // 中国省份/城市坐标范围（简化版，用于区域判断）
    private data class RegionBounds(
        val name: String,
        val minLat: Double, val maxLat: Double,
        val minLng: Double, val maxLng: Double
    )

    private val chinaRegions = listOf(
        RegionBounds("Beijing", 39.4, 41.1, 115.4, 117.5),
        RegionBounds("Shanghai", 30.7, 31.9, 120.9, 122.0),
        RegionBounds("Guangzhou", 22.5, 23.9, 112.9, 114.1),
        RegionBounds("Shenzhen", 22.4, 22.9, 113.7, 114.6),
        RegionBounds("Chengdu", 30.1, 31.1, 103.5, 104.9),
        RegionBounds("Hangzhou", 29.8, 30.9, 119.5, 120.9),
        RegionBounds("Wuhan", 29.9, 31.4, 113.7, 115.1),
        RegionBounds("Nanjing", 31.5, 32.6, 118.3, 119.3),
        RegionBounds("Xian", 33.7, 34.6, 108.5, 109.5),
        RegionBounds("Chongqing", 28.8, 30.8, 105.8, 107.8),
        RegionBounds("Tianjin", 38.5, 40.0, 116.5, 118.0),
        RegionBounds("Suzhou", 30.8, 31.9, 119.9, 121.4),
        RegionBounds("Zhengzhou", 34.1, 35.0, 113.1, 114.3),
        RegionBounds("Changsha", 27.8, 28.8, 112.5, 113.5),
        RegionBounds("Dalian", 38.7, 39.5, 121.2, 122.4),
        RegionBounds("Qingdao", 35.8, 36.9, 119.9, 121.0),
        RegionBounds("Xiamen", 24.3, 24.9, 117.8, 118.5),
        RegionBounds("Kunming", 24.5, 25.5, 102.3, 103.3),
        RegionBounds("Harbin", 45.2, 46.5, 126.0, 127.5),
        RegionBounds("Shenyang", 41.5, 42.2, 123.0, 124.0),
        RegionBounds("Jinan", 36.3, 37.2, 116.7, 117.5),
        RegionBounds("Fuzhou", 25.8, 26.5, 119.0, 119.8),
        RegionBounds("Hefei", 31.3, 32.2, 117.0, 117.6),
        RegionBounds("Urumqi", 43.2, 44.2, 87.0, 88.2),
        RegionBounds("Lhasa", 29.3, 29.9, 90.8, 91.5)
    )

    // 省份到 MNC 映射（主要运营商）
    // 00=中国移动，01=中国联通，03=中国电信
    private val regionToMnc = mapOf(
        "Beijing" to 0, "Shanghai" to 0, "Guangzhou" to 1,
        "Shenzhen" to 1, "Chengdu" to 0, "Hangzhou" to 0,
        "Wuhan" to 0, "Nanjing" to 0, "Xian" to 0,
        "Chongqing" to 0, "Tianjin" to 0, "Suzhou" to 0,
        "Zhengzhou" to 0, "Changsha" to 0, "Dalian" to 1,
        "Qingdao" to 0, "Xiamen" to 1, "Kunming" to 0,
        "Harbin" to 1, "Shenyang" to 1, "Jinan" to 0,
        "Fuzhou" to 0, "Hefei" to 0, "Urumqi" to 0,
        "Lhasa" to 0
    )

    /**
     * 根据 GPS 坐标获取关联的 WiFi 信息
     * 同一坐标始终返回相同结果（确定性）
     */
    fun getWifiInfoForLocation(lat: Double, lng: Double): CorrelatedWifiInfo {
        val key = buildCacheKey(lat, lng)
        return wifiCache.getOrPut(key) {
            generateCorrelatedWifi(lat, lng)
        }
    }

    /**
     * 根据 GPS 坐标获取关联的基站信息
     * 同一坐标始终返回相同结果（确定性）
     */
    fun getCellInfoForLocation(lat: Double, lng: Double): CorrelatedCellInfo {
        val key = buildCacheKey(lat, lng)
        return cellCache.getOrPut(key) {
            generateCorrelatedCell(lat, lng)
        }
    }

    /**
     * 生成关联的 WiFi 信息
     */
    private fun generateCorrelatedWifi(lat: Double, lng: Double): CorrelatedWifiInfo {
        val hash = coordinateHash(lat, lng)
        val regionName = getRegionName(lat, lng)

        // SSID: 区域名 + 3位编号
        val ssidIndex = (hash[0].toInt() and 0xFF) * 100 + (hash[1].toInt() and 0xFF)
        val ssid = "${regionName}_${String.format("%03d", ssidIndex % 1000)}"

        // BSSID: 基于哈希的 MAC 地址，设置 locally administered bit
        val bssidBytes = hash.copyOf(6)
        bssidBytes[0] = (bssidBytes[0].toInt() or 0x02).toByte() // locally administered
        bssidBytes[0] = (bssidBytes[0].toInt() and 0xFE).toByte() // unicast
        val bssid = bssidBytes.joinToString(":") { String.format("%02X", it) }

        return CorrelatedWifiInfo(ssid = ssid, bssid = bssid)
    }

    /**
     * 生成关联的基站信息
     */
    private fun generateCorrelatedCell(lat: Double, lng: Double): CorrelatedCellInfo {
        val hash = coordinateHash(lat, lng)
        val regionName = getRegionName(lat, lng)

        // MCC: 中国固定 460
        val mcc = 460

        // MNC: 根据区域推断
        val mnc = regionToMnc[regionName] ?: 0

        // LAC: 基于哈希，范围 1-65535
        val lac = ((hash[2].toInt() and 0xFF) shl 8 or (hash[3].toInt() and 0xFF)).coerceIn(1, 65535)

        // CID: 基于哈希，范围 1-65535（真实 CID 范围更广，但使用此范围以兼容 GSM）
        val cid = ((hash[4].toInt() and 0xFF) shl 8 or (hash[5].toInt() and 0xFF)).coerceIn(1, 65535)

        return CorrelatedCellInfo(
            mcc = mcc,
            mnc = mnc,
            lac = lac,
            cellId = cid
        )
    }

    /**
     * 根据坐标获取区域名称
     * 如果在中国区域内返回对应城市名，否则返回 "Unknown"
     */
    private fun getRegionName(lat: Double, lng: Double): String {
        for (region in chinaRegions) {
            if (lat in region.minLat..region.maxLat && lng in region.minLng..region.maxLng) {
                return region.name
            }
        }
        // 不在已知区域内，使用哈希生成一个通用名称
        val hash = coordinateHash(lat, lng)
        val index = (hash[0].toInt() and 0xFF) % chinaRegions.size
        return chinaRegions[index].name
    }

    /**
     * 坐标哈希：同一坐标始终产生相同的 16 字节哈希
     * 使用 SHA-256 截取前 16 字节
     */
    private fun coordinateHash(lat: Double, lng: Double): ByteArray {
        // 将坐标格式化为固定精度字符串，确保浮点一致性
        val input = String.format("%.6f,%.6f", lat, lng)
        val digest = MessageDigest.getInstance("SHA-256")
        val fullHash = digest.digest(input.toByteArray())
        return fullHash.copyOf(16)
    }

    /**
     * 构建缓存键（四舍五入到小数点后 4 位，约 11 米精度）
     */
    private fun buildCacheKey(lat: Double, lng: Double): String {
        return String.format("%.4f,%.4f", lat, lng)
    }

    /**
     * 清除缓存（配置变更时调用）
     */
    fun clearCache() {
        wifiCache.clear()
        cellCache.clear()
    }

    // ==================== 数据类 ====================

    data class CorrelatedWifiInfo(
        val ssid: String,
        val bssid: String
    )

    data class CorrelatedCellInfo(
        val mcc: Int,
        val mnc: Int,
        val lac: Int,
        val cellId: Int
    )
}
