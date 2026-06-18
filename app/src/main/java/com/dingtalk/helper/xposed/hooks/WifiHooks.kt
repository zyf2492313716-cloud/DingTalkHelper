package com.dingtalk.helper.xposed.hooks

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * WiFi 信息伪造 Hook
 * 负责拦截和替换 WiFi 信息
 */
class WifiHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${HookEntry.TAG}:Wifi"
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isFakeWifiEnabled()) {
            XposedBridge.log("$TAG: WiFi 伪造未启用，跳过")
            return
        }

        XposedBridge.log("$TAG: 开始注入 WiFi 伪造 Hook")

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
                        if (shouldHook(param)) {
                            val wifiInfo = param.result as? WifiInfo
                            if (wifiInfo != null) {
                                val fakeWifiInfo = createFakeWifiInfo(wifiInfo)
                                param.result = fakeWifiInfo
                                XposedBridge.log("$TAG: getConnectionInfo 已替换")
                            }
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: WifiManager Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: WifiManager Hook 失败: ${e.message}")
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

            val fakeSSID = ConfigManager.getWifiSSID()
            val fakeBSSID = ConfigManager.getWifiBSSID()

            // Hook getSSID
            XposedHelpers.findAndHookMethod(
                wifiInfoClass,
                "getSSID",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return "\"$fakeSSID\""
                    }
                }
            )

            // Hook getBSSID
            XposedHelpers.findAndHookMethod(
                wifiInfoClass,
                "getBSSID",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return fakeBSSID
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

            XposedBridge.log("$TAG: WifiInfo Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: WifiInfo Hook 失败: ${e.message}")
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
                        if (shouldHook(param)) {
                            val originalResults = param.result as? List<ScanResult>
                            val fakeResults = createFakeScanResults(originalResults)
                            param.result = fakeResults
                            XposedBridge.log("$TAG: getScanResults 已替换")
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: ScanResults Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: ScanResults Hook 失败: ${e.message}")
        }
    }

    /**
     * 创建伪造的 WifiInfo
     */
    private fun createFakeWifiInfo(original: WifiInfo): WifiInfo {
        val fakeSSID = ConfigManager.getWifiSSID()
        val fakeBSSID = ConfigManager.getWifiBSSID()

        // 通过反射修改 WifiInfo 的字段
        try {
            val ssidField = WifiInfo::class.java.getDeclaredField("mSSID")
            ssidField.isAccessible = true
            ssidField.set(original, "\"$fakeSSID\"")

            val bssidField = WifiInfo::class.java.getDeclaredField("mBSSID")
            bssidField.isAccessible = true
            bssidField.set(original, fakeBSSID)

            // 隐藏 MAC 地址随机化标记
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val randomizedField = WifiInfo::class.java.getDeclaredField("mIsRandomizedMac")
                    randomizedField.isAccessible = true
                    randomizedField.setBoolean(original, false)
                } catch (e: Exception) {
                    // 忽略
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG: 修改 WifiInfo 失败: ${e.message}")
        }

        return original
    }

    /**
     * 创建伪造的扫描结果
     */
    private fun createFakeScanResults(original: List<ScanResult>?): List<ScanResult> {
        val results = original?.toMutableList() ?: mutableListOf()
        val fakeSSID = ConfigManager.getWifiSSID()
        val fakeBSSID = ConfigManager.getWifiBSSID()

        // 检查是否已存在目标 WiFi
        val exists = results.any { it.SSID == fakeSSID || it.BSSID == fakeBSSID }

        if (!exists && fakeSSID.isNotEmpty()) {
            // 添加伪造的 WiFi 扫描结果
            val fakeScanResult = ScanResult().apply {
                SSID = fakeSSID
                BSSID = fakeBSSID
                level = -50 // 信号强度
                frequency = 2437 // 2.4GHz Channel 6
                capabilities = "[WPA2-PSK-CCMP][ESS]"
                timestamp = System.currentTimeMillis() * 1000
            }
            results.add(0, fakeScanResult)
        }

        return results
    }

    /**
     * 判断是否需要 Hook
     */
    private fun shouldHook(param: XC_MethodHook.MethodHookParam): Boolean {
        if (!ConfigManager.isEnabled()) return false
        if (!ConfigManager.isFakeWifiEnabled()) return false
        return true
    }
}