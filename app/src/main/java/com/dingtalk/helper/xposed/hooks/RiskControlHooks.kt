package com.dingtalk.helper.xposed.hooks

import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 风控数据拦截 Hook
 * 负责拦截和篡改 lbswua 和 ddsec 风控数据
 * 使用动态类查找替代硬编码，提高兼容性
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

        // 动态扫描并 Hook 风控相关类
        hookSecurityClasses(lpparam)

        // Hook 加密函数
        hookEncryptFunctions(lpparam)

        // Hook 网络请求（可选）
        hookNetworkRequests(lpparam)
    }

    /**
     * 动态扫描并 Hook 风控相关类
     */
    private fun hookSecurityClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 获取所有已加载的类
        val classLoader = lpparam.classLoader

        // 尝试查找并 Hook 风控类
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
            } catch (e: ClassNotFoundException) {
                // 类不存在，继续
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
        // 确定方法名列表
        val methodNames = when {
            className.contains("lbswua", true) || className.contains("SecurityGuard", true) -> {
                Constants.LBSWUA_REPORT_METHODS
            }
            className.contains("ddsec", true) || className.contains("SecurityBody", true) -> {
                Constants.DDSEC_GENERATE_METHODS
            }
            className.contains("riskcontrol", true) || className.contains("RiskControl", true) -> {
                Constants.RISK_CHECK_METHODS
            }
            else -> return
        }

        // Hook 所有匹配的方法
        hookMethodsByNames(clazz, methodNames)
    }

    /**
     * 尝试 Hook 多个方法名
     */
    private fun hookMethodsByNames(clazz: Class<*>, methodNames: List<String>) {
        for (methodName in methodNames) {
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    methodName,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            handleMethodResult(param, clazz.simpleName, methodName)
                        }
                    }
                )
            } catch (e: NoSuchMethodError) {
                // 方法不存在，尝试带参数的版本
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz,
                        methodName,
                        java.util.Map::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                handleMapParameter(param, clazz.simpleName, methodName)
                            }
                        }
                    )
                } catch (e2: NoSuchMethodError) {
                    // 继续尝试其他方法
                    continue
                }
            }
        }
    }

    /**
     * 处理方法返回值
     */
    private fun handleMethodResult(param: XC_MethodHook.MethodHookParam, className: String, methodName: String) {
        try {
            val result = param.result
            when (result) {
                is String -> {
                    // 篡改字符串格式的风控数据
                    param.result = tamperSecurityData(result)
                    HookUtils.logDebug("$TAG: 篡改 $className.$methodName 返回值")
                }
                is Map<*, *> -> {
                    // 篡改 Map 格式的风控数据
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
    private fun handleMapParameter(param: XC_MethodHook.MethodHookParam, className: String, methodName: String) {
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
        var tampered = data

        // 移除分身环境标记
        tampered = tampered.replace(Regex("\"p\":\"[12]\\|"), "\"p\":\"0|")

        // 移除 ROOT 检测标记
        tampered = tampered.replace("\"root\":true", "\"root\":false")
        tampered = tampered.replace("\"hasRoot\":true", "\"hasRoot\":false")
        tampered = tampered.replace("\"isRoot\":true", "\"isRoot\":false")

        // 移除 Xposed 检测标记
        tampered = tampered.replace("\"xposed\":true", "\"xposed\":false")
        tampered = tampered.replace("\"hasXposed\":true", "\"hasXposed\":false")
        tampered = tampered.replace("\"isXposed\":true", "\"isXposed\":false")

        // 移除模拟位置标记
        tampered = tampered.replace("\"mockLocation\":true", "\"mockLocation\":false")
        tampered = tampered.replace("\"isMock\":true", "\"isMock\":false")

        // 移除开发者模式标记
        tampered = tampered.replace("\"developerMode\":true", "\"developerMode\":false")

        return tampered
    }

    /**
     * 篡改风控数据（Map 格式）
     */
    private fun tamperSecurityMap(data: MutableMap<String, Any>) {
        // 移除分身环境标记
        data["p"] = "0|com.alibaba.android.rimet"

        // 移除 ROOT 检测标记
        data["root"] = false
        data["hasRoot"] = false
        data["isRoot"] = false

        // 移除 Xposed 检测标记
        data["xposed"] = false
        data["hasXposed"] = false
        data["isXposed"] = false

        // 移除模拟位置标记
        data["mockLocation"] = false
        data["isMock"] = false

        // 移除开发者模式标记
        data["developerMode"] = false

        // 移除 VPN 检测标记（可能被用来检测代理）
        data["vpn"] = false
        data["isVpn"] = false
    }

    /**
     * Hook 加密函数
     */
    private fun hookEncryptFunctions(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val encryptClassNames = listOf(
            "com.alibaba.wireless.security.securitybody.encrypt.EncryptManager",
            "com.alibaba.security.encrypt.EncryptUtils",
            "com.alibaba.security.sign.SignManager"
        )

        for (className in encryptClassNames) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)

                for (methodName in Constants.ENCRYPT_METHODS) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            clazz,
                            methodName,
                            String::class.java,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    HookUtils.logDebug("$TAG: 加密函数调用: $className.$methodName")
                                }
                            }
                        )
                    } catch (e: NoSuchMethodError) {
                        continue
                    }
                }

                HookUtils.logDebug("$TAG: 加密类 Hook 完成: $className")
                break
            } catch (e: ClassNotFoundException) {
                continue
            }
        }
    }

    /**
     * Hook 网络请求（可选，用于调试）
     */
    private fun hookNetworkRequests(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!Constants.DEBUG_MODE) return

        try {
            // Hook HttpURLConnection
            val urlClass = XposedHelpers.findClass("java.net.URL", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                urlClass,
                "openConnection",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.thisObject.toString()
                        if (url.contains("security") || url.contains("risk") || url.contains("lbswua")) {
                            HookUtils.log("$TAG: 检测到风控网络请求: $url")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            // 忽略
        }
    }
}