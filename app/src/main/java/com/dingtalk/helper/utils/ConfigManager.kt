package com.dingtalk.helper.utils

import android.content.Context
import android.content.SharedPreferences
import com.dingtalk.helper.xposed.utils.Constants

/**
 * 配置管理器
 * 管理虚拟定位的所有配置项，支持缓存和验证
 */
object ConfigManager {

    private var prefs: SharedPreferences? = null
    private var isInitialized = false

    // 配置缓存，减少 SharedPreferences 访问
    private val cache = mutableMapOf<String, Any>()

    /**
     * 初始化
     */
    fun init(context: Context) {
        if (isInitialized) return
        prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        isInitialized = true
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

    fun setLocation(latitude: Double, longitude: Double, altitude: Double = 0.0) {
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
}