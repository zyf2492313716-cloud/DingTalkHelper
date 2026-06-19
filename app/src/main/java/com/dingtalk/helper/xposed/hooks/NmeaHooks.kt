package com.dingtalk.helper.xposed.hooks

import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * NMEA 0183 语句生成器 Hook
 * 负责拦截和替换 GNSS NMEA 数据
 *
 * 生成的 NMEA 语句：
 * - $GPGGA: 定位数据（时间、经纬度、质量、卫星数、海拔）
 * - $GPRMC: 推荐最小定位（含速度、日期、磁偏角）
 * - $GPGSV: 可见卫星信息（PRN、仰角、方位角、信噪比）
 * - $GPGSA: DOP 值和活动卫星
 *
 * 参考 Portal 项目的 NMEA 实现
 */
class NmeaHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:NMEA"

        // 可见卫星数量
        private const val TOTAL_SATELLITE_COUNT = 12

        // 使用于定位的卫星数量
        private const val USED_SATELLITE_COUNT = 8

        // GPS 质量指示：0=无效, 1=GPS定位, 2=DGPS定位
        private const val GPS_QUALITY_GPS = 1
        private const val GPS_QUALITY_DGPS = 2

        // 卫星数据缓存（用于 GPGSV 分组和一致性）
        private val satelliteCache = java.util.concurrent.CopyOnWriteArrayList<SatelliteData>()

        // 定时任务管理
        private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "NmeaHooks-Scheduler").apply { isDaemon = true }
        }
        private val activeTasks = java.util.concurrent.ConcurrentHashMap<Any, ScheduledFuture<*>>()

        init {
            refreshSatelliteCache()
        }

        /**
         * 刷新卫星缓存数据
         */
        private fun refreshSatelliteCache() {
            satelliteCache.clear()
            val random = ThreadLocalRandom.current()
            for (i in 0 until TOTAL_SATELLITE_COUNT) {
                satelliteCache.add(
                    SatelliteData(
                        prn = i + 1,
                        elevation = 10 + random.nextInt(65),
                        azimuth = random.nextInt(360),
                        snr = 25 + random.nextInt(25)
                    )
                )
            }
        }

        /**
         * 计算 NMEA 0183 校验和
         * 校验和为 '$' 和 '*' 之间所有字符的异或值
         */
        fun calculateChecksum(sentence: String): String {
            var checksum = 0
            // 跳过起始 '$'，计算到 '*' 或句子末尾
            val start = if (sentence.startsWith('$')) 1 else 0
            for (i in start until sentence.length) {
                val c = sentence[i]
                if (c == '*') break
                checksum = checksum xor c.code
            }
            return String.format("%02X", checksum and 0xFF)
        }

        /**
         * 追加校验和到 NMEA 语句
         * 输入：不含 '$' 前缀和 '*' 后缀的句子体
         * 输出：完整 NMEA 语句，如 $GPGGA,...*HH\r\n
         */
        fun appendChecksum(body: String): String {
            val sentence = "$$body"
            val checksum = calculateChecksum(sentence)
            return "$sentence*$checksum\r\n"
        }

        /**
         * 十进制度转换为 NMEA 格式（度度分分.分分分分）
         * @param decimalDegrees 十进制度数
         * @param isLat true=纬度(2位度), false=经度(3位度)
         * @return NMEA 格式字符串
         */
        fun decimalToNmea(decimalDegrees: Double, isLat: Boolean): String {
            val absolute = Math.abs(decimalDegrees)
            val degrees = absolute.toInt()
            val minutes = (absolute - degrees) * 60.0
            val degWidth = if (isLat) 2 else 3
            return String.format(Locale.US, "%0${degWidth}d%07.4f", degrees, minutes)
        }

        /**
         * 获取 N/S 或 E/W 指示符
         */
        fun getDirectionIndicator(decimalDegrees: Double, isLat: Boolean): String {
            return if (isLat) {
                if (decimalDegrees >= 0) "N" else "S"
            } else {
                if (decimalDegrees >= 0) "E" else "W"
            }
        }

        /**
         * 生成 HHMMSS.ss 格式的 UTC 时间
         */
        fun formatNmeaTime(millis: Long): String {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = millis
            return String.format(
                Locale.US, "%02d%02d%02d.%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND),
                cal.get(java.util.Calendar.MILLISECOND) / 10
            )
        }

        /**
         * 生成 DDMMYY 格式的 UTC 日期
         */
        fun formatNmeaDate(millis: Long): String {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = millis
            return String.format(
                Locale.US, "%02d%02d%02d",
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.YEAR) % 100
            )
        }

        /**
         * 生成 $GPGGA 语句（GPS 定位数据）
         *
         * 格式：$GPGGA,hhmmss.ss,llll.ll,a,yyyyy.yy,a,x,xx,x.x,x.x,M,x.x,M,x.x,xxxx*hh
         *
         * 字段说明：
         * 1. UTC 时间
         * 2. 纬度
         * 3. N/S
         * 4. 经度
         * 5. E/W
         * 6. 质量指示 (0=无效, 1=GPS, 2=DGPS)
         * 7. 使用卫星数
         * 8. HDOP
         * 9. 海拔高度
         * 10. M（单位）
         * 11. 大地水准面高度
         * 12. M（单位）
         * 13. 差分数据龄期
         * 14. 差分基站 ID
         */
        fun generateGPGGA(location: ConfigManager.FakeLocation, timestamp: Long): String {
            val time = formatNmeaTime(timestamp)
            val lat = decimalToNmea(location.latitude, true)
            val latDir = getDirectionIndicator(location.latitude, true)
            val lon = decimalToNmea(location.longitude, false)
            val lonDir = getDirectionIndicator(location.longitude, false)
            val quality = GPS_QUALITY_GPS
            val satellites = String.format(Locale.US, "%02d", USED_SATELLITE_COUNT)
            val hdop = "0.9"
            val altitude = String.format(Locale.US, "%.1f", location.altitude)
            val geoidHeight = "-1.0"

            val body = "GPGGA,$time,$lat,$latDir,$lon,$lonDir,$quality,$satellites,$hdop,$altitude,M,$geoidHeight,M,,"
            return appendChecksum(body)
        }

        /**
         * 生成 $GPRMC 语句（推荐最小定位数据）
         *
         * 格式：$GPRMC,hhmmss.ss,A,llll.ll,a,yyyyy.yy,a,x.x,x.x,ddmmyy,x.x,a,a*hh
         *
         * 字段说明：
         * 1. UTC 时间
         * 2. 状态 A=有效, V=无效
         * 3. 纬度
         * 4. N/S
         * 5. 经度
         * 6. E/W
         * 7. 地面速度（节）
         * 8. 地面航向（度）
         * 9. 日期
         * 10. 磁偏角
         * 11. 磁偏角方向
         * 12. 模式指示 (A=自主, D=差分, E=估算)
         */
        fun generateGPRMC(location: ConfigManager.FakeLocation, timestamp: Long): String {
            val time = formatNmeaTime(timestamp)
            val status = "A"
            val lat = decimalToNmea(location.latitude, true)
            val latDir = getDirectionIndicator(location.latitude, true)
            val lon = decimalToNmea(location.longitude, false)
            val lonDir = getDirectionIndicator(location.longitude, false)
            // 速度：m/s -> 节 (1 m/s = 1.94384 knot)
            val speedKnots = String.format(Locale.US, "%.1f", location.speed * 1.94384)
            val bearing = String.format(Locale.US, "%.1f", location.bearing)
            val date = formatNmeaDate(timestamp)
            val magneticVariation = "0.0"
            val magneticDir = "E"
            val modeIndicator = "A"

            val body = "GPRMC,$time,$status,$lat,$latDir,$lon,$lonDir,$speedKnots,$bearing,$date,$magneticVariation,$magneticDir,$modeIndicator"
            return appendChecksum(body)
        }

        /**
         * 生成 $GPGSV 语句（可见卫星信息）
         *
         * 格式：$GPGSV,总消息数,消息编号,可见卫星数,PRN,仰角,方位角,信噪比,...*hh
         *
         * 每条消息最多包含 4 颗卫星信息
         */
        fun generateGSV(location: ConfigManager.FakeLocation, timestamp: Long): List<String> {
            val satellites = satelliteCache
            val sentences = mutableListOf<String>()
            val satsPerSentence = 4
            val totalMessages = (satellites.size + satsPerSentence - 1) / satsPerSentence

            for (msgIndex in 0 until totalMessages) {
                val startIdx = msgIndex * satsPerSentence
                val endIdx = minOf(startIdx + satsPerSentence, satellites.size)
                val msgNum = msgIndex + 1

                val sb = StringBuilder("GPGSV,$totalMessages,$msgNum,${satellites.size}")
                for (i in startIdx until endIdx) {
                    val sat = satellites[i]
                    sb.append(",${sat.prn},${sat.elevation},${sat.azimuth},${sat.snr}")
                }
                sentences.add(appendChecksum(sb.toString()))
            }

            return sentences
        }

        /**
         * 生成 $GPGSA 语句（DOP 值和活动卫星）
         *
         * 格式：$GPGSA,A,3,PRN1,PRN2,...,PDOP,HDOP,VDOP*hh
         *
         * 字段说明：
         * 1. 模式 M=手动, A=自动
         * 2. 定位类型 1=未定位, 2=2D, 3=3D
         * 3-14. 活动卫星 PRN（最多 12 颗）
         * 15. PDOP
         * 16. HDOP
         * 17. VDOP
         */
        fun generateGPGSA(location: ConfigManager.FakeLocation, timestamp: Long): String {
            val mode = "A"
            val fixType = "3"

            val sb = StringBuilder("GPGSA,$mode,$fixType")
            // 填入使用的卫星 PRN
            for (i in 0 until 12) {
                if (i < USED_SATELLITE_COUNT) {
                    sb.append(",${satelliteCache[i].prn}")
                } else {
                    sb.append(",")
                }
            }

            // DOP 值
            val pdop = "1.2"
            val hdop = "0.9"
            val vdop = "0.8"
            sb.append(",$pdop,$hdop,$vdop")

            return appendChecksum(sb.toString())
        }

        /**
         * 生成完整的 NMEA 语句集合
         */
        fun generateAllNmeaSentences(location: ConfigManager.FakeLocation, timestamp: Long): List<String> {
            val sentences = mutableListOf<String>()
            sentences.add(generateGPGGA(location, timestamp))
            sentences.add(generateGPRMC(location, timestamp))
            sentences.addAll(generateGSV(location, timestamp))
            sentences.add(generateGPGSA(location, timestamp))
            return sentences
        }

        /**
         * 获取当前伪造位置
         */
        private fun getCurrentFakeLocation(): ConfigManager.FakeLocation {
            return LocationHooks.getCurrentFakeLocation()
        }

        /**
         * 定时发送 NMEA 数据
         */
        private fun scheduleNmeaUpdates(
            key: Any,
            listener: Any,
            intervalMs: Long,
            callback: (String, Long) -> Unit
        ) {
            cancelNmeaUpdates(key)
            val interval = maxOf(intervalMs, 1000L)
            val future = scheduledExecutor.scheduleAtFixedRate({
                try {
                    val location = getCurrentFakeLocation()
                    val timestamp = System.currentTimeMillis()
                    val sentences = generateAllNmeaSentences(location, timestamp)
                    sentences.forEach { sentence ->
                        callback(sentence, timestamp)
                    }
                    // 每 10 次更新刷新卫星缓存
                    if (ThreadLocalRandom.current().nextInt(10) == 0) {
                        refreshSatelliteCache()
                    }
                } catch (_: Exception) {}
            }, 100, interval, TimeUnit.MILLISECONDS)
            activeTasks[key] = future
        }

        private fun cancelNmeaUpdates(key: Any) {
            activeTasks.remove(key)?.cancel(false)
        }
    }

    /**
     * 卫星数据
     */
    private data class SatelliteData(
        val prn: Int,
        val elevation: Int,
        val azimuth: Int,
        val snr: Int
    )

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isEnabled()) return

        HookUtils.log("$TAG: 开始注入 NMEA 伪造 Hook")

        val locationManagerClass = try {
            XposedHelpers.findClass("android.location.LocationManager", lpparam.classLoader)
        } catch (e: ClassNotFoundException) {
            HookUtils.log("$TAG: 找不到 LocationManager 类")
            return
        }

        hookRegisterGnssNmeaCallback(locationManagerClass, lpparam)
        hookOnNmeaReceived(lpparam)

        HookUtils.log("$TAG: NMEA Hook 注入完成")
    }

    /**
     * Hook LocationManager.registerGnssNmeaCallback
     * API 24+ (Android N)
     */
    private fun hookRegisterGnssNmeaCallback(
        locationManagerClass: Class<*>,
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        // (GnssNmeaListener, Handler) 签名 - API 24+
        val hookNmeaCallback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!shouldHook()) return

                val listener = param.args.firstOrNull()
                if (listener == null) return

                param.result = true

                val handler = param.args.getOrNull(1) as? android.os.Handler
                    ?: android.os.Handler(android.os.Looper.getMainLooper())

                // 发送初始 NMEA 数据
                handler.postDelayed({
                    sendNmeaToListener(listener)
                }, 200)

                // 定时发送
                scheduleNmeaUpdates(
                    listener, listener, 1000L
                ) { sentence, timestamp ->
                    invokeOnNmeaReceived(listener, timestamp, sentence)
                }

                HookUtils.log("$TAG: registerGnssNmeaCallback(Listener, Handler) Hook 完成")
            }
        }

        // API 24+ (Android N): (GnssNmeaListener, Handler) 签名
        try {
            val gnssNmeaListenerClass = XposedHelpers.findClass("android.location.GnssNmeaListener", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                locationManagerClass,
                "registerGnssNmeaCallback",
                gnssNmeaListenerClass,
                android.os.Handler::class.java,
                hookNmeaCallback
            )
        } catch (e: NoSuchMethodError) {
            HookUtils.log("$TAG: registerGnssNmeaCallback(Listener, Handler) 方法不存在")
        } catch (e: Exception) {
            HookUtils.log("$TAG: registerGnssNmeaCallback(Listener, Handler) Hook 失败: ${e.message}")
        }

        // API 26+ (Android O): (Executor, GnssNmeaListener) 签名
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val gnssNmeaListenerClass = XposedHelpers.findClass("android.location.GnssNmeaListener", lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    locationManagerClass,
                    "registerGnssNmeaCallback",
                    java.util.concurrent.Executor::class.java,
                    gnssNmeaListenerClass,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!shouldHook()) return

                            val executor = param.args[0] as? java.util.concurrent.Executor ?: return
                            val listener = param.args[1] ?: return

                            param.result = null

                            executor.execute {
                                Thread.sleep(200)
                                sendNmeaToListener(listener)
                            }

                            scheduleNmeaUpdates(
                                listener, listener, 1000L
                            ) { sentence, timestamp ->
                                executor.execute {
                                    invokeOnNmeaReceived(listener, timestamp, sentence)
                                }
                            }

                            HookUtils.log("$TAG: registerGnssNmeaCallback(Executor, Listener) Hook 完成")
                        }
                    }
                )
            } catch (e: NoSuchMethodError) {
                HookUtils.log("$TAG: registerGnssNmeaCallback(Executor, Listener) 方法不存在")
            } catch (e: Exception) {
                HookUtils.log("$TAG: registerGnssNmeaCallback(Executor, Listener) Hook 失败: ${e.message}")
            }
        }

        // 注销 Hook
        hookUnregisterGnssNmeaCallback(locationManagerClass)
    }

    /**
     * Hook unregisterGnssNmeaCallback 以清理定时任务
     */
    private fun hookUnregisterGnssNmeaCallback(locationManagerClass: Class<*>) {
        try {
            val gnssNmeaListenerClass = XposedHelpers.findClass("android.location.GnssNmeaListener", locationManagerClass.classLoader)
            XposedHelpers.findAndHookMethod(
                locationManagerClass,
                "unregisterGnssNmeaCallback",
                gnssNmeaListenerClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0] ?: return
                        cancelNmeaUpdates(listener)
                    }
                }
            )
        } catch (_: Exception) {}
    }

    /**
     * Hook GnssNmeaListener.onNmeaReceived 方法
     * 直接拦截 onNmeaReceived 回调，替换 NMEA 数据
     */
    private fun hookOnNmeaReceived(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val gnssNmeaListenerClass = XposedHelpers.findClass(
                "android.location.GnssNmeaListener", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                gnssNmeaListenerClass,
                "onNmeaReceived",
                Long::class.javaPrimitiveType,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldHook()) return

                        val location = getCurrentFakeLocation()
                        val timestamp = param.args[0] as Long
                        val originalNmea = param.args[1] as? String ?: return

                        // 仅替换 GPS 相关语句
                        if (originalNmea.startsWith("\$GP") || originalNmea.startsWith("\$GN")) {
                            val talker = if (originalNmea.startsWith("\$GN")) "GN" else "GP"
                            val sentenceType = originalNmea.substring(3, 6)

                            val replacement = when (sentenceType) {
                                "GGA" -> generateGPGGA(location, timestamp).replace("GPGGA", "${talker}GGA")
                                "RMC" -> generateGPRMC(location, timestamp).replace("GPRMC", "${talker}RMC")
                                "GSA" -> generateGPGSA(location, timestamp).replace("GPGSA", "${talker}GSA")
                                "GSV" -> null // GSV 由 registerGnssNmeaCallback 发送
                                else -> null
                            }

                            if (replacement != null) {
                                param.args[1] = replacement.trimEnd('\r', '\n')
                                HookUtils.logDebug("$TAG: onNmeaReceived 替换 $sentenceType")
                            }
                        }
                    }
                }
            )

            HookUtils.log("$TAG: GnssNmeaListener.onNmeaReceived Hook 完成")
        } catch (e: ClassNotFoundException) {
            HookUtils.log("$TAG: GnssNmeaListener 类不存在 (API < 24)")
        } catch (e: Exception) {
            HookUtils.log("$TAG: onNmeaReceived Hook 失败: ${e.message}")
        }
    }

    /**
     * 向监听器发送完整 NMEA 数据集
     */
    private fun sendNmeaToListener(listener: Any) {
        try {
            val location = getCurrentFakeLocation()
            val timestamp = System.currentTimeMillis()
            val sentences = generateAllNmeaSentences(location, timestamp)
            sentences.forEach { sentence ->
                invokeOnNmeaReceived(listener, timestamp, sentence.trimEnd('\r', '\n'))
            }
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 发送 NMEA 数据失败: ${e.message}")
        }
    }

    /**
     * 通过反射调用 onNmeaReceived
     * 兼容不同 API 版本的 GnssNmeaListener 接口
     */
    private fun invokeOnNmeaReceived(listener: Any, timestamp: Long, nmea: String) {
        try {
            val method = listener.javaClass.getMethod(
                "onNmeaReceived",
                Long::class.javaPrimitiveType,
                String::class.java
            )
            method.invoke(listener, timestamp, nmea)
        } catch (_: NoSuchMethodException) {
            try {
                val method = listener.javaClass.getMethod(
                    "onNmeaReceived",
                    java.lang.Long::class.java,
                    String::class.java
                )
                method.invoke(listener, timestamp, nmea)
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: invokeOnNmeaReceived 失败: ${e.message}")
            }
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: invokeOnNmeaReceived 失败: ${e.message}")
        }
    }

    private fun shouldHook(): Boolean {
        return ConfigManager.isEnabled() && ConfigManager.isFakeLocationEnabled()
    }
}
