package com.dingtalk.helper.xposed.hooks

import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 风控数据拦截 Hook
 * 负责拦截和篡改 lbswua 和 ddsec 风控数据
 *
 * 使用动态类查找替代硬编码，提高兼容性
 * 借鉴多个开源项目的风控绕过方案
 */
class RiskControlHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:RiskControl"
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isHideRiskControlEnabled()) {
            HookUtils.logDebug("$TAG: 风控隐藏未启用，跳过")
            return
        }

        HookUtils.log("$TAG: 开始注入风控数据拦截 Hook")

        hookSecurityClasses(lpparam)
        hookEncryptFunctions(lpparam)
    }

    /**
     * 动态扫描并 Hook 风控相关类
     */
    private fun hookSecurityClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val allClassNames = Constants.LBSWUA_CLASS_NAMES +
                Constants.DDSEC_CLASS_NAMES +
                Constants.RISK_CONTROL_CLASS_NAMES

        var hookedCount = 0

        for (className in allClassNames) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                hookSecurityClass(clazz, className)
                hookedCount++
                HookUtils.logDebug("$TAG: 成功 Hook 风控类: $className")
            } catch (_: ClassNotFoundException) {
                continue
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: Hook 风控类失败 $className: ${e.message}")
            }
        }

        HookUtils.log("$TAG: 风控类 Hook 完成，共 $hookedCount 个类")
    }

    /**
     * Hook 单个风控类
     */
    private fun hookSecurityClass(clazz: Class<*>, className: String) {
        val methodNames = when {
            className.contains("lbswua", true) ||
            className.contains("SecurityGuard", true) -> Constants.LBSWUA_REPORT_METHODS
            className.contains("ddsec", true) ||
            className.contains("SecurityBody", true) -> Constants.DDSEC_GENERATE_METHODS
            className.contains("riskcontrol", true) ||
            className.contains("RiskControl", true) -> Constants.RISK_CHECK_METHODS
            else -> return
        }

        hookMethodsByNames(clazz, methodNames)
    }

    /**
     * 尝试 Hook 多个方法名
     */
    private fun hookMethodsByNames(clazz: Class<*>, methodNames: List<String>) {
        for (methodName in methodNames) {
            // 无参版本
            try {
                XposedHelpers.findAndHookMethod(
                    clazz, methodName,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            handleMethodResult(param, clazz.simpleName, methodName)
                        }
                    }
                )
            } catch (_: NoSuchMethodError) {
                // 带 Map 参数的版本
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz, methodName,
                        java.util.Map::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                handleMapParameter(param, clazz.simpleName, methodName)
                            }
                        }
                    )
                } catch (_: NoSuchMethodError) {
                    continue
                }
            }
        }
    }

    /**
     * 处理方法返回值
     */
    private fun handleMethodResult(
        param: XC_MethodHook.MethodHookParam,
        className: String,
        methodName: String
    ) {
        try {
            when (val result = param.result) {
                is String -> {
                    param.result = tamperSecurityData(result)
                    HookUtils.logDebug("$TAG: 篡改 $className.$methodName 返回值")
                }
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = result as MutableMap<String, Any>
                    tamperSecurityMap(map)
                    HookUtils.logDebug("$TAG: 篡改 $className.$methodName Map 返回值")
                }
            }
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 处理返回值失败: ${e.message}")
        }
    }

    /**
     * 处理 Map 参数
     */
    private fun handleMapParameter(
        param: XC_MethodHook.MethodHookParam,
        className: String,
        methodName: String
    ) {
        try {
            val data = param.args[0] as? MutableMap<String, Any> ?: return
            tamperSecurityMap(data)
            HookUtils.logDebug("$TAG: 篡改 $className.$methodName 参数")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 处理参数失败: ${e.message}")
        }
    }

    /**
     * 篡改风控数据（字符串格式）
     */
    private fun tamperSecurityData(data: String): String {
        return data
            .replace(Regex("\"p\":\"[12]\\|"), "\"p\":\"0|")
            .replace("\"root\":true", "\"root\":false")
            .replace("\"hasRoot\":true", "\"hasRoot\":false")
            .replace("\"isRoot\":true", "\"isRoot\":false")
            .replace("\"xposed\":true", "\"xposed\":false")
            .replace("\"hasXposed\":true", "\"hasXposed\":false")
            .replace("\"isXposed\":true", "\"isXposed\":false")
            .replace("\"mockLocation\":true", "\"mockLocation\":false")
            .replace("\"isMock\":true", "\"isMock\":false")
            .replace("\"developerMode\":true", "\"developerMode\":false")
    }

    /**
     * 篡改风控数据（Map 格式）
     */
    private fun tamperSecurityMap(data: MutableMap<String, Any>) {
        data["p"] = "0|com.alibaba.android.rimet"
        data["root"] = false
        data["hasRoot"] = false
        data["isRoot"] = false
        data["xposed"] = false
        data["hasXposed"] = false
        data["isXposed"] = false
        data["mockLocation"] = false
        data["isMock"] = false
        data["developerMode"] = false
        data["vpn"] = false
        data["isVpn"] = false
    }

    /**
     * Hook 加密函数（仅日志记录）
     */
    private fun hookEncryptFunctions(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!Constants.DEBUG_MODE) return

        val classLoader = lpparam.classLoader
        for (className in listOf(
            "com.alibaba.wireless.security.securitybody.encrypt.EncryptManager",
            "com.alibaba.security.encrypt.EncryptUtils"
        )) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                for (methodName in Constants.ENCRYPT_METHODS) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            clazz, methodName, String::class.java,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    HookUtils.logDebug("$TAG: 加密函数调用: $className.$methodName")
                                }
                            }
                        )
                    } catch (_: NoSuchMethodError) {}
                }
                break
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
    }
}
