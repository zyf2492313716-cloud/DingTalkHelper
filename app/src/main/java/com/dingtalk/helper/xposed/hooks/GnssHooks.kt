package com.dingtalk.helper.xposed.hooks

import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssNavigationMessage
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
 * - 返回伪造的 GNSS 测量数据（8-12颗卫星）
 * - 返回伪造的导航消息数据
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
     * Hook GnssMeasurementsEvent - 返回伪造的测量数据
     */
    private fun hookGnssMeasurements(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        try {
            val eventClass = XposedHelpers.findClass(
                "android.location.GnssMeasurementsEvent", lpparam.classLoader
            )

            // 返回伪造的测量数据列表
            XposedHelpers.findAndHookMethod(
                eventClass, "getMeasurements",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val measurements = (1..SATELLITE_COUNT).map { svid ->
                            createFakeGnssMeasurement(svid)
                        }
                        param.result = measurements
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
     * 创建伪造的 GnssMeasurement 对象
     */
    private fun createFakeGnssMeasurement(svid: Int): GnssMeasurement {
        val measurement = XposedHelpers.newInstance(GnssMeasurement::class.java) as GnssMeasurement
        
        // 设置卫星基本信息
        XposedHelpers.setIntField(measurement, "mSvid", svid)
        XposedHelpers.setIntField(measurement, "mConstellationType", 1) // GPS
        
        // 设置信噪比 (25-45 dB-Hz)
        val cn0 = (25..45).random().toFloat()
        XposedHelpers.setFloatField(measurement, "mCn0DbHz", cn0)
        
        // 设置时间偏移保持一致性
        XposedHelpers.setDoubleField(measurement, "mTimeOffsetNanos", 0.0)
        
        // 设置伪距率 (模拟卫星运动)
        val pseudorangeRate = (Math.random() * 1000 - 500).toDouble()
        XposedHelpers.setDoubleField(measurement, "mPseudorangeRateMetersPerSecond", pseudorangeRate)
        
        // 设置接收卫星时间
        val receivedSvTime = System.currentTimeMillis() * 1_000_000L
        XposedHelpers.setLongField(measurement, "mReceivedSvTimeNanos", receivedSvTime)
        
        // 设置状态为已锁定
        XposedHelpers.setIntField(measurement, "mState", 1) // STATE_CODE_LOCK
        
        // 设置多路径指示器为无多路径
        XposedHelpers.setIntField(measurement, "mMultipathIndicator", 0) // MULTIPATH_INDICATOR_NOT_DETECTED
        
        return measurement
    }

    /**
     * Hook GnssClock - 返回与系统时间一致的时间数据
     */
    private fun hookGnssClock(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clockClass = XposedHelpers.findClass(
                "android.location.GnssClock", lpparam.classLoader
            )

            // 返回与系统时间一致的 GPS 时间（纳秒）
            XposedHelpers.findAndHookMethod(
                clockClass, "getTimeNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        // 系统时间转换为纳秒，并添加小的随机抖动模拟真实时钟
                        val baseTime = System.currentTimeMillis() * 1_000_000L
                        val jitter = (Math.random() * 1000).toLong() // 0-1000 纳秒抖动
                        return baseTime + jitter
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

            // 设置完整的时钟偏差信息以保持一致性
            XposedHelpers.findAndHookMethod(
                clockClass, "hasFullBiasNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = true
                }
            )

            XposedHelpers.findAndHookMethod(
                clockClass, "getFullBiasNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        // GPS 时间与系统时间的偏差
                        return System.currentTimeMillis() * 1_000_000L - System.nanoTime()
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                clockClass, "hasBiasNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = true
                }
            )

            XposedHelpers.findAndHookMethod(
                clockClass, "getBiasNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        // 小的亚纳秒级偏差
                        return Math.random() * 0.1
                    }
                }
            )

            HookUtils.log("$TAG: GnssClock Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: GnssClock Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook GnssNavigationMessage - 返回伪造的导航消息数据
     */
    private fun hookGnssNavigationMessage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        try {
            val locationManagerClass = XposedHelpers.findClass(
                "android.location.LocationManager", lpparam.classLoader
            )

            val hookCallback = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                    val callback = param.args.firstOrNull {
                        it is GnssNavigationMessage.Callback
                    } as? GnssNavigationMessage.Callback ?: return
                    val handler = param.args.firstOrNull {
                        it is android.os.Handler
                    } as? android.os.Handler
                    val executor = param.args.firstOrNull {
                        it is java.util.concurrent.Executor
                    } as? java.util.concurrent.Executor

                    val fakeMessage = createFakeGnssNavigationMessage()

                    val runnable = Runnable {
                        try {
                            callback.onGnssNavigationMessageReceived(fakeMessage)
                        } catch (e: Exception) {
                            HookUtils.log("$TAG: 发送伪造导航消息失败: ${e.message}")
                        }
                    }

                    when {
                        handler != null -> handler.post(runnable)
                        executor != null -> executor.execute(runnable)
                        else -> android.os.Handler(android.os.Looper.getMainLooper()).post(runnable)
                    }
                }
            }

            // (Callback, Handler)
            try {
                XposedHelpers.findAndHookMethod(
                    locationManagerClass,
                    "registerGnssNavigationMessageCallback",
                    GnssNavigationMessage.Callback::class.java,
                    android.os.Handler::class.java,
                    hookCallback
                )
                HookUtils.log("$TAG: registerGnssNavigationMessageCallback(Callback, Handler) Hook 完成")
            } catch (e: NoSuchMethodError) {
                HookUtils.log("$TAG: registerGnssNavigationMessageCallback(Callback, Handler) 重载不存在")
            } catch (e: Exception) {
                HookUtils.log("$TAG: registerGnssNavigationMessageCallback(Callback, Handler) Hook 失败: ${e.message}")
            }

            // API 30+: (Executor, Callback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    XposedHelpers.findAndHookMethod(
                        locationManagerClass,
                        "registerGnssNavigationMessageCallback",
                        java.util.concurrent.Executor::class.java,
                        GnssNavigationMessage.Callback::class.java,
                        hookCallback
                    )
                    HookUtils.log("$TAG: registerGnssNavigationMessageCallback(Executor, Callback) Hook 完成")
                } catch (e: NoSuchMethodError) {
                    HookUtils.log("$TAG: registerGnssNavigationMessageCallback(Executor, Callback) 重载不存在")
                } catch (e: Exception) {
                    HookUtils.log("$TAG: registerGnssNavigationMessageCallback(Executor, Callback) Hook 失败: ${e.message}")
                }
            }

            HookUtils.log("$TAG: GnssNavigationMessage Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: GnssNavigationMessage Hook 失败: ${e.message}")
        }
    }

    /**
     * 创建伪造的 GnssNavigationMessage 对象
     */
    private fun createFakeGnssNavigationMessage(): GnssNavigationMessage {
        val message = XposedHelpers.newInstance(GnssNavigationMessage::class.java) as GnssNavigationMessage
        
        // 设置卫星编号 (1-32 for GPS)
        XposedHelpers.setIntField(message, "mSvid", (1..32).random())
        
        // 设置星座类型 (GPS = 1)
        XposedHelpers.setIntField(message, "mConstellationType", 1)
        
        // 设置消息类型 (导航消息类型)
        XposedHelpers.setIntField(message, "mType", 1) // GPS L1 C/A
        
        // 设置状态 (已同步)
        XposedHelpers.setIntField(message, "mStatus", 1) // STATUS_PARITY_PASSED
        
        // 设置伪造的子帧数据 (24 字节)
        val data = ByteArray(24)
        (0 until 24).forEach { i ->
            data[i] = (Math.random() * 256).toInt().toByte()
        }
        XposedHelpers.setObjectField(message, "mData", data)
        
        // 设置消息编号
        XposedHelpers.setIntField(message, "mMessageId", (1..10).random())
        
        // 设置子消息编号
        XposedHelpers.setIntField(message, "mSubmessageId", (1..5).random())
        
        return message
    }
}
