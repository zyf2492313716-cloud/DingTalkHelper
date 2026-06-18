package com.dingtalk.helper.xposed.hooks

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 传感器数据伪造 Hook
 * 拦截传感器数据，使设备看起来处于静止状态
 * 防止钉钉通过陀螺仪/加速度计检测异常移动模式
 */
class SensorHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:Sensor"

        // 传感器数据修改器缓存
        private val sensorModifiers = mutableMapOf<Int, (FloatArray) -> FloatArray>()
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isEnabled()) return

        HookUtils.log("$TAG: 开始注入传感器伪造 Hook")
        hookSensorManager(lpparam)
        hookSensorEvent(lpparam)
    }

    /**
     * Hook SensorManager.registerListener - 记录监听的传感器类型
     */
    private fun hookSensorManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorManagerClass = XposedHelpers.findClass(
                "android.hardware.SensorManager", lpparam.classLoader
            )

            // Hook registerListener (带 int delay 参数)
            XposedHelpers.findAndHookMethod(
                sensorManagerClass, "registerListener",
                SensorEventListener::class.java,
                Sensor::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isEnabled()) return
                        val sensor = param.args[1] as? Sensor ?: return
                        registerSensorModifier(sensor.type)
                    }
                }
            )

            // Hook registerListener (带 Handler 参数)
            XposedHelpers.findAndHookMethod(
                sensorManagerClass, "registerListener",
                SensorEventListener::class.java,
                Sensor::class.java,
                Int::class.java,
                android.os.Handler::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isEnabled()) return
                        val sensor = param.args[1] as? Sensor ?: return
                        registerSensorModifier(sensor.type)
                    }
                }
            )

            HookUtils.log("$TAG: SensorManager Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: SensorManager Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook SensorEvent.values - 在数据回调时注入伪造值
     */
    private fun hookSensorEvent(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorEventClass = XposedHelpers.findClass(
                "android.hardware.SensorEvent", lpparam.classLoader
            )

            // 通过 Hook SensorEventListener.onSensorDispatch 间接修改数据
            // 更可靠的方式是直接在 SensorManager 层面拦截
            val sensorManagerClass = XposedHelpers.findClass(
                "android.hardware.SensorManager", lpparam.classLoader
            )

            // 尝试 Hook dispatchSensorEvent (内部方法)
            try {
                XposedHelpers.findAndHookMethod(
                    sensorManagerClass,
                    "dispatchSensorEvent",
                    Int::class.java,
                    FloatArray::class.java,
                    Int::class.java,
                    Long::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!ConfigManager.isEnabled()) return

                            val sensorType = param.args[0] as? Int ?: return
                            val values = param.args[1] as? FloatArray ?: return

                            val modifier = sensorModifiers[sensorType] ?: return
                            val modified = modifier(values)
                            System.arraycopy(modified, 0, values, 0, minOf(values.size, modified.size))
                        }
                    }
                )
            } catch (_: NoSuchMethodError) {
                // 不同 Android 版本方法签名不同，忽略
            }

            HookUtils.log("$TAG: SensorEvent Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: SensorEvent Hook 失败: ${e.message}")
        }
    }

    /**
     * 注册传感器数据修改器
     */
    private fun registerSensorModifier(sensorType: Int) {
        if (sensorModifiers.containsKey(sensorType)) return

        when (sensorType) {
            Sensor.TYPE_GYROSCOPE -> {
                sensorModifiers[sensorType] = { values ->
                    // 模拟静止：微小噪声
                    floatArrayOf(
                        (values[0] * 0.01 + (Math.random() * 0.002 - 0.001)).toFloat(),
                        (values[1] * 0.01 + (Math.random() * 0.002 - 0.001)).toFloat(),
                        (values[2] * 0.01 + (Math.random() * 0.002 - 0.001)).toFloat()
                    )
                }
                HookUtils.logDebug("$TAG: 注册陀螺仪修改器")
            }
            Sensor.TYPE_ACCELEROMETER -> {
                sensorModifiers[sensorType] = { values ->
                    // 模拟静止：重力 + 微小噪声
                    floatArrayOf(
                        (Math.random() * 0.04 - 0.02).toFloat(),
                        (Math.random() * 0.04 - 0.02).toFloat(),
                        (9.8 + Math.random() * 0.04 - 0.02).toFloat()
                    )
                }
                HookUtils.logDebug("$TAG: 注册加速度计修改器")
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                sensorModifiers[sensorType] = { values ->
                    // 返回合理的地球磁场值（微特斯拉）
                    floatArrayOf(
                        (25 + Math.random() * 2 - 1).toFloat(),
                        (5 + Math.random() * 2 - 1).toFloat(),
                        (-45 + Math.random() * 2 - 1).toFloat()
                    )
                }
                HookUtils.logDebug("$TAG: 注册磁力计修改器")
            }
        }
    }
}
