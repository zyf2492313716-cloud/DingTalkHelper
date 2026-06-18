package com.dingtalk.helper.xposed.hooks

import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

class RiskControlHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:RiskControl"

        private val BLOCKED_NATIVE_LIBS = setOf(
            "sgmain",
            "sgsecuritybody",
            "sgavso",
            "sgnocaptcha",
            "ddsec",
            "lbswua",
            "securitybody",
            "sgmiddletier",
            "securityguard",
            "avmp"
        )

        private val RISK_CONTROL_KEYS = setOf(
            "root", "hasRoot", "isRoot", "su",
            "xposed", "hasXposed", "isXposed", "xposedDetected",
            "xposedInstaller", "lposed", "edxposed",
            "mockLocation", "isMock", "mockGps", "fakeGps",
            "developerMode", "usbDebug", "adbEnabled",
            "vpn", "isVpn", "proxyDetected",
            "emulator", "isEmulator", "simulator",
            "hook", "isHook", "fridaDetected", "frida",
            "magisk", "magiskHide", "zygisk",
            "multOpen", "dualApp", "parallelSpace", "cloneApp",
            "debug", "isDebug", "debuggable",
            "tampered", "isTampered", "integrityCheck",
            "memoryHook", "codeInjection", "ptrace",
            "riskScore", "riskLevel", "deviceRisk", "envRisk"
        )
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isHideRiskControlEnabled()) {
            HookUtils.logDebug("$TAG: 风控隐藏未启用，跳过")
            return
        }

        HookUtils.log("$TAG: 开始注入风控数据拦截 Hook")

        hookNativeLibLoading(lpparam)
        hookDataCollection(lpparam)
        hookDeviceInfoCollectors(lpparam)
        hookSecurityClasses(lpparam)
        hookEncryptFunctions(lpparam)
        hookSignatureVerification(lpparam)
    }

    // ==================== Native 库加载拦截 ====================

    private fun hookNativeLibLoading(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                System::class.java, "loadLibrary",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val libName = (param.args[0] as? String)?.lowercase() ?: return
                        if (BLOCKED_NATIVE_LIBS.any { libName.contains(it) }) {
                            HookUtils.log("$TAG: 阻止加载风控 native 库: ${param.args[0]}")
                            param.args[0] = "nonexistent_blocked_lib_${System.currentTimeMillis()}"
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: System.loadLibrary Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: System.loadLibrary Hook 失败: ${e.message}")
        }

        try {
            val runtimeClass = Runtime::class.java
            XposedHelpers.findAndHookMethod(
                runtimeClass, "loadLibrary",
                ClassLoader::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val libName = (param.args[1] as? String)?.lowercase() ?: return
                        if (BLOCKED_NATIVE_LIBS.any { libName.contains(it) }) {
                            HookUtils.log("$TAG: 阻止 Runtime 加载风控 native 库: ${param.args[1]}")
                            param.args[1] = "nonexistent_blocked_lib_${System.currentTimeMillis()}"
                        }
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Runtime.loadLibrary Hook 失败: ${e.message}")
        }
    }

    // ==================== 数据收集阶段拦截 ====================

    private fun hookDataCollection(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val collectorClasses = listOf(
            "com.alibaba.wireless.security.securitybody.DataCollector",
            "com.alibaba.wireless.security.securitybody.DataCollection",
            "com.alibaba.wireless.security.securitybody.SecurityBodyData",
            "com.alibaba.security.ddsec.DataCollector",
            "com.alibaba.security.lbswua.DataCollector",
            "com.alibaba.security.SecurityDataCollector",
            "com.alibaba.wireless.security.securitybody.EnvironmentInfo",
            "com.alibaba.wireless.security.securitybody.DeviceInfo"
        )

        val collectMethods = listOf(
            "collect", "collectData", "collectAll", "gather", "gatherData",
            "buildData", "buildMap", "buildParams", "prepareData",
            "getSecurityData", "getSecurityInfo", "getDeviceInfo",
            "report", "reportData", "upload", "uploadData",
            "generateParams", "generateData"
        )

        for (className in collectorClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                for (method in collectMethods) {
                    hookMethodWithFallback(clazz, method) { param ->
                        tamperMethodResult(param)
                        HookUtils.logDebug("$TAG: 拦截数据收集 $className.$method")
                    }
                }
                HookUtils.logDebug("$TAG: 数据收集类 Hook 完成: $className")
            } catch (_: ClassNotFoundException) {
                continue
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: 数据收集类 Hook 失败 $className: ${e.message}")
            }
        }
    }

    // ==================== 设备信息收集拦截 ====================

    private fun hookDeviceInfoCollectors(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        hookDeviceIdentifierMethods(classLoader)
        hookBuildProperties(classLoader)
        hookEnvironmentCollection(classLoader)
        hookLocationCollection(classLoader)
        hookContextGetMethods(classLoader)
    }

    private fun hookDeviceIdentifierMethods(classLoader: ClassLoader) {
        val deviceIdClasses = listOf(
            "com.alibaba.wireless.security.securitybody.DeviceIdManager",
            "com.alibaba.security.DeviceIdentifier",
            "com.alibaba.wireless.security.securitybody.DeviceInfo"
        )

        for (className in deviceIdClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                for (method in listOf("getDeviceId", "getIMEI", "getIMSI", "getAndroidId",
                    "getSerialNumber", "getMacAddress", "getWifiMac", "getBluetoothMac",
                    "getAdvertisingId", "getOAID", "getUA")) {
                    hookMethodReturnString(clazz, method, getFakeValueForMethod(method))
                }
            } catch (_: ClassNotFoundException) {
                continue
            }
        }

        try {
            val telephonyClass = XposedHelpers.findClass(
                "android.telephony.TelephonyManager", classLoader
            )
            for (method in listOf("getDeviceId", "getImei", "getSubscriberId", "getSimSerialNumber")) {
                hookMethodReturnString(telephonyClass, method, "862123456789012")
            }
        } catch (_: Exception) {}

        try {
            val secureClass = XposedHelpers.findClass("android.provider.Settings\$Secure", classLoader)
            XposedHelpers.findAndHookMethod(
                secureClass, "getString",
                android.content.ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        when (key) {
                            "android_id" -> param.result = "a1b2c3d4e5f67890"
                            "bluetooth_address" -> param.result = "02:00:00:00:00:00"
                        }
                    }
                }
            )
        } catch (_: Exception) {}

        try {
            val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
            XposedHelpers.findAndHookMethod(
                wifiInfoClass, "getMacAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = "02:00:00:00:00:00"
                    }
                }
            )
        } catch (_: Exception) {}
    }

    private fun hookBuildProperties(classLoader: ClassLoader) {
        try {
            val buildClass = XposedHelpers.findClass("android.os.Build", classLoader)

            val stringFields = mapOf(
                "FINGERPRINT" to "google/raven/raven:13/TP1A.220624.014/8819057:user/release-keys",
                "DISPLAY" to "TP1A.220624.014",
                "HOST" to "abfarm-02",
                "TAGS" to "release-keys",
                "TYPE" to "user",
                "USER" to "android-build",
                "MODEL" to "Pixel 6",
                "MANUFACTURER" to "Google",
                "BRAND" to "google",
                "PRODUCT" to "raven",
                "DEVICE" to "raven",
                "BOARD" to "raven",
                "HARDWARE" to "raven"
            )

            for ((field, value) in stringFields) {
                try {
                    XposedHelpers.setStaticObjectField(buildClass, field, value)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        try {
            val buildVersionClass = XposedHelpers.findClass("android.os.Build\$VERSION", classLoader)
            XposedHelpers.setStaticObjectField(buildVersionClass, "RELEASE", "13")
            XposedHelpers.setStaticObjectField(buildVersionClass, "INCREMENTAL", "8819057")
        } catch (_: Exception) {}
    }

    private fun hookEnvironmentCollection(classLoader: ClassLoader) {
        val envClasses = listOf(
            "com.alibaba.wireless.security.securitybody.EnvironmentCollector",
            "com.alibaba.security.EnvironmentDetector",
            "com.alibaba.wireless.security.securitybody.EnvInfo"
        )

        for (className in envClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                for (method in listOf(
                    "collectEnvironment", "collectEnvInfo", "getEnvironment",
                    "isRoot", "checkRoot", "detectRoot", "hasRoot",
                    "isXposed", "checkXposed", "detectXposed", "hasXposed",
                    "isMockLocation", "checkMockLocation", "detectMock",
                    "isDebug", "checkDebug", "detectDebug",
                    "isEmulator", "checkEmulator", "detectEmulator",
                    "isVpn", "checkVpn", "detectVpn",
                    "isProxy", "checkProxy", "detectProxy",
                    "isHook", "checkHook", "detectHook",
                    "isTampered", "checkTampered", "checkIntegrity"
                )) {
                    hookMethodWithFallback(clazz, method) { param ->
                        tamperMethodResult(param)
                    }
                }
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
    }

    private fun hookLocationCollection(classLoader: ClassLoader) {
        val locationClasses = listOf(
            "com.alibaba.wireless.security.securitybody.LocationCollector",
            "com.alibaba.security.LocationInfo",
            "com.alibaba.wireless.security.lbswua.LbsCollector"
        )

        for (className in locationClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                for (method in listOf(
                    "collectLocation", "getLocation", "getLastLocation",
                    "requestLocation", "getGpsLocation", "getNetworkLocation",
                    "getCellInfo", "getWifiInfo", "getScanResults"
                )) {
                    hookMethodWithFallback(clazz, method) { param ->
                        tamperMethodResult(param)
                    }
                }
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
    }

    private fun hookContextGetMethods(classLoader: ClassLoader) {
        // No-op: removed useless getSystemService hook
    }

    // ==================== 风控类拦截（加密前数据篡改） ====================

    private fun hookSecurityClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val allClassNames = Constants.LBSWUA_CLASS_NAMES +
                Constants.DDSEC_CLASS_NAMES +
                Constants.RISK_CONTROL_CLASS_NAMES

        var hookedCount = 0

        for (className in allClassNames) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                hookSecurityClass(clazz, className, classLoader)
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

    private fun hookSecurityClass(clazz: Class<*>, className: String, classLoader: ClassLoader) {
        val methodNames = when {
            className.contains("lbswua", true) ||
            className.contains("SecurityGuard", true) -> Constants.LBSWUA_REPORT_METHODS
            className.contains("ddsec", true) ||
            className.contains("SecurityBody", true) -> Constants.DDSEC_GENERATE_METHODS
            className.contains("riskcontrol", true) ||
            className.contains("RiskControl", true) -> Constants.RISK_CHECK_METHODS
            else -> return
        }

        for (methodName in methodNames) {
            hookMethodWithFallback(clazz, methodName) { param ->
                tamperMethodResult(param)
                HookUtils.logDebug("$TAG: 篡改 $className.$methodName")
            }
        }

        hookMapParameterMethods(clazz, className)
        hookAllDeclaredMethods(clazz, className)
    }

    private fun hookMapParameterMethods(clazz: Class<*>, className: String) {
        val methods = try {
            clazz.declaredMethods
        } catch (_: Exception) {
            return
        }

        for (method in methods) {
            if (method.parameterTypes.size == 1 &&
                java.util.Map::class.java.isAssignableFrom(method.parameterTypes[0])
            ) {
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz, method.name,
                        java.util.Map::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                @Suppress("UNCHECKED_CAST")
                                val data = param.args[0] as? MutableMap<Any, Any> ?: return
                                tamperMapRecursive(data)
                                HookUtils.logDebug("$TAG: 篡改 $className.${method.name} Map 参数")
                            }
                        }
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private fun hookAllDeclaredMethods(clazz: Class<*>, className: String) {
        val methods = try {
            clazz.declaredMethods
        } catch (_: Exception) {
            return
        }

        for (method in methods) {
            if (method.parameterTypes.isNotEmpty()) continue

            val returnType = method.returnType
            if (returnType == String::class.java ||
                java.util.Map::class.java.isAssignableFrom(returnType) ||
                returnType == Any::class.java
            ) {
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz, method.name,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                tamperMethodResult(param)
                                HookUtils.logDebug("$TAG: 篡改 $className.${method.name} 返回值")
                            }
                        }
                    )
                } catch (_: NoSuchMethodError) {}
                catch (_: Exception) {}
            }
        }
    }

    // ==================== 加密函数拦截 ====================

    private fun hookEncryptFunctions(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        for (className in listOf(
            "com.alibaba.wireless.security.securitybody.encrypt.EncryptManager",
            "com.alibaba.security.encrypt.EncryptUtils",
            "com.alibaba.wireless.security.securitybody.SignatureManager",
            "com.alibaba.security.SecuritySignature"
        )) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                for (methodName in Constants.ENCRYPT_METHODS) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            clazz, methodName, String::class.java,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val input = param.args[0] as? String ?: return
                                    if (isSecurityData(input)) {
                                        param.args[0] = tamperSecurityData(input)
                                        HookUtils.logDebug("$TAG: 篡改加密输入: $className.$methodName")
                                    }
                                    if (Constants.DEBUG_MODE) {
                                        HookUtils.logDebug("$TAG: 加密函数调用: $className.$methodName, input=${input.take(200)}")
                                    }
                                }
                            }
                        )
                    } catch (_: NoSuchMethodError) {}
                }
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
    }

    // ==================== 签名验证拦截 ====================

    private fun hookSignatureVerification(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val signClasses = listOf(
            "com.alibaba.wireless.security.securitybody.SignatureManager",
            "com.alibaba.security.SecuritySignature",
            "com.alibaba.wireless.security.securitybody.IntegrityChecker",
            "com.alibaba.security.IntegrityCheck"
        )

        for (className in signClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                for (method in listOf(
                    "verify", "verifySign", "verifySignature", "checkSign",
                    "checkIntegrity", "validate", "validateSign"
                )) {
                    hookMethodReturnBool(clazz, method, true)
                }
            } catch (_: ClassNotFoundException) {
                continue
            }
        }

        // No-op: removed useless getPackageInfo hook
    }

    // ==================== 通用辅助方法 ====================

    private fun hookMethodWithFallback(
        clazz: Class<*>,
        methodName: String,
        handler: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz, methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            handler(param)
                        } catch (e: Exception) {
                            HookUtils.logDebug("$TAG: 处理方法结果失败: ${e.message}")
                        }
                    }
                }
            )
        } catch (_: NoSuchMethodError) {
            try {
                XposedHelpers.findAndHookMethod(
                    clazz, methodName,
                    java.util.Map::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val data = param.args[0] as? MutableMap<Any, Any>
                                if (data != null) {
                                    tamperMapRecursive(data)
                                }
                            } catch (e: Exception) {
                                HookUtils.logDebug("$TAG: 处理Map参数失败: ${e.message}")
                            }
                        }
                    }
                )
            } catch (_: NoSuchMethodError) {
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz, methodName,
                        String::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    handler(param)
                                } catch (e: Exception) {
                                    HookUtils.logDebug("$TAG: 处理String方法结果失败: ${e.message}")
                                }
                            }
                        }
                    )
                } catch (_: NoSuchMethodError) {}
            }
        }
    }

    private fun hookMethodReturnString(clazz: Class<*>, methodName: String, fakeValue: String) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz, methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = fakeValue
                        HookUtils.logDebug("$TAG: 替换 ${clazz.simpleName}.$methodName 返回值")
                    }
                }
            )
        } catch (_: NoSuchMethodError) {}
        catch (_: Exception) {}
    }

    private fun hookMethodReturnBool(clazz: Class<*>, methodName: String, fakeValue: Boolean) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz, methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = fakeValue
                        HookUtils.logDebug("$TAG: 替换 ${clazz.simpleName}.$methodName 返回值")
                    }
                }
            )
        } catch (_: NoSuchMethodError) {}
        catch (_: Exception) {}
    }

    // ==================== 数据篡改核心逻辑 ====================

    @Suppress("UNCHECKED_CAST")
    private fun tamperMethodResult(param: XC_MethodHook.MethodHookParam) {
        when (val result = param.result) {
            is String -> {
                if (isSecurityData(result)) {
                    param.result = tamperSecurityData(result)
                }
            }
            is MutableMap<*, *> -> {
                val map = result as MutableMap<Any, Any>
                tamperMapRecursive(map)
            }
            is Map<*, *> -> {
                val mutableMap = HashMap<Any?, Any?>()
                result.forEach { (k, v) -> mutableMap[k] = v }
                tamperMapRecursive(mutableMap as MutableMap<Any, Any>)
                param.result = mutableMap
            }
            is List<*> -> {
                val list = result as MutableList<Any>
                tamperListRecursive(list)
            }
            is Collection<*> -> {
                try {
                    val list = ArrayList<Any?>()
                    result.forEach { list.add(it) }
                    tamperListRecursive(list as MutableList<Any>)
                    param.result = list
                } catch (_: Exception) {}
            }
        }
    }

    private fun isSecurityData(data: String): Boolean {
        if (data.length < 10) return false
        val lower = data.lowercase()
        return lower.contains("\"root\"") ||
                lower.contains("\"xposed\"") ||
                lower.contains("\"mocklocation\"") ||
                lower.contains("\"developer\"") ||
                lower.contains("\"vpn\"") ||
                lower.contains("\"emulator\"") ||
                lower.contains("\"hook\"") ||
                lower.contains("\"debug\"") ||
                lower.contains("\"risk\"") ||
                lower.contains("\"security\"") ||
                lower.contains("\"p\":") ||
                lower.contains("\"deviceid\"") ||
                lower.contains("\"imei\"") ||
                lower.contains("\"androidid\"")
    }

    private fun tamperSecurityData(data: String): String {
        var result = data
        result = result.replace(Regex("\"p\":\"[12]\\|"), "\"p\":\"0|")
        result = result.replace("\"root\":true", "\"root\":false")
        result = result.replace("\"hasRoot\":true", "\"hasRoot\":false")
        result = result.replace("\"isRoot\":true", "\"isRoot\":false")
        result = result.replace("\"su\":true", "\"su\":false")
        result = result.replace("\"xposed\":true", "\"xposed\":false")
        result = result.replace("\"hasXposed\":true", "\"hasXposed\":false")
        result = result.replace("\"isXposed\":true", "\"isXposed\":false")
        result = result.replace("\"xposedDetected\":true", "\"xposedDetected\":false")
        result = result.replace("\"mockLocation\":true", "\"mockLocation\":false")
        result = result.replace("\"isMock\":true", "\"isMock\":false")
        result = result.replace("\"mockGps\":true", "\"mockGps\":false")
        result = result.replace("\"developerMode\":true", "\"developerMode\":false")
        result = result.replace("\"usbDebug\":true", "\"usbDebug\":false")
        result = result.replace("\"adbEnabled\":true", "\"adbEnabled\":false")
        result = result.replace("\"vpn\":true", "\"vpn\":false")
        result = result.replace("\"isVpn\":true", "\"isVpn\":false")
        result = result.replace("\"emulator\":true", "\"emulator\":false")
        result = result.replace("\"isEmulator\":true", "\"isEmulator\":false")
        result = result.replace("\"hook\":true", "\"hook\":false")
        result = result.replace("\"isHook\":true", "\"isHook\":false")
        result = result.replace("\"debug\":true", "\"debug\":false")
        result = result.replace("\"isDebug\":true", "\"isDebug\":false")
        result = result.replace("\"magisk\":true", "\"magisk\":false")
        result = result.replace("\"fridaDetected\":true", "\"fridaDetected\":false")
        result = result.replace("\"frida\":true", "\"frida\":false")
        result = result.replace("\"multOpen\":true", "\"multOpen\":false")
        result = result.replace("\"dualApp\":true", "\"dualApp\":false")
        result = result.replace("\"tampered\":true", "\"tampered\":false")
        result = result.replace(Regex("\"riskScore\":[0-9]+"), "\"riskScore\":0")
        result = result.replace(Regex("\"riskLevel\":[0-9]+"), "\"riskLevel\":0")
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun tamperMapRecursive(data: MutableMap<Any, Any>) {
        val iterator = data.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val key = (entry.key as? String)?.lowercase() ?: continue
            val value = entry.value

            if (RISK_CONTROL_KEYS.contains(key)) {
                when (value) {
                    is Boolean -> entry.setValue(false)
                    is Int -> entry.setValue(0)
                    is Long -> entry.setValue(0L)
                    is Float -> entry.setValue(0f)
                    is Double -> entry.setValue(0.0)
                    is String -> {
                        if (value.equals("true", true)) {
                            entry.setValue("false")
                        } else if (value.equals("1")) {
                            entry.setValue("0")
                        }
                    }
                }
            }

            when (value) {
                is MutableMap<*, *> -> {
                    try {
                        tamperMapRecursive(value as MutableMap<Any, Any>)
                    } catch (_: Exception) {}
                }
                is Map<*, *> -> {
                    try {
                        val mutableValue = HashMap<Any?, Any?>()
                        (value as Map<*, *>).forEach { (k, v) -> mutableValue[k] = v }
                        tamperMapRecursive(mutableValue as MutableMap<Any, Any>)
                        entry.setValue(mutableValue)
                    } catch (_: Exception) {}
                }
                is MutableList<*> -> {
                    try {
                        tamperListRecursive(value as MutableList<Any>)
                    } catch (_: Exception) {}
                }
                is List<*> -> {
                    try {
                        val mutableList = ArrayList<Any?>()
                        (value as List<*>).forEach { mutableList.add(it) }
                        tamperListRecursive(mutableList as MutableList<Any>)
                        entry.setValue(mutableList)
                    } catch (_: Exception) {}
                }
            }

            if (key == "deviceid" || key == "imei" || key == "imsi" || key == "androidid" ||
                key == "serial" || key == "mac" || key == "macaddress" ||
                key == "wifimac" || key == "bluetoothmac") {
                when (value) {
                    is String -> entry.setValue(getFakeValueForMethod(key))
                }
            }

            if (key == "model" || key == "device" || key == "brand" || key == "manufacturer" ||
                key == "fingerprint" || key == "build" || key == "hardware" || key == "board") {
                when (value) {
                    is String -> entry.setValue(getFakeBuildProp(key))
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tamperListRecursive(list: MutableList<Any>) {
        for (i in list.indices) {
            val item = list[i]
            when (item) {
                is MutableMap<*, *> -> {
                    try {
                        tamperMapRecursive(item as MutableMap<Any, Any>)
                    } catch (_: Exception) {}
                }
                is Map<*, *> -> {
                    try {
                        val mutableItem = HashMap<Any?, Any?>()
                        (item as Map<*, *>).forEach { (k, v) -> mutableItem[k] = v }
                        tamperMapRecursive(mutableItem as MutableMap<Any, Any>)
                        list[i] = mutableItem
                    } catch (_: Exception) {}
                }
                is MutableList<*> -> {
                    try {
                        tamperListRecursive(item as MutableList<Any>)
                    } catch (_: Exception) {}
                }
                is List<*> -> {
                    try {
                        val mutableItem = ArrayList<Any?>()
                        (item as List<*>).forEach { mutableItem.add(it) }
                        tamperListRecursive(mutableItem as MutableList<Any>)
                        list[i] = mutableItem
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun getFakeValueForMethod(methodName: String): String {
        return when (methodName.lowercase()) {
            "getdeviceid", "deviceid" -> "862123456789012"
            "getimei", "imei" -> "862123456789012"
            "getimsi", "imsi" -> "460001234567890"
            "getandroidid", "androidid" -> "a1b2c3d4e5f67890"
            "getserialnumber", "serial" -> "R5CR123456"
            "getmacaddress", "mac", "macaddress", "wifimac" -> "02:00:00:00:00:00"
            "getbluetoothmac", "bluetoothmac" -> "02:00:00:00:00:00"
            "getadvertisingid", "advertisingid" -> "00000000-0000-0000-0000-000000000000"
            "getoaid", "oaid" -> "00000000-0000-0000-0000-000000000000"
            "getua", "ua" -> "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36"
            else -> ""
        }
    }

    private fun getFakeBuildProp(key: String): String {
        return when (key.lowercase()) {
            "model" -> "Pixel 6"
            "device" -> "raven"
            "brand" -> "google"
            "manufacturer" -> "Google"
            "fingerprint" -> "google/raven/raven:13/TP1A.220624.014/8819057:user/release-keys"
            "build" -> "TP1A.220624.014"
            "hardware" -> "raven"
            "board" -> "raven"
            else -> ""
        }
    }
}
