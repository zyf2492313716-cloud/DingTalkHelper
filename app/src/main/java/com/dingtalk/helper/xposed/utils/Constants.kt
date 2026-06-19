package com.dingtalk.helper.xposed.utils

/**
 * 常量管理
 * 集中管理所有硬编码的类名、方法名、配置键等
 */
object Constants {

    // ==================== 调试配置 ====================
    const val DEBUG_MODE = false
    const val LOG_PREFIX = "DingTalkHelper"

    // ==================== 目标应用 ====================
    const val DINGTALK_PACKAGE = "com.alibaba.android.rimet"

    // ==================== SharedPreferences 配置 ====================
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

    // ==================== 默认值 ====================
    const val DEFAULT_LATITUDE = 39.9042  // 北京
    const val DEFAULT_LONGITUDE = 116.4074
    const val DEFAULT_ALTITUDE = 0.0
    const val DEFAULT_MCC = 460
    const val DEFAULT_MNC = 0
    const val DEFAULT_ACCURACY = 10f

    // ==================== 风控相关类名 ====================
    // lbswua 位置服务风控
    val LBSWUA_CLASS_NAMES = listOf(
        "com.alibaba.wireless.security.securitybody.SecurityGuardManager",
        "com.alibaba.security.lbswua.LbswuaManager",
        "com.alibaba.wireless.security.lbswua.Lbswua",
        "com.alibaba.security.SecurityGuard"
    )

    // ddsec 安全风控
    val DDSEC_CLASS_NAMES = listOf(
        "com.alibaba.wireless.security.securitybody.SecurityBody",
        "com.alibaba.security.ddsec.DdsecManager",
        "com.alibaba.wireless.security.ddsec.Ddsec",
        "com.alibaba.security.SecurityBody"
    )

    // 风控检测
    val RISK_CONTROL_CLASS_NAMES = listOf(
        "com.alibaba.wireless.security.riskcontrol.RiskControlManager",
        "com.alibaba.security.riskcontrol.RiskControl",
        "com.alibaba.security.riskcontrol.RiskManager"
    )

    // ==================== 风控相关方法名 ====================
    val LBSWUA_REPORT_METHODS = listOf(
        "reportLbsData",
        "uploadLbsData",
        "sendLbsData",
        "reportLocationData",
        "reportData",
        "upload"
    )

    val DDSEC_GENERATE_METHODS = listOf(
        "generateDdsecData",
        "getDdsecData",
        "collectSecurityData",
        "getSecurityInfo",
        "getData",
        "collect"
    )

    val RISK_CHECK_METHODS = listOf(
        "checkRisk",
        "isDeviceRisk",
        "isEnvironmentRisk",
        "detectRisk",
        "isRisk",
        "check"
    )

    val ENCRYPT_METHODS = listOf(
        "encrypt",
        "sign",
        "getParamSig",
        "generateSign",
        "hash"
    )

    // ==================== ROOT 相关路径 ====================
    val ROOT_PATHS = listOf(
        "/system/app/Superuser.apk",
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/system/su",
        "/system/framework/com.android.internal.os.ZygoteInit",
        "/system/lib/libhide.so"
    )

    // Magisk 相关路径
    val MAGISK_PATHS = listOf(
        "/sbin/magisk",
        "/data/adb/magisk",
        "/data/adb/magisk.img",
        "/data/adb/modules",
        "/data/adb/magisk.db",
        "/data/adb/lspd",
        "/data/misc/lspd"
    )

    // Xposed 相关路径
    val XPOSED_PATHS = listOf(
        "/system/framework/XposedBridge.jar",
        "/system/lib/libxposed_art.so",
        "/system/lib64/libxposed_art.so",
        "/data/data/de.robv.android.xposed.installer",
        "/data/data/org.meowcat.edxposed.manager",
        "/data/data/org.lsposed.manager",
        "/data/data/io.github.lsposed.manager"
    )

