package com.dingtalk.helper.xposed.hooks

import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 厂商 ROM 兼容性 Hook
 * 处理 MIUI、ColorOS、HarmonyOS 等定制系统的特殊检测
 *
 * 注意：系统类需要通过 BootClassLoader 或系统服务 Hook，
 * 不能通过应用的 ClassLoader 查找
 */
class RomCompatibilityHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:RomCompat"

        private val ROM_TYPE by lazy {
            when {
                Build.DISPLAY.contains("MIUI", true) ||
                Build.MANUFACTURER.equals("Xiaomi", true) -> "MIUI"
                Build.DISPLAY.contains("ColorOS", true) ||
                Build.MANUFACTURER.equals("OPPO", true) -> "ColorOS"
                Build.DISPLAY.contains("Harmony", true) ||
                Build.MANUFACTURER.equals("HUAWEI", true) -> "HarmonyOS"
                Build.DISPLAY.contains("OriginOS", true) ||
                Build.MANUFACTURER.equals("vivo", true) -> "OriginOS"
                Build.DISPLAY.contains("OneUI", true) ||
                Build.MANUFACTURER.equals("samsung", true) -> "OneUI"
                else -> "AOSP"
            }
        }
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isEnabled()) return

        HookUtils.log("$TAG: 检测到 ROM 类型: $ROM_TYPE")

        when (ROM_TYPE) {
            "MIUI" -> hookMIUI(lpparam)
            "ColorOS" -> hookColorOS(lpparam)
            "HarmonyOS" -> hookHarmonyOS(lpparam)
            "OriginOS" -> hookOriginOS(lpparam)
            "OneUI" -> hookOneUI(lpparam)
            else -> HookUtils.log("$TAG: AOSP 环境，跳过厂商 Hook")
        }
    }

    /**
     * MIUI/HyperOS 特殊处理
     * MIUI 对模拟位置有额外检测
     */
    private fun hookMIUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 尝试通过应用 ClassLoader 查找 MIUI 类（如果钉钉引用了 MIUI SDK）
            hookIfExists(lpparam.classLoader, listOf(
                "com.miui.server.MiuiLocationManager",
                "miui.location.LocationManager"
            ), mapOf(
                "isAllowMockLocation" to true
            ))

            // Hook MIUI 应用行为管理
            hookIfExists(lpparam.classLoader, listOf(
                "com.miui.server.MiuiAppControlManager",
                "com.miui.permcenter.AppControlManager"
            ), mapOf(
                "isAllowAutoStart" to true
            ))

            HookUtils.log("$TAG: MIUI 兼容性 Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: MIUI 兼容性 Hook 失败: ${e.message}")
        }
    }

    /**
     * ColorOS 特殊处理
     */
    private fun hookColorOS(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            hookIfExists(lpparam.classLoader, listOf(
                "com.coloros.deepthinker.manager.AutoStartManager",
                "com.oplus.deepthinker.manager.AutoStartManager"
            ), mapOf(
                "isAllowAutoStart" to true
            ))

            hookIfExists(lpparam.classLoader, listOf(
                "com.coloros.deepthinker.AppBehaviorManager"
            ), mapOf(
                "isAbnormalBehavior" to false
            ))

            HookUtils.log("$TAG: ColorOS 兼容性 Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: ColorOS 兼容性 Hook 失败: ${e.message}")
        }
    }

    /**
     * HarmonyOS 特殊处理
     */
    private fun hookHarmonyOS(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            hookIfExists(lpparam.classLoader, listOf(
                "com.huawei.hms.location.HwLocationManager"
            ), mapOf(
                "isMockLocation" to false
            ))

            HookUtils.log("$TAG: HarmonyOS 兼容性 Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: HarmonyOS 兼容性 Hook 失败: ${e.message}")
        }
    }

    /**
     * OriginOS (vivo) 特殊处理
     */
    private fun hookOriginOS(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            hookIfExists(lpparam.classLoader, listOf(
                "com.vivo.server.autostart.AutoStartManager"
            ), mapOf(
                "isAllowAutoStart" to true
            ))

            HookUtils.log("$TAG: OriginOS 兼容性 Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: OriginOS 兼容性 Hook 失败: ${e.message}")
        }
    }

    /**
     * OneUI (Samsung) 特殊处理
     */
    private fun hookOneUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            hookIfExists(lpparam.classLoader, listOf(
                "com.samsung.android.knox.EnterpriseDeviceManager",
                "com.samsung.android.knox.location.LocationPolicy"
            ), mapOf(
                "isLocationProviderBlocked" to false
            ))

            HookUtils.log("$TAG: OneUI 兼容性 Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: OneUI 兼容性 Hook 失败: ${e.message}")
        }
    }

    /**
     * 通用方法：尝试 Hook 存在的类和方法
     * @param classLoader 类加载器
     * @param classNames 候选类名列表
     * @param methodReturns 方法名 -> 返回值 映射
     */
    private fun hookIfExists(
        classLoader: ClassLoader,
        classNames: List<String>,
        methodReturns: Map<String, Any>
    ) {
        val clazz = HookUtils.findClassByNames(classNames, classLoader) ?: return

        for ((methodName, returnValue) in methodReturns) {
            try {
                XposedHelpers.findAndHookMethod(
                    clazz, methodName,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any = returnValue
                    }
                )
                HookUtils.logDebug("$TAG: ${clazz.simpleName}.$methodName -> $returnValue")
            } catch (_: NoSuchMethodError) {
                // 方法不存在，跳过
            }
        }
    }
}
