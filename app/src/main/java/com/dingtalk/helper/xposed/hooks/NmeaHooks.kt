package com.dingtalk.helper.xposed.hooks

import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.data.FakeDataProvider
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
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

        // 定时任务管理
        private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "NmeaHooks-Scheduler").apply { isDaemon = true }
        }
        private val activeTasks = java.util.concurrent.ConcurrentHashMap<Any, ScheduledFuture<*>>()

        fun generateGPGGA(location: ConfigManager.FakeLocation, timestamp: Long): String {
            return FakeDataProvider.generateNmeaGGA(location, timestamp)
        }

        fun generateGPRMC(location: ConfigManager.FakeLocation, timestamp: Long): String {
            return FakeDataProvider.generateNmeaRMC(location, timestamp)
        }

        fun generateGSV(location: ConfigManager.FakeLocation, timestamp: Long): List<String> {
            return FakeDataProvider.generateNmeaGSV(location, timestamp)
        }

        fun generateGPGSA(location: ConfigManager.FakeLocation, timestamp: Long): String {
            return FakeDataProvider.generateNmeaGPGSA(location, timestamp)
        }

        fun generateAllNmeaSentences(location: ConfigManager.FakeLocation, timestamp: Long): List<String> {
            return FakeDataProvider.generateAllNmeaSentences(location, timestamp)
        }

        fun calculateChecksum(sentence: String): String {
            return FakeDataProvider.calculateChecksum(sentence)
        }

        fun appendChecksum(body: String): String {
            return FakeDataProvider.appendChecksum(body)
        }

        fun decimalToNmea(decimalDegrees: Double, isLat: Boolean): String {
            return FakeDataProvider.decimalToNmea(decimalDegrees, isLat)
        }

        fun getDirectionIndicator(decimalDegrees: Double, isLat: Boolean): String {
            return FakeDataProvider.getDirectionIndicator(decimalDegrees, isLat)
        }

        fun formatNmeaTime(millis: Long): String {
            return FakeDataProvider.formatNmeaTime(millis)
        }

        fun formatNmeaDate(millis: Long): String {
            return FakeDataProvider.formatNmeaDate(millis)
        }

        private fun getCurrentFakeLocation(): ConfigManager.FakeLocation {
            return FakeDataProvider.getCurrentFakeLocation()
        }

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
                } catch (_: Exception) {}
            }, 100, interval, TimeUnit.MILLISECONDS)
            activeTasks[key] = future
        }

        private fun cancelNmeaUpdates(key: Any) {
            activeTasks.remove(key)?.cancel(false)
        }
    }

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
