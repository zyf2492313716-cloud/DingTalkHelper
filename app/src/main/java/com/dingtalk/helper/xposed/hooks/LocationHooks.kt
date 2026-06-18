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
import java.util.Random

/**
 * GPS 位置伪造 Hook
 * 负责拦截和替换位置信息
 *
 * 借鉴 FuckLocation 项目的简洁实现：
 * - 统一 Hook LocationManager 的所有获取位置入口
 * - 伪造位置带微小随机偏移模拟真实 GPS 波动
 * - 清除 mock 标记位
 */
class LocationHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:Location"

        // 随机偏移范围（约 5-15 米，模拟 GPS 漂移）
        private const val MIN_OFFSET = 0.00005
        private const val MAX_OFFSET = 0.00015

        private val random = Random()

        // 上次生成的伪造位置，用于一致性
        @Volatile
        private var cachedLocation: Location? = null

        // 缓存有效期（毫秒），超时后重新生成以模拟 GPS 更新
        private const val CACHE_TTL_MS = 3000L

        @Volatile
        private var lastCacheTime = 0L
    }

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

        // Hook 所有获取位置的方法
        hookGetLastLocation(locationManagerClass)
        hookGetLastKnownLocation(locationManagerClass)
        hookRequestLocationUpdates(locationManagerClass)
        hookCurrentLocation(locationManagerClass)
        hookGnssStatus(locationManagerClass)

        // 隐藏模拟位置标记
        if (ConfigManager.isHideMockLocationEnabled()) {
            hookLocationClass(lpparam)
            hookMockLocationDetection(lpparam)
        }

        HookUtils.log("$TAG: 位置伪造 Hook 注入完成")
    }

    /**
     * Hook getLastLocation
     */
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

    /**
     * Hook getLastKnownLocation(String)
     */
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

    /**
     * Hook getCurrentLocation (Android 11+)
     */
    private fun hookCurrentLocation(clazz: Class<*>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

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

    /**
     * Hook requestLocationUpdates - 立即回调一次伪造位置
     */
    private fun hookRequestLocationUpdates(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "requestLocationUpdates",
                String::class.java,
                Long::class.java,
                Float::class.java,
                android.location.LocationListener::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldHook()) return

                        val listener = param.args[3] as android.location.LocationListener
                        val fakeLocation = getOrCreateFakeLocation()

                        // 异步回调伪造位置
                        Thread {
                            try {
                                Thread.sleep(100)
                                listener.onLocationChanged(fakeLocation)
                            } catch (_: Exception) {}
                        }.start()
                    }
                }
            )
            HookUtils.logDebug("$TAG: requestLocationUpdates Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: requestLocationUpdates Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook GNSS 状态回调 - 阻止真实卫星数据
     */
    private fun hookGnssStatus(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
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
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: GNSS 状态 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Location 类，隐藏 mock 标记
     */
    private fun hookLocationClass(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locationClass = XposedHelpers.findClass(
                "android.location.Location", lpparam.classLoader
            )

            // Hook isMock
            XposedHelpers.findAndHookMethod(
                locationClass, "isMock",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )

            // Hook isFromMockProvider (Android 12+)
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
     * Hook 模拟位置检测（Settings.Secure）
     */
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

    /**
     * 获取或创建伪造的 Location 对象（带 TTL 缓存）
     */
    private fun getOrCreateFakeLocation(): Location {
        val now = System.currentTimeMillis()
        val cached = cachedLocation

        if (cached != null && (now - lastCacheTime) < CACHE_TTL_MS) {
            return cached
        }

        val newLocation = createFakeLocation()
        cachedLocation = newLocation
        lastCacheTime = now
        return newLocation
    }

    /**
     * 创建伪造的 Location 对象
     */
    private fun createFakeLocation(): Location {
        val fakeLocation = ConfigManager.getFakeLocation()

        // 微小随机偏移，模拟 GPS 信号波动
        val latOffset = MIN_OFFSET + random.nextDouble() * (MAX_OFFSET - MIN_OFFSET)
        val lngOffset = MIN_OFFSET + random.nextDouble() * (MAX_OFFSET - MIN_OFFSET)

        return Location(LocationManager.GPS_PROVIDER).apply {
            latitude = fakeLocation.latitude + latOffset * if (random.nextBoolean()) 1 else -1
            longitude = fakeLocation.longitude + lngOffset * if (random.nextBoolean()) 1 else -1
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
     * 清除 Location 的 mock 标记（通过反射）
     */
    @SuppressLint("BlockedPrivateApi")
    private fun Location.clearMockFlag() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val field = Location::class.java.getDeclaredField("mFieldsMask")
                field.isAccessible = true
                val mask = field.getInt(this)
                field.setInt(this, mask and 0x10.inv())
            } else {
                val field = Location::class.java.getDeclaredField("mIsMock")
                field.isAccessible = true
                field.setBoolean(this, false)
            }
        } catch (_: Exception) {}
    }

    private fun shouldHook(): Boolean {
        return ConfigManager.isEnabled() && ConfigManager.isFakeLocationEnabled()
    }
}
