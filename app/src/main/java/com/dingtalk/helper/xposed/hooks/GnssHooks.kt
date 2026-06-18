package com.dingtalk.helper.xposed.hooks

import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssStatus
import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * GNSS 卫星数据伪造 Hook
 * 提供伪造的 GPS 卫星数据以保持一致性
 *
 * 借鉴 XposedFakeLocation 的 GNSS 伪造思路：
 * - Hook GnssStatus 返回伪造的卫星数量和信号数据
 * - 阻止 GNSS 测量数据回调避免泄露真实信息
 * - 拦截导航消息回调
 */
class GnssHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:GNSS"

        // 模拟的卫星配置
        private const val SATELLITE_COUNT = 12
        private const val BASE_CN0 = 35.0f
        private const val BASE_ELEVATION = 45.0f
        private const val BASE_AZIMUTH_STEP = 30.0f
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isEnabled()) return

        HookUtils.log("$TAG: 开始注入 GNSS 数据伪造 Hook")

        hookGnssStatus(lpparam)
        hookGnssMeasurements(lpparam)
        hookGnssNavigationMessage(lpparam)

        HookUtils.log("$TAG: GNSS Hook 注入完成")
    }

    /**
     * Hook GnssStatus - 返回伪造的卫星状态
     */
    private fun hookGnssStatus(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val gnssStatusClass = XposedHelpers.findClass(
                "android.location.GnssStatus", lpparam.classLoader
            )

            // 卫星数量
            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getSatelliteCount",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = SATELLITE_COUNT
                }
            )

            // 卫星编号
            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getSvid", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return (param.args[0] as Int) + 1
                    }
                }
            )

            // 信噪比
            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getCn0DbHz", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return BASE_CN0 + (Math.random() * 10 - 5).toFloat()
                    }
                }
            )

            // 仰角
            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getElevationDegrees", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return BASE_ELEVATION + (Math.random() * 30 - 15).toFloat()
                    }
                }
            )

            // 方位角
            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getAzimuthDegrees", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val index = param.args[0] as Int
                        return (index * BASE_AZIMUTH_STEP + Math.random() * 20).toFloat() % 360
                    }
                }
            )

            // 是否用于定位（前8颗）
            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "usedInFix", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return (param.args[0] as Int) < 8
                    }
                }
            )

            // 星历数据
            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "hasEphemerisData", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = true
                }
            )

            // 年历数据
            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "hasAlmanacData", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = true
                }
            )

            HookUtils.log("$TAG: GnssStatus Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: GnssStatus Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook GnssMeasurementsEvent - 阻止真实测量数据
     */
    private fun hookGnssMeasurements(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        try {
            val eventClass = XposedHelpers.findClass(
                "android.location.GnssMeasurementsEvent", lpparam.classLoader
            )

            // 返回空测量列表
            XposedHelpers.findAndHookMethod(
                eventClass, "getMeasurements",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = emptyList<GnssMeasurement>()
                    }
                }
            )

            // Hook GnssClock
            hookGnssClock(lpparam)

            HookUtils.log("$TAG: GnssMeasurementsEvent Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: GnssMeasurementsEvent Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook GnssClock - 返回合理的时间数据
     */
    private fun hookGnssClock(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clockClass = XposedHelpers.findClass(
                "android.location.GnssClock", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                clockClass, "getTimeNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return System.nanoTime()
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                clockClass, "hasTimeUncertaintyNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = true
                }
            )

            XposedHelpers.findAndHookMethod(
                clockClass, "getTimeUncertaintyNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = 50.0
                }
            )

            HookUtils.log("$TAG: GnssClock Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: GnssClock Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook GnssNavigationMessage - 阻止导航消息回调
     */
    private fun hookGnssNavigationMessage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        try {
            val locationManagerClass = XposedHelpers.findClass(
                "android.location.LocationManager", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                locationManagerClass,
                "registerGnssNavigationMessageCallback",
                android.location.GnssNavigationMessage.Callback::class.java,
                android.os.Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                }
            )

            HookUtils.log("$TAG: GnssNavigationMessage Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: GnssNavigationMessage Hook 失败: ${e.message}")
        }
    }
}
