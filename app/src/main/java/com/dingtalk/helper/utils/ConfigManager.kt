package com.dingtalk.helper.utils

import android.content.Context
import android.content.SharedPreferences
import com.dingtalk.helper.xposed.data.GeoCorrelationManager
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 配置管理器
 * 管理虚拟定位的所有配置项，支持缓存和验证
 *
 * 使用 ConfigEncryption 进行配置加密存储：
 * - String 值使用 AES-256-GCM 加密
 * - Boolean/Int 值使用 XOR 混淆
 * - 文件名经过哈希混淆，不暴露模块意图
 */
object ConfigManager {

    private var prefs: SharedPreferences? = null
    @Volatile private var isInitialized = false
    private val lock = Any()

    // 配置缓存，减少 SharedPreferences 访问（线程安全）
    private val cache = ConcurrentHashMap<String, Any>()

    /**
     * 初始化
     * 使用 ConfigEncryption 加密存储配置
     */
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(lock) {
            if (isInitialized) return

            // 初始化加密环境
            ConfigEncryption.init(context)

            // 使用加密的 SharedPreferences
            // 文件名会被哈希混淆，不暴露 "dingtalk_helper_prefs" 等明显名称
            prefs = ConfigEncryption.getEncryptedPreferences(context, Constants.PREFS_NAME)

            isInitialized = true
        }
    }

    /**
     * 检查是否已初始化
     */
    private fun checkInit() {
        if (!isInitialized) {
            throw IllegalStateException("ConfigManager 未初始化，请先调用 init()")
        }
    }

    // ==================== 模块启用状态 ====================

    fun isEnabled(): Boolean {
        return getCachedBoolean(Constants.KEY_ENABLED, false)
    }

    fun setEnabled(enabled: Boolean) {
        putBoolean(Constants.KEY_ENABLED, enabled)
    }

    // ==================== 位置配置 ====================

    fun isFakeLocationEnabled(): Boolean {
        return getCachedBoolean(Constants.KEY_FAKE_LOCATION_ENABLED, false)
    }

    fun getLatitude(): Double {
        val value = getCachedString(Constants.KEY_LATITUDE, Constants.DEFAULT_LATITUDE.toString())
        return value.toDoubleOrNull()?.coerceIn(-90.0, 90.0) ?: Constants.DEFAULT_LATITUDE
    }

    fun getLongitude(): Double {
        val value = getCachedString(Constants.KEY_LONGITUDE, Constants.DEFAULT_LONGITUDE.toString())
        return value.toDoubleOrNull()?.coerceIn(-180.0, 180.0) ?: Constants.DEFAULT_LONGITUDE
    }

    fun getAltitude(): Double {
        val value = getCachedString(Constants.KEY_ALTITUDE, Constants.DEFAULT_ALTITUDE.toString())
        return value.toDoubleOrNull() ?: Constants.DEFAULT_ALTITUDE
    }

    /**
     * M14 修复：在方法开头调用 checkInit()，避免 prefs 为 null 时的空指针
     */
    fun setLocation(latitude: Double, longitude: Double, altitude: Double = 0.0) {
        checkInit()
        // 验证坐标范围
        val validLatitude = latitude.coerceIn(-90.0, 90.0)
        val validLongitude = longitude.coerceIn(-180.0, 180.0)

        prefs?.edit()?.apply {
            putString(Constants.KEY_LATITUDE, validLatitude.toString())
            putString(Constants.KEY_LONGITUDE, validLongitude.toString())
            putString(Constants.KEY_ALTITUDE, altitude.toString())
            apply()
        }

        // 更新缓存
        cache[Constants.KEY_LATITUDE] = validLatitude.toString()
        cache[Constants.KEY_LONGITUDE] = validLongitude.toString()
        cache[Constants.KEY_ALTITUDE] = altitude.toString()
    }

    // ==================== WiFi 配置 ====================

    fun isFakeWifiEnabled(): Boolean {
        return getCachedBoolean(Constants.KEY_FAKE_WIFI_ENABLED, false)
    }

    fun getWifiSSID(): String {
        return getCachedString(Constants.KEY_WIFI_SSID, "")
    }

    fun getWifiBSSID(): String {
        return getCachedString(Constants.KEY_WIFI_BSSID, "")
    }

    fun setWifiInfo(ssid: String, bssid: String) {
        // 验证 BSSID 格式 (XX:XX:XX:XX:XX:XX)
        val validBssid = if (isValidBssid(bssid)) bssid else ""

        prefs?.edit()?.apply {
            putString(Constants.KEY_WIFI_SSID, ssid)
            putString(Constants.KEY_WIFI_BSSID, validBssid)
            apply()
        }

        cache[Constants.KEY_WIFI_SSID] = ssid
        cache[Constants.KEY_WIFI_BSSID] = validBssid
    }

    // ==================== 基站配置 ====================

    fun isFakeCellEnabled(): Boolean {
        return getCachedBoolean(Constants.KEY_FAKE_CELL_ENABLED, false)
    }

    fun getCellId(): Int {
        return getCachedInt(Constants.KEY_CELL_ID, 0)
    }

    fun getLac(): Int {
        return getCachedInt(Constants.KEY_LAC, 0)
    }

    fun getMcc(): Int {
        return getCachedInt(Constants.KEY_MCC, Constants.DEFAULT_MCC)
    }

    fun getMnc(): Int {
        return getCachedInt(Constants.KEY_MNC, Constants.DEFAULT_MNC)
    }

    fun setCellInfo(cellId: Int, lac: Int, mcc: Int = 460, mnc: Int = 0) {
        prefs?.edit()?.apply {
            putInt(Constants.KEY_CELL_ID, cellId.coerceAtLeast(0))
            putInt(Constants.KEY_LAC, lac.coerceAtLeast(0))
            putInt(Constants.KEY_MCC, mcc.coerceIn(0, 999))
            putInt(Constants.KEY_MNC, mnc.coerceIn(0, 99))
            apply()
        }

        cache[Constants.KEY_CELL_ID] = cellId
        cache[Constants.KEY_LAC] = lac
        cache[Constants.KEY_MCC] = mcc
        cache[Constants.KEY_MNC] = mnc
    }

    // ==================== 环境隐藏配置 ====================

    fun isHideRootEnabled(): Boolean {
        return getCachedBoolean(Constants.KEY_HIDE_ROOT, true)
    }

    fun isHideXposedEnabled(): Boolean {
        return getCachedBoolean(Constants.KEY_HIDE_XPOSED, true)
    }

    fun isHideMockLocationEnabled(): Boolean {
        return getCachedBoolean(Constants.KEY_HIDE_MOCK_LOCATION, true)
    }

    fun isHideRiskControlEnabled(): Boolean {
        return getCachedBoolean(Constants.KEY_HIDE_RISK_CONTROL, true)
    }

    fun isHideAppsEnabled(): Boolean {
        return getCachedBoolean(Constants.KEY_HIDE_APPS, true)
    }

    fun isDeepHidingEnabled(): Boolean {
        return getCachedBoolean(Constants.KEY_DEEP_HIDING, true)
    }

    // ==================== 地理关联配置 ====================

    fun isAutoCorrelationEnabled(): Boolean {
        return getCachedBoolean(Constants.KEY_AUTO_CORRELATION_ENABLED, false)
    }

    fun setAutoCorrelationEnabled(enabled: Boolean) {
        putBoolean(Constants.KEY_AUTO_CORRELATION_ENABLED, enabled)
    }

    /**
     * 获取关联的 WiFi 信息
     * 优先返回用户手动配置，若未配置且开启自动关联则基于 GPS 生成
     */
    fun getCorrelatedWifiInfo(): CorrelatedWifiInfo {
        val ssid = getWifiSSID()
        val bssid = getWifiBSSID()

        // 用户已手动配置，直接返回
        if (ssid.isNotEmpty()) {
            return CorrelatedWifiInfo(ssid = ssid, bssid = bssid, isAutoGenerated = false)
        }

        // 未开启自动关联，返回空
        if (!isAutoCorrelationEnabled()) {
            return CorrelatedWifiInfo(ssid = "", bssid = "", isAutoGenerated = false)
        }

        // 基于 GPS 坐标自动生成
        val lat = getLatitude()
        val lng = getLongitude()
        val generated = GeoCorrelationManager.getWifiInfoForLocation(lat, lng)
        return CorrelatedWifiInfo(
            ssid = generated.ssid,
            bssid = generated.bssid,
            isAutoGenerated = true,
            rssi = generated.rssi,
            frequency = generated.frequency,
            neighborBssids = generated.neighborBssids,
            neighborRssi = generated.neighborRssi,
            neighborFrequency = generated.neighborFrequency
        )
    }

    /**
     * 获取关联的基站信息
     * 优先返回用户手动配置，若未配置且开启自动关联则基于 GPS 生成
     */
    fun getCorrelatedCellInfo(): CorrelatedCellInfo {
        val cellId = getCellId()
        val lac = getLac()

        // 用户已手动配置（cellId 和 lac 非零），直接返回
        if (cellId != 0 && lac != 0) {
            return CorrelatedCellInfo(
                cellId = cellId,
                lac = lac,
                mcc = getMcc(),
                mnc = getMnc(),
                isAutoGenerated = false
            )
        }

        // 未开启自动关联，返回用户配置
        if (!isAutoCorrelationEnabled()) {
            return CorrelatedCellInfo(
                cellId = cellId,
                lac = lac,
                mcc = getMcc(),
                mnc = getMnc(),
                isAutoGenerated = false
            )
        }

        // 基于 GPS 坐标自动生成
        val lat = getLatitude()
        val lng = getLongitude()
        val generated = GeoCorrelationManager.getCellInfoForLocation(lat, lng)
        return CorrelatedCellInfo(
            cellId = generated.cellId,
            lac = generated.lac,
            mcc = generated.mcc,
            mnc = generated.mnc,
            isAutoGenerated = true,
            neighborCells = generated.neighborCells
        )
    }

    // ==================== 便捷方法 ====================

    /**
     * 获取 Location 对象数据
     */
    fun getFakeLocation(): FakeLocation {
        return FakeLocation(
            latitude = getLatitude(),
            longitude = getLongitude(),
            altitude = getAltitude()
        )
    }

    /**
     * 获取 WiFi 数据
     */
    fun getFakeWifi(): FakeWifi {
        return FakeWifi(
            ssid = getWifiSSID(),
            bssid = getWifiBSSID()
        )
    }

    /**
     * 获取基站数据
     */
    fun getFakeCell(): FakeCell {
        return FakeCell(
            cellId = getCellId(),
            lac = getLac(),
            mcc = getMcc(),
            mnc = getMnc()
        )
    }

    // ==================== 缓存操作 ====================

    private fun getCachedBoolean(key: String, defaultValue: Boolean): Boolean {
        checkInit()
        return (cache[key] as? Boolean) ?: run {
            val value = prefs?.getBoolean(key, defaultValue) ?: defaultValue
            cache[key] = value
            value
        }
    }

    private fun getCachedString(key: String, defaultValue: String): String {
        checkInit()
        return (cache[key] as? String) ?: run {
            val value = prefs?.getString(key, defaultValue) ?: defaultValue
            cache[key] = value
            value
        }
    }

    private fun getCachedInt(key: String, defaultValue: Int): Int {
        checkInit()
        return (cache[key] as? Int) ?: run {
            val value = prefs?.getInt(key, defaultValue) ?: defaultValue
            cache[key] = value
            value
        }
    }

    private fun putBoolean(key: String, value: Boolean) {
        checkInit()
        prefs?.edit()?.putBoolean(key, value)?.apply()
        cache[key] = value
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        cache.clear()
        GeoCorrelationManager.clearCache()
    }

    // ==================== 验证方法 ====================

    /**
     * 验证 BSSID 格式
     */
    private fun isValidBssid(bssid: String): Boolean {
        if (bssid.isEmpty()) return true
        val regex = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        return regex.matches(bssid)
    }

    // ==================== 数据类 ====================

    data class FakeLocation(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double = 0.0,
        val speed: Float = 0f,
        val bearing: Float = 0f,
        val accuracy: Float = Constants.DEFAULT_ACCURACY
    )

    data class FakeWifi(
        val ssid: String,
        val bssid: String
    )

    data class FakeCell(
        val cellId: Int,
        val lac: Int,
        val mcc: Int,
        val mnc: Int
    )

    data class CorrelatedWifiInfo(
        val ssid: String,
        val bssid: String,
        val isAutoGenerated: Boolean,
        val rssi: Int = -50,
        val frequency: Int = 2437,
        val neighborBssids: List<String> = emptyList(),
        val neighborRssi: Int = -65,
        val neighborFrequency: Int = 2437
    )

    data class CorrelatedCellInfo(
        val cellId: Int,
        val lac: Int,
        val mcc: Int,
        val mnc: Int,
        val isAutoGenerated: Boolean,
        val neighborCells: List<GeoCorrelationManager.NeighborCell> = emptyList()
    )

    // ==================== 配置验证 ====================

    /**
     * 验证当前配置的有效性
     */
    fun validateConfig(): ValidationResult {
        checkInit()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (isFakeLocationEnabled()) {
            val lat = getCachedString(Constants.KEY_LATITUDE, "")
            val lng = getCachedString(Constants.KEY_LONGITUDE, "")
            if (lat.isEmpty() || lng.isEmpty()) {
                errors.add("虚拟定位已启用但坐标未设置")
            } else {
                val latValue = lat.toDoubleOrNull()
                val lngValue = lng.toDoubleOrNull()
                if (latValue == null || latValue < -90 || latValue > 90) {
                    errors.add("纬度超出范围（-90 ~ 90）")
                }
                if (lngValue == null || lngValue < -180 || lngValue > 180) {
                    errors.add("经度超出范围（-180 ~ 180）")
                }
            }
        }

        if (isFakeWifiEnabled()) {
            val ssid = getWifiSSID()
            if (ssid.isEmpty()) {
                errors.add("WiFi 伪造已启用但未配置 SSID")
            }
            val bssid = getWifiBSSID()
            if (bssid.isNotEmpty() && !isValidBssid(bssid)) {
                errors.add("WiFi BSSID 格式不正确（应为 XX:XX:XX:XX:XX:XX）")
            }
        }

        if (isFakeCellEnabled()) {
            val cellId = getCellId()
            val lac = getLac()
            val mcc = getMcc()
            val mnc = getMnc()
            if (cellId != 0 && lac == 0) {
                warnings.add("设置了 Cell ID 但 LAC 为空，可能导致定位异常")
            }
            if (lac != 0 && cellId == 0) {
                warnings.add("设置了 LAC 但 Cell ID 为空，可能导致定位异常")
            }
            if (mcc < 0 || mcc > 999) {
                errors.add("MCC 应在 0 ~ 999 之间")
            }
            if (mnc < 0 || mnc > 99) {
                errors.add("MNC 应在 0 ~ 99 之间")
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String> = emptyList()
    )

    // ==================== 配置导入/导出 ====================

    fun exportConfig(): String {
        checkInit()
        val json = org.json.JSONObject()
        json.put(Constants.KEY_ENABLED, isEnabled())
        json.put(Constants.KEY_FAKE_LOCATION_ENABLED, isFakeLocationEnabled())
        json.put(Constants.KEY_LATITUDE, getCachedString(Constants.KEY_LATITUDE, Constants.DEFAULT_LATITUDE.toString()))
        json.put(Constants.KEY_LONGITUDE, getCachedString(Constants.KEY_LONGITUDE, Constants.DEFAULT_LONGITUDE.toString()))
        json.put(Constants.KEY_ALTITUDE, getCachedString(Constants.KEY_ALTITUDE, Constants.DEFAULT_ALTITUDE.toString()))
        json.put(Constants.KEY_FAKE_WIFI_ENABLED, isFakeWifiEnabled())
        json.put(Constants.KEY_WIFI_SSID, getWifiSSID())
        json.put(Constants.KEY_WIFI_BSSID, getWifiBSSID())
        json.put(Constants.KEY_FAKE_CELL_ENABLED, isFakeCellEnabled())
        json.put(Constants.KEY_CELL_ID, getCellId())
        json.put(Constants.KEY_LAC, getLac())
        json.put(Constants.KEY_MCC, getMcc())
        json.put(Constants.KEY_MNC, getMnc())
        json.put(Constants.KEY_HIDE_ROOT, isHideRootEnabled())
        json.put(Constants.KEY_HIDE_XPOSED, isHideXposedEnabled())
        json.put(Constants.KEY_HIDE_MOCK_LOCATION, isHideMockLocationEnabled())
        json.put(Constants.KEY_HIDE_RISK_CONTROL, isHideRiskControlEnabled())
        return json.toString(2)
    }

    /**
     * C6 修复：添加数据验证，对非法值进行 coerceIn 处理
     * 导入配置后调用 validateConfig() 验证，确保数据合法性
     */
    fun importConfig(jsonStr: String): Boolean {
        checkInit()
        return try {
            val json = org.json.JSONObject(jsonStr)
            val editor = prefs?.edit() ?: return false
            if (json.has(Constants.KEY_ENABLED)) editor.putBoolean(Constants.KEY_ENABLED, json.getBoolean(Constants.KEY_ENABLED))
            if (json.has(Constants.KEY_FAKE_LOCATION_ENABLED)) editor.putBoolean(Constants.KEY_FAKE_LOCATION_ENABLED, json.getBoolean(Constants.KEY_FAKE_LOCATION_ENABLED))
            // 坐标值：解析后 coerceIn 保证范围合法
            if (json.has(Constants.KEY_LATITUDE)) {
                val lat = json.getString(Constants.KEY_LATITUDE).toDoubleOrNull()?.coerceIn(-90.0, 90.0)
                if (lat != null) editor.putString(Constants.KEY_LATITUDE, lat.toString())
            }
            if (json.has(Constants.KEY_LONGITUDE)) {
                val lng = json.getString(Constants.KEY_LONGITUDE).toDoubleOrNull()?.coerceIn(-180.0, 180.0)
                if (lng != null) editor.putString(Constants.KEY_LONGITUDE, lng.toString())
            }
            if (json.has(Constants.KEY_ALTITUDE)) editor.putString(Constants.KEY_ALTITUDE, json.getString(Constants.KEY_ALTITUDE))
            if (json.has(Constants.KEY_FAKE_WIFI_ENABLED)) editor.putBoolean(Constants.KEY_FAKE_WIFI_ENABLED, json.getBoolean(Constants.KEY_FAKE_WIFI_ENABLED))
            if (json.has(Constants.KEY_WIFI_SSID)) editor.putString(Constants.KEY_WIFI_SSID, json.getString(Constants.KEY_WIFI_SSID))
            if (json.has(Constants.KEY_WIFI_BSSID)) editor.putString(Constants.KEY_WIFI_BSSID, json.getString(Constants.KEY_WIFI_BSSID))
            if (json.has(Constants.KEY_FAKE_CELL_ENABLED)) editor.putBoolean(Constants.KEY_FAKE_CELL_ENABLED, json.getBoolean(Constants.KEY_FAKE_CELL_ENABLED))
            // 整数值：coerceIn 保证范围合法
            if (json.has(Constants.KEY_CELL_ID)) editor.putInt(Constants.KEY_CELL_ID, json.getInt(Constants.KEY_CELL_ID).coerceAtLeast(0))
            if (json.has(Constants.KEY_LAC)) editor.putInt(Constants.KEY_LAC, json.getInt(Constants.KEY_LAC).coerceAtLeast(0))
            if (json.has(Constants.KEY_MCC)) editor.putInt(Constants.KEY_MCC, json.getInt(Constants.KEY_MCC).coerceIn(0, 999))
            if (json.has(Constants.KEY_MNC)) editor.putInt(Constants.KEY_MNC, json.getInt(Constants.KEY_MNC).coerceIn(0, 99))
            if (json.has(Constants.KEY_HIDE_ROOT)) editor.putBoolean(Constants.KEY_HIDE_ROOT, json.getBoolean(Constants.KEY_HIDE_ROOT))
            if (json.has(Constants.KEY_HIDE_XPOSED)) editor.putBoolean(Constants.KEY_HIDE_XPOSED, json.getBoolean(Constants.KEY_HIDE_XPOSED))
            if (json.has(Constants.KEY_HIDE_MOCK_LOCATION)) editor.putBoolean(Constants.KEY_HIDE_MOCK_LOCATION, json.getBoolean(Constants.KEY_HIDE_MOCK_LOCATION))
            if (json.has(Constants.KEY_HIDE_RISK_CONTROL)) editor.putBoolean(Constants.KEY_HIDE_RISK_CONTROL, json.getBoolean(Constants.KEY_HIDE_RISK_CONTROL))
            editor.apply()
            clearCache()

            // C6：导入后验证配置有效性
            val validation = validateConfig()
            if (!validation.isValid) {
                HookUtils.log("ConfigManager: 导入配置验证失败: ${validation.errors.joinToString(", ")}")
            }

            true
        } catch (e: Exception) {
            false
        }
    }

}
