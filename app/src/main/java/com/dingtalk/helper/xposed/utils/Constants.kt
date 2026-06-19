package com.dingtalk.helper.xposed.utils

/**
 * Constants management
 * Centralizes all class names, method names, config keys, etc.
 */
object Constants {

    // ==================== Debug ====================
    const val DEBUG_MODE = false
    const val LOG_PREFIX = "DingTalkHelper"

    // ==================== Target App ====================
    const val DINGTALK_PACKAGE = "com.alibaba.android.rimet"

    // ==================== SharedPreferences ====================
    const val PREFS_NAME = "dingtalk_helper_prefs"
    const val KEY_ENABLED = "enabled"
    const val KEY_FAKE_LOCATION_ENABLED = "fake_location_enabled"
    const val KEY_LATITUDE = "latitude"
    const val KEY_LONGITUDE = "longitude"
    const val KEY_ALTITUDE = "altitude"
    const val KEY_FAKE_WIFI_ENABLED = "fake_wifi_enabled"
    const val KEY_WIFI_SSID = "wifi_ssid"
    const val KEY_WIFI_BSSID = "wifi_bssid"
    const val KEY_FAKE_CELL_ENABLED = "fake_cell_enabled"
    const val KEY_CELL_ID = "cell_id"
    const val KEY_LAC = "lac"
    const val KEY_MCC = "mcc"
    const val KEY_MNC = "mnc"
    const val KEY_HIDE_ROOT = "hide_root"
    const val KEY_HIDE_XPOSED = "hide_xposed"
    const val KEY_HIDE_MOCK_LOCATION = "hide_mock_location"
    const val KEY_HIDE_RISK_CONTROL = "hide_risk_control"
    const val KEY_HIDE_APPS = "hide_apps"
    const val KEY_AUTO_CORRELATION_ENABLED = "auto_correlation_enabled"
    const val KEY_DEEP_HIDING = "deep_hiding"

    // ==================== Defaults ====================
    const val DEFAULT_LATITUDE = 39.9042
    const val DEFAULT_LONGITUDE = 116.4074
    const val DEFAULT_ALTITUDE = 0.0
    const val DEFAULT_MCC = 460
    const val DEFAULT_MNC = 0
    const val DEFAULT_ACCURACY = 10f

    // ==================== Risk Control Class Patterns (Dynamic Lookup) ====================
    // Solves R8 obfuscation ClassNotFoundException via method signature + package pattern matching

