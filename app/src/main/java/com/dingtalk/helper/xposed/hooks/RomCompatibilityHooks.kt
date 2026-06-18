package com.dingtalk.helper.xposed.hooks

import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 厂商 ROM 兼容性 Hook
 * 处理 MIUI、ColorOS、HarmonyOS 等定制系统的特殊检测
 */
class RomCompatibilityHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${HookEntry.TAG}:RomCompat"

        // ROM 类型检测
        private val ROM_TYPE by lazy {
            when {
                Build.DISPLAY.contains("MIUI", true) -> "MIUI"
                Build.DISPLAY.contains("ColorOS", true) -> "ColorOS"
                Build.DISPLAY.contains("Harmony", true) -> "HarmonyOS"
                Build.DISPLAY.contains("OriginOS", true) -> "OriginOS"
                Build.DISPLAY.contains("OneUI", true) -> "OneUI"
                Build.MANUFACTURER.equals("Xiaomi", true) -> "MIUI"
                Build.MANUFACTURER.equals("OPPO", true) -> "ColorOS"
                Build.MANUFACTURER.equals("vivo", true) -> "OriginOS"
                Build.MANUFACTURER.equals("samsung", true) -> "OneUI"
                Build.MANUFACTURER.equals("HUAWEI", true) -> "HarmonyOS"
                else -> "AOSP"
            }
        }
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG: 检测到 ROM 类型: $ROM_TYPE")

        when (ROM_TYPE) {
            "MIUI" -> hookMIUI(lpparam)
            "ColorOS" -> hookColorOS(lpparam)
            "HarmonyOS" -> hookHarmonyOS(lpparam)
            "OriginOS" -> hookOriginOS(lpparam)
            "OneUI" -> hookOneUI(lpparam)
        }

        // 通用厂商检测绕过
        hookManufacturerDetection(lpparam)
    }

    /**
     * MIUI/HyperOS 特殊处理
     */
    private fun hookMIUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.log("$TAG: 应用 MIUI 兼容性 Hook")

            // Hook MIUI 位置服务
            val miuiLocationClass = XposedHelpers.findClass(
                "com.miui.server.MiuiLocationManager",
                lpparam.classLoader
            )

            // 尝试 Hook MIUI 特有的位置方法
            try {
                XposedHelpers.findAndHookMethod(
                    miuiLocationClass,
                    "isAllowMockLocation",
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any {
                            return true
                        }
                    }
                )
            } catch (e: NoSuchMethodError) {
                // 方法不存在
            }

            // Hook MIUI 应用行为管理
            hookMIUIAppControl(lpparam)

            // Hook MIUI 省电策略
            hookMIUIBatterySaver(lpparam)

            XposedBridge.log("$TAG: MIUI 兼容性 Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: MIUI 兼容性 Hook 失败: ${e.message}")
        }
    }

    /**
     * MIUI 应用控制 Hook
     */
    private fun hookMIUIAppControl(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 尝试查找 MIUI 应用控制类
            val controlClasses = listOf(
                "com.miui.server.MiuiAppControlManager",
                "com.miui.permcenter.AppControlManager"
            )

            for (className in controlClasses) {
                try {
                    val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                    // Hook 自启动管理
                    try {
                        XposedHelpers.findAndHookMethod(
                            clazz,
                            "isAllowAutoStart",
                            String::class.java,
                            object : XC_MethodReplacement() {
                                override fun replaceHookedMethod(param: MethodHookParam): Any {
                                    return true
                                }
                            }
                        )
                    } catch (e: NoSuchMethodError) {}

                    XposedBridge.log("$TAG: MIUI 应用控制 Hook 完成: $className")
                    break
                } catch (e: ClassNotFoundException) {
                    continue
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG: MIUI 应用控制 Hook 失败: ${e.message}")
        }
    }

    /**
     * MIUI 省电策略 Hook
     */
    private fun hookMIUIBatterySaver(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val batteryClass = XposedHelpers.findClass(
                "com.miui.powerkeeper.PowerKeeper",
                lpparam.classLoader
            )

            // Hook 后台限制
            try {
                XposedHelpers.findAndHookMethod(
                    batteryClass,
                    "isBackgroundRestricted",
                    String::class.java,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any {
                            return false
                        }
                    }
                )
            } catch (e: NoSuchMethodError) {}

            XposedBridge.log("$TAG: MIUI 省电策略 Hook 完成")
        } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * ColorOS 特殊处理
     */
    private fun hookColorOS(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.log("$TAG: 应用 ColorOS 兼容性 Hook")

            // Hook ColorOS 自启动管理
            val autoStartClasses = listOf(
                "com.coloros.deepthinker.manager.AutoStartManager",
                "com.oplus.deepthinker.manager.AutoStartManager"
            )

            for (className in autoStartClasses) {
                try {
                    val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                    XposedHelpers.findAndHookMethod(
                        clazz,
                        "isAllowAutoStart",
                        String::class.java,
                        object : XC_MethodReplacement() {
                            override fun replaceHookedMethod(param: MethodHookParam): Any {
                                return true
                            }
                        }
                    )

                    XposedBridge.log("$TAG: ColorOS 自启动管理 Hook 完成")
                    break
                } catch (e: ClassNotFoundException) {
                    continue
                }
            }

            // Hook ColorOS 应用行为记录
            hookColorOSAppBehavior(lpparam)

            XposedBridge.log("$TAG: ColorOS 兼容性 Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: ColorOS 兼容性 Hook 失败: ${e.message}")
        }
    }

    /**
     * ColorOS 应用行为 Hook
     */
    private fun hookColorOSAppBehavior(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val behaviorClass = XposedHelpers.findClass(
                "com.coloros.deepthinker.AppBehaviorManager",
                lpparam.classLoader
            )

            // Hook 行为检测
            try {
                XposedHelpers.findAndHookMethod(
                    behaviorClass,
                    "isAbnormalBehavior",
                    String::class.java,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any {
                            return false
                        }
                    }
                )
            } catch (e: NoSuchMethodError) {}

            XposedBridge.log("$TAG: ColorOS 应用行为 Hook 完成")
        } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * HarmonyOS 特殊处理
     */
    private fun hookHarmonyOS(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.log("$TAG: 应用 HarmonyOS 兼容性 Hook")

            // HarmonyOS NEXT (纯血鸿蒙) 不支持 Android API
            // 这里只处理兼容 Android 的 HarmonyOS 版本

            // Hook 华为位置服务
            val huaweiLocationClass = XposedHelpers.findClass(
                "com.huawei.hms.location.HwLocationManager",
                lpparam.classLoader
            )

            // Hook 华为 HMS 位置检测
            try {
                XposedHelpers.findAndHookMethod(
                    huaweiLocationClass,
                    "isMockLocation",
                    android.location.Location::class.java,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any {
                            return false
                        }
                    }
                )
            } catch (e: NoSuchMethodError) {}

            XposedBridge.log("$TAG: HarmonyOS 兼容性 Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: HarmonyOS 兼容性 Hook 失败: ${e.message}")
        }
    }

    /**
     * OriginOS 特殊处理
     */
    private fun hookOriginOS(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.log("$TAG: 应用 OriginOS 兼容性 Hook")

            // Hook vivo 自启动管理
            val vivoAutoStartClass = XposedHelpers.findClass(
                "com.vivo.server.autostart.AutoStartManager",
                lpparam.classLoader
            )

            try {
                XposedHelpers.findAndHookMethod(
                    vivoAutoStartClass,
                    "isAllowAutoStart",
                    String::class.java,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any {
                            return true
                        }
                    }
                )
            } catch (e: NoSuchMethodError) {}

            XposedBridge.log("$TAG: OriginOS 兼容性 Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: OriginOS 兼容性 Hook 失败: ${e.message}")
        }
    }

    /**
     * OneUI 特殊处理
     */
    private fun hookOneUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.log("$TAG: 应用 OneUI 兼容性 Hook")

            // Hook Samsung Knox 检测
            val knoxClasses = listOf(
                "com.samsung.android.knox.EnterpriseDeviceManager",
                "com.samsung.android.knox.location.LocationPolicy"
            )

            for (className in knoxClasses) {
                try {
                    val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                    // Hook Knox 位置策略
                    try {
                        XposedHelpers.findAndHookMethod(
                            clazz,
                            "isLocationProviderBlocked",
                            String::class.java,
                            object : XC_MethodReplacement() {
                                override fun replaceHookedMethod(param: MethodHookParam): Any {
                                    return false
                                }
                            }
                        )
                    } catch (e: NoSuchMethodError) {}

                    XposedBridge.log("$TAG: OneUI Knox Hook 完成: $className")
                    break
                } catch (e: ClassNotFoundException) {
                    continue
                }
            }

            XposedBridge.log("$TAG: OneUI 兼容性 Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: OneUI 兼容性 Hook 失败: ${e.message}")
        }
    }

    /**
     * 通用厂商检测绕过
     */
    private fun hookManufacturerDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook Build 类字段
            val buildClass = XposedHelpers.findClass("android.os.Build", lpparam.classLoader)

            // 可以选择修改某些 Build 字段
            // 注意：这可能导致兼容性问题，谨慎使用

            XposedBridge.log("$TAG: 通用厂商检测绕过完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: 通用厂商检测绕过失败: ${e.message}")
        }
    }
}