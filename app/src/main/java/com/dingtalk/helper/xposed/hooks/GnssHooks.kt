package com.dingtalk.helper.xposed.hooks

import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.data.FakeDataProvider
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
 * - 返回伪造的 GNSS 测量数据（多星座卫星）
 * - 返回伪造的导航消息数据
 */
class GnssHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:GNSS"

        private const val GPS_EPOCH_OFFSET_SECONDS = 315964800L
        private const val STATE_CODE_LOCK = 1
        private const val STATE_TOW_DECODED = 2
        private const val STATE_BIT_SYNC = 4
        private const val STATE_SUBFRAME_SYNC = 8
        private const val FULL_STATE = STATE_CODE_LOCK or STATE_TOW_DECODED or STATE_BIT_SYNC or STATE_SUBFRAME_SYNC

        private const val FULL_BIAS_BASE_GPS_NANOS = (GPS_EPOCH_OFFSET_SECONDS * 1_000_000_000L)
        private const val FULL_BIAS_DRIFT_PER_DAY_NANOS = 5L

        // 类级别的 ScheduledExecutorService，避免每次注册都创建新 executor
        private val scheduledExecutor: java.util.concurrent.ScheduledExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "GnssNavMsg-Scheduler").apply { isDaemon = true }
            }
        }

        // 跟踪活跃的定时任务，用于取消
        private val activeNavMessageTasks = java.util.concurrent.ConcurrentHashMap<GnssNavigationMessage.Callback, java.util.concurrent.ScheduledFuture<*>>()
    }

    private val satellites: List<FakeDataProvider.SatelliteInfo>
        get() = FakeDataProvider.getSatellites()

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isEnabled()) return

        HookUtils.log("$TAG: 开始注入 GNSS 数据伪造 Hook")

        hookGnssStatus(lpparam)
        hookGnssMeasurements(lpparam)
        hookGnssMeasurement(lpparam)
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

            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getSatelliteCount",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = satellites.size
                }
            )

            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getSvid", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val index = param.args[0] as Int
                        return satellites.getOrElse(index) { satellites.last() }.svid
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getConstellationType", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return satellites[param.args[0] as Int].constellationType
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getCn0DbHz", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val base = satellites[param.args[0] as Int].cn0
                        return base + (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 4f - 2f).toFloat()
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getElevationDegrees", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return satellites[param.args[0] as Int].elevation
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getAzimuthDegrees", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return satellites[param.args[0] as Int].azimuth
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "usedInFix", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return satellites[param.args[0] as Int].usedInFix
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "hasCarrierFrequencyHz", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return satellites[param.args[0] as Int].usedInFix
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getCarrierFrequencyHz", Int::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return satellites[param.args[0] as Int].carrierFrequencyHz
                    }
                }
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                XposedHelpers.findAndHookMethod(
                    gnssStatusClass, "hasBasebandCn0DbHz", Int::class.java,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any {
                            return satellites[param.args[0] as Int].usedInFix
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    gnssStatusClass, "getBasebandCn0DbHz", Int::class.java,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any {
                            val baseCn0 = satellites[param.args[0] as Int].cn0
                            val loss = 3f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 3f
                            return baseCn0 - loss
                        }
                    }
                )
            }

            XposedHelpers.findAndHookMethod(
                gnssStatusClass, "getUsedInFixCount",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return satellites.count { it.usedInFix }
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

            XposedHelpers.findAndHookMethod(
                eventClass, "getMeasurements",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val measurements = satellites.mapIndexed { index, sat ->
                            createFakeGnssMeasurement(sat, index)
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
     * Hook GnssMeasurement - 返回伪造的 AGC 值
     */
    private fun hookGnssMeasurement(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val measurementClass = XposedHelpers.findClass(
                "android.location.GnssMeasurement", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                measurementClass, "getAutomaticGainControlLevelDb",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return 25f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 20f
                    }
                }
            )

            HookUtils.log("$TAG: GnssMeasurement Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: GnssMeasurement Hook 失败: ${e.message}")
        }
    }

    /**
     * 创建伪造的 GnssMeasurement 对象
     */
    private fun createFakeGnssMeasurement(sat: FakeDataProvider.SatelliteInfo, index: Int): GnssMeasurement {
        val cn0 = sat.cn0 + (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 4f - 2f).toFloat()
        val pseudorangeRate = java.util.Random(index.toLong() + 1000).nextGaussian() * 200.0
        val gpsTimeNanos = (System.currentTimeMillis() / 1000 - GPS_EPOCH_OFFSET_SECONDS) * 1_000_000_000L
        val receivedSvTime = gpsTimeNanos + (index * 100_000L)
        val agc = 25f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 20f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val builderClass = XposedHelpers.findClass("android.location.GnssMeasurement\$Builder", null)
                val builder = builderClass.getConstructor().newInstance()
                XposedHelpers.callMethod(builder, "setSvid", sat.svid)
                XposedHelpers.callMethod(builder, "setConstellationType", sat.constellationType)
                XposedHelpers.callMethod(builder, "setCn0DbHz", cn0)
                XposedHelpers.callMethod(builder, "setTimeOffsetNanos", 0.0)
                XposedHelpers.callMethod(builder, "setPseudorangeRateMetersPerSecond", pseudorangeRate)
                XposedHelpers.callMethod(builder, "setReceivedSvTimeNanos", receivedSvTime)
                XposedHelpers.callMethod(builder, "setState", FULL_STATE)
                XposedHelpers.callMethod(builder, "setMultipathIndicator", 0)
                XposedHelpers.callMethod(builder, "setCarrierFrequencyHz", sat.carrierFrequencyHz)
                XposedHelpers.callMethod(builder, "setAutomaticGainControlLevelDb", agc)
                return XposedHelpers.callMethod(builder, "build") as GnssMeasurement
            } catch (_: Exception) {}
        }

        val measurement = XposedHelpers.newInstance(GnssMeasurement::class.java) as GnssMeasurement
        XposedHelpers.setIntField(measurement, "mSvid", sat.svid)
        XposedHelpers.setIntField(measurement, "mConstellationType", sat.constellationType)
        XposedHelpers.setFloatField(measurement, "mCn0DbHz", cn0)
        XposedHelpers.setDoubleField(measurement, "mTimeOffsetNanos", 0.0)
        XposedHelpers.setDoubleField(measurement, "mPseudorangeRateMetersPerSecond", pseudorangeRate)
        XposedHelpers.setLongField(measurement, "mReceivedSvTimeNanos", receivedSvTime)
        XposedHelpers.setIntField(measurement, "mState", FULL_STATE)
        XposedHelpers.setIntField(measurement, "mMultipathIndicator", 0)
        XposedHelpers.setFloatField(measurement, "mCarrierFrequencyHz", sat.carrierFrequencyHz)
        XposedHelpers.setFloatField(measurement, "mAutomaticGainControlLevelDb", agc)
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

            XposedHelpers.findAndHookMethod(
                clockClass, "getTimeNanos",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val gpsSeconds = System.currentTimeMillis() / 1000 - GPS_EPOCH_OFFSET_SECONDS
                        val jitter = (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 100).toLong()
                        return gpsSeconds * 1_000_000_000L + jitter
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
                        val daysSinceEpoch = (System.currentTimeMillis() / 1000 - GPS_EPOCH_OFFSET_SECONDS) / 86400
                        return FULL_BIAS_BASE_GPS_NANOS + daysSinceEpoch * FULL_BIAS_DRIFT_PER_DAY_NANOS
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
                        return java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 0.1
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                clockClass, "hasLeapSecond",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = true
                }
            )

            XposedHelpers.findAndHookMethod(
                clockClass, "getLeapSecond",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = 18
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

                    val targetExecutor = executor ?: handler?.let { h ->
                        java.util.concurrent.Executor { r -> h.post(r) }
                    } ?: java.util.concurrent.Executor { r ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post(r)
                    }

                    val deliver = {
                        try {
                            val fakeMessage = createFakeGnssNavigationMessage()
                            callback.onGnssNavigationMessageReceived(fakeMessage)
                        } catch (e: Exception) {
                            HookUtils.log("$TAG: 发送伪造导航消息失败: ${e.message}")
                        }
                    }

                    targetExecutor.execute { deliver() }

                    // 取消该 callback 之前可能存在的定时任务
                    activeNavMessageTasks[callback]?.cancel(false)

                    // 使用类级别的 scheduledExecutor，避免每次创建新 executor
                    val future = scheduledExecutor.scheduleAtFixedRate({
                        try { targetExecutor.execute { deliver() } } catch (_: Exception) {}
                    }, 1000, 1000, java.util.concurrent.TimeUnit.MILLISECONDS)

                    // 记录活跃任务
                    activeNavMessageTasks[callback] = future
                }
            }

            // Hook unregisterGnssNavigationMessageCallback - 取消定时任务
            try {
                XposedHelpers.findAndHookMethod(
                    locationManagerClass,
                    "unregisterGnssNavigationMessageCallback",
                    GnssNavigationMessage.Callback::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val callback = param.args[0] as? GnssNavigationMessage.Callback ?: return
                            activeNavMessageTasks.remove(callback)?.cancel(false)
                        }
                    }
                )
                HookUtils.log("$TAG: unregisterGnssNavigationMessageCallback Hook 完成")
            } catch (e: Exception) {
                HookUtils.log("$TAG: unregisterGnssNavigationMessageCallback Hook 失败: ${e.message}")
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

        val sat = satellites.random()
        XposedHelpers.setIntField(message, "mSvid", sat.svid)
        XposedHelpers.setIntField(message, "mConstellationType", sat.constellationType)

        XposedHelpers.setIntField(message, "mType", 1)

        XposedHelpers.setIntField(message, "mStatus", 1)

        val data = ByteArray(24)
        (0 until 24).forEach { i ->
            data[i] = java.util.concurrent.ThreadLocalRandom.current().nextInt(256).toByte()
        }
        XposedHelpers.setObjectField(message, "mData", data)

        XposedHelpers.setIntField(message, "mMessageId", (1..10).random())

        XposedHelpers.setIntField(message, "mSubmessageId", (1..5).random())

        return message
    }
}
