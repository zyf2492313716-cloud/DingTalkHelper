package com.dingtalk.helper.xposed.hooks

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class FusedLocationHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:FusedLocation"

        private val GMS_FUSED_CLASS = "com.google.android.gms.location.FusedLocationProviderClient"
        private val GMS_LOCATION_REQUEST = "com.google.android.gms.location.LocationRequest"
        private val GMS_LOCATION_CALLBACK = "com.google.android.gms.location.LocationCallback"
        private val GMS_LOCATION_RESULT = "com.google.android.gms.location.LocationResult"

        private val SYSTEM_FUSED_CLASS = "com.android.location.fused.FusedLocationProvider"

        private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "FusedLocationHooks-Scheduler").apply { isDaemon = true }
        }
        private val activeTasks = ConcurrentHashMap<Any, ScheduledFuture<*>>()

        private fun getFakeLocation(): Location {
            val fake = LocationHooks.getCurrentFakeLocation()
            return Location(LocationManager.GPS_PROVIDER).apply {
                latitude = fake.latitude
                longitude = fake.longitude
                altitude = fake.altitude
                speed = fake.speed
                bearing = fake.bearing
                accuracy = fake.accuracy
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                clearMockFlag()
            }
        }

        private fun Location.clearMockFlag() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setMock(false)
                } else {
                    val field = Location::class.java.getDeclaredField("mIsMock")
                    field.isAccessible = true
                    field.setBoolean(this, false)
                }
            } catch (_: Exception) {}
        }
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isFakeLocationEnabled()) {
            HookUtils.logDebug("$TAG: 虚拟定位未启用，跳过")
            return
        }

        HookUtils.log("$TAG: 开始注入 FusedLocation Hook")

        hookGmsFusedLocation(lpparam)
        hookSystemFusedLocation(lpparam)

        HookUtils.log("$TAG: FusedLocation Hook 注入完成")
    }

    private fun hookGmsFusedLocation(lpparam: XC_LoadPackage.LoadPackageParam) {
        val fusedClass = HookUtils.findClassByNames(
            listOf(GMS_FUSED_CLASS),
            lpparam.classLoader
        )

        if (fusedClass == null) {
            HookUtils.logDebug("$TAG: GMS FusedLocationProviderClient 未找到，跳过")
            return
        }

        HookUtils.log("$TAG: 找到 GMS FusedLocationProviderClient，开始 Hook")

        blindHookAllLocationMethods(fusedClass)

        hookGetLastLocation(fusedClass)
        hookGetCurrentLocation(fusedClass)
        hookRequestLocationUpdates(fusedClass, lpparam.classLoader)
        hookRemoveLocationUpdates(fusedClass)
    }

    private fun hookSystemFusedLocation(lpparam: XC_LoadPackage.LoadPackageParam) {
        val systemFusedClass = HookUtils.findClassByNames(
            listOf(
                SYSTEM_FUSED_CLASS,
                "com.android.location.fused.FusedLocationProviderService",
                "com.android.location.fused.FusedLocationProvider"
            ),
            lpparam.classLoader
        )

        if (systemFusedClass == null) {
            HookUtils.logDebug("$TAG: 系统级 FusedLocationProvider 未找到，跳过")
            return
        }

        HookUtils.log("$TAG: 找到系统级 FusedLocationProvider，开始 Hook")

        blindHookAllLocationMethods(systemFusedClass)

        hookSystemGetLastLocation(systemFusedClass)
        hookSystemRequestLocationUpdates(systemFusedClass, lpparam.classLoader)
    }

    private fun blindHookAllLocationMethods(clazz: Class<*>) {
        var hookedCount = 0
        try {
            val methods = clazz.declaredMethods
            for (method in methods) {
                if (method.returnType == Location::class.java) {
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!shouldHook()) return
                                param.result = getFakeLocation()
                            }
                        })
                        hookedCount++
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: BlindHook 扫描失败: ${e.message}")
        }
        HookUtils.logDebug("$TAG: BlindHook 已拦截 $hookedCount 个返回 Location 的方法")
    }

    private fun hookGetLastLocation(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getLastLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldHook()) return
                        param.result = getFakeLocation()
                        HookUtils.logDebug("$TAG: GMS getLastLocation 已替换")
                    }
                }
            )
        } catch (e: NoSuchMethodError) {
            HookUtils.logDebug("$TAG: GMS getLastLocation 方法不存在")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: GMS getLastLocation Hook 失败: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getLastLocation",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldHook()) return
                        param.result = getFakeLocation()
                        HookUtils.logDebug("$TAG: GMS getLastLocation(priority) 已替换")
                    }
                }
            )
        } catch (_: Exception) {}
    }

    private fun hookGetCurrentLocation(clazz: Class<*>) {
        val signatures = listOf(
            arrayOf(GMS_LOCATION_REQUEST),
            arrayOf(Int::class.javaPrimitiveType)
        )

        for (signature in signatures) {
            try {
                val paramTypes = signature.map { type ->
                    if (type is String) XposedHelpers.findClass(type, clazz.classLoader) else type
                }.toTypedArray()

                XposedHelpers.findAndHookMethod(
                    clazz,
                    "getCurrentLocation",
                    *paramTypes,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!shouldHook()) return

                            val fakeLocation = getFakeLocation()

                            val task = param.args.lastOrNull()
                            if (task != null) {
                                try {
                                    val isCompleteField = task.javaClass.getDeclaredField("isComplete")
                                    isCompleteField.isAccessible = true

                                    val resultField = task.javaClass.superclass?.getDeclaredField("result")
                                    resultField?.isAccessible = true

                                    XposedHelpers.callMethod(task, "trySetResult", fakeLocation)
                                } catch (_: Exception) {
                                    try {
                                        XposedHelpers.callMethod(task, "setResult", fakeLocation)
                                    } catch (_: Exception) {}
                                }
                            }

                            param.result = task
                            HookUtils.logDebug("$TAG: GMS getCurrentLocation 已拦截")
                        }
                    }
                )
            } catch (_: NoSuchMethodError) {
                continue
            } catch (_: Exception) {
                continue
            }
        }
    }

    private fun hookRequestLocationUpdates(clazz: Class<*>, classLoader: ClassLoader) {
        val callbackClass = try {
            XposedHelpers.findClass(GMS_LOCATION_CALLBACK, classLoader)
        } catch (_: ClassNotFoundException) {
            HookUtils.logDebug("$TAG: LocationCallback 类未找到")
            return
        }

        val requestClass = try {
            XposedHelpers.findClass(GMS_LOCATION_REQUEST, classLoader)
        } catch (_: ClassNotFoundException) {
            HookUtils.logDebug("$TAG: LocationRequest 类未找到")
            return
        }

        val signatures = listOf(
            arrayOf(requestClass, callbackClass, Looper::class.java),
            arrayOf(requestClass, callbackClass, Executor::class.java)
        )

        for (signature in signatures) {
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "requestLocationUpdates",
                    *signature,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!shouldHook()) return

                            val callback = param.args[1] ?: return
                            val looper = param.args.firstOrNull { it is Looper } as? Looper
                            val executor = param.args.firstOrNull { it is Executor } as? Executor

                            val intervalMs = extractIntervalFromRequest(param.args[0], requestClass)

                            param.result = null

                            scheduleCallbackUpdates(callback, intervalMs, looper, executor)

                            HookUtils.logDebug("$TAG: GMS requestLocationUpdates 已拦截")
                        }
                    }
                )
            } catch (_: NoSuchMethodError) {
                continue
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: requestLocationUpdates Hook 失败: ${e.message}")
            }
        }
    }

    private fun extractIntervalFromRequest(requestObj: Any?, requestClass: Class<*>): Long {
        if (requestObj == null) return 1000L
        return try {
            val getInterval = requestClass.getDeclaredMethod("getIntervalMillis")
            getInterval.isAccessible = true
            getInterval.invoke(requestObj) as? Long ?: 1000L
        } catch (_: Exception) {
            try {
                val getInterval = requestClass.getDeclaredMethod("getInterval")
                getInterval.isAccessible = true
                (getInterval.invoke(requestObj) as? Long) ?: 1000L
            } catch (_: Exception) {
                1000L
            }
        }
    }

    private fun scheduleCallbackUpdates(
        callback: Any,
        intervalMs: Long,
        looper: Looper?,
        executor: Executor?
    ) {
        cancelUpdates(callback)

        val effectiveInterval = maxOf(intervalMs, 1000L)

        val future = scheduledExecutor.scheduleAtFixedRate({
            try {
                val fakeLocation = getFakeLocation()
                val locationResult = createLocationResult(fakeLocation)

                val deliverRunnable = Runnable {
                    try {
                        val onLocationResult = callback.javaClass.getMethod(
                            "onLocationResult",
                            locationResult.javaClass
                        )
                        onLocationResult.invoke(callback, locationResult)
                    } catch (_: Exception) {
                        try {
                            XposedHelpers.callMethod(callback, "onLocationResult", locationResult)
                        } catch (_: Exception) {}
                    }
                }

                when {
                    executor != null -> executor.execute(deliverRunnable)
                    looper != null -> android.os.Handler(looper).post(deliverRunnable)
                    else -> android.os.Handler(Looper.getMainLooper()).post(deliverRunnable)
                }
            } catch (_: Exception) {}
        }, 100, effectiveInterval, TimeUnit.MILLISECONDS)

        activeTasks[callback] = future
    }

        @Volatile
        private var cachedCreateLocationResultMethod: java.lang.reflect.Method? = null

        private fun createLocationResult(location: Location): Any {
            cachedCreateLocationResultMethod?.let { method ->
                try {
                    return method.invoke(null, listOf(location))!!
                } catch (_: Exception) {
                    cachedCreateLocationResultMethod = null
                }
            }

            return try {
                val resultClass = XposedHelpers.findClass(GMS_LOCATION_RESULT, location.javaClass.classLoader)
                val method = resultClass.getDeclaredMethod("create", Location::class.java)
                method.isAccessible = true
                cachedCreateLocationResultMethod = method
                method.invoke(null, location)!!
            } catch (_: Exception) {
                try {
                    val resultClass = XposedHelpers.findClass(GMS_LOCATION_RESULT, location.javaClass.classLoader)
                    val locations = listOf(location)
                    XposedHelpers.newInstance(resultClass, locations)
                } catch (_: Exception) {
                    try {
                        val resultClass = XposedHelpers.findClass(GMS_LOCATION_RESULT, location.javaClass.classLoader)
                        val constructor = resultClass.getDeclaredConstructor(List::class.java)
                        constructor.isAccessible = true
                        constructor.newInstance(listOf(location))
                    } catch (_: Exception) {
                        val resultClass = XposedHelpers.findClass(GMS_LOCATION_RESULT, location.javaClass.classLoader)
                        val obj = XposedHelpers.newInstance(resultClass)
                        XposedHelpers.setObjectField(obj, "mLocations", listOf(location))
                        obj
                    }
                }
            }
        }

    private fun hookRemoveLocationUpdates(clazz: Class<*>) {
        val callbackClass = try {
            XposedHelpers.findClass(GMS_LOCATION_CALLBACK, clazz.classLoader)
        } catch (_: ClassNotFoundException) {
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "removeLocationUpdates",
                callbackClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callback = param.args[0] ?: return
                        cancelUpdates(callback)
                        HookUtils.logDebug("$TAG: removeLocationUpdates 已清理定时任务")
                    }
                }
            )
        } catch (_: Exception) {}
    }

    private fun hookSystemGetLastLocation(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getLastLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldHook()) return
                        param.result = getFakeLocation()
                        HookUtils.logDebug("$TAG: System getLastLocation 已替换")
                    }
                }
            )
        } catch (_: NoSuchMethodError) {
        } catch (_: Exception) {}
    }

    private fun hookSystemRequestLocationUpdates(clazz: Class<*>, classLoader: ClassLoader) {
        val methods = try {
            clazz.declaredMethods.filter { it.name == "requestLocationUpdates" }
        } catch (_: Exception) {
            emptyList()
        }

        for (method in methods) {
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldHook()) return

                        val callback = param.args.firstOrNull { arg ->
                            arg != null && hasOnLocationResultMethod(arg)
                        } ?: return

                        val pendingIntent = param.args.firstOrNull { arg ->
                            arg is android.app.PendingIntent
                        } as? android.app.PendingIntent

                        if (pendingIntent != null) {
                            schedulePendingIntentUpdates(pendingIntent, 1000L)
                        } else {
                            scheduleSystemCallbackUpdates(callback, 1000L)
                        }

                        param.result = null
                        HookUtils.logDebug("$TAG: System requestLocationUpdates 已拦截")
                    }
                })
            } catch (_: Exception) {}
        }
    }

    private fun hasOnLocationResultMethod(obj: Any): Boolean {
        return try {
            obj.javaClass.methods.any { it.name == "onLocationResult" }
        } catch (_: Exception) {
            false
        }
    }

    private fun scheduleSystemCallbackUpdates(callback: Any, intervalMs: Long) {
        cancelUpdates(callback)

        val effectiveInterval = maxOf(intervalMs, 1000L)

        val future = scheduledExecutor.scheduleAtFixedRate({
            try {
                val fakeLocation = getFakeLocation()

                val onLocationChanged = callback.javaClass.methods.firstOrNull {
                    it.name == "onLocationResult" && it.parameterTypes.size == 1
                }

                if (onLocationChanged != null) {
                    val locationResult = createLocationResult(fakeLocation)
                    onLocationChanged.invoke(callback, locationResult)
                } else {
                    val fallback = callback.javaClass.methods.firstOrNull {
                        it.name == "onLocationChanged" && it.parameterTypes.size == 1
                    }
                    fallback?.invoke(callback, fakeLocation)
                }
            } catch (_: Exception) {}
        }, 100, effectiveInterval, TimeUnit.MILLISECONDS)

        activeTasks[callback] = future
    }

    private fun schedulePendingIntentUpdates(pendingIntent: android.app.PendingIntent, intervalMs: Long) {
        cancelUpdates(pendingIntent)

        val effectiveInterval = maxOf(intervalMs, 1000L)

        val future = scheduledExecutor.scheduleAtFixedRate({
            try {
                val fakeLocation = getFakeLocation()
                val intent = android.content.Intent().apply {
                    putExtra(LocationManager.KEY_LOCATION_CHANGED, fakeLocation)
                }
                val context = android.app.AndroidAppHelper.currentApplication()
                pendingIntent.send(context, 0, intent)
            } catch (_: Exception) {}
        }, 100, effectiveInterval, TimeUnit.MILLISECONDS)

        activeTasks[pendingIntent] = future
    }

    private fun cancelUpdates(key: Any) {
        activeTasks.remove(key)?.cancel(false)
    }

    private fun shouldHook(): Boolean {
        return ConfigManager.isEnabled() && ConfigManager.isFakeLocationEnabled()
    }
}
