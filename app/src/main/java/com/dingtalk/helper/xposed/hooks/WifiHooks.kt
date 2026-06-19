package com.dingtalk.helper.xposed.hooks

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.data.WiFiScanResultGenerator
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * WiFi 信息伪造 Hook
 * 负责拦截和替换 WiFi 信息，生成真实感的周围 WiFi 环境
 */
class WifiHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:Wifi"
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isFakeWifiEnabled()) {
            HookUtils.logDebug("$TAG: WiFi 伪造未启用，跳过")
            return
        }

        HookUtils.log("$TAG: 开始注入 WiFi 伪造 Hook")

        // Hook WifiManager
        hookWifiManager(lpparam)

        // Hook WifiInfo
        hookWifiInfo(lpparam)

        // Hook 扫描结果
        hookScanResults(lpparam)
    }

    /**
     * Hook WifiManager 获取 WiFi 信息的方法
     */
    private fun hookWifiManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val wifiManagerClass = XposedHelpers.findClass(
                "android.net.wifi.WifiManager",
                lpparam.classLoader
            )

            // Hook getConnectionInfo
            XposedHelpers.findAndHookMethod(
                wifiManagerClass,
                "getConnectionInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (shouldHook()) {
                            val wifiInfo = param.result as? WifiInfo
                            if (wifiInfo != null) {
                                modifyWifiInfo(wifiInfo)
                                HookUtils.logDebug("$TAG: getConnectionInfo 已替换")
                            }
                        }
                    }
                }
            )

            HookUtils.log("$TAG: WifiManager Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: WifiManager Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook WifiInfo 类的方法
     */
    private fun hookWifiInfo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val wifiInfoClass = XposedHelpers.findClass(
                "android.net.wifi.WifiInfo",
                lpparam.classLoader
            )

            // Hook getSSID - 动态读取配置，支持热更新
            XposedHelpers.findAndHookMethod(
                wifiInfoClass,
                "getSSID",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val correlated = ConfigManager.getCorrelatedWifiInfo()
                        return if (correlated.ssid.isNotEmpty()) "\"${correlated.ssid}\"" else
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                    }
                }
            )

            // Hook getBSSID
            XposedHelpers.findAndHookMethod(
                wifiInfoClass,
                "getBSSID",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val correlated = ConfigManager.getCorrelatedWifiInfo()
                        return if (correlated.bssid.isNotEmpty()) correlated.bssid else
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                    }
                }
            )

            // Hook getNetworkId
            XposedHelpers.findAndHookMethod(
                wifiInfoClass,
                "getNetworkId",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return 1
                    }
                }
            )

            // Hook getRssi - 返回伪造的信号强度
            XposedHelpers.findAndHookMethod(
                wifiInfoClass,
                "getRssi",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val correlated = ConfigManager.getCorrelatedWifiInfo()
                        return if (correlated.ssid.isNotEmpty()) correlated.rssi else
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                    }
                }
            )

            // Hook getFrequency - 返回伪造的频率
            XposedHelpers.findAndHookMethod(
                wifiInfoClass,
                "getFrequency",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val correlated = ConfigManager.getCorrelatedWifiInfo()
                        return if (correlated.ssid.isNotEmpty()) correlated.frequency else
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                    }
                }
            )

            // Hook getLinkSpeed - 返回伪造的连接速度
            XposedHelpers.findAndHookMethod(
                wifiInfoClass,
                "getLinkSpeed",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val correlated = ConfigManager.getCorrelatedWifiInfo()
                        if (correlated.ssid.isNotEmpty()) {
                            // 根据频率返回合理的连接速度
                            return if (correlated.frequency >= 5000) 433 else 72
                        }
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                    }
                }
            )

            HookUtils.log("$TAG: WifiInfo Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: WifiInfo Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook WiFi 扫描结果
     */
    private fun hookScanResults(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val wifiManagerClass = XposedHelpers.findClass(
                "android.net.wifi.WifiManager",
                lpparam.classLoader
            )

            // Hook getScanResults
            XposedHelpers.findAndHookMethod(
                wifiManagerClass,
                "getScanResults",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (shouldHook()) {
                            val fakeResults = generateRealisticScanResults()
                            if (fakeResults.isNotEmpty()) {
                                param.result = fakeResults
                                HookUtils.logDebug("$TAG: getScanResults 已替换，${fakeResults.size} 个 AP")
                            }
                        }
                    }
                }
            )

            // Hook startScan - 返回 true 但不实际触发扫描
            XposedHelpers.findAndHookMethod(
                wifiManagerClass,
                "startScan",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return true
                    }
                }
            )

            HookUtils.log("$TAG: ScanResults Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: ScanResults Hook 失败: ${e.message}")
        }
    }

    /**
     * 生成真实感的 WiFi 扫描结果
     * 使用 WiFiScanResultGenerator 基于 GPS 坐标确定性生成
     */
    private fun generateRealisticScanResults(): List<ScanResult> {
        val correlated = ConfigManager.getCorrelatedWifiInfo()
        if (correlated.ssid.isEmpty()) return emptyList()

        val lat = ConfigManager.getLatitude()
        val lng = ConfigManager.getLongitude()

        return try {
            WiFiScanResultGenerator.generateScanResults(
                lat = lat,
                lng = lng,
                connectedSsid = correlated.ssid,
                connectedBssid = correlated.bssid
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 生成扫描结果失败: ${e.message}")
            // 回退：至少返回已连接的 WiFi
            createMinimalScanResult(correlated)
        }
    }

    /**
     * 创建最小的扫描结果（仅已连接的 WiFi）
     */
    private fun createMinimalScanResult(correlated: ConfigManager.CorrelatedWifiInfo): List<ScanResult> {
        return listOf(
            ScanResult().apply {
                SSID = correlated.ssid
                BSSID = correlated.bssid
                level = correlated.rssi
                frequency = correlated.frequency
                capabilities = "[WPA2-PSK-CCMP][ESS]"
                timestamp = System.currentTimeMillis() * 1000
            }
        )
    }

    /**
     * 通过反射修改 WifiInfo 的字段
     */
    private fun modifyWifiInfo(wifiInfo: WifiInfo) {
        val correlated = ConfigManager.getCorrelatedWifiInfo()

        try {
            if (correlated.ssid.isNotEmpty()) {
                val ssidField = WifiInfo::class.java.getDeclaredField("mSSID")
                ssidField.isAccessible = true
                ssidField.set(wifiInfo, "\"${correlated.ssid}\"")
            }

            if (correlated.bssid.isNotEmpty()) {
                val bssidField = WifiInfo::class.java.getDeclaredField("mBSSID")
                bssidField.isAccessible = true
                bssidField.set(wifiInfo, correlated.bssid)
            }

            // 修改信号强度
            try {
                val rssiField = WifiInfo::class.java.getDeclaredField("mRssi")
                rssiField.isAccessible = true
                rssiField.setInt(wifiInfo, correlated.rssi)
            } catch (e: Exception) {
                // 忽略
            }

            // 修改频率
            try {
                val freqField = WifiInfo::class.java.getDeclaredField("mFrequency")
                freqField.isAccessible = true
                freqField.setInt(wifiInfo, correlated.frequency)
            } catch (e: Exception) {
                // 忽略
            }

            // 隐藏 MAC 地址随机化标记
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val randomizedField = WifiInfo::class.java.getDeclaredField("mIsRandomizedMac")
                    randomizedField.isAccessible = true
                    randomizedField.setBoolean(wifiInfo, false)
                } catch (e: Exception) {
                    // 忽略
                }
            }
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 修改 WifiInfo 失败: ${e.message}")
        }
    }

    /**
     * 判断是否需要 Hook
     */
    private fun shouldHook(): Boolean {
        return ConfigManager.isEnabled() && ConfigManager.isFakeWifiEnabled()
    }
}
