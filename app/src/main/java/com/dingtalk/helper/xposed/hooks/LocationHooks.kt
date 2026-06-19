package com.dingtalk.helper.xposed.hooks

import android.annotation.SuppressLint
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * GPS 位置伪造 Hook
 * 负责拦截和替换位置信息
 *
 * 优化特性：
 * - 可变 TTL 缓存（1-10秒随机），模拟真实 GPS 更新间隔
 * - 位置漂移模拟，每次返回微小变化的位置
 * - 历史位置轨迹记录
 * - 速度/方向/海拔动态模拟
 * - 位置一致性验证接口，供 WiFi/基站 Hook 交叉校验
 * - 完整的 mock 标记隐藏（provider/hasAltitude/hasSpeed）
 */
class LocationHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:Location"

        // 定时任务管理
        private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LocationHooks-Scheduler").apply { isDaemon = true }
        }
        private val activeTasks = java.util.concurrent.ConcurrentHashMap<Any, ScheduledFuture<*>>()

        fun getCurrentFakeLocation(): ConfigManager.FakeLocation {
            return FakeDataProvider.getCurrentFakeLocation()
        }

        fun isLocationMatch(lat: Double, lng: Double): Boolean {
            return FakeDataProvider.isLocationMatch(lat, lng)
        }

        fun getLocationHistory(): List<LocationEntry> {
            return FakeDataProvider.getLocationHistory().map {
                LocationEntry(it.latitude, it.longitude, it.altitude, it.speed, it.bearing, it.timestamp)
            }
        }

        internal fun getOrCreateFakeLocation(): Location {
            return FakeDataProvider.getOrCreateFakeLocation()
        }

        fun reset() {
            FakeDataProvider.resetLocation()
            activeTasks.values.forEach { it.cancel(false) }
            activeTasks.clear()
        }
    }

    /**
     * 历史轨迹数据条目
     */
    data class LocationEntry(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val speed: Float,
        val bearing: Float,
        val timestamp: Long
    )

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isFakeLocationEnabled()) {
            HookUtils.logDebug("$TAG: 虚拟定位未启用，跳过")
            return
        }

        HookUtils.log("$TAG: 开始注入位置伪造 Hook")

        val locationManagerClass = try {
            XposedHelpers.findClass("android.location.LocationManager", lpparam.classLoader)
        } catch (e: ClassNotFoundException) {
            HookUtils.log("$TAG: 找不到 LocationManager 类")
            return
        }

        hookGetLastLocation(locationManagerClass)
        hookGetLastKnownLocation(locationManagerClass)
        hookRequestLocationUpdates(locationManagerClass)
        hookCurrentLocation(locationManagerClass)
        hookGnssStatus(locationManagerClass)
        hookProviderEnabled(locationManagerClass)

        if (ConfigManager.isHideMockLocationEnabled()) {
            hookLocationClass(lpparam)
            hookMockLocationDetection(lpparam)
            hookLocationProperties(lpparam)
        }

        HookUtils.log("$TAG: 位置伪造 Hook 注入完成")
    }

    private fun hookGetLastLocation(clazz: Class<*>) {
        HookUtils.hookMethodSafely(
            clazz.name, clazz.classLoader ?: return,
            "getLastLocation",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (shouldHook()) {
                        param.result = getOrCreateFakeLocation()
                        HookUtils.logDebug("$TAG: getLastLocation 已替换")
                    }
                }
            }
        )
    }

    private fun hookGetLastKnownLocation(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (shouldHook()) {
                            param.result = getOrCreateFakeLocation()
                            HookUtils.logDebug("$TAG: getLastKnownLocation 已替换")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: getLastKnownLocation Hook 失败: ${e.message}")
        }
    }

    private fun hookCurrentLocation(clazz: Class<*>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val signatures = listOf(
            arrayOf(
                String::class.java,
                android.os.CancellationSignal::class.java,
                java.util.concurrent.Executor::class.java,
                android.location.LocationListener::class.java
            ),
            arrayOf(
                String::class.java,
                java.util.concurrent.Executor::class.java,
                android.location.LocationListener::class.java
            )
        )

        for (signature in signatures) {
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "getCurrentLocation",
                    *signature,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!shouldHook()) return

                            val fakeLocation = getOrCreateFakeLocation()
                            val listener = param.args.firstOrNull {
                                it is android.location.LocationListener
                            } as? android.location.LocationListener ?: return

                            val executor = param.args.firstOrNull {
                                it is java.util.concurrent.Executor
                            } as? java.util.concurrent.Executor

                            val runnable = Runnable {
                                try {
                                    listener.onLocationChanged(fakeLocation)
                                } catch (e: Exception) {
                                    HookUtils.logDebug("$TAG: getCurrentLocation 回调失败: ${e.message}")
                                }
                            }

                            executor?.execute(runnable) ?: Thread(runnable).start()
                            param.result = null
                            HookUtils.logDebug("$TAG: getCurrentLocation 已拦截")
                        }
                    }
                )
                break
            } catch (e: NoSuchMethodError) {
                continue
            }
        }
    }

    private fun scheduleLocationUpdates(
        listener: Any,
        minTimeMs: Long,
        callback: (Location) -> Unit
    ) {
        cancelLocationUpdates(listener)
        val intervalMs = maxOf(minTimeMs, 1000L)
        val future = scheduledExecutor.scheduleAtFixedRate({
            try {
                val location = getOrCreateFakeLocation()
                callback(location)
            } catch (_: Exception) {}
        }, 100, intervalMs, TimeUnit.MILLISECONDS)
        activeTasks[listener] = future
    }

    private fun schedulePendingIntentUpdates(
        key: Any,
        pendingIntent: android.app.PendingIntent,
        minTimeMs: Long
    ) {
        cancelLocationUpdates(key)
        val intervalMs = maxOf(minTimeMs, 1000L)
        val future = scheduledExecutor.scheduleAtFixedRate({
            try {
                val location = getOrCreateFakeLocation()
                val intent = android.content.Intent().apply {
                    putExtra(android.location.LocationManager.KEY_LOCATION_CHANGED, location)
                }
                val context = android.app.AndroidAppHelper.currentApplication()
                pendingIntent.send(context, 0, intent)
            } catch (_: Exception) {}
        }, 100, intervalMs, TimeUnit.MILLISECONDS)
        activeTasks[key] = future
    }

    private fun cancelLocationUpdates(key: Any) {
        activeTasks.remove(key)?.cancel(false)
    }

    private fun hookRequestLocationUpdates(clazz: Class<*>) {
        val hookCallback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!shouldHook()) return

                val minTimeMs = (param.args[1] as? Long) ?: 1000L

                val listener = param.args.firstOrNull {
                    it is android.location.LocationListener
                } as? android.location.LocationListener ?: return

                param.result = null

                scheduleLocationUpdates(listener, minTimeMs) { location ->
                    listener.onLocationChanged(location)
                }
            }
        }

        val signatures = listOf(
            arrayOf(
                String::class.java,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                android.location.LocationListener::class.java
            ),
            arrayOf(
                String::class.java,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                android.location.LocationListener::class.java,
                android.os.Looper::class.java
            )
        )

        for (signature in signatures) {
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "requestLocationUpdates",
                    *signature,
                    hookCallback
                )
                HookUtils.logDebug("$TAG: requestLocationUpdates Hook 完成: ${signatureToString(signature)}")
            } catch (e: NoSuchMethodError) {
                HookUtils.logDebug("$TAG: requestLocationUpdates 重载不存在: ${signatureToString(signature)}")
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: requestLocationUpdates Hook 失败: ${e.message}")
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "requestLocationUpdates",
                String::class.java,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                android.app.PendingIntent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldHook()) return

                        val minTimeMs = (param.args[1] as? Long) ?: 1000L
                        val pendingIntent = param.args[3] as? android.app.PendingIntent ?: return

                        param.result = null

                        schedulePendingIntentUpdates(pendingIntent, pendingIntent, minTimeMs)
                    }
                }
            )
            HookUtils.logDebug("$TAG: requestLocationUpdates(PendingIntent) Hook 完成")
        } catch (e: NoSuchMethodError) {
            HookUtils.logDebug("$TAG: requestLocationUpdates(PendingIntent) 重载不存在")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: requestLocationUpdates(PendingIntent) Hook 失败: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "requestLocationUpdates",
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    android.location.LocationListener::class.java,
                    java.util.concurrent.Executor::class.java,
                    hookCallback
                )
                HookUtils.logDebug("$TAG: requestLocationUpdates(Executor) Hook 完成")
            } catch (e: NoSuchMethodError) {
                HookUtils.logDebug("$TAG: requestLocationUpdates(Executor) 重载不存在")
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: requestLocationUpdates(Executor) Hook 失败: ${e.message}")
            }
        }

        hookRemoveUpdates(clazz)
    }

    private fun hookRemoveUpdates(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "removeUpdates",
                android.location.LocationListener::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0] ?: return
                        cancelLocationUpdates(listener)
                    }
                }
            )
        } catch (_: Exception) {}

        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "removeUpdates",
                android.app.PendingIntent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pendingIntent = param.args[0] ?: return
                        cancelLocationUpdates(pendingIntent)
                    }
                }
            )
        } catch (_: Exception) {}
    }

    private fun hookProviderEnabled(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "isProviderEnabled",
                String::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return param.args[0] == LocationManager.GPS_PROVIDER || param.args[0] == LocationManager.NETWORK_PROVIDER
                    }
                }
            )
            HookUtils.logDebug("$TAG: isProviderEnabled Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: isProviderEnabled Hook 失败: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getProviders",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] == true) {
                            val providers = param.result as? List<*> ?: return
                            if (!providers.contains(LocationManager.GPS_PROVIDER)) {
                                val mutable = providers.toMutableList()
                                mutable.add(0, LocationManager.GPS_PROVIDER)
                                param.result = mutable
                            }
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: getProviders Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: getProviders Hook 失败: ${e.message}")
        }
    }

    private fun signatureToString(signature: Array<out Class<*>?>): String {
        return signature.joinToString(", ") { it?.simpleName ?: "null" }
    }

    private fun hookGnssStatus(clazz: Class<*>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val hookStatusCallback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!shouldHook()) return

                val callback = param.args.firstOrNull {
                    it is GnssStatus.Callback
                } as? GnssStatus.Callback ?: return

                val handler = param.args.firstOrNull {
                    it is android.os.Handler
                } as? android.os.Handler
                    ?: android.os.Handler(android.os.Looper.getMainLooper())

                param.result = true

                handler.post {
                    try {
                        callback.onStarted()
                    } catch (_: Exception) {}

                    scheduledExecutor.schedule({
                        try {
                            val fakeStatus = createFakeGnssStatus()
                            callback.onFirstFix(0)
                            callback.onSatelliteStatusChanged(fakeStatus)
                        } catch (_: Exception) {}
                    }, 200, TimeUnit.MILLISECONDS)
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "registerGnssStatusCallback",
                GnssStatus.Callback::class.java,
                android.os.Handler::class.java,
                hookStatusCallback
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: GNSS 状态(Handler) Hook 失败: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "registerGnssStatusCallback",
                    java.util.concurrent.Executor::class.java,
                    GnssStatus.Callback::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!shouldHook()) return

                            val executor = param.args.firstOrNull {
                                it is java.util.concurrent.Executor
                            } as? java.util.concurrent.Executor ?: return

                            val callback = param.args.firstOrNull {
                                it is GnssStatus.Callback
                            } as? GnssStatus.Callback ?: return

                            param.result = true

                            executor.execute {
                                try {
                                    callback.onStarted()
                                } catch (_: Exception) {}

                                scheduledExecutor.schedule({
                                    try {
                                        val fakeStatus = createFakeGnssStatus()
                                        callback.onFirstFix(0)
                                        callback.onSatelliteStatusChanged(fakeStatus)
                                    } catch (_: Exception) {}
                                }, 200, TimeUnit.MILLISECONDS)
                            }
                        }
                    }
                )
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun createFakeGnssStatus(): GnssStatus {
        val status = XposedHelpers.newInstance(GnssStatus::class.java) as GnssStatus
        val sats = FakeDataProvider.getSatellites()
        val satelliteCount = sats.size

        XposedHelpers.setIntField(status, "mSvCount", satelliteCount)

        val prns = IntArray(satelliteCount) { sats[it].svid }
        val cn0s = FloatArray(satelliteCount) { sats[it].cn0 + (Math.random() * 4 - 2).toFloat() }
        val elevations = FloatArray(satelliteCount) { sats[it].elevation }
        val azimuths = FloatArray(satelliteCount) { sats[it].azimuth }
        val usedInFix = BooleanArray(satelliteCount) { sats[it].usedInFix }
        val ephemeris = BooleanArray(satelliteCount) { true }
        val almanac = BooleanArray(satelliteCount) { true }
        val carrierFreqs = FloatArray(satelliteCount) { sats[it].carrierFrequencyHz }
        val constellationTypes = IntArray(satelliteCount) { sats[it].constellationType }

        XposedHelpers.setObjectField(status, "mPrnWithFlags", IntArray(satelliteCount * 2) { i ->
            if (i < satelliteCount) prns[i] else 0
        })
        XposedHelpers.setObjectField(status, "mCn0DbHz", cn0s)
        XposedHelpers.setObjectField(status, "mSvElevations", elevations)
        XposedHelpers.setObjectField(status, "mSvAzimuths", azimuths)
        XposedHelpers.setObjectField(status, "mSvUsedInFix", usedInFix)
        XposedHelpers.setObjectField(status, "mHasEphemerisData", ephemeris)
        XposedHelpers.setObjectField(status, "mHasAlmanacData", almanac)
        XposedHelpers.setObjectField(status, "mCarrierFreqHz", carrierFreqs)
        XposedHelpers.setObjectField(status, "mConstellationTypes", constellationTypes)

        return status
    }

    /**
     * Hook Location 类的基础 mock 标记
     */
    private fun hookLocationClass(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locationClass = XposedHelpers.findClass(
                "android.location.Location", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                locationClass, "isMock",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    XposedHelpers.findAndHookMethod(
                        locationClass, "isFromMockProvider",
                        object : XC_MethodReplacement() {
                            override fun replaceHookedMethod(param: MethodHookParam): Any = false
                        }
                    )
                } catch (_: NoSuchMethodError) {}
            }

            HookUtils.log("$TAG: Location.isMock Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Location.isMock Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Location 属性方法，确保伪造位置看起来真实：
     * - getProvider 返回 "gps"
     * - hasAltitude 返回 true
     * - hasSpeed 返回 true（当 speed > 0）
     * - hasBearing 返回 true（当 bearing 有效）
     */
    private fun hookLocationProperties(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locationClass = XposedHelpers.findClass(
                "android.location.Location", lpparam.classLoader
            )

            // Hook getProvider：仅对 mock 位置返回 "gps"
            XposedHelpers.findAndHookMethod(
                locationClass, "getProvider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val location = param.thisObject as Location
                        val provider = param.result as? String ?: return
                        if (provider == "fused" || provider == "network" || provider == "passive") {
                            try {
                                val field = Location::class.java.getDeclaredField("mIsMock")
                                field.isAccessible = true
                                if (field.getBoolean(location)) {
                                    param.result = LocationManager.GPS_PROVIDER
                                }
                            } catch (_: Exception) {
                                param.result = LocationManager.GPS_PROVIDER
                            }
                        }
                    }
                }
            )

            // Hook hasAltitude：始终返回 true
            XposedHelpers.findAndHookMethod(
                locationClass, "hasAltitude",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = true
                }
            )

            // Hook hasSpeed：当 speed > 0 时返回 true
            XposedHelpers.findAndHookMethod(
                locationClass, "hasSpeed",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val speed = (param.thisObject as Location).speed
                        return speed > 0f
                    }
                }
            )

            // Hook hasBearing：检查 bearing 是否有效
            XposedHelpers.findAndHookMethod(
                locationClass, "hasBearing",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val bearing = (param.thisObject as Location).bearing
                        return !bearing.isNaN()
                    }
                }
            )

            // Hook hasAccuracy：始终返回 true
            XposedHelpers.findAndHookMethod(
                locationClass, "hasAccuracy",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = true
                }
            )

            HookUtils.log("$TAG: Location 属性 Hook 完成 (provider/hasAltitude/hasSpeed/hasBearing)")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Location 属性 Hook 失败: ${e.message}")
        }
    }

    private fun hookMockLocationDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val settingsSecureClass = XposedHelpers.findClass(
                "android.provider.Settings\$Secure", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                settingsSecureClass, "getInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        when (param.args[1] as String) {
                            "mock_location", "development_settings_enabled" -> {
                                param.result = 0
                            }
                        }
                    }
                }
            )
            HookUtils.log("$TAG: 模拟位置检测 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 模拟位置检测 Hook 失败: ${e.message}")
        }
    }

    private fun shouldHook(): Boolean {
        return ConfigManager.isEnabled() && ConfigManager.isFakeLocationEnabled()
    }
}
