package com.dingtalk.helper.xposed.hooks

import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Native 层拦截 Hook
 * 拦截 JNI 方法调用，解决 lbswua/ddsec 数据流在 native 层完成的问题
 *
 * 原理：
 * - lbswua/ddsec 的数据采集和加密在 native (.so) 层完成
 * - Java 层的全局 Hook (Cipher/MessageDigest) 无法拦截 native 数据流
 * - 通过 Hook Java 侧的 native 方法声明，可以在数据进入 native 层前进行拦截
 * - Xposed 的 findAndHookMethod 可以替换 native 方法的 Java 侧入口
 */
class NativeHook : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:NativeHook"

        // 阿里安全 SDK 包名前缀
        private val SECURITY_SDK_PACKAGES = listOf(
            "com.alibaba.wireless.security",
            "com.alibaba.security",
            "com.alibaba.sdk.android.security",
            "com.taobao.security",
            "com.alipay.security"
        )

        // M7 修复：快速排除关键词，用于 ClassLoader.loadClass 监控的快速跳过
        private val QUICK_FILTER_KEYWORDS = listOf("alibaba", "taobao", "alipay")
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isHideRiskControlEnabled()) {
            HookUtils.logDebug("$TAG: 风控隐藏未启用，跳过")
            return
        }

        HookUtils.log("$TAG: 开始注入 Native 层拦截 Hook")

        hookNativeMethodDeclarations(lpparam)
        hookNativeLibraryLoadEvents(lpparam)
        hookSecuritySdkNativeMethods(lpparam)
    }

    // ==================== Native 方法声明拦截 ====================

    /**
     * Hook 阿里安全 SDK 类中的 native 方法声明
     * Xposed 可以通过替换 Java 实现来 Hook native 方法，
     * 使 native 代码无法执行，同时返回我们控制的假数据
     */
    private fun hookNativeMethodDeclarations(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        var hookedCount = 0

        for (packageName in SECURITY_SDK_PACKAGES) {
            try {
                val classes = findClassesWithNativeMethods(packageName, classLoader)
                for (clazz in classes) {
                    val nativeMethods = getNativeMethods(clazz)
                    for (method in nativeMethods) {
                        if (hookNativeMethod(clazz, method)) {
                            hookedCount++
                        }
                    }
                }
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: 扫描包 $packageName 失败: ${e.message}")
            }
        }

        HookUtils.log("$TAG: Native 方法声明 Hook 完成，共 $hookedCount 个")
    }

    /**
     * 查找包含 native 方法的已知类
     */
    private fun findClassesWithNativeMethods(
        packageName: String,
        classLoader: ClassLoader
    ): List<Class<*>> {
        val result = mutableListOf<Class<*>>()
        val knownClasses = listOf(
            "$packageName.securityguard.SecurityGuardManager",
            "$packageName.securitybody.SecurityBody",
            "$packageName.securitybody.DataCollector",
            "$packageName.securitybody.EncryptManager",
            "$packageName.securitybody.SignatureManager",
            "$packageName.lbswua.LbswuaManager",
            "$packageName.ddsec.DdsecManager",
            "$packageName.encrypt.EncryptManager",
            "$packageName.util.DeviceUtils",
            "$packageName.util.SecurityUtils",
            "$packageName.security.SecurityGuard",
            "$packageName.security.SecurityBody"
        )

        for (className in knownClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                if (hasNativeMethods(clazz)) {
                    result.add(clazz)
                }
            } catch (_: ClassNotFoundException) {
                continue
            }
        }

        return result
    }

    private fun hasNativeMethods(clazz: Class<*>): Boolean {
        return try {
            clazz.declaredMethods.any { Modifier.isNative(it.modifiers) }
        } catch (_: Exception) {
            false
        }
    }

    private fun getNativeMethods(clazz: Class<*>): List<Method> {
        return try {
            clazz.declaredMethods.filter { Modifier.isNative(it.modifiers) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Hook 单个 native 方法
     *
     * 策略：
     * - 数据收集方法 -> beforeHookedMethod 返回空默认值，阻止 native 代码执行
     * - 加密/签名方法 -> afterHookedMethod 篡改返回结果
     */
    private fun hookNativeMethod(clazz: Class<*>, method: Method): Boolean {
        return try {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val methodName = method.name.lowercase()

                    // 数据收集方法：返回空默认值，阻止 native 代码执行
                    if (methodName.contains("collect") || methodName.contains("gather") ||
                        methodName.contains("getdata") || methodName.contains("getsecurity")
                    ) {
                        param.result = getDefaultValue(method.returnType)
                        HookUtils.logDebug(
                            "$TAG: 拦截 native 数据收集: ${clazz.simpleName}.${method.name}"
                        )
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    tamperNativeResult(param)
                }
            }

            XposedHelpers.findAndHookMethod(
                clazz, method.name,
                *method.parameterTypes,
                hook
            )
            HookUtils.logDebug("$TAG: Hook native 方法: ${clazz.simpleName}.${method.name}")
            true
        } catch (_: NoSuchMethodError) {
            false
        } catch (e: Exception) {
            HookUtils.logDebug(
                "$TAG: Hook native 方法失败 ${clazz.simpleName}.${method.name}: ${e.message}"
            )
            false
        }
    }

    // ==================== Native 库加载监控 ====================

    /**
     * 监控 ClassLoader 类加载事件
     * 当安全 SDK 的类被加载时记录日志，用于调试
     *
     * M7 修复：添加快速排除检查，类名不包含 "alibaba"/"taobao"/"alipay" 则直接跳过
     * 避免对所有类加载事件产生不必要的性能开销
     */
    private fun hookNativeLibraryLoadEvents(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java, "loadClass",
                String::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        // 快速排除：类名不包含关键词则直接跳过
                        if (!QUICK_FILTER_KEYWORDS.any { className.contains(it, ignoreCase = true) }) return
                        if (isSecuritySdkClass(className)) {
                            HookUtils.logDebug("$TAG: 检测到安全 SDK 类加载: $className")
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: ClassLoader.loadClass 监控已启动")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: ClassLoader 监控失败: ${e.message}")
        }
    }

    // ==================== JNI 入口方法拦截 ====================

    /**
     * Hook 特定的 JNI 入口方法
     * 这些是已知的 native 方法，直接拦截并返回空数据
     */
    private fun hookSecuritySdkNativeMethods(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        val jniEntryPoints = listOf(
            "com.alibaba.wireless.security.securitybody.SecurityBody" to
                    listOf("nativeInit", "nativeCollect", "nativeReport"),
            "com.alibaba.security.lbswua.LbswuaManager" to
                    listOf("nativeInit", "nativeCollect", "nativeReport"),
            "com.alibaba.security.ddsec.DdsecManager" to
                    listOf("nativeInit", "nativeCollect", "nativeReport"),
            "com.alibaba.wireless.security.securitybody.EncryptManager" to
                    listOf("nativeEncrypt", "nativeSign", "nativeVerify"),
            "com.alibaba.wireless.security.securitybody.DataCollector" to
                    listOf("nativeCollect", "nativeGather", "nativeReport")
        )

        var hookedCount = 0

        for ((className, methods) in jniEntryPoints) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                for (methodName in methods) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            clazz, methodName,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    HookUtils.logDebug(
                                        "$TAG: 拦截 JNI 入口: $className.$methodName"
                                    )
                                    param.result = getDefaultValue(
                                        (param.method as Method).returnType
                                    )
                                }
                            }
                        )
                        hookedCount++
                    } catch (_: NoSuchMethodError) {
                        continue
                    }
                }
            } catch (_: ClassNotFoundException) {
                continue
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: Hook JNI 入口失败 $className: ${e.message}")
            }
        }

        HookUtils.log("$TAG: JNI 入口 Hook 完成，共 $hookedCount 个")
    }

    // ==================== 结果篡改 ====================

    /**
     * 篡改 native 方法返回结果
     */
    private fun tamperNativeResult(param: XC_MethodHook.MethodHookParam) {
        val result = param.result ?: return

        when (result) {
            is String -> {
                if (containsSecurityData(result)) {
                    param.result = tamperSecurityString(result)
                    HookUtils.logDebug("$TAG: 篡改 native String 返回值")
                }
            }
            is ByteArray -> {
                try {
                    val str = String(result)
                    if (containsSecurityData(str)) {
                        param.result = tamperSecurityString(str).toByteArray()
                        HookUtils.logDebug("$TAG: 篡改 native byte[] 返回值")
                    }
                } catch (_: Exception) {}
            }
            is Map<*, *> -> {
                if (result is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    tamperMapValues(result as MutableMap<Any, Any>)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tamperMapValues(map: MutableMap<Any, Any>) {
        val securityKeys = setOf(
            "root", "xposed", "mocklocation", "emulator",
            "hook", "debug", "risk", "vpn", "magisk"
        )

        for (entry in map.entries) {
            val key = (entry.key as? String)?.lowercase() ?: continue
            if (securityKeys.any { key.contains(it) }) {
                when (val value = entry.value) {
                    is Boolean -> entry.setValue(false)
                    is Int -> entry.setValue(0)
                    is String -> {
                        if (value.equals("true", ignoreCase = true)) {
                            entry.setValue("false")
                        }
                    }
                }
            }
        }
    }

    // ==================== 工具方法 ====================

    private fun containsSecurityData(data: String): Boolean {
        val lower = data.lowercase()
        return lower.contains("\"root\"") ||
                lower.contains("\"xposed\"") ||
                lower.contains("\"mocklocation\"") ||
                lower.contains("\"emulator\"") ||
                lower.contains("\"hook\"") ||
                lower.contains("\"debug\"") ||
                lower.contains("\"risk\"")
    }

    private fun tamperSecurityString(data: String): String {
        var result = data
        result = result.replace("\"root\":true", "\"root\":false")
        result = result.replace("\"xposed\":true", "\"xposed\":false")
        result = result.replace("\"mockLocation\":true", "\"mockLocation\":false")
        result = result.replace("\"emulator\":true", "\"emulator\":false")
        result = result.replace("\"hook\":true", "\"hook\":false")
        result = result.replace("\"debug\":true", "\"debug\":false")
        return result
    }

    private fun getDefaultValue(type: Class<*>): Any? {
        return when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Short::class.javaPrimitiveType -> 0.toShort()
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> '\u0000'
            String::class.java -> ""
            ByteArray::class.java -> ByteArray(0)
            else -> null
        }
    }

    private fun isSecuritySdkClass(className: String): Boolean {
        return SECURITY_SDK_PACKAGES.any { className.startsWith(it) }
    }
}
