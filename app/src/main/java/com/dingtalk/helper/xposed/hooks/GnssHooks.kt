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

        private const val GNSS_CONSTELLATION_GPS = 1
        private const val GNSS_CONSTELLATION_SBAS = 2
        private const val GNSS_CONSTELLATION_GLONASS = 3
        private const val GNSS_CONSTELLATION_QZSS = 4
        private const val GNSS_CONSTELLATION_BEIDOU = 5
        private const val GNSS_CONSTELLATION_GALILEO = 6
        private const val GNSS_CONSTELLATION_IRNSS = 7

        private const val FULL_BIAS_BASE_GPS_NANOS = (GPS_EPOCH_OFFSET_SECONDS * 1_000_000_000L)
        private const val FULL_BIAS_DRIFT_PER_DAY_NANOS = 5L
    }

    private data class SatelliteInfo(
        val svid: Int,
        val constellationType: Int,
        val cn0: Float,
        val elevation: Float,
        val azimuth: Float,
        val usedInFix: Boolean
    )

    private val satellites: List<SatelliteInfo> by lazy { generateSatellites() }

    private fun generateSatellites(): List<SatelliteInfo> {
        val result = mutableListOf<SatelliteInfo>()
        val random = java.util.Random(42)
        val usedCount = 12 + random.nextInt(5)

        val gpsCount = 7 + random.nextInt(4)
        val glonassCount = 4 + random.nextInt(3)
        val beidouCount = 5 + random.nextInt(4)
        val galileoCount = 3 + random.nextInt(3)

        fun addSatellites(count: Int, constellation: Int, svidRange: IntRange) {
            val svids = svidRange.toList().shuffled(random).take(count)
            for (svid in svids) {
                val cn0 = (28f + random.nextFloat() * 18f)
                val elevation = (10f + random.nextFloat() * 70f)
                val azimuth = (random.nextFloat() * 360f)
                result.add(SatelliteInfo(svid, constellation, cn0, elevation, azimuth, result.size < usedCount))
            }
        }

        addSatellites(gpsCount, GNSS_CONSTELLATION_GPS, 1..32)
        addSatellites(glonassCount, GNSS_CONSTELLATION_GLONASS, 1..24)
        addSatellites(beidouCount, GNSS_CONSTELLATION_BEIDOU, 1..37)
        addSatellites(galileoCount, GNSS_CONSTELLATION_GALILEO, 1..36)

        return result.shuffled(random)
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
                        return satellites[param.args[0] as Int].svid
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
     * 创建伪造的 GnssMeasurement 对象
     */
    private fun createFakeGnssMeasurement(sat: SatelliteInfo, index: Int): GnssMeasurement {
        val measurement = XposedHelpers.newInstance(GnssMeasurement::class.java) as GnssMeasurement

        XposedHelpers.setIntField(measurement, "mSvid", sat.svid)
        XposedHelpers.setIntField(measurement, "mConstellationType", sat.constellationType)

        val cn0 = sat.cn0 + (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 4f - 2f).toFloat()
        XposedHelpers.setFloatField(measurement, "mCn0DbHz", cn0)

        XposedHelpers.setDoubleField(measurement, "mTimeOffsetNanos", 0.0)

        val pseudorangeRate = java.util.Random(index.toLong() + 1000).nextGaussian() * 200.0
        XposedHelpers.setDoubleField(measurement, "mPseudorangeRateMetersPerSecond", pseudorangeRate)

        val gpsTimeNanos = (System.currentTimeMillis() / 1000 - GPS_EPOCH_OFFSET_SECONDS) * 1_000_000_000L
        val receivedSvTime = gpsTimeNanos + (index * 100_000L)
        XposedHelpers.setLongField(measurement, "mReceivedSvTimeNanos", receivedSvTime)

        XposedHelpers.setIntField(measurement, "mState", FULL_STATE)

        XposedHelpers.setIntField(measurement, "mMultipathIndicator", 0)

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
