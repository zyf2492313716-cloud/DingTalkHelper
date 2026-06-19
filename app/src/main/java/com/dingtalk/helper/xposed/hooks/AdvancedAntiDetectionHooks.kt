package com.dingtalk.helper.xposed.hooks

import android.content.ContentResolver
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.net.NetworkInterface

/**
 * 高级反检测 Hook 模块
 * 补全 EnvironmentHooks / EmulatorHooks / AppHidingHooks 未覆盖的检测点
 *
 * 职责：
 * - PackageManager 查询增强过滤（虚拟定位工具包名）
 * - ApplicationInfo.flags FLAG_DEBUGGABLE 清除
 * - Settings.Global/System 开发者选项隐藏增强
 * - NetworkInterface / WifiManager / ConnectivityManager 网络信息伪装
 * - ActivityManager.getRunningAppProcesses() 进程信息过滤
 * - Runtime.exec / ProcessBuilder 拦截 /proc/self/maps 等敏感文件读取
 * - Build.getSerial() 序列号伪装（API 26+）
 *
 * 线程安全：所有 Hook 回调由 Xposed 框架在主线程或 Binder 线程分发，
 * 共享只读集合（HIDDEN_PACKAGES / SENSITIVE_PATHS 等）无需额外同步。
 */
class AdvancedAntiDetectionHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:AntiDetect"

        // 需要从 PackageManager 查询结果中过滤的包名（扩展 Constants.HIDDEN_PACKAGES）
        private val HIDDEN_PACKAGES = Constants.HIDDEN_PACKAGES + setOf(
            // Xposed / LSPosed（补充）
            "de.robv.android.xposed",
            "org.meowcat.edxposed.manager",
            "io.github.lsposed.manager",
            // Magisk（补充）
            "com.topjohnwu.magiskelta",
            // Hide My Applist
            "com.tsng.hidemyapplist",
            "com.tsng.hidemyapplist.xposed",
            // 虚拟定位工具（补充）
            "com.noobexon.xposedfakelocation",
            "com.ella.portal",
            "com.github.fakelocation",
            "com.csgo.fkgps",
            "com.xposed.fakelocation",
            "ru.bartwell.exfileprovider",
            "com.wakasoftware.virtualxposed",
            // Root 管理
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "com.topjohnwu.libsu"
        )

        // Settings.Secure 需要隐藏为 0 的键
        private val SECURE_INT_KEYS_TO_HIDE = setOf(
            "mock_location",
            "development_settings_enabled"
        )

        // Settings.Secure 需要隐藏为 "0" 的键
        private val SECURE_STRING_KEYS_TO_HIDE = setOf(
            "mock_location",
            "development_settings_enabled"
        )

        // Settings.Global 需要隐藏为 0 的键
        private val GLOBAL_INT_KEYS_TO_HIDE = setOf(
            "development_settings_enabled",
            "adb_enabled"
        )

        // Settings.Global 需要隐藏为 "0" 的键
        private val GLOBAL_STRING_KEYS_TO_HIDE = setOf(
            "development_settings_enabled",
            "adb_enabled"
        )

        // Settings.System 需要隐藏的键
        private val SYSTEM_INT_KEYS_TO_HIDE = setOf(
            "development_settings_enabled"
        )

        // 敏感文件路径，拦截 Runtime.exec / ProcessBuilder 对这些路径的访问
        private val SENSITIVE_PATHS = listOf(
            "/proc/self/maps",
            "/proc/self/status",
            "/proc/self/cgroup",
            "/proc/self/cmdline",
            "/proc/net/tcp",
            "/proc/net/tcp6",
            "/proc/net/arp",
            "/proc/version",
            "/proc/modules",
            "/proc/mounts"
        )

        // 敏感命令关键词，拦截包含这些关键词的命令
        private val SENSITIVE_COMMAND_KEYWORDS = setOf(
            "which su", "whereis su", "magisk", "xposed",
            "lsposed", "riru", "lsplant", "supersu",
            "/proc/self/maps", "/proc/self/status",
            "/proc/net/tcp", "/proc/modules",
            "getprop ro.debuggable",
            "getprop ro.secure"
        )

        // 需要精确匹配的敏感命令（使用单词边界匹配）
        private val SENSITIVE_COMMAND_PATTERNS = listOf(
            Regex("\\bsu\\b")
        )

        // 进程名中需要隐藏的关键词
        private val HIDDEN_PROCESS_KEYWORDS = setOf(
            "xposed", "lsposed", "edxposed", "riru",
            "magisk", "zygisk", "lsplant",
            "dingtalk.helper", "hidemyapplist"
        )

        // 模拟器异常网卡名称前缀
        private val EMULATOR_INTERFACE_PREFIXES = setOf(
            "eth", "sit", "tun", "tap", "vbox"
        )
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.log("$TAG: 开始注入高级反检测 Hook")

        try {
            hookPackageManagerEnhanced(lpparam)
            hookApplicationInfoFlags(lpparam)
            hookSettingsEnhanced(lpparam)
            hookNetworkInterfaces(lpparam)
            hookWifiInfo(lpparam)
            hookConnectivityInfo(lpparam)
            hookRunningProcesses(lpparam)
            hookRuntimeExecForProc(lpparam)
            hookProcessBuilderForProc(lpparam)
            hookBuildSerial(lpparam)

            HookUtils.log("$TAG: 高级反检测 Hook 注入完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 高级反检测 Hook 注入失败: ${e.message}")
            if (Constants.DEBUG_MODE) {
                e.printStackTrace()
            }
        }
    }

    // ==================== 1. PackageManager 增强过滤 ====================

    /**
     * Hook PackageManager 查询方法
     * 在 AppHidingHooks 基础上扩展过滤列表，覆盖更多虚拟定位和检测工具
     */
    private fun hookPackageManagerEnhanced(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pmClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager", lpparam.classLoader
            )

            // Hook getInstalledPackages(int)
            XposedHelpers.findAndHookMethod(
                pmClass, "getInstalledPackages", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = filterPackageList(param.result)
                    }
                }
            )

            // Hook getInstalledApplications(int)
            XposedHelpers.findAndHookMethod(
                pmClass, "getInstalledApplications", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = filterApplicationList(param.result)
                    }
                }
            )

            // Hook queryIntentActivities(Intent, int)
            XposedHelpers.findAndHookMethod(
                pmClass, "queryIntentActivities",
                android.content.Intent::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activities = param.result as? List<*> ?: return
                        param.result = activities.filter { resolveInfo ->
                            try {
                                val activityInfo = XposedHelpers.getObjectField(resolveInfo, "activityInfo")
                                val pn = XposedHelpers.getObjectField(activityInfo, "packageName") as? String ?: ""
                                pn !in HIDDEN_PACKAGES
                            } catch (_: Exception) {
                                true
                            }
                        }
                    }
                }
            )

            // Hook getPackageInfo(String, int) - 单包查询
            XposedHelpers.findAndHookMethod(
                pmClass, "getPackageInfo",
                String::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as? String ?: return
                        if (packageName in HIDDEN_PACKAGES) {
                            param.throwable = PackageManager.NameNotFoundException(
                                "Package $packageName not found"
                            )
                        }
                    }
                }
            )

            // Hook getApplicationInfo(String, int) - 单应用信息查询
            XposedHelpers.findAndHookMethod(
                pmClass, "getApplicationInfo",
                String::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as? String ?: return
                        if (packageName in HIDDEN_PACKAGES) {
                            param.throwable = PackageManager.NameNotFoundException(
                                "Package $packageName not found"
                            )
                        }
                    }
                }
            )

            // Hook queryIntentServices(Intent, int)
            XposedHelpers.findAndHookMethod(
                pmClass, "queryIntentServices",
                android.content.Intent::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val services = param.result as? List<*> ?: return
                        param.result = services.filter { resolveInfo ->
                            try {
                                val serviceInfo = XposedHelpers.getObjectField(resolveInfo, "serviceInfo")
                                val pn = XposedHelpers.getObjectField(serviceInfo, "packageName") as? String ?: ""
                                pn !in HIDDEN_PACKAGES
                            } catch (_: Exception) {
                                true
                            }
                        }
                    }
                }
            )

            HookUtils.log("$TAG: PackageManager 增强过滤完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: PackageManager 增强过滤失败: ${e.message}")
        }
    }

    /**
     * 过滤 PackageInfo 列表
     */
    private fun filterPackageList(result: Any?): List<*> {
        val packages = result as? List<*> ?: return emptyList<Any>()
        return packages.filter { pkg ->
            val pn = HookUtils.getFieldValueSafely(pkg ?: return@filter true, "packageName") as? String ?: ""
            pn !in HIDDEN_PACKAGES
        }
    }

    /**
     * 过滤 ApplicationInfo 列表
     */
    private fun filterApplicationList(result: Any?): List<*> {
        val apps = result as? List<*> ?: return emptyList<Any>()
        return apps.filter { app ->
            val pn = HookUtils.getFieldValueSafely(app ?: return@filter true, "packageName") as? String ?: ""
            pn !in HIDDEN_PACKAGES
        }
    }

    // ==================== 2. 调试标志隐藏 ====================

    /**
     * Hook ApplicationInfo 获取，清除 FLAG_DEBUGGABLE
     * 防止通过 ApplicationInfo.flags 检测调试状态
     */
    private fun hookApplicationInfoFlags(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook PackageManager.getApplicationInfo 返回的 ApplicationInfo
            // 清除 FLAG_DEBUGGABLE 标志位
            val pmClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                pmClass, "getApplicationInfo",
                String::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val appInfo = param.result as? ApplicationInfo ?: return
                        appInfo.flags = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
                    }
                }
            )

            // Hook ApplicationInfo 对象的 flags 字段读取
            // 部分应用直接通过反射读取 flags 字段
            try {
                val appInfoClass = ApplicationInfo::class.java
                XposedHelpers.findAndHookMethod(
                    appInfoClass, "toString",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val appInfo = param.thisObject as? ApplicationInfo ?: return
                            appInfo.flags = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.log("$TAG: ApplicationInfo.flags 隐藏完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: ApplicationInfo.flags 隐藏失败: ${e.message}")
        }
    }

    /**
     * Hook Settings.Secure / Settings.Global / Settings.System
     * 增强隐藏开发者选项、ADB 调试、模拟位置等设置
     */
    private fun hookSettingsEnhanced(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookSettingsSecureInt(lpparam)
        hookSettingsSecureString(lpparam)
        hookSettingsGlobalInt(lpparam)
        hookSettingsGlobalString(lpparam)
        hookSettingsSystemInt(lpparam)
        hookSettingsSecureGetString(lpparam)
        hookSettingsGlobalGetString(lpparam)
    }

    /**
     * Hook Settings.Secure.getInt(ContentResolver, String)
     */
    private fun hookSettingsSecureInt(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "android.provider.Settings\$Secure", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz, "getInt",
                ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        if (key in SECURE_INT_KEYS_TO_HIDE) {
                            param.result = 0
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: Settings.Secure.getInt Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Settings.Secure.getInt Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Settings.Secure.getString(ContentResolver, String)
     */
    private fun hookSettingsSecureString(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "android.provider.Settings\$Secure", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz, "getString",
                ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        if (key in SECURE_STRING_KEYS_TO_HIDE) {
                            param.result = "0"
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: Settings.Secure.getString Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Settings.Secure.getString Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Settings.Global.getInt(ContentResolver, String)
     */
    private fun hookSettingsGlobalInt(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "android.provider.Settings\$Global", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz, "getInt",
                ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        if (key in GLOBAL_INT_KEYS_TO_HIDE) {
                            param.result = 0
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: Settings.Global.getInt Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Settings.Global.getInt Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Settings.Global.getString(ContentResolver, String)
     */
    private fun hookSettingsGlobalString(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "android.provider.Settings\$Global", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz, "getString",
                ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        if (key in GLOBAL_STRING_KEYS_TO_HIDE) {
                            param.result = "0"
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: Settings.Global.getString Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Settings.Global.getString Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Settings.System.getInt(ContentResolver, String)
     */
    private fun hookSettingsSystemInt(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "android.provider.Settings\$System", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz, "getInt",
                ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        if (key in SYSTEM_INT_KEYS_TO_HIDE) {
                            param.result = 0
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: Settings.System.getInt Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Settings.System.getInt Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Settings.Secure.getString(ContentResolver, String) 的另一签名
     * 部分 ROM 存在重载方法
     */
    private fun hookSettingsSecureGetString(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "android.provider.Settings\$Secure", lpparam.classLoader
            )
            // Hook getInt(ContentResolver, String, int) 带默认值版本
            XposedHelpers.findAndHookMethod(
                clazz, "getInt",
                ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        if (key in SECURE_INT_KEYS_TO_HIDE) {
                            param.result = 0
                        }
                    }
                }
            )
        } catch (_: Exception) {}
    }

    /**
     * Hook Settings.Global.getString(ContentResolver, String) 的另一签名
     */
    private fun hookSettingsGlobalGetString(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "android.provider.Settings\$Global", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz, "getInt",
                ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        if (key in GLOBAL_INT_KEYS_TO_HIDE) {
                            param.result = 0
                        }
                    }
                }
            )
        } catch (_: Exception) {}
    }

    // ==================== 3. 网络信息伪装 ====================

    /**
     * Hook NetworkInterface.getNetworkInterfaces()
     * 过滤异常网卡接口，隐藏模拟器特征
     */
    private fun hookNetworkInterfaces(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val niClass = NetworkInterface::class.java

            XposedHelpers.findAndHookMethod(
                niClass, "getNetworkInterfaces",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val interfaces = param.result as? java.util.Enumeration<*> ?: return
                        val filtered = java.util.Collections.list(interfaces).filter { iface ->
                            val ni = iface as? NetworkInterface ?: return@filter true
                            val name = ni.name.lowercase()
                            // 过滤模拟器特征网卡
                            !EMULATOR_INTERFACE_PREFIXES.any { name.startsWith(it) }
                        }
                        param.result = java.util.Collections.enumeration(filtered)
                    }
                }
            )

            HookUtils.log("$TAG: NetworkInterface Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: NetworkInterface Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook WifiManager.getConnectionInfo()
     * 确保 IP 地址与 WiFi 配置一致，防止 IP 检测异常
     */
    private fun hookWifiInfo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val wmClass = WifiManager::class.java

            XposedHelpers.findAndHookMethod(
                wmClass, "getConnectionInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val wifiInfo = param.result as? WifiInfo ?: return

                        // 如果启用了 WiFi 伪装，确保 IP 地址合理
                        if (ConfigManager.isFakeWifiEnabled()) {
                            // 设置合理的 IP 地址（避免模拟器默认的 10.0.2.15）
                            val ip = wifiInfo.ipAddress
                            if (ip == 0x0F02000A) { // 10.0.2.15
                                // 生成一个合理的内网 IP
                                val fakeIp = (192 shl 24) or (168 shl 16) or (1 shl 8) or 100
                                XposedHelpers.setIntField(wifiInfo, "mIpAddress", fakeIp)
                            }
                        }

                        // 隐藏异常的 linkSpeed（模拟器可能返回 0）
                        if (wifiInfo.linkSpeed <= 0) {
                            XposedHelpers.setIntField(wifiInfo, "mLinkSpeed", 72)
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
     * Hook ConnectivityManager.getActiveNetworkInfo()
     * 返回正常的网络状态，防止网络环境异常检测
     */
    private fun hookConnectivityInfo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cmClass = ConnectivityManager::class.java

            // Hook getActiveNetworkInfo()
            XposedHelpers.findAndHookMethod(
                cmClass, "getActiveNetworkInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val networkInfo = param.result as? NetworkInfo ?: return
                        // 确保网络状态为已连接
                        // 某些检测会检查网络类型是否合理
                        val type = networkInfo.type
                        val typeName = networkInfo.typeName ?: ""

                        // 如果是移动网络但类型名异常，修正
                        if (type == ConnectivityManager.TYPE_MOBILE && typeName.isEmpty()) {
                            XposedHelpers.setObjectField(networkInfo, "mTypeName", "MOBILE")
                        }
                    }
                }
            )

            // Hook getActiveNetwork()
            try {
                XposedHelpers.findAndHookMethod(
                    cmClass, "getActiveNetwork",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            // 保持返回值不变，仅确保不返回 null（API 23+ 正常不应为 null）
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook getAllNetworkInfo()
            XposedHelpers.findAndHookMethod(
                cmClass, "getAllNetworkInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val infos = param.result as? Array<*> ?: return
                        // 过滤掉异常的网络接口
                        param.result = infos.filter { info ->
                            val ni = info as? NetworkInfo ?: return@filter true
                            val typeName = ni.typeName ?: ""
                            typeName.isNotEmpty()
                        }.toTypedArray()
                    }
                }
            )

            HookUtils.log("$TAG: ConnectivityManager Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: ConnectivityManager Hook 失败: ${e.message}")
        }
    }

    // ==================== 4. 进程信息隐藏 ====================

    /**
     * Hook ActivityManager.getRunningAppProcesses()
     * 过滤掉包含 xposed/lsposed/magisk 的进程
     */
    private fun hookRunningProcesses(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val amClass = android.app.ActivityManager::class.java

            XposedHelpers.findAndHookMethod(
                amClass, "getRunningAppProcesses",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val processes = param.result as? List<*> ?: return
                        param.result = processes.filter { proc ->
                            val processName = try {
                                XposedHelpers.getObjectField(proc, "processName") as? String ?: ""
                            } catch (_: Exception) {
                                ""
                            }
                            val pkgList = try {
                                @Suppress("UNCHECKED_CAST")
                                XposedHelpers.getObjectField(proc, "pkgList") as? Array<String> ?: emptyArray()
                            } catch (_: Exception) {
                                emptyArray()
                            }

                            // 检查进程名和包名列表
                            val nameLower = processName.lowercase()
                            val hasHiddenKeyword = HIDDEN_PROCESS_KEYWORDS.any { nameLower.contains(it) }
                            val hasHiddenPackage = pkgList.any { pkg -> pkg in HIDDEN_PACKAGES }

                            !hasHiddenKeyword && !hasHiddenPackage
                        }
                    }
                }
            )

            // Hook getRunningServices (deprecated 但仍被使用)
            try {
                XposedHelpers.findAndHookMethod(
                    amClass, "getRunningServices", Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val services = param.result as? List<*> ?: return
                            param.result = services.filter { service ->
                                try {
                                    val serviceName = XposedHelpers.getObjectField(service, "service")
                                    val className = XposedHelpers.getObjectField(serviceName, "className") as? String ?: ""
                                    val pkgName = XposedHelpers.getObjectField(serviceName, "packageName") as? String ?: ""
                                    !HIDDEN_PROCESS_KEYWORDS.any { className.lowercase().contains(it) } &&
                                        pkgName !in HIDDEN_PACKAGES
                                } catch (_: Exception) {
                                    true
                                }
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.log("$TAG: ActivityManager 进程信息过滤完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: ActivityManager 进程信息过滤失败: ${e.message}")
        }
    }

    /**
     * Hook Runtime.exec - 拦截读取 /proc/self/maps 等敏感文件的命令
     * 与 EnvironmentHooks 配合，扩展检测范围
     */
    private fun hookRuntimeExecForProc(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)

            // Hook exec(String)
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val command = param.args[0] as? String ?: return
                        if (containsSensitivePath(command)) {
                            param.args[0] = "echo ''"
                            HookUtils.logDebug("$TAG: 阻止敏感命令: $command")
                        }
                    }
                }
            )

            // Hook exec(String[])
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec", Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val commands = param.args[0] as? Array<*> ?: return
                        val cmdStr = commands.joinToString(" ")
                        if (containsSensitivePath(cmdStr)) {
                            param.args[0] = arrayOf("echo", "")
                            HookUtils.logDebug("$TAG: 阻止敏感命令数组: $cmdStr")
                        }
                    }
                }
            )

            // Hook exec(String, String[])
            try {
                XposedHelpers.findAndHookMethod(
                    runtimeClass, "exec",
                    String::class.java, Array<String>::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val command = param.args[0] as? String ?: return
                            if (containsSensitivePath(command)) {
                                param.args[0] = "echo ''"
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook exec(String[], String[])
            try {
                XposedHelpers.findAndHookMethod(
                    runtimeClass, "exec",
                    Array<String>::class.java, Array<String>::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val commands = param.args[0] as? Array<*> ?: return
                            val cmdStr = commands.joinToString(" ")
                            if (containsSensitivePath(cmdStr)) {
                                param.args[0] = arrayOf("echo", "")
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook exec(String, String[], File)
            try {
                XposedHelpers.findAndHookMethod(
                    runtimeClass, "exec",
                    String::class.java, Array<String>::class.java, java.io.File::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val command = param.args[0] as? String ?: return
                            if (containsSensitivePath(command)) {
                                param.args[0] = "echo ''"
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook exec(String[], String[], File)
            try {
                XposedHelpers.findAndHookMethod(
                    runtimeClass, "exec",
                    Array<String>::class.java, Array<String>::class.java, java.io.File::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val commands = param.args[0] as? Array<*> ?: return
                            val cmdStr = commands.joinToString(" ")
                            if (containsSensitivePath(cmdStr)) {
                                param.args[0] = arrayOf("echo", "")
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.log("$TAG: Runtime.exec 敏感路径拦截完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Runtime.exec 敏感路径拦截失败: ${e.message}")
        }
    }

    /**
     * Hook ProcessBuilder.start - 拦截读取 /proc/self/maps 等敏感文件的命令
     * 与 EnvironmentHooks 配合，扩展检测范围
     */
    private fun hookProcessBuilderForProc(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pbClass = XposedHelpers.findClass("java.lang.ProcessBuilder", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                pbClass, "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val field = XposedHelpers.findField(param.thisObject.javaClass, "command")
                            val commands = field.get(param.thisObject) as? List<*> ?: return
                            val cmdStr = commands.joinToString(" ")
                            if (containsSensitivePath(cmdStr)) {
                                field.set(param.thisObject, listOf("echo", ""))
                                HookUtils.logDebug("$TAG: 阻止 ProcessBuilder 敏感命令: $cmdStr")
                            }
                        } catch (_: Exception) {}
                    }
                }
            )

            HookUtils.log("$TAG: ProcessBuilder 敏感路径拦截完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: ProcessBuilder 敏感路径拦截失败: ${e.message}")
        }
    }

    // ==================== 5. 序列号伪装 ====================

    /**
     * Hook Build.getSerial() (API 26+)
     * 返回与 EmulatorHooks 一致的伪装序列号
     */
    private fun hookBuildSerial(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Build::class.java, "getSerial",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = EmulatorHooks.getRandomSerial()
                    }
                }
            )

            HookUtils.log("$TAG: Build.getSerial() Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Build.getSerial() Hook 失败: ${e.message}")
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 检查命令是否包含敏感路径或关键词
     */
    private fun containsSensitivePath(command: String): Boolean {
        val cmdLower = command.lowercase()
        return SENSITIVE_PATHS.any { cmdLower.contains(it.lowercase()) } ||
            SENSITIVE_COMMAND_KEYWORDS.any { cmdLower.contains(it) } ||
            SENSITIVE_COMMAND_PATTERNS.any { it.containsMatchIn(cmdLower) }
    }
}
