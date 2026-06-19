package com.dingtalk.helper.xposed.hooks

import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 数据采集阶段拦截
 * 在数据进入阿里安全 SDK 前，从系统 API 层面替换返回值
 *
 * 职责：
 * - 蓝牙信息采集拦截（MAC 地址、设备名）
 * - ContentResolver 敏感数据查询拦截（联系人、短信、通话记录、日历）
 * - 账户信息采集拦截
 *
 * 设计原则：
 * - 不重复 EnvironmentHooks / EmulatorHooks / AppHidingHooks 已覆盖的 Hook
 * - EnvironmentHooks: Runtime.exec, ProcessBuilder, Class.forName, StackTrace, System.getProperty
 * - EmulatorHooks: Build, SystemProperties, Telephony, Sensor, Hardware, Settings
 * - AppHidingHooks: PackageManager 查询, 文件系统, Intent 解析
 * - WifiHooks / CellHooks: WiFi 和基站信息
 */
class DataCollectionInterceptor : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:DataCollect"

        // 敏感 ContentProvider URI 前缀
        private val SENSITIVE_CONTENT_URIS = listOf(
            "content://com.android.contacts",
            "content://contacts",
            "content://call_log",
            "content://sms",
            "content://mms",
            "content://mms-sms",
            "content://calendar",
            "content://com.android.calendar",
            "content://browser/bookmarks",
            "content://com.android.browser",
            "content://telephony",
            "content://com.android.providers.telephony"
        )
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isHideRiskControlEnabled()) {
            HookUtils.logDebug("$TAG: 风控隐藏未启用，跳过")
            return
        }

        HookUtils.log("$TAG: 开始注入数据采集拦截 Hook")

        hookBluetoothInfoCollection(lpparam)
        hookContentResolverQueries(lpparam)
        hookAccountInfoCollection(lpparam)
    }

    // ==================== 蓝牙信息采集拦截 ====================

    /**
     * 替换蓝牙 MAC 地址和设备名，防止硬件指纹追踪
     * 阿里安全 SDK 会采集蓝牙信息作为设备指纹的一部分
     */
    private fun hookBluetoothInfoCollection(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        try {
            val btClass = XposedHelpers.findClass("android.bluetooth.BluetoothAdapter", classLoader)

            // Hook getAddress - 替换蓝牙 MAC 地址
            XposedHelpers.findAndHookMethod(
                btClass, "getAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = RiskControlHooks.generateDeterministicMac().lowercase()
                        HookUtils.logDebug("$TAG: 替换蓝牙 MAC 地址")
                    }
                }
            )

            // Hook getName - 替换蓝牙设备名
            try {
                XposedHelpers.findAndHookMethod(
                    btClass, "getName",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = EmulatorHooks.getDeviceModel()
                            HookUtils.logDebug("$TAG: 替换蓝牙设备名")
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.log("$TAG: 蓝牙信息 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 蓝牙信息 Hook 失败: ${e.message}")
        }
    }

    // ==================== ContentResolver 敏感数据查询拦截 ====================

    /**
     * 阻止访问联系人、短信、通话记录、日历等敏感数据
     * 阿里安全 SDK 可能通过 ContentResolver 采集用户隐私数据
     */
    private fun hookContentResolverQueries(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        try {
            val crClass = XposedHelpers.findClass("android.content.ContentResolver", classLoader)

            // Hook query(Uri, String[], String, String[], String)
            XposedHelpers.findAndHookMethod(
                crClass, "query",
                android.net.Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val uri = param.args[0] as? android.net.Uri ?: return
                        val uriStr = uri.toString()

                        if (SENSITIVE_CONTENT_URIS.any { uriStr.startsWith(it) }) {
                            param.result = null
                            HookUtils.logDebug("$TAG: 阻止敏感 ContentResolver 查询: $uriStr")
                        }
                    }
                }
            )

            // Hook query(Uri, String[], String, String[], String, CancellationSignal)
            // API 16+
            try {
                XposedHelpers.findAndHookMethod(
                    crClass, "query",
                    android.net.Uri::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    android.os.CancellationSignal::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val uri = param.args[0] as? android.net.Uri ?: return
                            val uriStr = uri.toString()

                            if (SENSITIVE_CONTENT_URIS.any { uriStr.startsWith(it) }) {
                                param.result = null
                                HookUtils.logDebug(
                                    "$TAG: 阻止敏感 ContentResolver 查询(带取消): $uriStr"
                                )
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.log("$TAG: ContentResolver Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: ContentResolver Hook 失败: ${e.message}")
        }
    }

    // ==================== 账户信息采集拦截 ====================

    /**
     * 阻止读取设备上的账户列表，防止关联用户身份
     * 阿里安全 SDK 可能通过 AccountManager 采集设备上的 Google/其他账户信息
     */
    private fun hookAccountInfoCollection(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        try {
            val amClass = XposedHelpers.findClass("android.accounts.AccountManager", classLoader)

            // Hook getAccounts - 返回空数组
            XposedHelpers.findAndHookMethod(
                amClass, "getAccounts",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = arrayOf<android.accounts.Account>()
                        HookUtils.logDebug("$TAG: 隐藏账户列表")
                    }
                }
            )

            // Hook getAccountsByType - 返回空数组
            try {
                XposedHelpers.findAndHookMethod(
                    amClass, "getAccountsByType",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = arrayOf<android.accounts.Account>()
                            HookUtils.logDebug("$TAG: 隐藏账户列表 (byType)")
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook getAccountsByTypeAndFeatures
            // 返回 AccountManagerFuture，比较复杂，仅记录日志
            try {
                XposedHelpers.findAndHookMethod(
                    amClass, "getAccountsByTypeAndFeatures",
                    String::class.java,
                    Array<String>::class.java,
                    android.accounts.AccountManagerCallback::class.java,
                    android.os.Handler::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            HookUtils.logDebug("$TAG: getAccountsByTypeAndFeatures 被调用")
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.log("$TAG: AccountManager Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: AccountManager Hook 失败: ${e.message}")
        }
    }
}
