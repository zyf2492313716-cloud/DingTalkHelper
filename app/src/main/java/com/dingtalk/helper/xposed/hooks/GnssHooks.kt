package com.dingtalk.helper.xposed.hooks

import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * GNSS 卫星数据伪造 Hook
 * 借鉴 XposedFakeLocation 项目
 * 提供完整的 GPS 卫星数据以保持一致性
 */
class GnssHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${HookEntry.TAG}:GNSS"

        // 模拟的卫星配置
        private const val SATELLITE_COUNT = 12
        private const val BASE_CN0 = 35.0f // 信噪比
        private const val BASE_ELEVATION = 45.0f // 仰角
        private const val BASE_AZIMUTH_STEP = 30.0f // 方位角间隔
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG: 开始注入 GNSS 数据伪造 Hook")

        // Hook GnssStatus
        hookGnssStatus(lpparam)

        // Hook GnssMeasurementsEvent
        hookGnssMeasurements(lpparam)

        // Hook GnssClock
        hookGnssClock(lpparam)

        // Hook GnssNavigationMessage
        hookGnssNavigationMessage(lpparam)
    }

    /**
     * Hook GnssStatus
     * 提供伪造的卫星状态信息
     */
    private fun hookGnssStatus(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook GnssStatus.Callback
            val callbackClass = XposedHelpers.findClass(
                "android.location.GnssStatus\$Callback",
                lpparam.classLoader
            )

            // Hook GnssManagerService
            val gnssManagerClass = XposedHelpers.findClass(
                "android.location.GnssStatus",
                lpparam.classLoader
            )

            // Hook getSatelliteCount
            XposedHelpers.findAndHookMethod(
                gnssManagerClass,
                "getSatelliteCount",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return SATELLITE_COUNT
                    }
                }
            )

            // Hook getSvid (卫星编号)
            XposedHelpers.findAndHookMethod(
                gnssManagerClass,
                "getSvid",
                Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val index = param.args[0] as Int
                        return index + 1 // 返回 1-12 的卫星编号
                    }
                }
            )

            // Hook getCn0DbHz (信噪比)
            XposedHelpers.findAndHookMethod(
                gnssManagerClass,
                "getCn0DbHz",
                Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val index = param.args[0] as Int
                        // 返回带有随机噪声的信噪比
                        return BASE_CN0 + (Math.random() * 10 - 5).toFloat()
                    }
                }
            )

            // Hook getElevationDegrees (仰角)
            XposedHelpers.findAndHookMethod(
                gnssManagerClass,
                "getElevationDegrees",
                Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val index = param.args[0] as Int
                        return BASE_ELEVATION + (Math.random() * 30 - 15).toFloat()
                    }
                }
            )

            // Hook getAzimuthDegrees (方位角)
            XposedHelpers.findAndHookMethod(
                gnssManagerClass,
                "getAzimuthDegrees",
                Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val index = param.args[0] as Int
                        return (index * BASE_AZIMUTH_STEP + Math.random() * 20).toFloat() % 360
                    }
                }
            )

            // Hook usedInFix
            XposedHelpers.findAndHookMethod(
                gnssManagerClass,
                "usedInFix",
                Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        // 前8颗卫星用于定位
                        val index = param.args[0] as Int
                        return index < 8
                    }
                }
            )

            // Hook hasEphemerisData
            XposedHelpers.findAndHookMethod(
                gnssManagerClass,
                "hasEphemerisData",
                Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return true
                    }
                }
            )

            // Hook hasAlmanacData
            XposedHelpers.findAndHookMethod(
                gnssManagerClass,
                "hasAlmanacData",
                Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return true
                    }
                }
            )

            XposedBridge.log("$TAG: GnssStatus Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: GnssStatus Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook GnssMeasurementsEvent
     * 提供伪造的 GNSS 测量数据
     */
    private fun hookGnssMeasurements(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return
            }

            val eventClass = XposedHelpers.findClass(
                "android.location.GnssMeasurementsEvent",
                lpparam.classLoader
            )

            // Hook getMeasurements
            XposedHelpers.findAndHookMethod(
                eventClass,
                "getMeasurements",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 返回空列表或伪造的测量数据
                        param.result = emptyList<GnssMeasurement>()
                        XposedBridge.log("$TAG: GnssMeasurementsEvent 已拦截")
                    }
                }
            )

            // Hook getClock
            XposedHelpers.findAndHookMethod(
                eventClass,
                "getClock",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 返回伪造的时钟数据
                        val clock = createFakeGnssClock()
                        param.result = clock
                        XposedBridge.log("$TAG: GnssClock 已替换")
                    }
                }
            )

            XposedBridge.log("$TAG: GnssMeasurementsEvent Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: GnssMeasurementsEvent Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook GnssClock
     */
    private fun hookGnssClock(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return
            }

            val clockClass = XposedHelpers.findClass(
                "android.location.GnssClock",
                lpparam.classLoader
            )

            // Hook getTimeNanos
            XposedHelpers.findAndHookMethod(
                clockClass,
                "getTimeNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return System.nanoTime()
                    }
                }
            )

            // Hook hasTimeUncertaintyNanos
            XposedHelpers.findAndHookMethod(
                clockClass,
                "hasTimeUncertaintyNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return true
                    }
                }
            )

            // Hook getTimeUncertaintyNanos
            XposedHelpers.findAndHookMethod(
                clockClass,
                "getTimeUncertaintyNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return 50.0 // 50 纳秒不确定度
                    }
                }
            )

            XposedBridge.log("$TAG: GnssClock Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: GnssClock Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook GnssNavigationMessage
     */
    private fun hookGnssNavigationMessage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return
            }

            val locationManagerClass = XposedHelpers.findClass(
                "android.location.LocationManager",
                lpparam.classLoader
            )

            // 阻止注册导航消息回调
            XposedHelpers.findAndHookMethod(
                locationManagerClass,
                "registerGnssNavigationMessageCallback",
                android.location.GnssNavigationMessage.Callback::class.java,
                android.os.Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 返回 false 表示注册失败
                        param.result = false
                        XposedBridge.log("$TAG: GnssNavigationMessage 回调已阻止")
                    }
                }
            )

            XposedBridge.log("$TAG: GnssNavigationMessage Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: GnssNavigationMessage Hook 失败: ${e.message}")
        }
    }

    /**
     * 创建伪造的 GnssClock
     */
    private fun createFakeGnssClock(): Any? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val clockClass = GnssClock::class.java
                val constructor = clockClass.getDeclaredConstructor()
                constructor.isAccessible = true
                val clock = constructor.newInstance()

                // 设置时间
                val timeField = clockClass.getDeclaredField("mTimeNanos")
                timeField.isAccessible = true
                timeField.setLong(clock, System.nanoTime())

                // 设置不确定度
                val uncertaintyField = clockClass.getDeclaredField("mTimeUncertaintyNanos")
                uncertaintyField.isAccessible = true
                uncertaintyField.setDouble(clock, 50.0)

                clock
            } else {
                null
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG: 创建伪造 GnssClock 失败: ${e.message}")
            null
        }
    }
}