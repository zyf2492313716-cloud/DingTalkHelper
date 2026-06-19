package com.dingtalk.helper.xposed.hooks

import android.hardware.SensorManager
import android.os.Build
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.data.RandomizedDeviceId
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class EmulatorHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}/Emulator"

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
            val securityPatch: String
        )

        @Suppress("SpellCheckingInspection")
        private val DEVICE_PROFILES = listOf(
            // ==================== Samsung ====================
            DeviceConfig(
                brand = "samsung", model = "SM-S928B", device = "e3q", product = "e3q",
                board = "pineapple", hardware = "qcom", manufacturer = "samsung",
                fingerprint = "samsung/e3q/e3q:14/UP1A.231005.007/S928BXXU3AXK4:user/release-keys",
                display = "UP1A.231005.007", incremental = "S928BXXU3AXK4",
                release = "14", sdkInt = 34, securityPatch = "2024-11-01"
            ),
            DeviceConfig(
                brand = "samsung", model = "SM-S911B", device = "r11", product = "r11",
                board = "taro", hardware = "qcom", manufacturer = "samsung",
                fingerprint = "samsung/r11/r11:14/UP1A.231005.007/S911BXXU5CWK1:user/release-keys",
                display = "UP1A.231005.007", incremental = "S911BXXU5CWK1",
                release = "14", sdkInt = 34, securityPatch = "2024-11-01"
            ),
            DeviceConfig(
                brand = "samsung", model = "SM-A546B", device = "a54x", product = "a54x",
                board = "s5e8835", hardware = "samsungexynos1380", manufacturer = "samsung",
                fingerprint = "samsung/a54x/a54x:14/UP1A.231005.007/A546BXXU7DXJ3:user/release-keys",
                display = "UP1A.231005.007", incremental = "A546BXXU7DXJ3",
                release = "14", sdkInt = 34, securityPatch = "2024-10-01"
            ),
            DeviceConfig(
                brand = "samsung", model = "SM-A346B", device = "a34x", product = "a34x",
                board = "mt6877", hardware = "mt6877", manufacturer = "samsung",
                fingerprint = "samsung/a34x/a34x:14/UP1A.231005.007/A346BXXU7DXI1:user/release-keys",
                display = "UP1A.231005.007", incremental = "A346BXXU7DXI1",
                release = "14", sdkInt = 34, securityPatch = "2024-09-01"
            ),
            DeviceConfig(
                brand = "samsung", model = "SM-F731B", device = "b3q", product = "b3q",
                board = "taro", hardware = "qcom", manufacturer = "samsung",
                fingerprint = "samsung/b3q/b3q:14/UP1A.231005.007/F731BXXU3AXK3:user/release-keys",
                display = "UP1A.231005.007", incremental = "F731BXXU3AXK3",
                release = "14", sdkInt = 34, securityPatch = "2024-11-01"
            ),
            DeviceConfig(
                brand = "samsung", model = "SM-N986B", device = "c2q", product = "c2q",
                board = "exynos990", hardware = "samsungexynos990", manufacturer = "samsung",
                fingerprint = "samsung/c2q/c2q:13/TP1A.220624.014/N986BXXS8FWA1:user/release-keys",
                display = "TP1A.220624.014", incremental = "N986BXXS8FWA1",
                release = "13", sdkInt = 33, securityPatch = "2024-01-01"
            ),
            // ==================== Xiaomi ====================
            DeviceConfig(
                brand = "xiaomi", model = "23127PN0CC", device = "houji", product = "houji",
                board = "kalama", hardware = "qcom", manufacturer = "Xiaomi",
                fingerprint = "Xiaomi/houji/houji:14/UKQ1.231003.001/V816.0.24.10.17.DEV:user/release-keys",
                display = "UKQ1.231003.001", incremental = "V816.0.24.10.17.DEV",
                release = "14", sdkInt = 34, securityPatch = "2024-10-01"
            ),
            DeviceConfig(
                brand = "xiaomi", model = "23116PN5BC", device = "manet", product = "manet",
                board = "manet", hardware = "qcom", manufacturer = "Xiaomi",
                fingerprint = "Xiaomi/manet/manet:14/UKQ1.231003.001/OS1.0.23.12.11.DEV:user/release-keys",
                display = "UKQ1.231003.001", incremental = "OS1.0.23.12.11.DEV",
                release = "14", sdkInt = 34, securityPatch = "2024-06-01"
            ),
            DeviceConfig(
                brand = "xiaomi", model = "Redmi Note 12", device = "topaz", product = "topaz",
                board = "mt6768", hardware = "mt6768", manufacturer = "Xiaomi",
                fingerprint = "Xiaomi/topaz/topaz:13/TP1A.220624.014/V14.0.23.12.13.DEV:user/release-keys",
                display = "TP1A.220624.014", incremental = "V14.0.23.12.13.DEV",
                release = "13", sdkInt = 33, securityPatch = "2023-12-01"
            ),
            DeviceConfig(
                brand = "xiaomi", model = "M2012K11AC", device = "star", product = "star",
                board = "kona", hardware = "qcom", manufacturer = "Xiaomi",
                fingerprint = "Xiaomi/star/star:13/TKQ1.220829.002/V14.0.23.9.27.DEV:user/release-keys",
                display = "TKQ1.220829.002", incremental = "V14.0.23.9.27.DEV",
                release = "13", sdkInt = 33, securityPatch = "2023-09-01"
            ),
            // ==================== Huawei ====================
            DeviceConfig(
                brand = "huawei", model = "ALN-AL10", device = "ALN", product = "ALN",
                board = "kirin9000s", hardware = "kirin9000s", manufacturer = "HUAWEI",
                fingerprint = "HUAWEI/ALN-AL10/ALN:14/HUAWEIALN-AL10/110.0.1.130C234E1:user/release-keys",
                display = "HUAWEIALN-AL10", incremental = "110.0.1.130C234E1",
                release = "14", sdkInt = 34, securityPatch = "2024-09-01"
            ),
            DeviceConfig(
                brand = "huawei", model = "MNA-AL00", device = "MNA", product = "MNA",
                board = "kirin990", hardware = "kirin990", manufacturer = "HUAWEI",
                fingerprint = "HUAWEI/MNA-AL00/MNA:12/SP1A.210812.016/12.0.1.160C605E2:user/release-keys",
                display = "SP1A.210812.016", incremental = "12.0.1.160C605E2",
                release = "12", sdkInt = 31, securityPatch = "2023-07-01"
            ),
            DeviceConfig(
                brand = "huawei", model = "BNE-AL00", device = "BNE", product = "BNE",
                board = "kirin9000s", hardware = "kirin9000s", manufacturer = "HUAWEI",
                fingerprint = "HUAWEI/BNE-AL00/BNE:14/HUAWEIBNE-AL00/102.0.1.120C636E1:user/release-keys",
                display = "HUAWEIBNE-AL00", incremental = "102.0.1.120C636E1",
                release = "14", sdkInt = 34, securityPatch = "2024-06-01"
            ),
            DeviceConfig(
                brand = "huawei", model = "VCN-AL00", device = "VCN", product = "VCN",
                board = "kirin990", hardware = "kirin990", manufacturer = "HUAWEI",
                fingerprint = "HUAWEI/VCN-AL00/VCN:12/SP1A.210812.016/12.0.0.126C605E2:user/release-keys",
                display = "SP1A.210812.016", incremental = "12.0.0.126C605E2",
                release = "12", sdkInt = 31, securityPatch = "2023-01-01"
            ),
            // ==================== OPPO ====================
            DeviceConfig(
                brand = "oppo", model = "PHZ110", device = "socrates", product = "socrates",
                board = "kite", hardware = "qcom", manufacturer = "OPPO",
                fingerprint = "OPPO/socrates/socrates:14/UKQ1.231003.001/PHZ110_14.0.1.600(EX01V110P02)_:user/release-keys",
                display = "UKQ1.231003.001", incremental = "PHZ110_14.0.1.600(EX01V110P02)_",
                release = "14", sdkInt = 34, securityPatch = "2024-08-01"
            ),
            DeviceConfig(
                brand = "oppo", model = "PJZ110", device = "astrid", product = "astrid",
                board = "mt6895", hardware = "mt6895", manufacturer = "OPPO",
                fingerprint = "OPPO/astrid/astrid:14/UKQ1.231003.001/PJZ110_14.0.1.500(EX01V110P01)_:user/release-keys",
                display = "UKQ1.231003.001", incremental = "PJZ110_14.0.1.500(EX01V110P01)_",
                release = "14", sdkInt = 34, securityPatch = "2024-07-01"
            ),
            DeviceConfig(
                brand = "oppo", model = "CPH2591", device = "magnolia", product = "magnolia",
                board = "mt6769", hardware = "mt6769", manufacturer = "OPPO",
                fingerprint = "OPPO/magnolia/magnolia:13/TP1A.220624.014/CPH2591_13.1.1.400(EX01V110P01)_:user/release-keys",
                display = "TP1A.220624.014", incremental = "CPH2591_13.1.1.400(EX01V110P01)_",
                release = "13", sdkInt = 33, securityPatch = "2023-11-01"
            ),
            // ==================== vivo ====================
            DeviceConfig(
                brand = "vivo", model = "V2324A", device = "V2324A", product = "V2324A",
                board = "kite", hardware = "qcom", manufacturer = "vivo",
                fingerprint = "vivo/V2324A/V2324A:14/UP1A.231005.007/14.0.23.12.12.W30.V1:user/release-keys",
                display = "UP1A.231005.007", incremental = "14.0.23.12.12.W30.V1",
                release = "14", sdkInt = 34, securityPatch = "2024-06-01"
            ),
            DeviceConfig(
                brand = "vivo", model = "V2332A", device = "V2332A", product = "V2332A",
                board = "mt6895", hardware = "mt6895", manufacturer = "vivo",
                fingerprint = "vivo/V2332A/V2332A:14/UKQ1.231003.001/14.0.12.2.W30.V1:user/release-keys",
                display = "UKQ1.231003.001", incremental = "14.0.12.2.W30.V1",
                release = "14", sdkInt = 34, securityPatch = "2024-05-01"
            ),
            DeviceConfig(
                brand = "vivo", model = "V2249A", device = "V2249A", product = "V2249A",
                board = "mt6769", hardware = "mt6769", manufacturer = "vivo",
                fingerprint = "vivo/V2249A/V2249A:13/TP1A.220624.014/13.0.8.5.W30.V1:user/release-keys",
                display = "TP1A.220624.014", incremental = "13.0.8.5.W30.V1",
                release = "13", sdkInt = 33, securityPatch = "2023-10-01"
            ),
            // ==================== OnePlus ====================
            DeviceConfig(
                brand = "oneplus", model = "CPH2573", device = "aston", product = "aston",
                board = "kalama", hardware = "qcom", manufacturer = "OnePlus",
                fingerprint = "OnePlus/aston/aston:14/UKQ1.230924.001/R.136e757_1:user/release-keys",
                display = "UKQ1.230924.001", incremental = "R.136e757_1",
                release = "14", sdkInt = 34, securityPatch = "2024-09-01"
            ),
            DeviceConfig(
                brand = "oneplus", model = "PJZ110", device = "sultan", product = "sultan",
                board = "mt6895", hardware = "mt6895", manufacturer = "OnePlus",
                fingerprint = "OnePlus/sultan/sultan:14/UKQ1.231003.001/Sultan_14.0.1.600:user/release-keys",
                display = "UKQ1.231003.001", incremental = "Sultan_14.0.1.600",
                release = "14", sdkInt = 34, securityPatch = "2024-08-01"
            ),
            // ==================== Honor ====================
            DeviceConfig(
                brand = "honor", model = "BVL-AN16", device = "BVL", product = "BVL",
                board = "kite", hardware = "qcom", manufacturer = "HONOR",
                fingerprint = "HONOR/BVL-AN16/BVL:14/UKQ1.231003.001/7.2.0.162SP2C636E2:user/release-keys",
                display = "UKQ1.231003.001", incremental = "7.2.0.162SP2C636E2",
                release = "14", sdkInt = 34, securityPatch = "2024-09-01"
            ),
            DeviceConfig(
                brand = "honor", model = "REP-AN00", device = "REP", product = "REP",
                board = "taro", hardware = "qcom", manufacturer = "HONOR",
                fingerprint = "HONOR/REP-AN00/REP:13/TP1A.220624.014/7.1.0.155C636E2:user/release-keys",
                display = "TP1A.220624.014", incremental = "7.1.0.155C636E2",
                release = "13", sdkInt = 33, securityPatch = "2023-12-01"
            ),
            // ==================== Realme ====================
            DeviceConfig(
                brand = "realme", model = "RMX3888", device = "gokun", product = "gokun",
                board = "kite", hardware = "qcom", manufacturer = "realme",
                fingerprint = "realme/gokun/gokun:14/UKQ1.231003.001/RMX3888_14.0.1.600EX110P01:user/release-keys",
                display = "UKQ1.231003.001", incremental = "RMX3888_14.0.1.600EX110P01",
                release = "14", sdkInt = 34, securityPatch = "2024-07-01"
            ),
            DeviceConfig(
                brand = "realme", model = "RMX3771", device = "kritzl", product = "kritzl",
                board = "mt6893", hardware = "mt6893", manufacturer = "realme",
                fingerprint = "realme/kritzl/kritzl:13/TP1A.220624.014/RMX3771_13.1.1.400EX110P01:user/release-keys",
                display = "TP1A.220624.014", incremental = "RMX3771_13.1.1.400EX110P01",
                release = "13", sdkInt = 33, securityPatch = "2023-11-01"
            ),
            // ==================== Google ====================
            DeviceConfig(
                brand = "google", model = "Pixel 8 Pro", device = "shiba", product = "shiba",
                board = "zuma", hardware = "zuma", manufacturer = "Google",
                fingerprint = "google/shiba/shiba:14/UD1A.231105.004/11021471:user/release-keys",
                display = "UD1A.231105.004", incremental = "11021471",
                release = "14", sdkInt = 34, securityPatch = "2024-11-05"
            ),
            DeviceConfig(
                brand = "google", model = "Pixel 7a", device = "lynx", product = "lynx",
                board = "taro", hardware = "qcom", manufacturer = "Google",
                fingerprint = "google/lynx/lynx:14/UD1A.231105.004/11021471:user/release-keys",
                display = "UD1A.231105.004", incremental = "11021471",
                release = "14", sdkInt = 34, securityPatch = "2024-11-05"
            ),
            // ==================== Motorola ====================
            DeviceConfig(
                brand = "motorola", model = "XT2313-2", device = "fog", product = "fog",
                board = "taro", hardware = "qcom", manufacturer = "motorola",
                fingerprint = "motorola/fog/fog:14/UD1A.231105.004/11021471:user/release-keys",
                display = "UD1A.231105.004", incremental = "11021471",
                release = "14", sdkInt = 34, securityPatch = "2024-11-05"
            ),
            DeviceConfig(
                brand = "motorola", model = "XT2201-2", device = "dubai", product = "dubai",
                board = "taro", hardware = "qcom", manufacturer = "motorola",
                fingerprint = "motorola/dubai/dubai:13/TP1A.220624.014/SDS22.22-12-14:user/release-keys",
                display = "TP1A.220624.014", incremental = "SDS22.22-12-14",
                release = "13", sdkInt = 33, securityPatch = "2023-08-01"
            )
        )

        private const val CPU_CORES = 8
        private const val MEMORY_SIZE = 12884901888L

        private val SELECTED_DEVICE by lazy { DEVICE_PROFILES.random() }
        private val DEVICE_SEED by lazy {
            (SELECTED_DEVICE.model.hashCode().toLong() shl 32) or
                    (android.os.Build.SERIAL.hashCode().toLong() and 0xFFFFFFFFL)
        }

        fun getRandomIMEI(): String = RandomizedDeviceId.generateIMEI(DEVICE_SEED)

        fun getRandomAndroidID(): String = RandomizedDeviceId.generateAndroidId(DEVICE_SEED)

        fun getRandomSerial(): String = RandomizedDeviceId.generateSerial(DEVICE_SEED)

        fun getRandomIMSI(): String = RandomizedDeviceId.generateIMSI(DEVICE_SEED)

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
            // 新增：网络接口检测绕过
            hookNetworkInterfaceDetection(lpparam)

            HookUtils.log("$TAG: 模拟器特征隐藏完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 模拟器隐藏失败: ${e.message}")
            if (Constants.DEBUG_MODE) {
                e.printStackTrace()
            }
        }
    }

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

            XposedHelpers.findAndHookMethod(
                propsClass, "get", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        propOverrides[key]?.let { param.result = it }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                propsClass, "get", String::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        propOverrides[key]?.let { param.result = it }
                    }
                }
            )

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

    private fun hookEmulatorDetection() {
        try {
            try {
                XposedHelpers.setStaticBooleanField(Build::class.java, "IS_EMULATOR", false)
            } catch (_: Exception) {}

            val emulatorChecks = listOf(
                "isEmulator" to false,
                "isSimulator" to false,
                "isVirtual" to false,
                "isRunningInEmulator" to false,
                "isTest" to false
            )

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

    private fun hookFileDetection() {
        try {
            XposedHelpers.findAndHookMethod(
                File::class.java, "exists",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == true) {
                            val file = param.thisObject as File
                            val path = file.absolutePath
                            if (path in Constants.EMULATOR_FILES) {
                                param.result = false
                                return
                            }
                        }
                    }
                }
            )

            try {
                val fisClass = java.io.FileInputStream::class.java
                XposedHelpers.findAndHookMethod(
                    fisClass, "read", ByteArray::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val field = fisClass.getDeclaredField("path")
                                field.isAccessible = true
                                val path = field.get(param.thisObject) as? String
                                if (path == "/proc/cpuinfo") {
                                    val bytes = param.args[0] as? ByteArray ?: return
                                    val content = String(bytes)
                                    if (content.contains("goldfish") || content.contains("ranchu")) {
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

    private fun hookSensorDetection() {
        try {
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

    private fun hookTelephonyDetection() {
        try {
            val tmClass = android.telephony.TelephonyManager::class.java

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

    private fun hookHardwareDetection() {
        try {
            XposedHelpers.findAndHookMethod(
                Runtime::class.java, "availableProcessors",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = CPU_CORES
                    }
                }
            )

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

            HookUtils.logDebug("$TAG: 硬件检测已 Hook")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 硬件检测 Hook 失败: ${e.message}")
        }
    }

    private fun hookSettingsDetection() {
        try {
            val settingsSecureClass = android.provider.Settings.Secure::class.java
            val settingsGlobalClass = android.provider.Settings.Global::class.java

            XposedHelpers.findAndHookMethod(
                settingsSecureClass, "getString",
                android.content.ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        when (key) {
                            "android_id" -> {
                                param.result = generateAndroidId()
                            }
                            "mock_location" -> {
                                param.result = "0"
                            }
                        }
                    }
                }
            )

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

    private fun hookNativeProperties() {
        try {
            HookUtils.logDebug("$TAG: Native 属性读取已处理 (通过 SystemProperties Hook)")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Native 属性读取处理失败: ${e.message}")
        }
    }

    private fun generateAndroidId(): String = getRandomAndroidID()

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
                lat in 18.0..54.0 && lon in 73.0..135.0 -> OperatorInfo(
                    networkOperator = "46000",
                    networkOperatorName = "中国移动",
                    simOperator = "46000",
                    simOperatorName = "中国移动",
                    networkCountryIso = "cn",
                    simCountryIso = "cn"
                )
                lat in 24.0..49.0 && lon in -125.0..-66.0 -> OperatorInfo(
                    networkOperator = "310260",
                    networkOperatorName = "T-Mobile",
                    simOperator = "310260",
                    simOperatorName = "T-Mobile",
                    networkCountryIso = "us",
                    simCountryIso = "us"
                )
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

    /**
     * Hook NetworkInterface.getNetworkInterfaces()
     * 模拟器通常有 eth0, sit0, tun0, tap0 等特征网卡
     * 真实设备通常只有 wlan0, rmnet0 等
     */
    private fun hookNetworkInterfaceDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val niClass = java.net.NetworkInterface::class.java

            // 模拟器特征网卡名称
            val emulatorInterfacePrefixes = setOf(
                "eth", "sit", "tun", "tap", "vbox", "virbr",
                "docker", "br-", "veth"
            )

            // Hook getNetworkInterfaces()
            XposedHelpers.findAndHookMethod(
                niClass, "getNetworkInterfaces",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val interfaces = param.result as? java.util.Enumeration<*> ?: return

                        val filteredInterfaces = mutableListOf<java.net.NetworkInterface>()
                        while (interfaces.hasMoreElements()) {
                            val ni = interfaces.nextElement() as? java.net.NetworkInterface ?: continue
                            val name = ni.name.lowercase()

                            // 过滤模拟器特征网卡
                            val isEmulatorInterface = emulatorInterfacePrefixes.any { name.startsWith(it) }
                            if (!isEmulatorInterface) {
                                filteredInterfaces.add(ni)
                            }
                        }

                        // 返回过滤后的枚举
                        param.result = java.util.Collections.enumeration(filteredInterfaces)
                    }
                }
            )

            // Hook NetworkInterface.getName() - 备份
            XposedHelpers.findAndHookMethod(
                niClass, "getName",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.result as? String ?: return
                        val nameLower = name.lowercase()

                        // 如果是模拟器特征网卡，返回 "wlan0"
                        if (emulatorInterfacePrefixes.any { nameLower.startsWith(it) }) {
                            param.result = "wlan0"
                        }
                    }
                }
            )

            // Hook NetworkInterface.getDisplayName() - 备份
            XposedHelpers.findAndHookMethod(
                niClass, "getDisplayName",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.result as? String ?: return
                        val nameLower = name.lowercase()

                        if (emulatorInterfacePrefixes.any { nameLower.startsWith(it) }) {
                            param.result = "wlan0"
                        }
                    }
                }
            )

            HookUtils.logDebug("$TAG: NetworkInterface 检测已 Hook")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: NetworkInterface Hook 失败: ${e.message}")
        }
    }
}
