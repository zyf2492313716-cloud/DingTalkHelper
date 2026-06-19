package com.dingtalk.helper.xposed.hooks

import android.hardware.SensorManager
import android.os.Build
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * 模拟器隐藏模块
 * 隐藏 Android 模拟器特征，绕过钉钉的模拟器检测
 */
class EmulatorHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}/Emulator"

        // 模拟器传感器特征关键词
        private val EMULATOR_SENSOR_KEYWORDS = setOf(
            "goldfish", "ranchu", "emulator", "qemu", "vbox",
            "genymotion", "nox", "bluestacks", "ldplayer"
        )

        // 模拟器文件特征
        private val EMULATOR_FILES = listOf(
            "/dev/qemu_pipe",
            "/dev/qemud",
            "/dev/socket/qemud",
            "/dev/socket/genyd",
            "/dev/socket/baseband_genyd",
            "/sys/qemu_trace",
            "/system/bin/qemu-props",
            "/dev/avf_guest",
            "/system/etc/init.goldfish.rc",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/system/lib64/libc_malloc_debug_qemu.so"
        )

        // 真实设备配置 (Samsung Galaxy S24 Ultra)
        private const val DEVICE_BRAND = "samsung"
        private const val DEVICE_MODEL = "SM-S928B"
        private const val DEVICE_DEVICE = "e3q"
        private const val DEVICE_PRODUCT = "e3q"
        private const val DEVICE_BOARD = "pineapple"
        private const val DEVICE_HARDWARE = "pineapple"
        private const val DEVICE_MANUFACTURER = "samsung"
        private const val DEVICE_FINGERPRINT =
            "samsung/e3q/e3q:14/UP1A.231005.007/S928BXXU3AXK4:user/release-keys"
        private const val DEVICE_DISPLAY = "UP1A.231005.007"
        private const val DEVICE_BUILD_TYPE = "user"
        private const val DEVICE_BUILD_TAGS = "release-keys"
        private const val DEVICE_SERIAL = "R5CX21ABCDEF"

        // CPU 核心数
        private const val CPU_CORES = 8

        // 内存大小 (bytes) - 12GB
        private const val MEMORY_SIZE = 12884901888L

        // IMEI 格式: 15位随机数
        private fun generateIMEI(): String {
            val sb = StringBuilder("86")
            repeat(13) { sb.append((0..9).random()) }
            return sb.toString()
        }
    }

    // 设备特定的 IMEI（每个实例不同）
    private val deviceIMEI by lazy { generateIMEI() }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.log("$TAG: 开始隐藏模拟器特征")

        try {
            hookBuildProperties()
            hookSystemProperties()
            hookEmulatorDetection()
            hookFileDetection()
            hookSensorDetection()
            hookTelephonyDetection()
            hookHardwareDetection()
            hookSettingsDetection()
            hookNativeProperties()

            HookUtils.log("$TAG: 模拟器特征隐藏完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 模拟器隐藏失败: ${e.message}")
            if (Constants.DEBUG_MODE) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Hook Build 类属性 - 伪装为真实设备
     */
    private fun hookBuildProperties() {
        try {
            // 修改 Build 类静态字段
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", DEVICE_BRAND)
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", DEVICE_MODEL)
            XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", DEVICE_DEVICE)
            XposedHelpers.setStaticObjectField(Build::class.java, "PRODUCT", DEVICE_PRODUCT)
            XposedHelpers.setStaticObjectField(Build::class.java, "BOARD", DEVICE_BOARD)
            XposedHelpers.setStaticObjectField(Build::class.java, "HARDWARE", DEVICE_HARDWARE)
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", DEVICE_MANUFACTURER)
            XposedHelpers.setStaticObjectField(Build::class.java, "FINGERPRINT", DEVICE_FINGERPRINT)
            XposedHelpers.setStaticObjectField(Build::class.java, "DISPLAY", DEVICE_DISPLAY)
            XposedHelpers.setStaticObjectField(Build::class.java, "TYPE", DEVICE_BUILD_TYPE)
            XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", DEVICE_BUILD_TAGS)
            XposedHelpers.setStaticObjectField(Build::class.java, "SERIAL", DEVICE_SERIAL)
            XposedHelpers.setStaticObjectField(Build::class.java, "HOST", "SRPXG000000")
            XposedHelpers.setStaticObjectField(Build::class.java, "USER", "dpi")
            XposedHelpers.setStaticObjectField(Build::class.java, "ID", "UP1A.231005.007")
            XposedHelpers.setStaticObjectField(Build::class.java, "BOOTLOADER", "e3q-1.0-123456")
            XposedHelpers.setStaticObjectField(Build::class.java, "RADIO", "G998BXXU3AXK4")

            // 修改 Build.VERSION 字段
            val versionClass = Build.VERSION::class.java
            XposedHelpers.setStaticObjectField(versionClass, "RELEASE", "14")
            XposedHelpers.setStaticIntField(versionClass, "SDK_INT", 34)
            XposedHelpers.setStaticObjectField(versionClass, "INCREMENTAL", "S928BXXU3AXK4")
            XposedHelpers.setStaticObjectField(versionClass, "RELEASE_OR_CODENAME", "14")
            XposedHelpers.setStaticObjectField(versionClass, "SECURITY_PATCH", "2024-11-01")

            // Hook Build.getString() - 拦截反射读取
            XposedHelpers.findAndHookMethod(
                Build::class.java, "getString", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        param.result = when (key) {
                            "ro.product.brand" -> DEVICE_BRAND
                            "ro.product.model" -> DEVICE_MODEL
                            "ro.product.device" -> DEVICE_DEVICE
                            "ro.product.board" -> DEVICE_BOARD
                            "ro.product.name" -> DEVICE_PRODUCT
                            "ro.hardware" -> DEVICE_HARDWARE
                            "ro.build.fingerprint" -> DEVICE_FINGERPRINT
                            "ro.serialno" -> DEVICE_SERIAL
                            else -> param.result
                        }
                    }
                }
            )

            // Hook Build.getSerial()
            try {
                XposedHelpers.findAndHookMethod(
                    Build::class.java, "getSerial",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = DEVICE_SERIAL
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.logDebug("$TAG: Build 属性已伪装")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Build 属性伪装失败: ${e.message}")
        }
    }

    /**
     * Hook SystemProperties - 拦截系统属性读取
     */
    private fun hookSystemProperties() {
        try {
            val propsClass = XposedHelpers.findClass("android.os.SystemProperties", null)

            // 属性映射表
            val propOverrides = mapOf(
                "ro.hardware" to DEVICE_HARDWARE,
                "ro.boot.hardware" to DEVICE_HARDWARE,
                "ro.product.model" to DEVICE_MODEL,
                "ro.product.device" to DEVICE_DEVICE,
                "ro.product.board" to DEVICE_BOARD,
                "ro.product.brand" to DEVICE_BRAND,
                "ro.product.name" to DEVICE_PRODUCT,
                "ro.product.manufacturer" to DEVICE_MANUFACTURER,
                "ro.build.fingerprint" to DEVICE_FINGERPRINT,
                "ro.build.display.id" to DEVICE_DISPLAY,
                "ro.build.type" to DEVICE_BUILD_TYPE,
                "ro.build.tags" to DEVICE_BUILD_TAGS,
                "ro.build.characteristics" to "default",
                "ro.boot.qemu" to "0",
                "ro.kernel.qemu" to "0",
                "ro.debuggable" to "0",
                "ro.secure" to "1",
                "ro.boot.verifiedbootstate" to "green",
                "ro.boot.flash.locked" to "1",
                "gsm.version.baseband" to "G998BXXU3AXK4",
                "ro.serialno" to DEVICE_SERIAL,
                "ro.boot.serialno" to DEVICE_SERIAL,
                "net.dns1" to "8.8.8.8",
                "net.dns2" to "8.8.4.4",
                "ro.setupwizard.mode" to "OPTIONAL",
                "qemu.sf.fake_camera" to "",
                "qemu.hw.mainkeys" to "",
                "init.svc.qemu-props" to ""
            )

            // Hook SystemProperties.get(String)
            XposedHelpers.findAndHookMethod(
                propsClass, "get", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        propOverrides[key]?.let { param.result = it }
                    }
                }
            )

            // Hook SystemProperties.get(String, String)
            XposedHelpers.findAndHookMethod(
                propsClass, "get", String::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        propOverrides[key]?.let { param.result = it }
                    }
                }
            )

            // Hook SystemProperties.getInt(String, int)
            XposedHelpers.findAndHookMethod(
                propsClass, "getInt", String::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        when (key) {
                            "ro.kernel.qemu" -> param.result = 0
                            "ro.boot.qemu" -> param.result = 0
                            "ro.debuggable" -> param.result = 0
                            "ro.secure" -> param.result = 1
                        }
                    }
                }
            )

            // Hook SystemProperties.getBoolean(String, boolean)
            XposedHelpers.findAndHookMethod(
                propsClass, "getBoolean", String::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        when (key) {
                            "ro.kernel.qemu" -> param.result = false
                            "ro.boot.qemu" -> param.result = false
                            "ro.debuggable" -> param.result = false
                            "ro.secure" -> param.result = true
                        }
                    }
                }
            )

            HookUtils.logDebug("$TAG: SystemProperties 已 Hook")
        } catch (e: Exception) {
            HookUtils.log("$TAG: SystemProperties Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook 模拟器检测方法
     */
    private fun hookEmulatorDetection() {
        try {
            // Hook Build.IS_EMULATOR (API 31+)
            try {
                XposedHelpers.setStaticBooleanField(Build::class.java, "IS_EMULATOR", false)
            } catch (_: Exception) {}

            // Hook 常见的模拟器检测方法
            val emulatorChecks = listOf(
                "isEmulator" to false,
                "isSimulator" to false,
                "isVirtual" to false,
                "isRunningInEmulator" to false,
                "isTest" to false
            )

            // 尝试 Hook 各种检测类
            val detectionClasses = listOf(
                "com.alibaba.dingtalk.util.DeviceUtils",
                "com.alibaba.dingtalk.runtimebase.DeviceInfo",
                "com.alibaba.security.util.DeviceDetector",
                "com.alibaba.wireless.security.util.DeviceUtils"
            )

            for (className in detectionClasses) {
                try {
                    val clazz = XposedHelpers.findClass(className, null)
                    for ((methodName, _) in emulatorChecks) {
                        try {
                            XposedHelpers.findAndHookMethod(
                                clazz, methodName,
                                object : XC_MethodHook() {
                                    override fun afterHookedMethod(param: MethodHookParam) {
                                        param.result = false
                                    }
                                }
                            )
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            // Hook System.getProperty - 隐藏模拟器属性
            XposedHelpers.findAndHookMethod(
                System::class.java, "getProperty", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        when (key) {
                            "ro.kernel.qemu" -> param.result = "0"
                            "ro.hardware" -> param.result = DEVICE_HARDWARE
                            "ro.product.model" -> param.result = DEVICE_MODEL
                        }
                    }
                }
            )

            HookUtils.logDebug("$TAG: 模拟器检测方法已 Hook")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 模拟器检测 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook 文件检测 - 隐藏模拟器特征文件
     */
    private fun hookFileDetection() {
        try {
            XposedHelpers.findAndHookMethod(
                File::class.java, "exists",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == true) {
                            val file = param.thisObject as File
                            val path = file.absolutePath

                            // 隐藏模拟器特征文件
                            if (EMULATOR_FILES.any { path.contains(it) }) {
                                param.result = false
                                return
                            }

                            // 隐藏 /proc/cpuinfo 中的模拟器特征
                            if (path == "/proc/cpuinfo") {
                                // 不修改文件存在性，但 Hook 读取内容
                            }
                        }
                    }
                }
            )

            // Hook FileInputStream - 修改 /proc/cpuinfo 内容
            try {
                val fisClass = java.io.FileInputStream::class.java
                XposedHelpers.findAndHookMethod(
                    fisClass, "read", ByteArray::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            // 检查是否在读取 /proc/cpuinfo
                            try {
                                val field = fisClass.getDeclaredField("path")
                                field.isAccessible = true
                                val path = field.get(param.thisObject) as? String
                                if (path == "/proc/cpuinfo") {
                                    val bytes = param.args[0] as? ByteArray ?: return
                                    val content = String(bytes)
                                    if (content.contains("goldfish") || content.contains("ranchu")) {
                                        // 替换为 ARM 架构信息
                                        val fakeContent = content
                                            .replace("goldfish", "armv8")
                                            .replace("ranchu", "armv8")
                                            .replace("x86_64", "aarch64")
                                            .replace("GenuineIntel", "ARM")
                                        val fakeBytes = fakeContent.toByteArray()
                                        System.arraycopy(fakeBytes, 0, bytes, 0, minOf(fakeBytes.size, bytes.size))
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.logDebug("$TAG: 文件检测已 Hook")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 文件检测 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook 传感器检测 - 过滤模拟器传感器
     */
    private fun hookSensorDetection() {
        try {
            // Hook SensorManager.getSensorList(int)
            XposedHelpers.findAndHookMethod(
                SensorManager::class.java, "getSensorList", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val sensors = param.result as? List<Any> ?: return
                        val filtered = sensors.filter { sensor ->
                            val name = XposedHelpers.callMethod(sensor, "getName") as? String ?: ""
                            val vendor = XposedHelpers.callMethod(sensor, "getVendor") as? String ?: ""
                            val nameLower = name.lowercase()
                            val vendorLower = vendor.lowercase()
                            !EMULATOR_SENSOR_KEYWORDS.any { keyword ->
                                nameLower.contains(keyword) || vendorLower.contains(keyword)
                            }
                        }
                        param.result = filtered
                    }
                }
            )

            // Hook SensorManager.getDefaultSensor(int)
            XposedHelpers.findAndHookMethod(
                SensorManager::class.java, "getDefaultSensor", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sensor = param.result ?: return
                        val name = XposedHelpers.callMethod(sensor, "getName") as? String ?: ""
                        val vendor = XposedHelpers.callMethod(sensor, "getVendor") as? String ?: ""
                        val nameLower = name.lowercase()
                        val vendorLower = vendor.lowercase()
                        if (EMULATOR_SENSOR_KEYWORDS.any { keyword ->
                                nameLower.contains(keyword) || vendorLower.contains(keyword)
                            }) {
                            param.result = null
                        }
                    }
                }
            )

            // Hook SensorManager.getDefaultSensor(int, boolean)
            try {
                XposedHelpers.findAndHookMethod(
                    SensorManager::class.java, "getDefaultSensor",
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val sensor = param.result ?: return
                            val name = XposedHelpers.callMethod(sensor, "getName") as? String ?: ""
                            val vendor = XposedHelpers.callMethod(sensor, "getVendor") as? String ?: ""
                            val nameLower = name.lowercase()
                            val vendorLower = vendor.lowercase()
                            if (EMULATOR_SENSOR_KEYWORDS.any { keyword ->
                                    nameLower.contains(keyword) || vendorLower.contains(keyword)
                                }) {
                                param.result = null
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.logDebug("$TAG: 传感器检测已 Hook")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 传感器检测 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook 电话状态检测 - 伪装为真实设备
     */
    private fun hookTelephonyDetection() {
        try {
            val tmClass = android.telephony.TelephonyManager::class.java

            // IMEI 相关
            val imeiMethods = listOf(
                "getDeviceId", "getImei", "getMeid"
            )
            for (method in imeiMethods) {
                try {
                    XposedHelpers.findAndHookMethod(
                        tmClass, method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                param.result = deviceIMEI
                            }
                        }
                    )
                    // 带 slotId 的重载
                    try {
                        XposedHelpers.findAndHookMethod(
                            tmClass, method, Int::class.javaPrimitiveType,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    param.result = deviceIMEI
                                }
                            }
                        )
                    } catch (_: Exception) {}
                } catch (_: Exception) {}
            }

            // IMSI
            try {
                XposedHelpers.findAndHookMethod(
                    tmClass, "getSubscriberId",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = "460001234567890"
                        }
                    }
                )
            } catch (_: Exception) {}

            // SIM 序列号
            try {
                XposedHelpers.findAndHookMethod(
                    tmClass, "getSimSerialNumber",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = "8986012345678901234"
                        }
                    }
                )
            } catch (_: Exception) {}

            // 电话号码
            try {
                XposedHelpers.findAndHookMethod(
                    tmClass, "getLine1Number",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = "13812345678"
                        }
                    }
                )
            } catch (_: Exception) {}

            // 运营商信息
            val operatorOverrides = mapOf(
                "getNetworkOperator" to "46000",
                "getNetworkOperatorName" to "中国移动",
                "getSimOperator" to "46000",
                "getSimOperatorName" to "中国移动",
                "getNetworkCountryIso" to "cn",
                "getSimCountryIso" to "cn"
            )
            for ((method, value) in operatorOverrides) {
                try {
                    XposedHelpers.findAndHookMethod(
                        tmClass, method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                param.result = value
                            }
                        }
                    )
                } catch (_: Exception) {}
            }

            // 电话类型
            try {
                XposedHelpers.findAndHookMethod(
                    tmClass, "getPhoneType",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = android.telephony.TelephonyManager.PHONE_TYPE_GSM
                        }
                    }
                )
            } catch (_: Exception) {}

            // 网络类型
            try {
                XposedHelpers.findAndHookMethod(
                    tmClass, "getNetworkType",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = android.telephony.TelephonyManager.NETWORK_TYPE_LTE
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.logDebug("$TAG: 电话状态检测已 Hook")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 电话状态检测 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook 硬件检测 - 伪装 CPU 和内存
     */
    private fun hookHardwareDetection() {
        try {
            // Hook Runtime.availableProcessors()
            XposedHelpers.findAndHookMethod(
                Runtime::class.java, "availableProcessors",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = CPU_CORES
                    }
                }
            )

            // Hook ActivityManager.getMemoryInfo()
            try {
                val amClass = android.app.ActivityManager::class.java
                val miClass = android.app.ActivityManager.MemoryInfo::class.java

                XposedHelpers.findAndHookMethod(
                    amClass, "getMemoryInfo", miClass,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val mi = param.args[0]
                            XposedHelpers.setLongField(mi, "totalMem", MEMORY_SIZE)
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook android.os.Build.VERSION_CODES 处理
            // 确保 API 版本检查不会暴露模拟器

            HookUtils.logDebug("$TAG: 硬件检测已 Hook")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 硬件检测 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Settings 检测 - 隐藏模拟器设置
     */
    private fun hookSettingsDetection() {
        try {
            val settingsSecureClass = android.provider.Settings.Secure::class.java
            val settingsGlobalClass = android.provider.Settings.Global::class.java

            // Hook Settings.Secure.getString
            XposedHelpers.findAndHookMethod(
                settingsSecureClass, "getString",
                android.content.ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        when (key) {
                            "android_id" -> {
                                // 生成随机但有效的 android_id
                                param.result = generateAndroidId()
                            }
                            "mock_location" -> {
                                param.result = "0"
                            }
                        }
                    }
                }
            )

            // Hook Settings.Secure.getInt
            XposedHelpers.findAndHookMethod(
                settingsSecureClass, "getInt",
                android.content.ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        when (key) {
                            "mock_location" -> param.result = 0
                            "development_settings_enabled" -> param.result = 0
                        }
                    }
                }
            )

            // Hook Settings.Global.getString
            XposedHelpers.findAndHookMethod(
                settingsGlobalClass, "getString",
                android.content.ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        when (key) {
                            "development_settings_enabled" -> param.result = "0"
                            "adb_enabled" -> param.result = "0"
                        }
                    }
                }
            )

            // Hook Settings.Global.getInt
            XposedHelpers.findAndHookMethod(
                settingsGlobalClass, "getInt",
                android.content.ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        when (key) {
                            "development_settings_enabled" -> param.result = 0
                            "adb_enabled" -> param.result = 0
                        }
                    }
                }
            )

            HookUtils.logDebug("$TAG: Settings 检测已 Hook")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Settings 检测 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Native 层系统属性读取
     * 注意: Xposed 框架无法直接 Hook native 函数
     * 需要通过其他方式处理，如 Magisk 模块或 XposedBridge 的 native hook
     */
    private fun hookNativeProperties() {
        try {
            // Xposed 框架主要处理 Java 层
            // Native 层的 __system_property_get 需要通过以下方式处理:
            // 1. 使用 Magisk 模块修改系统属性 (resetprop)
            // 2. 使用 LSPosed 的 native hook 功能
            // 3. 通过 JNI 调用修改属性值

            // 这里我们通过 Hook SystemProperties 来间接处理
            // 大部分应用会通过 SystemProperties Java API 读取属性
            // 对于直接通过 JNI 调用 __system_property_get 的应用
            // 需要配合 Magisk 模块使用

            HookUtils.logDebug("$TAG: Native 属性读取已处理 (通过 SystemProperties Hook)")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Native 属性读取处理失败: ${e.message}")
        }
    }

    /**
     * 生成随机但有效的 Android ID
     */
    private fun generateAndroidId(): String {
        val chars = "0123456789abcdef"
        return buildString(16) {
            repeat(16) { append(chars.random()) }
        }
    }
}
