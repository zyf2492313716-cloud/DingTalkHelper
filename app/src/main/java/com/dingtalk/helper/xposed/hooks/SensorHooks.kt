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
import java.util.concurrent.ThreadLocalRandom
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

        private const val MAG_DOWN_BASE = -42.0f

        private const val PRESSURE_BASE = 1013.25f

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

            val hookCallback = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isEnabled()) return
                    val sensor = param.args[1] as? Sensor ?: return
                    registerSensorModifier(sensor.type)
                }
            }

            val signatures = listOf(
                // (SensorEventListener, Sensor, int)
                arrayOf(
                    SensorEventListener::class.java,
                    Sensor::class.java,
                    Int::class.javaPrimitiveType
                ),
                // (SensorEventListener, Sensor, int, int)
                arrayOf(
                    SensorEventListener::class.java,
                    Sensor::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ),
                // (SensorEventListener, Sensor, int, Handler)
                arrayOf(
                    SensorEventListener::class.java,
                    Sensor::class.java,
                    Int::class.javaPrimitiveType,
                    android.os.Handler::class.java
                ),
                // (SensorEventListener, Sensor, int, int, Handler)
                arrayOf(
                    SensorEventListener::class.java,
                    Sensor::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    android.os.Handler::class.java
                )
            )

            for (signature in signatures) {
                try {
                    XposedHelpers.findAndHookMethod(
                        sensorManagerClass,
                        "registerListener",
                        *signature,
                        hookCallback
                    )
                } catch (_: NoSuchMethodError) {
                } catch (_: Exception) {
                }
            }

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
                    Int::class.javaPrimitiveType,
                    FloatArray::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!ConfigManager.isEnabled()) return

                            val sensorType = (param.args[0] as? Number)?.toInt() ?: return
                            val values = param.args[1] as? FloatArray ?: return
                            val timestamp = (param.args[3] as? Number)?.toLong() ?: 0L

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
        val modifier: (FloatArray, Long) -> FloatArray = when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> ::modifyAccelerometer
            Sensor.TYPE_GYROSCOPE -> ::modifyGyroscope
            Sensor.TYPE_MAGNETIC_FIELD -> ::modifyMagneticField
            Sensor.TYPE_GRAVITY -> ::modifyGravity
            Sensor.TYPE_LINEAR_ACCELERATION -> ::modifyLinearAcceleration
            Sensor.TYPE_PRESSURE -> ::modifyPressure
            else -> return
        }
        sensorModifiers.putIfAbsent(sensorType, modifier)
    }

    private fun isMoving(): Boolean {
        return try {
            LocationHooks.getCurrentFakeLocation().speed > 0.5f
        } catch (_: Exception) { false }
    }

    private fun getSpeed(): Float {
        return try {
            LocationHooks.getCurrentFakeLocation().speed
        } catch (_: Exception) {
            0f
        }
    }

    private fun getBearing(): Float {
        return try {
            LocationHooks.getCurrentFakeLocation().bearing
        } catch (_: Exception) {
            0f
        }
    }

    private fun modifyAccelerometer(values: FloatArray, timestamp: Long): FloatArray {
        val t = (timestamp % 1_000_000_000) / 1_000_000_000.0f + (timestamp / 1_000_000_000).toFloat()
        val result = FloatArray(3)

        if (isMoving()) {
            val walkPhase = (t * WALK_FREQ_HZ * TWO_PI).toDouble()
            val walkSin = sin(walkPhase).toFloat()
            val speedFactor = (getSpeed() / 3.0f).coerceIn(0.5f, 2.0f)

            result[0] = (ACCEL_WALK_AMPLITUDE * 0.3f * walkSin * speedFactor +
                    gaussianNoise(ACCEL_STATIC_NOISE * 0.5f))
            result[1] = (ACCEL_WALK_AMPLITUDE * 0.15f * sin(walkPhase * 0.7).toFloat() * speedFactor +
                    gaussianNoise(ACCEL_STATIC_NOISE * 0.5f))
            result[2] = (GRAVITY + ACCEL_WALK_AMPLITUDE * 0.5f * sin(walkPhase * 2).toFloat() * speedFactor +
                    gaussianNoise(ACCEL_STATIC_NOISE * 0.5f))
        } else {
            result[0] = gaussianNoise(ACCEL_STATIC_NOISE)
            result[1] = gaussianNoise(ACCEL_STATIC_NOISE)
            result[2] = GRAVITY + gaussianNoise(ACCEL_STATIC_NOISE)
        }

        return result
    }

    private fun modifyGyroscope(values: FloatArray, timestamp: Long): FloatArray {
        val t = (timestamp % 1_000_000_000) / 1_000_000_000.0f + (timestamp / 1_000_000_000).toFloat()
        val result = FloatArray(3)

        if (isMoving()) {
            val walkPhase = (t * WALK_FREQ_HZ * TWO_PI).toDouble()
            val speedFactor = (getSpeed() / 3.0f).coerceIn(0.5f, 2.0f)

            result[0] = (GYRO_WALK_AMPLITUDE * sin(walkPhase * 0.8).toFloat() * speedFactor +
                    gaussianNoise(GYRO_STATIC_NOISE))
            result[1] = (GYRO_WALK_AMPLITUDE * 0.5f * cos(walkPhase).toFloat() * speedFactor +
                    gaussianNoise(GYRO_STATIC_NOISE))
            result[2] = gaussianNoise(GYRO_STATIC_NOISE * 1.5f)
        } else {
            result[0] = gaussianNoise(GYRO_STATIC_NOISE)
            result[1] = gaussianNoise(GYRO_STATIC_NOISE)
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
            val lon = ConfigManager.getLongitude()
            Math.toRadians(-0.15 * lon).toFloat()
        } catch (_: Exception) {
            0.1f
        }
    }

    private fun gaussianNoise(amplitude: Float): Float {
        return (ThreadLocalRandom.current().nextGaussian() * amplitude).toFloat()
    }

    private fun modifyGravity(values: FloatArray, timestamp: Long): FloatArray {
        val result = FloatArray(3)

        if (isMoving()) {
            val t = (timestamp % 1_000_000_000) / 1_000_000_000.0f + (timestamp / 1_000_000_000).toFloat()
            val walkPhase = (t * WALK_FREQ_HZ * TWO_PI).toDouble()
            val speedFactor = (getSpeed() / 3.0f).coerceIn(0.5f, 2.0f)

            result[0] = gaussianNoise(ACCEL_STATIC_NOISE * 0.3f)
            result[1] = gaussianNoise(ACCEL_STATIC_NOISE * 0.3f)
            result[2] = GRAVITY + ACCEL_WALK_AMPLITUDE * 0.2f * sin(walkPhase).toFloat() * speedFactor +
                    gaussianNoise(ACCEL_STATIC_NOISE * 0.3f)
        } else {
            result[0] = gaussianNoise(ACCEL_STATIC_NOISE * 0.2f)
            result[1] = gaussianNoise(ACCEL_STATIC_NOISE * 0.2f)
            result[2] = GRAVITY + gaussianNoise(ACCEL_STATIC_NOISE * 0.2f)
        }

        return result
    }

    private fun modifyLinearAcceleration(values: FloatArray, timestamp: Long): FloatArray {
        val result = FloatArray(3)

        if (isMoving()) {
            val t = (timestamp % 1_000_000_000) / 1_000_000_000.0f + (timestamp / 1_000_000_000).toFloat()
            val walkPhase = (t * WALK_FREQ_HZ * TWO_PI).toDouble()
            val speedFactor = (getSpeed() / 3.0f).coerceIn(0.5f, 2.0f)

            result[0] = ACCEL_WALK_AMPLITUDE * 0.3f * sin(walkPhase).toFloat() * speedFactor +
                    gaussianNoise(ACCEL_STATIC_NOISE * 0.5f)
            result[1] = ACCEL_WALK_AMPLITUDE * 0.15f * sin(walkPhase * 0.7).toFloat() * speedFactor +
                    gaussianNoise(ACCEL_STATIC_NOISE * 0.5f)
            result[2] = ACCEL_WALK_AMPLITUDE * 0.5f * sin(walkPhase * 2).toFloat() * speedFactor +
                    gaussianNoise(ACCEL_STATIC_NOISE * 0.5f)
        } else {
            result[0] = gaussianNoise(ACCEL_STATIC_NOISE)
            result[1] = gaussianNoise(ACCEL_STATIC_NOISE)
            result[2] = gaussianNoise(ACCEL_STATIC_NOISE)
        }

        return result
    }

    private fun modifyPressure(values: FloatArray, timestamp: Long): FloatArray {
        val result = FloatArray(1)
        val altitude = try {
            ConfigManager.getFakeLocation().altitude.toFloat()
        } catch (_: Exception) {
            0f
        }
        val pressureFromAltitude = PRESSURE_BASE * Math.pow(1.0 - 2.2558e-5 * altitude, 5.2558).toFloat()
        result[0] = pressureFromAltitude + gaussianNoise(0.1f)
        return result
    }
}
