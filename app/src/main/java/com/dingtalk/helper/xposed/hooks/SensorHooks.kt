package com.dingtalk.helper.xposed.hooks

import android.hardware.Sensor
import android.hardware.SensorEventListener
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

class SensorHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:Sensor"

        private const val GRAVITY = 9.80665f

        // 静止状态噪声幅度
        private const val ACCEL_STATIC_NOISE = 0.02f
        private const val GYRO_STATIC_NOISE = 0.001f
        private const val MAG_STATIC_NOISE = 1.0f

        // 移动状态参数
        private const val ACCEL_WALK_AMPLITUDE = 0.4f
        private const val GYRO_WALK_AMPLITUDE = 0.03f
        private const val WALK_FREQ_HZ = 2.0
        private const val TWO_PI = 6.2831853f

        // 呼吸/心跳微小变化频率
        private const val BREATH_FREQ_HZ = 0.25
        private const val HEARTBEAT_FREQ_HZ = 1.2
        private const val BREATH_AMPLITUDE = 0.005f
        private const val HEARTBEAT_AMPLITUDE = 0.002f

        // 地球磁场垂直分量 (微特斯拉)，中国地区参考值
        private const val MAG_DOWN_BASE = -42.0f

        // 传感器类型到修改器的映射
        private val sensorModifiers = ConcurrentHashMap<Int, (FloatArray, Long) -> FloatArray>()
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isEnabled()) return

        HookUtils.log("$TAG: 开始注入传感器伪造 Hook")
        hookSensorManager(lpparam)
        hookSensorEvent(lpparam)
    }

    private fun hookSensorManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorManagerClass = XposedHelpers.findClass(
                "android.hardware.SensorManager", lpparam.classLoader
            )

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

    private fun hookSensorEvent(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorManagerClass = XposedHelpers.findClass(
                "android.hardware.SensorManager", lpparam.classLoader
            )

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
                            val timestamp = param.args[3] as? Long ?: 0L

                            val modifier = sensorModifiers[sensorType] ?: return
                            val modified = modifier(values, timestamp)
                            System.arraycopy(modified, 0, values, 0, minOf(values.size, modified.size))
                        }
                    }
                )
            } catch (_: NoSuchMethodError) {
            }

            HookUtils.log("$TAG: SensorEvent Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: SensorEvent Hook 失败: ${e.message}")
        }
    }

    private fun registerSensorModifier(sensorType: Int) {
        if (sensorModifiers.containsKey(sensorType)) return

        when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> {
                sensorModifiers[sensorType] = ::modifyAccelerometer
                HookUtils.logDebug("$TAG: 注册加速度计修改器")
            }
            Sensor.TYPE_GYROSCOPE -> {
                sensorModifiers[sensorType] = ::modifyGyroscope
                HookUtils.logDebug("$TAG: 注册陀螺仪修改器")
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                sensorModifiers[sensorType] = ::modifyMagneticField
                HookUtils.logDebug("$TAG: 注册磁力计修改器")
            }
        }
    }

    private fun isMoving(): Boolean {
        return try {
            ConfigManager.getFakeLocation().speed > 0f
        } catch (_: Exception) {
            false
        }
    }

    private fun getBearing(): Float {
        return try {
            ConfigManager.getFakeLocation().bearing
        } catch (_: Exception) {
            0f
        }
    }

    private fun modifyAccelerometer(values: FloatArray, timestamp: Long): FloatArray {
        val t = timestamp / 1_000_000_000.0f
        val result = FloatArray(3)

        if (isMoving()) {
            val walkPhase = (t * WALK_FREQ_HZ * TWO_PI).toDouble()
            val walkSin = sin(walkPhase).toFloat()

            result[0] = (ACCEL_WALK_AMPLITUDE * 0.3f * walkSin +
                    gaussianNoise(ACCEL_STATIC_NOISE * 0.5f))
            result[1] = (ACCEL_WALK_AMPLITUDE * 0.15f * sin(walkPhase * 0.7).toFloat() +
                    gaussianNoise(ACCEL_STATIC_NOISE * 0.5f))
            result[2] = (GRAVITY + ACCEL_WALK_AMPLITUDE * 0.5f * sin(walkPhase * 2).toFloat() +
                    gaussianNoise(ACCEL_STATIC_NOISE * 0.5f))
        } else {
            val breathPhase = (t * BREATH_FREQ_HZ * TWO_PI).toDouble()
            val heartPhase = (t * HEARTBEAT_FREQ_HZ * TWO_PI).toDouble()

            val breathX = (BREATH_AMPLITUDE * sin(breathPhase)).toFloat()
            val breathY = (BREATH_AMPLITUDE * sin(breathPhase + 1.57)).toFloat()
            val heartZ = (HEARTBEAT_AMPLITUDE * sin(heartPhase)).toFloat()

            result[0] = breathX + gaussianNoise(ACCEL_STATIC_NOISE)
            result[1] = breathY + gaussianNoise(ACCEL_STATIC_NOISE)
            result[2] = GRAVITY + heartZ + gaussianNoise(ACCEL_STATIC_NOISE)
        }

        return result
    }

    private fun modifyGyroscope(values: FloatArray, timestamp: Long): FloatArray {
        val t = timestamp / 1_000_000_000.0f
        val result = FloatArray(3)

        if (isMoving()) {
            val walkPhase = (t * WALK_FREQ_HZ * TWO_PI).toDouble()

            result[0] = (GYRO_WALK_AMPLITUDE * sin(walkPhase * 0.8).toFloat() +
                    gaussianNoise(GYRO_STATIC_NOISE))
            result[1] = (GYRO_WALK_AMPLITUDE * 0.5f * cos(walkPhase).toFloat() +
                    gaussianNoise(GYRO_STATIC_NOISE))
            result[2] = gaussianNoise(GYRO_STATIC_NOISE * 1.5f)
        } else {
            val drift = (t * 0.0001f) % 0.0005f

            result[0] = gaussianNoise(GYRO_STATIC_NOISE) + drift * sin(t * 0.3).toFloat()
            result[1] = gaussianNoise(GYRO_STATIC_NOISE) + drift * cos(t * 0.5).toFloat()
            result[2] = gaussianNoise(GYRO_STATIC_NOISE)
        }

        return result
    }

    private fun modifyMagneticField(values: FloatArray, timestamp: Long): FloatArray {
        val bearing = getBearing()
        val bearingRad = Math.toRadians(bearing.toDouble()).toFloat()

        val declination = getMagneticDeclination()

        val noiseScale = if (isMoving()) MAG_STATIC_NOISE * 2f else MAG_STATIC_NOISE

        val result = FloatArray(3)
        val horizontalMag = 30.0f
        result[0] = horizontalMag * cos(bearingRad) + gaussianNoise(noiseScale)
        result[1] = -horizontalMag * sin(bearingRad) * cos(declination) +
                MAG_DOWN_BASE * sin(declination) + gaussianNoise(noiseScale)
        result[2] = horizontalMag * sin(bearingRad) * sin(declination) +
                MAG_DOWN_BASE * cos(declination) + gaussianNoise(noiseScale)

        return result
    }

    private fun getMagneticDeclination(): Float {
        return try {
            val lat = ConfigManager.getLatitude()
            val lon = ConfigManager.getLongitude()
            ((lon * 0.01 + lat * 0.005) % 360.0).toFloat() * 0.01745f
        } catch (_: Exception) {
            0.1f
        }
    }

    private fun gaussianNoise(amplitude: Float): Float {
        return ((Math.random() + Math.random() + Math.random() - 1.5) * 0.667 * amplitude).toFloat()
    }
}
