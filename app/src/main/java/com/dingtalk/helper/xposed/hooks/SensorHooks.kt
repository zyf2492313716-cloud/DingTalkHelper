package com.dingtalk.helper.xposed.hooks

import android.hardware.Sensor
import android.hardware.SensorEventListener
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.data.FakeDataProvider
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
        return FakeDataProvider.isMoving()
    }

    private fun getSpeed(): Float {
        return FakeDataProvider.getSpeed()
    }

    private fun getBearing(): Float {
        return FakeDataProvider.getBearing()
    }

    private fun modifyAccelerometer(values: FloatArray, timestamp: Long): FloatArray {
        val t = timestampToSeconds(timestamp).toFloat()
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
        val t = timestampToSeconds(timestamp).toFloat()
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
        val horizontalMag = getHorizontalMagComponent()
        val verticalMag = getVerticalMagComponent()

        val noiseScale = if (isMoving()) MAG_STATIC_NOISE * 2f else MAG_STATIC_NOISE

        val result = FloatArray(3)
        result[0] = horizontalMag * cos(bearingRad) + gaussianNoise(noiseScale)
        result[1] = -horizontalMag * sin(bearingRad) * cos(declination) +
                verticalMag * sin(declination) + gaussianNoise(noiseScale)
        result[2] = horizontalMag * sin(bearingRad) * sin(declination) +
                verticalMag * cos(declination) + gaussianNoise(noiseScale)

        return result
    }

    /**
     * 磁偏角（度）= -0.15 * 经度（适用于中国地区，误差约 1-2 度）
     * 结果限制在 [-20, +20] 度范围内
     * 注意：返回值为弧度制，供三角函数使用
     */
    private fun getMagneticDeclination(): Float {
        return try {
            val lon = ConfigManager.getLongitude()
            val declinationDeg = (-0.15 * lon).coerceIn(-20.0, 20.0)
            Math.toRadians(declinationDeg).toFloat()
        } catch (_: Exception) {
            0.1f
        }
    }

    /**
     * 基于纬度计算水平磁场分量 H
     * H ≈ 25 + 15 * cos(2*lat)（中国地区近似）
     */
    private fun getHorizontalMagComponent(): Float {
        return try {
            val lat = ConfigManager.getLatitude()
            val latRad = Math.toRadians(lat)
            (25.0 + 15.0 * cos(2.0 * latRad)).toFloat()
        } catch (_: Exception) {
            30.0f
        }
    }

    /**
     * 基于纬度计算垂直磁场分量 Z
     * Z ≈ -45 * sin(lat)（中国地区近似）
     */
    private fun getVerticalMagComponent(): Float {
        return try {
            val lat = ConfigManager.getLatitude()
            val latRad = Math.toRadians(lat)
            (-45.0 * sin(latRad)).toFloat()
        } catch (_: Exception) {
            MAG_DOWN_BASE
        }
    }

    private fun gaussianNoise(amplitude: Float): Float {
        return (ThreadLocalRandom.current().nextGaussian() * amplitude).toFloat()
    }

    private fun timestampToSeconds(timestamp: Long): Double {
        return timestamp / 1_000_000_000.0
    }

    private fun modifyGravity(values: FloatArray, timestamp: Long): FloatArray {
        val result = FloatArray(3)

        if (isMoving()) {
            val t = timestampToSeconds(timestamp).toFloat()
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
            val t = timestampToSeconds(timestamp).toFloat()
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
