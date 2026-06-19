package com.dingtalk.helper.xposed.hooks

import android.annotation.SuppressLint
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
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

        // GPS 漂移范围（约 5-15 米）
        private const val MAX_OFFSET = 0.00015

        // 可变 TTL 范围（毫秒），模拟真实 GPS 芯片更新间隔
        private const val CACHE_TTL_MIN_MS = 1000L
        private const val CACHE_TTL_MAX_MS = 10000L

        // 速度模拟范围（m/s）：静止 0~0.3，步行 0.8~1.5，车载 5~15
        private const val SPEED_STILL_MAX = 0.3f
        private const val SPEED_WALK_MIN = 0.8f
        private const val SPEED_WALK_MAX = 1.5f

        // 海拔漂移范围（米）
        private const val ALTITUDE_DRIFT_MAX = 3.0

        // 方向漂移范围（度）
        private const val BEARING_DRIFT_MAX = 15.0f

        // 历史轨迹最大记录数
        private const val MAX_HISTORY_SIZE = 100

        @Volatile
        private var cachedLocation: Location? = null

        @Volatile
        private var lastCacheTime = 0L

        @Volatile
        private var currentCacheTtlMs = CACHE_TTL_MIN_MS

        // 上一次返回的位置坐标，用于漂移连续性
        @Volatile
        private var lastLatitude = 0.0

        @Volatile
        private var lastLongitude = 0.0

        @Volatile
        private var lastAltitude = 0.0

        @Volatile
        private var lastBearing = 0f

        @Volatile
        private var lastSpeed = 0f

        @Volatile
        private var isInitialized = false

        // 历史轨迹
        private val locationHistory = CopyOnWriteArrayList<LocationEntry>()

        // 定时任务管理
        private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LocationHooks-Scheduler").apply { isDaemon = true }
        }
        private val activeTasks = java.util.concurrent.ConcurrentHashMap<Any, ScheduledFuture<*>>()

        /**
         * 获取当前伪造位置（供其他 Hook 模块查询，确保一致性）
         */
        fun getCurrentFakeLocation(): ConfigManager.FakeLocation {
            val location = getOrCreateFakeLocation()
            return ConfigManager.FakeLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speed = location.speed,
                bearing = location.bearing,
                accuracy = location.accuracy
            )
        }

        /**
         * 检查给定坐标是否与当前伪造位置匹配（容差 50 米）
         * 供 WiFi/基站 Hook 进行一致性校验
         */
        fun isLocationMatch(lat: Double, lng: Double): Boolean {
            val current = getOrCreateFakeLocation()
            val distance = calculateDistance(
                current.latitude, current.longitude, lat, lng
            )
            return distance < 50.0
        }

        /**
         * 获取历史轨迹（只读副本）
         */
        fun getLocationHistory(): List<LocationEntry> {
            return locationHistory.toList()
        }

        /**
         * 获取或创建伪造位置（内部方法）
         */
        internal fun getOrCreateFakeLocation(): Location {
            val now = System.currentTimeMillis()
            val cached = cachedLocation

            if (cached != null && (now - lastCacheTime) < currentCacheTtlMs) {
                return cached
            }

            val newLocation = createFakeLocationInternal()
            cachedLocation = newLocation
            lastCacheTime = now

            // 生成下一次的随机 TTL
            currentCacheTtlMs = CACHE_TTL_MIN_MS +
                ThreadLocalRandom.current().nextInt((CACHE_TTL_MAX_MS - CACHE_TTL_MIN_MS + 1).toInt())

            return newLocation
        }

        /**
         * 创建伪造位置（内部实现）
         */
        private fun createFakeLocationInternal(): Location {
            val fakeConfig = ConfigManager.getFakeLocation()
            val baseLat = fakeConfig.latitude
            val baseLng = fakeConfig.longitude
            val baseAlt = fakeConfig.altitude

            // 漂移：在上一次位置基础上微调，保持连续性
            val wasInitialized = isInitialized
            val latDrift = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * MAX_OFFSET
            val lngDrift = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * MAX_OFFSET

            val newLat: Double
            val newLng: Double
            if (!wasInitialized) {
                // 首次使用基准坐标 + 小偏移
                newLat = baseLat + latDrift
                newLng = baseLng + lngDrift
                isInitialized = true
            } else {
                // 后续在上一次位置上漂移
                newLat = lastLatitude + latDrift * 0.3
                newLng = lastLongitude + lngDrift * 0.3
            }

            // 限制漂移范围：距基准点不超过 50 米
            val distFromBase = calculateDistance(baseLat, baseLng, newLat, newLng)
            val clampedLat: Double
            val clampedLng: Double
            if (distFromBase > 50.0) {
                val ratio = 45.0 / distFromBase
                clampedLat = baseLat + (newLat - baseLat) * ratio
                clampedLng = baseLng + (newLng - baseLng) * ratio
            } else {
                clampedLat = newLat
                clampedLng = newLng
            }

            // 海拔变化
            val altDrift = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * ALTITUDE_DRIFT_MAX
            val newAlt = if (!wasInitialized) baseAlt else lastAltitude + altDrift * 0.3

            // 速度模拟：大部分时间静止，偶尔步行速度
            val movementRoll = ThreadLocalRandom.current().nextFloat()
            val newSpeed = when {
                movementRoll < 0.7f -> {
                    // 70% 概率静止
                    ThreadLocalRandom.current().nextFloat() * SPEED_STILL_MAX
                }
                movementRoll < 0.95f -> {
                    // 25% 概率步行
                    SPEED_WALK_MIN + ThreadLocalRandom.current().nextFloat() * (SPEED_WALK_MAX - SPEED_WALK_MIN)
                }
                else -> {
                    // 5% 概率快速移动
                    2.0f + ThreadLocalRandom.current().nextFloat() * 3.0f
                }
            }

            // 方向变化：在上一次方向基础上微调
            val bearingDrift = (ThreadLocalRandom.current().nextFloat() * 2 - 1) * BEARING_DRIFT_MAX
            val newBearing = if (!wasInitialized) {
                ThreadLocalRandom.current().nextFloat() * 360f
            } else {
                ((lastBearing + bearingDrift) % 360f + 360f) % 360f
            }

            // 精度模拟
            val accuracy = fakeConfig.accuracy + (ThreadLocalRandom.current().nextFloat() * 6 - 3f)

            // 更新状态
            lastLatitude = clampedLat
            lastLongitude = clampedLng
            lastAltitude = newAlt
            lastBearing = newBearing
            lastSpeed = newSpeed

            // 记录历史
            recordHistory(clampedLat, clampedLng, newAlt, newSpeed, newBearing)

            return Location(LocationManager.GPS_PROVIDER).apply {
                latitude = clampedLat
                longitude = clampedLng
                altitude = newAlt
                speed = newSpeed
                bearing = newBearing
                this.accuracy = accuracy
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    // 确保 hasElapsedRealtimeNanos() 返回 true
                    try {
                        val field = Location::class.java.getDeclaredField("mElapsedRealtimeNanos")
                        field.isAccessible = true
                        field.setLong(this, elapsedRealtimeNanos)
                    } catch (_: Exception) {}
                }

                clearMockFlag()

                val extras = android.os.Bundle()
                extras.putInt("satellites", 12)
                setExtras(extras)
            }
        }

        /**
         * 记录历史位置
         */
        private fun recordHistory(
            lat: Double, lng: Double, alt: Double, speed: Float, bearing: Float
        ) {
            locationHistory.add(
                LocationEntry(lat, lng, alt, speed, bearing, System.currentTimeMillis())
            )
            while (locationHistory.size > MAX_HISTORY_SIZE) {
                locationHistory.removeAt(0)
            }
        }

        /**
         * 清除 Location 的 mock 标记
         */
        @SuppressLint("BlockedPrivateApi")
        private fun Location.clearMockFlag() {
            try {
                val field = Location::class.java.getDeclaredField("mIsMock")
                field.isAccessible = true
                field.setBoolean(this, false)
            } catch (_: Exception) {}
        }

        /**
         * 计算两个坐标之间的距离（米），使用 Haversine 公式
         */
        private fun calculateDistance(
            lat1: Double, lng1: Double, lat2: Double, lng2: Double
        ): Double {
            val earthRadius = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return earthRadius * c
        }

        /**
         * 重置状态（配置变更时调用）
         */
        fun reset() {
            cachedLocation = null
            lastCacheTime = 0L
            isInitialized = false
            locationHistory.clear()
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
        val satelliteCount = 12

        XposedHelpers.setIntField(status, "mSvCount", satelliteCount)

        val prns = IntArray(satelliteCount) { it + 1 }
        val cn0s = FloatArray(satelliteCount) { 35f + (Math.random() * 10 - 5).toFloat() }
        val elevations = FloatArray(satelliteCount) { 45f + (Math.random() * 30 - 15).toFloat() }
        val azimuths = FloatArray(satelliteCount) { (it * 30f + Math.random() * 20).toFloat() % 360f }
        val usedInFix = BooleanArray(satelliteCount) { it < 8 }
        val ephemeris = BooleanArray(satelliteCount) { true }
        val almanac = BooleanArray(satelliteCount) { true }
        val carrierFreqs = FloatArray(satelliteCount) { 1575.42f }

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
        XposedHelpers.setObjectField(status, "mConstellationTypes", IntArray(satelliteCount) { 1 })

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
