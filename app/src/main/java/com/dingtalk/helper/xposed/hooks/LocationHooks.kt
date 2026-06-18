package com.dingtalk.helper.xposed.hooks

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
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * GPS 位置伪造 Hook
 * 负责拦截和替换位置信息
 */
class LocationHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:Location"

        // 随机偏移范围（约 5-15 米）
        private const val MIN_OFFSET = 0.00005
        private const val MAX_OFFSET = 0.00015

        // 位置缓存，避免频繁计算
        private val locationCache = ConcurrentHashMap<String, Location>()

        // 随机数生成器
        private val random = Random()
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isFakeLocationEnabled()) {
            HookUtils.logDebug("$TAG: 虚拟定位未启用，跳过")
            return
        }

        HookUtils.log("$TAG: 开始注入位置伪造 Hook")

        // Hook LocationManagerService
        hookLocationManagerService(lpparam)

        // Hook Location 对象
        hookLocationClass(lpparam)

        // Hook GNSS 状态回调
        hookGnssStatus(lpparam)

        // 隐藏模拟位置标记
        if (ConfigManager.isHideMockLocationEnabled()) {
            hookMockLocationDetection(lpparam)
        }
    }

    /**
     * Hook LocationManagerService 获取位置的方法
     */
    private fun hookLocationManagerService(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locationManagerClass = XposedHelpers.findClass(
                "android.location.LocationManager",
                lpparam.classLoader
            )

            // Hook getLastLocation
            XposedHelpers.findAndHookMethod(
                locationManagerClass,
                "getLastLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (shouldHook()) {
                            val fakeLocation = getOrCreateFakeLocation()
                            param.result = fakeLocation
                            HookUtils.logDebug("$TAG: getLastLocation 已替换")
                        }
                    }
                }
            )

            // Hook getLastKnownLocation
            XposedHelpers.findAndHookMethod(
                locationManagerClass,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (shouldHook()) {
                            val fakeLocation = getOrCreateFakeLocation()
                            param.result = fakeLocation
                            HookUtils.logDebug("$TAG: getLastKnownLocation 已替换")
                        }
                    }
                }
            )

            // Hook getCurrentLocation (Android 11+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                hookCurrentLocation(locationManagerClass)
            }

            // Hook requestLocationUpdates
            hookLocationUpdates(locationManagerClass)

            HookUtils.log("$TAG: LocationManagerService Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: LocationManagerService Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook getCurrentLocation (Android 11+)
     */
    private fun hookCurrentLocation(locationManagerClass: Class<*>) {
        try {
            // 尝试不同的方法签名
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
                        locationManagerClass,
                        "getCurrentLocation",
                        *signature,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (shouldHook()) {
                                    val fakeLocation = getOrCreateFakeLocation()

                                    // 找到 Listener 参数
                                    val listener = param.args.firstOrNull {
                                        it is android.location.LocationListener
                                    } as? android.location.LocationListener

                                    if (listener != null) {
                                        // 异步回调伪造位置
                                        val executor = param.args.firstOrNull {
                                            it is java.util.concurrent.Executor
                                        } as? java.util.concurrent.Executor

                                        val runnable = Runnable {
                                            listener.onLocationChanged(fakeLocation)
                                        }

                                        executor?.execute(runnable) ?: Thread(runnable).start()

                                        // 阻止原始调用
                                        param.result = null
                                        HookUtils.logDebug("$TAG: getCurrentLocation 已拦截")
                                    }
                                }
                            }
                        }
                    )
                    break // 成功则退出
                } catch (e: NoSuchMethodError) {
                    continue
                }
            }
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: getCurrentLocation Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook requestLocationUpdates
     */
    private fun hookLocationUpdates(locationManagerClass: Class<*>) {
        try {
            // Hook 带 LocationListener 的重载
            XposedHelpers.findAndHookMethod(
                locationManagerClass,
                "requestLocationUpdates",
                String::class.java,
                Long::class.java,
                Float::class.java,
                android.location.LocationListener::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (shouldHook()) {
                            val listener = param.args[3] as android.location.LocationListener
                            val fakeLocation = getOrCreateFakeLocation()

                            // 立即回调一次
                            Thread {
                                try {
                                    Thread.sleep(100) // 短暂延迟
                                    listener.onLocationChanged(fakeLocation)
                                } catch (e: Exception) {
                                    // 忽略
                                }
                            }.start()
                        }
                    }
                }
            )

            HookUtils.logDebug("$TAG: requestLocationUpdates Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: requestLocationUpdates Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Location 类，隐藏 mock 标记
     */
    private fun hookLocationClass(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locationClass = XposedHelpers.findClass(
                "android.location.Location",
                lpparam.classLoader
            )

            // Hook isMock 方法
            XposedHelpers.findAndHookMethod(
                locationClass,
                "isMock",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return false
                    }
                }
            )

            // Hook isFromMockProvider 方法 (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    XposedHelpers.findAndHookMethod(
                        locationClass,
                        "isFromMockProvider",
                        object : XC_MethodReplacement() {
                            override fun replaceHookedMethod(param: MethodHookParam): Any {
                                return false
                            }
                        }
                    )
                } catch (e: NoSuchMethodError) {
                    // 忽略
                }
            }

            HookUtils.log("$TAG: Location.isMock Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Location.isMock Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook GNSS 状态回调
     */
    private fun hookGnssStatus(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locationManagerClass = XposedHelpers.findClass(
                "android.location.LocationManager",
                lpparam.classLoader
            )

            // 阻止注册 GNSS 状态回调
            XposedHelpers.findAndHookMethod(
                locationManagerClass,
                "registerGnssStatusCallback",
                GnssStatus.Callback::class.java,
                android.os.Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (shouldHook()) {
                            param.result = false
                            HookUtils.logDebug("$TAG: GNSS 状态回调已阻止")
                        }
                    }
                }
            )

            HookUtils.log("$TAG: GNSS 状态 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: GNSS 状态 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook 模拟位置检测
     */
    private fun hookMockLocationDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val settingsSecureClass = XposedHelpers.findClass(
                "android.provider.Settings\$Secure",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                settingsSecureClass,
                "getInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args[1] as String
                        when (name) {
                            "mock_location", "development_settings_enabled" -> {
                                param.result = 0
                                HookUtils.logDebug("$TAG: 隐藏设置: $name")
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

    /**
     * 获取或创建伪造的 Location 对象（带缓存）
     */
    private fun getOrCreateFakeLocation(): Location {
        val cacheKey = "${ConfigManager.getLatitude()}_${ConfigManager.getLongitude()}"

        return locationCache.getOrPut(cacheKey) {
            createFakeLocation()
        }
    }

    /**
     * 创建伪造的 Location 对象
     */
    private fun createFakeLocation(): Location {
        val fakeLocation = ConfigManager.getFakeLocation()

        // 计算随机偏移（模拟真实定位的微小波动）
        val latOffset = MIN_OFFSET + random.nextDouble() * (MAX_OFFSET - MIN_OFFSET)
        val lngOffset = MIN_OFFSET + random.nextDouble() * (MAX_OFFSET - MIN_OFFSET)
        val signLat = if (random.nextBoolean()) 1 else -1
        val signLng = if (random.nextBoolean()) 1 else -1

        return Location(LocationManager.GPS_PROVIDER).apply {
            latitude = fakeLocation.latitude + (latOffset * signLat)
            longitude = fakeLocation.longitude + (lngOffset * signLng)
            altitude = fakeLocation.altitude + (random.nextDouble() * 2 - 1)
            speed = fakeLocation.speed
            bearing = fakeLocation.bearing
            accuracy = fakeLocation.accuracy + (random.nextFloat() * 5 - 2.5f)
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()

            // 清除 mock 标记
            clearMockFlag()
        }
    }

    /**
     * 清除 Location 的 mock 标记
     */
    private fun Location.clearMockFlag() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ : 清除 mFieldsMask 中的 HAS_MOCK 标志位
                val field = Location::class.java.getDeclaredField("mFieldsMask")
                field.isAccessible = true
                val mask = field.getInt(this)
                field.setInt(this, mask and 0x10.inv()) // 清除第4位
            } else {
                // Android 11 及以下
                val field = Location::class.java.getDeclaredField("mIsMock")
                field.isAccessible = true
                field.setBoolean(this, false)
            }
        } catch (e: Exception) {
            // 忽略反射失败
        }
    }

    /**
     * 判断是否需要 Hook
     */
    private fun shouldHook(): Boolean {
        return ConfigManager.isEnabled() && ConfigManager.isFakeLocationEnabled()
    }
}