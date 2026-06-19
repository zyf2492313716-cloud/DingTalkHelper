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

        private data class DeviceConfig(
            val brand: String,
            val model: String,
            val device: String,
            val product: String,
            val board: String,
            val hardware: String,
            val manufacturer: String,
            val fingerprint: String,
            val display: String,
            val incremental: String,
            val release: String,
            val sdkInt: Int,
            val securityPatch: String,
            val serial: String
        )

        private val DEVICE_PROFILES = listOf(
            DeviceConfig(
                brand = "samsung", model = "SM-S928B", device = "e3q", product = "e3q",
                board = "pineapple", hardware = "pineapple", manufacturer = "samsung",
                fingerprint = "samsung/e3q/e3q:14/UP1A.231005.007/S928BXXU3AXK4:user/release-keys",
                display = "UP1A.231005.007", incremental = "S928BXXU3AXK4",
                release = "14", sdkInt = 34, securityPatch = "2024-11-01", serial = "R5CX21ABCDEF"
            ),
            DeviceConfig(
                brand = "samsung", model = "SM-S911B", device = "r11", product = "r11",
                board = "taro", hardware = "taro", manufacturer = "samsung",
                fingerprint = "samsung/r11/r11:14/UP1A.231005.007/S911BXXU5CWK1:user/release-keys",
                display = "UP1A.231005.007", incremental = "S911BXXU5CWK1",
                release = "14", sdkInt = 34, securityPatch = "2024-11-01", serial = "R5CXA0ABCDEF"
            ),
            DeviceConfig(
                brand = "Xiaomi", model = "23127PN0CC", device = "houji", product = "houji",
                board = "taro", hardware = "qcom", manufacturer = "Xiaomi",
                fingerprint = "Xiaomi/houji/houji:14/UKQ1.231003.001/V816.0.24.10.17.DEV:user/release-keys",
                display = "UKQ1.231003.001", incremental = "V816.0.24.10.17.DEV",
                release = "14", sdkInt = 34, securityPatch = "2024-10-01", serial = "XA0BCDEF1234"
            ),
            DeviceConfig(
                brand = "OnePlus", model = "CPH2573", device = "aston", product = "aston",
                board = "taro", hardware = "qcom", manufacturer = "OnePlus",
                fingerprint = "OnePlus/aston/aston:14/UKQ1.230924.001/R.136e757_1:user/release-keys",
                display = "UKQ1.230924.001", incremental = "R.136e757_1",
                release = "14", sdkInt = 34, securityPatch = "2024-09-01", serial = "OP5ABCDEF123"
            ),
            DeviceConfig(
                brand = "google", model = "Pixel 8 Pro", device = "shiba", product = "shiba",
                board = "tensor", hardware = "tensor", manufacturer = "Google",
                fingerprint = "google/shiba/shiba:14/UD1A.231105.004/11021471:user/release-keys",
                display = "UD1A.231105.004", incremental = "11021471",
                release = "14", sdkInt = 34, securityPatch = "2024-11-05", serial = "GP8ABCDEF123"
            )
        )

        private const val CPU_CORES = 8
        private const val MEMORY_SIZE = 12884901888L

        private val SELECTED_DEVICE by lazy { DEVICE_PROFILES.random() }
        private val DEVICE_SEED by lazy { (SELECTED_DEVICE.model.hashCode().toLong() shl 32) or (android.os.Build.SERIAL.hashCode().toLong() and 0xFFFFFFFFL) }

        fun getRandomIMEI(): String {
            val rng = java.util.Random(DEVICE_SEED xor 0x494D4549)
            val sb = StringBuilder("86")
            repeat(13) { sb.append(rng.nextInt(10)) }
            return sb.toString()
        }

        fun getRandomAndroidID(): String {
            val rng = java.util.Random(DEVICE_SEED xor 0x414E4449)
            val chars = "0123456789abcdef"
            return buildString(16) { repeat(16) { append(chars[rng.nextInt(16)]) } }
        }

        fun getRandomSerial(): String = SELECTED_DEVICE.serial

        fun getRandomIMSI(): String {
            val rng = java.util.Random(DEVICE_SEED xor 0x494D5349)
            val sb = StringBuilder("46000")
            repeat(10) { sb.append(rng.nextInt(10)) }
            return sb.toString()
        }

        fun getDeviceBrand(): String = SELECTED_DEVICE.brand
        fun getDeviceModel(): String = SELECTED_DEVICE.model
        fun getDeviceDevice(): String = SELECTED_DEVICE.device
        fun getDeviceProduct(): String = SELECTED_DEVICE.product
        fun getDeviceBoard(): String = SELECTED_DEVICE.board
        fun getDeviceHardware(): String = SELECTED_DEVICE.hardware
        fun getDeviceManufacturer(): String = SELECTED_DEVICE.manufacturer
        fun getDeviceFingerprint(): String = SELECTED_DEVICE.fingerprint
        fun getDeviceDisplay(): String = SELECTED_DEVICE.display
        fun getDeviceIncremental(): String = SELECTED_DEVICE.incremental
        fun getDeviceRelease(): String = SELECTED_DEVICE.release
        fun getDeviceSdkInt(): Int = SELECTED_DEVICE.sdkInt
        fun getDeviceSecurityPatch(): String = SELECTED_DEVICE.securityPatch
    }

    private val deviceIMEI by lazy { getRandomIMEI() }

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
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", getDeviceBrand())
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", getDeviceModel())
            XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", getDeviceDevice())
            XposedHelpers.setStaticObjectField(Build::class.java, "PRODUCT", getDeviceProduct())
            XposedHelpers.setStaticObjectField(Build::class.java, "BOARD", getDeviceBoard())
            XposedHelpers.setStaticObjectField(Build::class.java, "HARDWARE", getDeviceHardware())
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", getDeviceManufacturer())
            XposedHelpers.setStaticObjectField(Build::class.java, "FINGERPRINT", getDeviceFingerprint())
            XposedHelpers.setStaticObjectField(Build::class.java, "DISPLAY", getDeviceDisplay())
            XposedHelpers.setStaticObjectField(Build::class.java, "TYPE", "user")
            XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
            XposedHelpers.setStaticObjectField(Build::class.java, "SERIAL", getRandomSerial())
            XposedHelpers.setStaticObjectField(Build::class.java, "HOST", "SRPXG000000")
            XposedHelpers.setStaticObjectField(Build::class.java, "USER", "dpi")
            XposedHelpers.setStaticObjectField(Build::class.java, "ID", getDeviceDisplay())
            XposedHelpers.setStaticObjectField(Build::class.java, "BOOTLOADER", "${getDeviceDevice()}-1.0-123456")
            XposedHelpers.setStaticObjectField(Build::class.java, "RADIO", getDeviceIncremental())

            val versionClass = Build.VERSION::class.java
            XposedHelpers.setStaticObjectField(versionClass, "RELEASE", getDeviceRelease())
            XposedHelpers.setStaticIntField(versionClass, "SDK_INT", getDeviceSdkInt())
            XposedHelpers.setStaticObjectField(versionClass, "INCREMENTAL", getDeviceIncremental())
            XposedHelpers.setStaticObjectField(versionClass, "RELEASE_OR_CODENAME", getDeviceRelease())
            XposedHelpers.setStaticObjectField(versionClass, "SECURITY_PATCH", getDeviceSecurityPatch())

            XposedHelpers.findAndHookMethod(
                Build::class.java, "getString", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        param.result = when (key) {
                            "ro.product.brand" -> getDeviceBrand()
                            "ro.product.model" -> getDeviceModel()
                            "ro.product.device" -> getDeviceDevice()
                            "ro.product.board" -> getDeviceBoard()
                            "ro.product.name" -> getDeviceProduct()
                            "ro.hardware" -> getDeviceHardware()
                            "ro.build.fingerprint" -> getDeviceFingerprint()
                            "ro.serialno" -> getRandomSerial()
                            else -> param.result
                        }
                    }
                }
            )

            try {
                XposedHelpers.findAndHookMethod(
                    Build::class.java, "getSerial",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = getRandomSerial()
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

            val propOverrides = mapOf(
                "ro.hardware" to getDeviceHardware(),
                "ro.boot.hardware" to getDeviceHardware(),
                "ro.product.model" to getDeviceModel(),
                "ro.product.device" to getDeviceDevice(),
                "ro.product.board" to getDeviceBoard(),
                "ro.product.brand" to getDeviceBrand(),
                "ro.product.name" to getDeviceProduct(),
                "ro.product.manufacturer" to getDeviceManufacturer(),
                "ro.build.fingerprint" to getDeviceFingerprint(),
                "ro.build.display.id" to getDeviceDisplay(),
                "ro.build.type" to "user",
                "ro.build.tags" to "release-keys",
                "ro.build.characteristics" to "default",
                "ro.boot.qemu" to "0",
                "ro.kernel.qemu" to "0",
                "ro.debuggable" to "0",
                "ro.secure" to "1",
                "ro.boot.verifiedbootstate" to "green",
                "ro.boot.flash.locked" to "1",
                "gsm.version.baseband" to getDeviceIncremental(),
                "ro.serialno" to getRandomSerial(),
                "ro.boot.serialno" to getRandomSerial(),
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
                            "ro.hardware" -> param.result = getDeviceHardware()
                            "ro.product.model" -> param.result = getDeviceModel()
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

                            // 只在检测到模拟器特征路径时才拦截，其他路径保持原样
                            if (path in Constants.EMULATOR_FILES) {
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

            try {
                XposedHelpers.findAndHookMethod(
                    tmClass, "getSubscriberId",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = getRandomIMSI()
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

            // 运营商信息（根据 GPS 坐标动态推断）
            val operatorInfo = getOperatorInfoForLocation()
            val operatorOverrides = mapOf(
                "getNetworkOperator" to operatorInfo.networkOperator,
                "getNetworkOperatorName" to operatorInfo.networkOperatorName,
                "getSimOperator" to operatorInfo.simOperator,
                "getSimOperatorName" to operatorInfo.simOperatorName,
                "getNetworkCountryIso" to operatorInfo.networkCountryIso,
                "getSimCountryIso" to operatorInfo.simCountryIso
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

    private fun generateAndroidId(): String = getRandomAndroidID()

    /**
     * 根据 GPS 坐标推断国家/地区，返回对应的运营商信息
     * 中国：460/中国移动/中国联通/中国电信
     * 美国：310/T-Mobile/AT&T/Verizon
     * 其他地区：使用默认值（中国）
     */
    private data class OperatorInfo(
        val networkOperator: String,
        val networkOperatorName: String,
        val simOperator: String,
        val simOperatorName: String,
        val networkCountryIso: String,
        val simCountryIso: String
    )

    private fun getOperatorInfoForLocation(): OperatorInfo {
        return try {
            val lat = com.dingtalk.helper.utils.ConfigManager.getLatitude()
            val lon = com.dingtalk.helper.utils.ConfigManager.getLongitude()

            when {
                // 中国地区：纬度 18-54，经度 73-135
                lat in 18.0..54.0 && lon in 73.0..135.0 -> OperatorInfo(
                    networkOperator = "46000",
                    networkOperatorName = "中国移动",
                    simOperator = "46000",
                    simOperatorName = "中国移动",
                    networkCountryIso = "cn",
                    simCountryIso = "cn"
                )
                // 美国地区：纬度 24-49，经度 -125..-66
                lat in 24.0..49.0 && lon in -125.0..-66.0 -> OperatorInfo(
                    networkOperator = "310260",
                    networkOperatorName = "T-Mobile",
                    simOperator = "310260",
                    simOperatorName = "T-Mobile",
                    networkCountryIso = "us",
                    simCountryIso = "us"
                )
                // 其他地区：默认使用中国运营商
                else -> OperatorInfo(
                    networkOperator = "46000",
                    networkOperatorName = "中国移动",
                    simOperator = "46000",
                    simOperatorName = "中国移动",
                    networkCountryIso = "cn",
                    simCountryIso = "cn"
                )
            }
        } catch (_: Exception) {
            OperatorInfo(
                networkOperator = "46000",
                networkOperatorName = "中国移动",
                simOperator = "46000",
                simOperatorName = "中国移动",
                networkCountryIso = "cn",
                simCountryIso = "cn"
            )
        }
    }
}
