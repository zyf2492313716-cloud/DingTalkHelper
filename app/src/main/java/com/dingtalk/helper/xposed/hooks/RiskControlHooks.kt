package com.dingtalk.helper.xposed.hooks

import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.ClassFinder
import com.dingtalk.helper.xposed.utils.ClassPattern
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import com.dingtalk.helper.xposed.utils.MethodSignature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.LinkedHashMap
import java.util.TreeMap
import kotlin.math.roundToInt
import kotlin.random.Random

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

        private val SENSITIVE_PATHS = listOf(
            "/proc/self/maps", "/proc/self/status", "/proc/self/cmdline",
            "/proc/self/fd", "/proc/self/exe", "/proc/maps",
            "/proc/cpuinfo", "/proc/version", "/proc/net/tcp",
            "/system/build.prop", "/sys/class/net", "/sys/devices"
        )

        fun generateDeterministicMac(): String {
            val seed = EmulatorHooks.getDeviceModel().hashCode().toLong() xor 0x4D414330L
            val rng = java.util.Random(seed)
            return String.format(
                "%02X:%02X:%02X:%02X:%02X:%02X",
                (rng.nextInt(254) + 1) and 0xFE,
                rng.nextInt(256), rng.nextInt(256),
                rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)
            )
        }

        private fun sampleRiskScore(): Int {
            val mean = 3.0
            val stddev = 2.0
            var score = java.util.concurrent.ThreadLocalRandom.current().nextGaussian() * stddev + mean
            score = score.coerceIn(0.0, 10.0)
            return score.roundToInt()
        }

        private fun sampleRiskLevel(): String {
            val roll = Random.nextDouble()
            return when {
                roll < 0.80 -> "low"
                roll < 0.95 -> "normal"
                else -> "medium"
            }
        }
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
            XposedHelpers.findAndHookMethod(
                System::class.java, "load",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = (param.args[0] as? String)?.lowercase() ?: return
                        if (BLOCKED_NATIVE_LIBS.any { path.contains(it) }) {
                            HookUtils.log("$TAG: 阻止 System.load 加载风控库: ${param.args[0]}")
                            param.args[0] = "/system/lib/nonexistent_blocked_${System.currentTimeMillis()}.so"
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: System.load Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: System.load Hook 失败: ${e.message}")
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
                            HookUtils.log("$TAG: 阻止 Runtime.loadLibrary 加载风控库: ${param.args[1]}")
                            param.args[1] = "nonexistent_blocked_lib_${System.currentTimeMillis()}"
                        }
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Runtime.loadLibrary Hook 失败: ${e.message}")
        }

        try {
            val runtimeClass = Runtime::class.java
            XposedHelpers.findAndHookMethod(
                runtimeClass, "load",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = (param.args[0] as? String)?.lowercase() ?: return
                        if (BLOCKED_NATIVE_LIBS.any { path.contains(it) }) {
                            HookUtils.log("$TAG: 阻止 Runtime.load 加载风控库: ${param.args[0]}")
                            param.args[0] = "/system/lib/nonexistent_blocked_${System.currentTimeMillis()}.so"
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: Runtime.load Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Runtime.load Hook 失败: ${e.message}")
        }

        hookRuntimeExec(lpparam)
    }

    private fun hookRuntimeExec(lpparam: XC_LoadPackage.LoadPackageParam) {
        val runtimeClass = Runtime::class.java

        // Hook Runtime.exec(String)
        try {
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmd = (param.args[0] as? String) ?: return
                        if (isSensitiveExec(cmd)) {
                            HookUtils.log("$TAG: 阻止敏感 exec: ${cmd.take(120)}")
                            param.args[0] = "echo blocked"
                        }
                    }
                }
            )
        } catch (_: Exception) {}

        // Hook Runtime.exec(String[])
        try {
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec",
                Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val cmdArray = param.args[0] as? Array<String> ?: return
                        val cmdStr = cmdArray.joinToString(" ")
                        if (isSensitiveExec(cmdStr)) {
                            HookUtils.log("$TAG: 阻止敏感 exec 数组: ${cmdStr.take(120)}")
                            param.args[0] = arrayOf("echo", "blocked")
                        }
                    }
                }
            )
        } catch (_: Exception) {}

        // Hook Runtime.exec(String, String[])
        try {
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec",
                String::class.java, Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmd = (param.args[0] as? String) ?: return
                        if (isSensitiveExec(cmd)) {
                            HookUtils.log("$TAG: 阻止敏感 exec(env): ${cmd.take(120)}")
                            param.args[0] = "echo blocked"
                        }
                    }
                }
            )
        } catch (_: Exception) {}

        // Hook ProcessBuilder 构造 + start
        try {
            val pbClass = ProcessBuilder::class.java
            XposedHelpers.findAndHookMethod(
                pbClass, "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val cmdList = XposedHelpers.getObjectField(param.thisObject, "command") as? List<*>
                            val cmdStr = cmdList?.joinToString(" ") ?: return
                            if (isSensitiveExec(cmdStr)) {
                                HookUtils.log("$TAG: 阻止敏感 ProcessBuilder: ${cmdStr.take(120)}")
                                XposedHelpers.setObjectField(param.thisObject, "command", listOf("echo", "blocked"))
                            }
                        } catch (_: Exception) {}
                    }
                }
            )
        } catch (_: Exception) {}

        HookUtils.logDebug("$TAG: Runtime.exec/ProcessBuilder Hook 完成")
    }

    private fun isSensitiveExec(cmd: String): Boolean {
        val lower = cmd.lowercase()
        return SENSITIVE_PATHS.any { lower.contains(it.lowercase()) } ||
                lower.contains("cat /proc") ||
                lower.contains("getprop") ||
                lower.contains("which su") ||
                lower.contains("busybox") ||
                lower.contains("magisk") ||
                lower.contains("xposed") ||
                lower.contains("frida") ||
                lower.contains("pm list packages") ||
                lower.contains("dumpsys") ||
                lower.contains("ps -") ||
                lower.contains("top -")
    }

    // ==================== 数据收集阶段拦截 ====================

    private fun hookDataCollection(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val collectMethods = listOf(
            "collect", "collectData", "collectAll", "gather", "gatherData",
            "buildData", "buildMap", "buildParams", "prepareData",
            "getSecurityData", "getSecurityInfo", "getDeviceInfo",
            "report", "reportData", "upload", "uploadData",
            "generateParams", "generateData"
        )

        // 动态查找数据收集类
        val classes = ClassFinder.findClassesByPatterns(classLoader, Constants.COLLECTOR_CLASS_PATTERNS)

        if (classes.isNotEmpty()) {
            HookUtils.log("$TAG: 动态找到 ${classes.size} 个数据收集类")
            for (clazz in classes) {
                for (method in collectMethods) {
                    hookMethodWithFallback(clazz, method) { param ->
                        tamperMethodResult(param)
                        HookUtils.logDebug("$TAG: 拦截数据收集 ${clazz.name}.$method")
                    }
                }
            }
        } else {
            HookUtils.log("$TAG: 动态查找未找到数据收集类，回退到硬编码列表")
            @Suppress("DEPRECATION")
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
            for (className in collectorClasses) {
                try {
                    val clazz = XposedHelpers.findClass(className, classLoader)
                    for (method in collectMethods) {
                        hookMethodWithFallback(clazz, method) { param ->
                            tamperMethodResult(param)
                            HookUtils.logDebug("$TAG: 拦截数据收集 $className.$method")
                        }
                    }
                } catch (_: ClassNotFoundException) {
                    continue
                } catch (e: Exception) {
                    HookUtils.logDebug("$TAG: 数据收集类 Hook 失败 $className: ${e.message}")
                }
            }
        }
    }

    // ==================== 设备信息收集拦截 ====================

    private fun hookDeviceInfoCollectors(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        hookDeviceIdentifierMethods(classLoader)
        hookEnvironmentCollection(classLoader)
        hookLocationCollection(classLoader)
        hookContextGetMethods(classLoader)
    }

    private fun hookDeviceIdentifierMethods(classLoader: ClassLoader) {
        // 动态查找设备标识收集类
        val classes = ClassFinder.findClassesByPatterns(classLoader, Constants.DEVICE_ID_CLASS_PATTERNS)

        if (classes.isNotEmpty()) {
            HookUtils.log("$TAG: 动态找到 ${classes.size} 个设备标识类")
            for (clazz in classes) {
                for (method in listOf("getDeviceId", "getIMEI", "getIMSI", "getAndroidId",
                    "getSerialNumber", "getMacAddress", "getWifiMac", "getBluetoothMac",
                    "getAdvertisingId", "getOAID", "getUA")) {
                    hookMethodReturnString(clazz, method, getFakeValueForMethod(method))
                }
            }
        } else {
            HookUtils.log("$TAG: 动态查找未找到设备标识类，回退到硬编码列表")
            @Suppress("DEPRECATION")
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
        }

        try {
            val telephonyClass = XposedHelpers.findClass(
                "android.telephony.TelephonyManager", classLoader
            )
            for (method in listOf("getDeviceId", "getImei", "getSubscriberId", "getSimSerialNumber")) {
                hookMethodReturnString(telephonyClass, method, EmulatorHooks.getRandomIMEI())
            }
        } catch (_: Exception) {}

        try {
            val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
            XposedHelpers.findAndHookMethod(
                wifiInfoClass, "getMacAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = generateDeterministicMac().lowercase()
                    }
                }
            )
        } catch (_: Exception) {}
    }

    private fun hookEnvironmentCollection(classLoader: ClassLoader) {
        // 动态查找环境检测类
        val classes = ClassFinder.findClassesByPatterns(classLoader, Constants.ENVIRONMENT_CLASS_PATTERNS)

        if (classes.isNotEmpty()) {
            HookUtils.log("$TAG: 动态找到 ${classes.size} 个环境检测类")
            for (clazz in classes) {
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
            }
        } else {
            HookUtils.log("$TAG: 动态查找未找到环境检测类，回退到硬编码列表")
            @Suppress("DEPRECATION")
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
    }

    private fun hookLocationCollection(classLoader: ClassLoader) {
        // 动态查找位置收集类
        val classes = ClassFinder.findClassesByPatterns(classLoader, Constants.LOCATION_CLASS_PATTERNS)

        if (classes.isNotEmpty()) {
            HookUtils.log("$TAG: 动态找到 ${classes.size} 个位置收集类")
            for (clazz in classes) {
                for (method in listOf(
                    "collectLocation", "getLocation", "getLastLocation",
                    "requestLocation", "getGpsLocation", "getNetworkLocation",
                    "getCellInfo", "getWifiInfo", "getScanResults"
                )) {
                    hookMethodWithFallback(clazz, method) { param ->
                        tamperMethodResult(param)
                    }
                }
            }
        } else {
            HookUtils.log("$TAG: 动态查找未找到位置收集类，回退到硬编码列表")
            @Suppress("DEPRECATION")
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
    }

    private fun hookContextGetMethods(classLoader: ClassLoader) {
        // No-op: removed useless getSystemService hook
    }

    // ==================== 风控类拦截（加密前数据篡改） ====================

    private fun hookSecurityClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 动态查找所有风控类
        val lbswuaClasses = ClassFinder.findClassesByPatterns(classLoader, Constants.LBSWUA_CLASS_PATTERNS)
        val ddsecClasses = ClassFinder.findClassesByPatterns(classLoader, Constants.DDSEC_CLASS_PATTERNS)
        val riskClasses = ClassFinder.findClassesByPatterns(classLoader, Constants.RISK_CONTROL_CLASS_PATTERNS)

        var hookedCount = 0

        // Hook lbswua 类
        for (clazz in lbswuaClasses) {
            try {
                hookSecurityClassByMethods(clazz, Constants.LBSWUA_REPORT_METHODS, "lbswua")
                hookedCount++
                HookUtils.log("$TAG: 动态 Hook lbswua 类: ${clazz.name}")
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: Hook lbswua 类失败 ${clazz.name}: ${e.message}")
            }
        }

        // Hook ddsec 类
        for (clazz in ddsecClasses) {
            try {
                hookSecurityClassByMethods(clazz, Constants.DDSEC_GENERATE_METHODS, "ddsec")
                hookedCount++
                HookUtils.log("$TAG: 动态 Hook ddsec 类: ${clazz.name}")
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: Hook ddsec 类失败 ${clazz.name}: ${e.message}")
            }
        }

        // Hook 风控检测类
        for (clazz in riskClasses) {
            try {
                hookSecurityClassByMethods(clazz, Constants.RISK_CHECK_METHODS, "riskcontrol")
                hookedCount++
                HookUtils.log("$TAG: 动态 Hook 风控检测类: ${clazz.name}")
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: Hook 风控检测类失败 ${clazz.name}: ${e.message}")
            }
        }

        // 如果动态查找未找到任何类，回退到硬编码列表
        if (hookedCount == 0) {
            HookUtils.log("$TAG: 动态查找未找到风控类，回退到硬编码列表")
            @Suppress("DEPRECATION")
            val allClassNames = Constants.LBSWUA_CLASS_NAMES +
                    Constants.DDSEC_CLASS_NAMES +
                    Constants.RISK_CONTROL_CLASS_NAMES

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
        }

        HookUtils.log("$TAG: 风控类 Hook 完成，共 $hookedCount 个类")
    }

    /**
     * 按方法名列表 Hook 安全类（用于动态查找后的 Hook）
     */
    private fun hookSecurityClassByMethods(clazz: Class<*>, methodNames: List<String>, category: String) {
        for (methodName in methodNames) {
            hookMethodWithFallback(clazz, methodName) { param ->
                tamperMethodResult(param)
                HookUtils.logDebug("$TAG: 篡改 [$category] ${clazz.name}.$methodName")
            }
        }

        hookMapParameterMethods(clazz, clazz.name)
        hookAllDeclaredMethods(clazz, clazz.name)
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

        // 动态查找加密类
        val encryptClasses = ClassFinder.findClassesByPatterns(classLoader, Constants.ENCRYPT_CLASS_PATTERNS)

        if (encryptClasses.isNotEmpty()) {
            HookUtils.log("$TAG: 动态找到 ${encryptClasses.size} 个加密类")
            for (clazz in encryptClasses) {
                for (methodName in Constants.ENCRYPT_METHODS) {
                    // Hook String 参数的加密方法
                    try {
                        XposedHelpers.findAndHookMethod(
                            clazz, methodName, String::class.java,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val input = param.args[0] as? String ?: return
                                    if (isSecurityData(input)) {
                                        param.args[0] = tamperSecurityData(input)
                                        HookUtils.logDebug("$TAG: 篡改加密输入: ${clazz.name}.$methodName")
                                    }
                                }
                            }
                        )
                    } catch (_: NoSuchMethodError) {}

                    // Hook byte[] 参数的加密方法
                    try {
                        XposedHelpers.findAndHookMethod(
                            clazz, methodName, ByteArray::class.java,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val input = param.args[0] as? ByteArray ?: return
                                    val str = try { String(input) } catch (_: Exception) { return }
                                    if (isSecurityData(str)) {
                                        param.args[0] = tamperSecurityData(str).toByteArray()
                                        HookUtils.logDebug("$TAG: 篡改加密 byte[] 输入: ${clazz.name}.$methodName")
                                    }
                                }
                            }
                        )
                    } catch (_: NoSuchMethodError) {}
                }
            }
        } else {
            HookUtils.log("$TAG: 动态查找未找到加密类，回退到硬编码列表")
            @Suppress("DEPRECATION")
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
                                    }
                                }
                            )
                        } catch (_: NoSuchMethodError) {}

                        try {
                            XposedHelpers.findAndHookMethod(
                                clazz, methodName, ByteArray::class.java,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        val input = param.args[0] as? ByteArray ?: return
                                        val str = try { String(input) } catch (_: Exception) { return }
                                        if (isSecurityData(str)) {
                                            param.args[0] = tamperSecurityData(str).toByteArray()
                                            HookUtils.logDebug("$TAG: 篡改加密 byte[] 输入: $className.$methodName")
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

        // 注意：已移除全局 Cipher/MessageDigest/Mac Hook
        // 原因：
        // 1. 全局 Hook 影响 HTTPS/TLS 等正常加密操作，导致性能严重下降
        // 2. lbswua/ddsec 的数据流在 native (.so) 层完成，Java 层全局 Hook 无法拦截
        // 3. 替代方案：精确拦截阿里安全 SDK 特定类（上方代码）+ NativeHook JNI 层拦截
    }

    // ==================== 签名验证拦截 ====================

    private fun hookSignatureVerification(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 动态查找签名验证类
        val classes = ClassFinder.findClassesByPatterns(classLoader, Constants.SIGNATURE_CLASS_PATTERNS)

        if (classes.isNotEmpty()) {
            HookUtils.log("$TAG: 动态找到 ${classes.size} 个签名验证类")
            for (clazz in classes) {
                for (method in listOf(
                    "verify", "verifySign", "verifySignature", "checkSign",
                    "checkIntegrity", "validate", "validateSign"
                )) {
                    hookMethodReturnBool(clazz, method, true)
                }
            }
        } else {
            HookUtils.log("$TAG: 动态查找未找到签名验证类，回退到硬编码列表")
            @Suppress("DEPRECATION")
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
        }
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
                val mutableMap = when (result) {
                    is LinkedHashMap<*, *> -> LinkedHashMap<Any?, Any?>(result.size)
                    is java.util.SortedMap<*, *> -> TreeMap<Any?, Any?>((result as java.util.SortedMap<Any?, Any?>).comparator())
                    else -> HashMap<Any?, Any?>(result.size)
                }
                result.forEach { (k, v) -> mutableMap[k] = v }
                tamperMapRecursive(mutableMap as MutableMap<Any, Any>)
                param.result = mutableMap
            }
            is List<*> -> {
                val mutableList = ArrayList(result)
                tamperListRecursive(mutableList as MutableList<Any>)
                param.result = mutableList
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
        result = result.replace(Regex("\"riskScore\":[0-9]+")) { "\"riskScore\":${sampleRiskScore()}" }
        result = result.replace(Regex("\"riskLevel\":\"?[a-zA-Z]+\"?")) { "\"riskLevel\":\"${sampleRiskLevel()}\"" }
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
                when {
                    key == "riskscore" -> entry.setValue(sampleRiskScore())
                    key == "risklevel" -> entry.setValue(sampleRiskLevel())
                    value is Boolean -> entry.setValue(false)
                    value is Int -> entry.setValue(0)
                    value is Long -> entry.setValue(0L)
                    value is Float -> entry.setValue(0f)
                    value is Double -> entry.setValue(0.0)
                    value is String -> {
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
                        val src = value as Map<*, *>
                        val mutableValue = when (src) {
                            is LinkedHashMap<*, *> -> LinkedHashMap<Any?, Any?>(src.size)
                            is java.util.SortedMap<*, *> -> TreeMap<Any?, Any?>((src as java.util.SortedMap<Any?, Any?>).comparator())
                            else -> HashMap<Any?, Any?>(src.size)
                        }
                        src.forEach { (k, v) -> mutableValue[k] = v }
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
                        val src = item as Map<*, *>
                        val mutableItem = when (src) {
                            is LinkedHashMap<*, *> -> LinkedHashMap<Any?, Any?>(src.size)
                            is java.util.SortedMap<*, *> -> TreeMap<Any?, Any?>((src as java.util.SortedMap<Any?, Any?>).comparator())
                            else -> HashMap<Any?, Any?>(src.size)
                        }
                        src.forEach { (k, v) -> mutableItem[k] = v }
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
            "getdeviceid", "deviceid" -> EmulatorHooks.getRandomIMEI()
            "getimei", "imei" -> EmulatorHooks.getRandomIMEI()
            "getimsi", "imsi" -> EmulatorHooks.getRandomIMSI()
            "getandroidid", "androidid" -> EmulatorHooks.getRandomAndroidID()
            "getserialnumber", "serial" -> EmulatorHooks.getRandomSerial()
            "getmacaddress", "mac", "macaddress", "wifimac" -> generateDeterministicMac().lowercase()
            "getbluetoothmac", "bluetoothmac" -> generateDeterministicMac().lowercase()
            "getadvertisingid", "advertisingid" -> "00000000-0000-0000-0000-000000000000"
            "getoaid", "oaid" -> "00000000-0000-0000-0000-000000000000"
            "getua", "ua" -> "Mozilla/5.0 (Linux; Android ${EmulatorHooks.getDeviceRelease()}; ${EmulatorHooks.getDeviceModel()}) AppleWebKit/537.36"
            else -> ""
        }
    }

    private fun getFakeBuildProp(key: String): String {
        return when (key.lowercase()) {
            "model" -> EmulatorHooks.getDeviceModel()
            "device" -> EmulatorHooks.getDeviceDevice()
            "brand" -> EmulatorHooks.getDeviceBrand()
            "manufacturer" -> EmulatorHooks.getDeviceManufacturer()
            "fingerprint" -> EmulatorHooks.getDeviceFingerprint()
            "build" -> EmulatorHooks.getDeviceDisplay()
            "hardware" -> EmulatorHooks.getDeviceHardware()
            "board" -> EmulatorHooks.getDeviceBoard()
            else -> ""
        }
    }
}