    val LBSWUA_CLASS_PATTERNS = listOf(
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("report", Any::class.java, Any::class.java),
                MethodSignature("reportLbsData", Any::class.java)
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("uploadLbsData", Any::class.java),
                MethodSignature("upload", Any::class.java)
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("generateData", Any::class.java)
            )
        )
    )

    val DDSEC_CLASS_PATTERNS = listOf(
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("generateDdsecData", Any::class.java),
                MethodSignature("getDdsecData", Any::class.java)
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("collectSecurityData", Any::class.java),
                MethodSignature("getSecurityInfo", Any::class.java)
            )
        )
    )

    val RISK_CONTROL_CLASS_PATTERNS = listOf(
        ClassPattern(
            packageNamePattern = "com.alibaba.*riskcontrol*",
            methodSignatures = listOf(
                MethodSignature("checkRisk", Any::class.java),
                MethodSignature("isDeviceRisk", Any::class.java)
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("detectRisk", Any::class.java),
                MethodSignature("isRisk", Any::class.java)
            )
        )
    )

    val COLLECTOR_CLASS_PATTERNS = listOf(
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("collect", Any::class.java),
                MethodSignature("collectData", Any::class.java)
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("report", Any::class.java),
                MethodSignature("upload", Any::class.java)
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("generateParams", Any::class.java),
                MethodSignature("generateData", Any::class.java)
            )
        )
    )

    val DEVICE_ID_CLASS_PATTERNS = listOf(
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("getDeviceId"),
                MethodSignature("getIMEI")
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("getAndroidId"),
                MethodSignature("getSerialNumber")
            )
        )
    )

    val ENVIRONMENT_CLASS_PATTERNS = listOf(
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("isRoot"),
                MethodSignature("checkRoot")
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("isXposed"),
                MethodSignature("detectXposed")
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("collectEnvironment", Any::class.java),
                MethodSignature("collectEnvInfo", Any::class.java)
            )
        )
    )

    val LOCATION_CLASS_PATTERNS = listOf(
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("collectLocation", Any::class.java),
                MethodSignature("getLocation", Any::class.java)
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("requestLocation", Any::class.java),
                MethodSignature("getGpsLocation", Any::class.java)
            )
        )
    )

    val ENCRYPT_CLASS_PATTERNS = listOf(
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("encrypt", Any::class.java),
                MethodSignature("sign", Any::class.java)
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("getParamSig", Any::class.java),
                MethodSignature("generateSign", Any::class.java)
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("hash", Any::class.java)
            )
        )
    )

    val SIGNATURE_CLASS_PATTERNS = listOf(
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("verify", Any::class.java),
                MethodSignature("verifySign", Any::class.java)
            )
        ),
        ClassPattern(
            packageNamePattern = "com.alibaba.*security*",
            methodSignatures = listOf(
                MethodSignature("checkIntegrity", Any::class.java),
                MethodSignature("validate", Any::class.java)
            )
        )
    )

    // ==================== Legacy hardcoded class names (fallback) ====================
    @Deprecated("Use LBSWUA_CLASS_PATTERNS instead. R8 obfuscation breaks these names.")
    val LBSWUA_CLASS_NAMES = listOf(
        "com.alibaba.wireless.security.securitybody.SecurityGuardManager",
        "com.alibaba.security.lbswua.LbswuaManager",
        "com.alibaba.wireless.security.lbswua.Lbswua",
        "com.alibaba.security.SecurityGuard"
    )

    @Deprecated("Use DDSEC_CLASS_PATTERNS instead. R8 obfuscation breaks these names.")
    val DDSEC_CLASS_NAMES = listOf(
        "com.alibaba.wireless.security.securitybody.SecurityBody",
        "com.alibaba.security.ddsec.DdsecManager",
        "com.alibaba.wireless.security.ddsec.Ddsec",
        "com.alibaba.security.SecurityBody"
    )

    @Deprecated("Use RISK_CONTROL_CLASS_PATTERNS instead. R8 obfuscation breaks these names.")
    val RISK_CONTROL_CLASS_NAMES = listOf(
        "com.alibaba.wireless.security.riskcontrol.RiskControlManager",
        "com.alibaba.security.riskcontrol.RiskControl",
        "com.alibaba.security.riskcontrol.RiskManager"
    )

    // ==================== Risk Control Method Names ====================
    val LBSWUA_REPORT_METHODS = listOf(
        "reportLbsData", "uploadLbsData", "sendLbsData",
        "reportLocationData", "reportData", "upload"
    )

    val DDSEC_GENERATE_METHODS = listOf(
        "generateDdsecData", "getDdsecData", "collectSecurityData",
        "getSecurityInfo", "getData", "collect"
    )

    val RISK_CHECK_METHODS = listOf(
        "checkRisk", "isDeviceRisk", "isEnvironmentRisk",
        "detectRisk", "isRisk", "check"
    )

    val ENCRYPT_METHODS = listOf(
        "encrypt", "sign", "getParamSig", "generateSign", "hash"
    )

    // ==================== ROOT Paths ====================
    val ROOT_PATHS = listOf(
        "/system/app/Superuser.apk", "/system/xbin/su", "/system/bin/su",
        "/sbin/su", "/data/local/xbin/su", "/data/local/bin/su",
        "/data/local/su", "/su/bin/su", "/system/su",
        "/system/framework/com.android.internal.os.ZygoteInit",
        "/system/lib/libhide.so"
    )

    val MAGISK_PATHS = listOf(
        "/sbin/magisk", "/data/adb/magisk", "/data/adb/magisk.img",
        "/data/adb/modules", "/data/adb/magisk.db", "/data/adb/lspd",
        "/data/misc/lspd", "/data/adb/zygisk"
    )

    val XPOSED_PATHS = listOf(
        "/system/framework/XposedBridge.jar",
        "/system/lib/libxposed_art.so", "/system/lib64/libxposed_art.so",
        "/data/data/de.robv.android.xposed.installer",
        "/data/data/org.meowcat.edxposed.manager",
        "/data/data/org.lsposed.manager",
        "/data/data/io.github.lsposed.manager"
    )

    // ==================== App Hiding ====================
    val HIDDEN_PACKAGES = setOf(
        "com.dingtalk.helper",
        "com.noobexon.xposedfakelocation", "com.ella.portal",
        "com.github.fakelocation", "com.csgo.fkgps", "com.xposed.fakelocation",
        "org.lsposed.manager", "de.robv.android.xposed.installer",
        "org.meowcat.edxposed.manager", "io.github.lsposed.manager",
        "com.topjohnwu.magisk",
        "com.lbe.parallel", "io.virtualapp", "com.excean.dualaid",
        "com.ludashi.dualspace", "com.parallel.space.lite", "com.parallel.space.mini",
        "com.topjohnwu.magiskelta", "io.github.vvb2060.magisk",
        "io.github.huskydg.magisk", "me.weishu.kernelsu",
        "com.tsng.hidemyapplist", "com.tsng.hidemyapplist.xposed"
    )

    val HIDDEN_PATH_PREFIXES = listOf(
        "/data/adb/magisk", "/data/adb/modules", "/data/adb/lspd",
        "/data/misc/lspd", "/data/data/org.lsposed.manager",
        "/data/data/com.topjohnwu.magisk", "/data/data/com.dingtalk.helper",
        "/data/adb/riru", "/data/misc/riru", "/data/adb/zygisk",
        "/data/user/0/com.dingtalk.helper", "/data/user/10/com.dingtalk.helper",
        "/sbin/magisk", "/sbin/su"
    )

    // ==================== ROM Types ====================
    val MIUI_MANUFACTURERS = listOf("xiaomi", "redmi", "poco", "blackshark")
    val COLOROS_MANUFACTURERS = listOf("oppo", "oneplus", "realme")
    val ORIGINOS_MANUFACTURERS = listOf("vivo", "iqoo")
    val ONEUI_MANUFACTURERS = listOf("samsung")
    val HARMONYOS_MANUFACTURERS = listOf("huawei", "honor")

    // ==================== Emulator Detection ====================
    val EMULATOR_SENSOR_KEYWORDS = setOf(
        "goldfish", "ranchu", "emulator", "qemu", "vbox",
        "genymotion", "nox", "bluestacks", "ldplayer"
    )

    val EMULATOR_FILES = setOf(
        "/dev/qemu_pipe", "/dev/qemud", "/dev/socket/qemud",
        "/dev/socket/genyd", "/dev/socket/baseband_genyd",
        "/sys/qemu_trace", "/system/bin/qemu-props", "/dev/avf_guest",
        "/system/etc/init.goldfish.rc",
        "/system/lib/libc_malloc_debug_qemu.so",
        "/system/lib64/libc_malloc_debug_qemu.so"
    )

    val EMULATOR_HARDWARE_VALUES = listOf("goldfish", "ranchu", "vbox86")
    val EMULATOR_BRAND_VALUES = listOf("generic", "generic_x86", "generic_x86_64")
    val EMULATOR_DEVICE_VALUES = listOf("generic", "vbox86p", "generic_x86", "generic_x86_64")
    val EMULATOR_MODEL_KEYWORDS = listOf("sdk", "google_sdk", "android sdk", "emulator")
    val EMULATOR_FINGERPRINT_KEYWORDS = listOf("generic", "vbox", "nox", "test-keys")
    val EMULATOR_BUILD_TAGS = listOf("test-keys")
    val EMULATOR_BUILD_TYPES = listOf("userdebug", "eng")

    val EMULATOR_SYSTEM_PROPERTIES = mapOf(
        "ro.kernel.qemu" to "1", "ro.boot.qemu" to "1",
        "ro.hardware" to "ranchu", "ro.product.model" to "sdk",
        "ro.product.device" to "generic", "ro.debuggable" to "1"
    )

    const val EMULATOR_IMEI_DEFAULT = "000000000000000"
    const val EMULATOR_IMSI_DEFAULT = "310260000000000"
    const val EMULATOR_PHONE_NUMBER_DEFAULT = "15555215554"
    const val EMULATOR_OPERATOR_NAME_DEFAULT = "Android"
    const val EMULATOR_OPERATOR_CODE_DEFAULT = "310260"
    const val EMULATOR_IP_DEFAULT = "10.0.2.15"
    const val EMULATOR_DNS_DEFAULT = "10.0.2.3"
    const val EMULATOR_GATEWAY_DEFAULT = "10.0.2.2"
}