    // ==================== 应用隐藏相关 ====================
    val HIDDEN_PACKAGES = setOf(
        // 本模块
        "com.dingtalk.helper",

        // 虚拟定位工具
        "com.noobexon.xposedfakelocation",
        "com.ella.portal",
        "com.github.fakelocation",
        "com.csgo.fkgps",
        "com.xposed.fakelocation",

        // Xposed/LSPosed
        "org.lsposed.manager",
        "de.robv.android.xposed.installer",
        "org.meowcat.edxposed.manager",
        "io.github.lsposed.manager",

        // Magisk
        "com.topjohnwu.magisk",

        // 分身应用
        "com.lbe.parallel",
        "io.virtualapp",
        "com.excean.dualaid",
        "com.ludashi.dualspace",
        "com.parallel.space.lite",
        "com.parallel.space.mini"
    )

    // 需要隐藏的文件路径前缀
    val HIDDEN_PATH_PREFIXES = listOf(
        "/data/adb/magisk",
        "/data/adb/modules",
        "/data/adb/lspd",
        "/data/misc/lspd",
        "/data/data/org.lsposed.manager",
        "/data/data/com.topjohnwu.magisk",
        "/data/data/com.dingtalk.helper"
    )

    // ==================== 厂商 ROM 类型 ====================
    val MIUI_MANUFACTURERS = listOf("xiaomi", "redmi", "poco", "blackshark")
    val COLOROS_MANUFACTURERS = listOf("oppo", "oneplus", "realme")
    val ORIGINOS_MANUFACTURERS = listOf("vivo", "iqoo")
    val ONEUI_MANUFACTURERS = listOf("samsung")
    val HARMONYOS_MANUFACTURERS = listOf("huawei", "honor")

    // ==================== 模拟器检测相关 ====================
    // 模拟器传感器特征关键词
    val EMULATOR_SENSOR_KEYWORDS = setOf(
        "goldfish", "ranchu", "emulator", "qemu", "vbox",
        "genymotion", "nox", "bluestacks", "ldplayer"
    )

    // 模拟器文件特征路径
    val EMULATOR_FILES = setOf(
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

    // 模拟器 Build 特征值
    val EMULATOR_HARDWARE_VALUES = listOf("goldfish", "ranchu", "vbox86")
    val EMULATOR_BRAND_VALUES = listOf("generic", "generic_x86", "generic_x86_64")
    val EMULATOR_DEVICE_VALUES = listOf("generic", "vbox86p", "generic_x86", "generic_x86_64")
    val EMULATOR_MODEL_KEYWORDS = listOf("sdk", "google_sdk", "android sdk", "emulator")
    val EMULATOR_FINGERPRINT_KEYWORDS = listOf("generic", "vbox", "nox", "test-keys")
    val EMULATOR_BUILD_TAGS = listOf("test-keys")
    val EMULATOR_BUILD_TYPES = listOf("userdebug", "eng")

    // 模拟器系统属性
    val EMULATOR_SYSTEM_PROPERTIES = mapOf(
        "ro.kernel.qemu" to "1",
        "ro.boot.qemu" to "1",
        "ro.hardware" to "ranchu",
        "ro.product.model" to "sdk",
        "ro.product.device" to "generic",
        "ro.debuggable" to "1"
    )

    // 模拟器 TelephonyManager 默认值
    const val EMULATOR_IMEI_DEFAULT = "000000000000000"
    const val EMULATOR_IMSI_DEFAULT = "310260000000000"
    const val EMULATOR_PHONE_NUMBER_DEFAULT = "15555215554"
    const val EMULATOR_OPERATOR_NAME_DEFAULT = "Android"
    const val EMULATOR_OPERATOR_CODE_DEFAULT = "310260"

    // 模拟器网络特征
    const val EMULATOR_IP_DEFAULT = "10.0.2.15"
    const val EMULATOR_DNS_DEFAULT = "10.0.2.3"
    const val EMULATOR_GATEWAY_DEFAULT = "10.0.2.2"
}