package com.dingtalk.helper.xposed.hooks

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 传感器数据伪造 Hook
 * 负责拦截和替换传感器数据
 */
class SensorHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${HookEntry.TAG}:Sensor"
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG: 开始注入传感器伪造 Hook")

        // Hook SensorManager
        hookSensorManager(lpparam)

        // Hook SensorEvent
        hookSensorEvent(lpparam)
    }

    /**
     * Hook SensorManager 注册监听器的方法
     */
    private fun hookSensorManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorManagerClass = XposedHelpers.findClass(
                "android.hardware.SensorManager",
                lpparam.classLoader
            )

            // Hook registerListener
            XposedHelpers.findAndHookMethod(
                sensorManagerClass,
                "registerListener",
                SensorEventListener::class.java,
                Sensor::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (shouldHook(param)) {
                            val listener = param.args[0] as SensorEventListener
                            val sensor = param.args[1] as Sensor

                            // 根据传感器类型进行伪造
                            when (sensor.type) {
                                Sensor.TYPE_GYROSCOPE -> {
                                    XposedBridge.log("$TAG: 拦截陀螺仪传感器")
                                    // 可以在这里注入伪造的陀螺仪数据
                                }
                                Sensor.TYPE_ACCELEROMETER -> {
                                    XposedBridge.log("$TAG: 拦截加速度传感器")
                                    // 可以在这里注入伪造的加速度数据
                                }
                                Sensor.TYPE_MAGNETIC_FIELD -> {
                                    XposedBridge.log("$TAG: 拦截磁力传感器")
                                }
                            }
                        }
                    }
                }
            )

            // Hook registerListener (带 Handler 参数)
            XposedHelpers.findAndHookMethod(
                sensorManagerClass,
                "registerListener",
                SensorEventListener::class.java,
                Sensor::class.java,
                Int::class.java,
                android.os.Handler::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (shouldHook(param)) {
                            XposedBridge.log("$TAG: 拦截传感器监听器注册 (Handler)")
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: SensorManager Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: SensorManager Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook SensorEvent 数据
     */
    private fun hookSensorEvent(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorEventClass = XposedHelpers.findClass(
                "android.hardware.SensorEvent",
                lpparam.classLoader
            )

            // Hook values 数组访问
            XposedHelpers.findAndHookMethod(
                sensorEventClass,
                "toString",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 可以在这里修改返回的字符串表示
                    }
                }
            )

            XposedBridge.log("$TAG: SensorEvent Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: SensorEvent Hook 失败: ${e.message}")
        }
    }

    /**
     * 伪造陀螺仪数据
     * 模拟手机静止状态
     */
    private fun createFakeGyroscopeData(): FloatArray {
        // 返回微小噪声，模拟手机静止
        return floatArrayOf(
            (Math.random() * 0.01 - 0.005).toFloat(),
            (Math.random() * 0.01 - 0.005).toFloat(),
            (Math.random() * 0.01 - 0.005).toFloat()
        )
    }

    /**
     * 伪造加速度数据
     * 模拟手机静止状态（只受重力影响）
     */
    private fun createFakeAccelerometerData(): FloatArray {
        // 返回重力加速度 + 微小噪声
        return floatArrayOf(
            (Math.random() * 0.1 - 0.05).toFloat(),
            (Math.random() * 0.1 - 0.05).toFloat(),
            (9.8 + Math.random() * 0.1 - 0.05).toFloat()
        )
    }

    /**
     * 伪造磁力计数据
     * 返回合理的磁场强度
     */
    private fun createFakeMagneticFieldData(): FloatArray {
        // 返回合理的磁场强度（单位：微特斯拉）
        return floatArrayOf(
            (25 + Math.random() * 10 - 5).toFloat(),
            (5 + Math.random() * 10 - 5).toFloat(),
            (-45 + Math.random() * 10 - 5).toFloat()
        )
    }

    /**
     * 判断是否需要 Hook
     */
    private fun shouldHook(param: XC_MethodHook.MethodHookParam): Boolean {
        if (!ConfigManager.isEnabled()) return false
        return true
    }
}