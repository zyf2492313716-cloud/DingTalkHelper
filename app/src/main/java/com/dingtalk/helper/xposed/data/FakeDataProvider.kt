package com.dingtalk.helper.xposed.data

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.utils.Constants
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * 统一数据源层
 * 解决各模块数据不一致的核心问题：GnssHooks/LocationHooks/NmeaHooks/SensorHooks 共享同一份数据
 */
object FakeDataProvider {

    private const val TAG = "${Constants.LOG_PREFIX}:FakeData"

    // ==================== 卫星星座常量 ====================

    const val CONSTELLATION_GPS = 1
    const val CONSTELLATION_SBAS = 2
    const val CONSTELLATION_GLONASS = 3
    const val CONSTELLATION_QZSS = 4
    const val CONSTELLATION_BEIDOU = 5
    const val CONSTELLATION_GALILEO = 6
    const val CONSTELLATION_IRNSS = 7

    private const val GPS_L1 = 1575.42f * 1_000_000f
    private const val GPS_L5 = 1176.45f * 1_000_000f
    private const val GLONASS_L1_BASE = 1602.0f * 1_000_000f
    private const val GLONASS_L1_STEP = 0.5625f * 1_000_000f
    private const val BEIDOU_B1I = 1561.098f * 1_000_000f
    private const val BEIDOU_B1C = 1575.42f * 1_000_000f
    private const val GALILEO_E1 = 1575.42f * 1_000_000f
    private const val GALILEO_E5A = 1176.45f * 1_000_000f

    // ==================== 位置模拟常量 ====================

    private const val MAX_OFFSET = 0.00015
    private const val CACHE_TTL_MIN_MS = 1000L
    private const val CACHE_TTL_MAX_MS = 10000L
    private const val SPEED_STILL_MAX = 0.3f
    private const val SPEED_WALK_MIN = 0.8f
    private const val SPEED_WALK_MAX = 1.5f
    private const val ALTITUDE_DRIFT_MAX = 3.0
    private const val BEARING_DRIFT_MAX = 15.0f
    private const val MAX_HISTORY_SIZE = 100

    // NMEA 常量
    private const val GPS_QUALITY_GPS = 1

    // ==================== 卫星数据 ====================

    data class SatelliteInfo(
        val svid: Int,
        val constellationType: Int,
        val cn0: Float,
        val elevation: Float,
        val azimuth: Float,
        val usedInFix: Boolean,
        val carrierFrequencyHz: Float
    )

    private val satellitesRef = AtomicReference<List<SatelliteInfo>>(null)

    fun getSatellites(): List<SatelliteInfo> {
        var sats = satellitesRef.get()
        if (sats == null) {
            sats = generateSatellites()
            satellitesRef.compareAndSet(null, sats)
        }
        return sats
    }

    fun refreshSatellites() {
        satellitesRef.set(generateSatellites())
    }

    private fun generateSatellites(): List<SatelliteInfo> {
        val result = mutableListOf<SatelliteInfo>()
        val random = java.util.Random(System.currentTimeMillis() xor android.os.Process.myPid().toLong())
        val usedCount = 12 + random.nextInt(5)

        val gpsCount = 7 + random.nextInt(4)
        val glonassCount = 4 + random.nextInt(3)
        val beidouCount = 5 + random.nextInt(4)
        val galileoCount = 3 + random.nextInt(3)

        fun addSatellites(count: Int, constellation: Int, svidRange: IntRange) {
            val svids = svidRange.toList().shuffled(random).take(count)
            for (svid in svids) {
                val cn0 = 28f + random.nextFloat() * 18f
                val elevation = 10f + random.nextFloat() * 70f
                val azimuth = random.nextFloat() * 360f
                val carrierFrequencyHz = getCarrierFrequency(constellation, svid, random)
                result.add(
                    SatelliteInfo(
                        svid, constellation, cn0, elevation, azimuth,
                        result.size < usedCount, carrierFrequencyHz
                    )
                )
            }
        }

        addSatellites(gpsCount, CONSTELLATION_GPS, 1..32)
        addSatellites(glonassCount, CONSTELLATION_GLONASS, 1..24)
        addSatellites(beidouCount, CONSTELLATION_BEIDOU, 1..37)
        addSatellites(galileoCount, CONSTELLATION_GALILEO, 1..36)

        return result.shuffled(random)
    }

    private fun getCarrierFrequency(constellation: Int, svid: Int, random: java.util.Random): Float {
        return when (constellation) {
            CONSTELLATION_GPS -> if (random.nextBoolean()) GPS_L1 else GPS_L5
            CONSTELLATION_GLONASS -> {
                val k = (svid - 1) % 14 - 7
                GLONASS_L1_BASE + k * GLONASS_L1_STEP
            }
            CONSTELLATION_BEIDOU -> if (random.nextBoolean()) BEIDOU_B1I else BEIDOU_B1C
            CONSTELLATION_GALILEO -> if (random.nextBoolean()) GALILEO_E1 else GALILEO_E5A
            else -> GPS_L1
        }
    }

    // ==================== 位置数据 ====================

    data class LocationEntry(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val speed: Float,
        val bearing: Float,
        val timestamp: Long
    )

    @Volatile
    private var cachedLocation: Location? = null

    @Volatile
    private var lastCacheTime = 0L

    @Volatile
    private var currentCacheTtlMs = CACHE_TTL_MIN_MS

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
    private var isLocationInitialized = false

    private val locationHistory = CopyOnWriteArrayList<LocationEntry>()

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

    internal fun getOrCreateFakeLocation(): Location {
        val now = System.currentTimeMillis()
        val cached = cachedLocation

        if (cached != null && (now - lastCacheTime) < currentCacheTtlMs) {
            return cached
        }

        val newLocation = createFakeLocationInternal()
        cachedLocation = newLocation
        lastCacheTime = now

        currentCacheTtlMs = CACHE_TTL_MIN_MS +
            ThreadLocalRandom.current().nextInt((CACHE_TTL_MAX_MS - CACHE_TTL_MIN_MS + 1).toInt())

        return newLocation
    }

    private fun createFakeLocationInternal(): Location {
        val fakeConfig = ConfigManager.getFakeLocation()
        val baseLat = fakeConfig.latitude
        val baseLng = fakeConfig.longitude
        val baseAlt = fakeConfig.altitude

        val wasInitialized = isLocationInitialized
        val latDrift = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * MAX_OFFSET
        val lngDrift = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * MAX_OFFSET

        val newLat: Double
        val newLng: Double
        if (!wasInitialized) {
            newLat = baseLat + latDrift
            newLng = baseLng + lngDrift
            isLocationInitialized = true
        } else {
            newLat = lastLatitude + latDrift * 0.3
            newLng = lastLongitude + lngDrift * 0.3
        }

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

        val altDrift = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * ALTITUDE_DRIFT_MAX
        val newAlt = if (!wasInitialized) baseAlt else lastAltitude + altDrift * 0.3

        val movementRoll = ThreadLocalRandom.current().nextFloat()
        val newSpeed = when {
            movementRoll < 0.7f -> ThreadLocalRandom.current().nextFloat() * SPEED_STILL_MAX
            movementRoll < 0.95f -> SPEED_WALK_MIN + ThreadLocalRandom.current().nextFloat() * (SPEED_WALK_MAX - SPEED_WALK_MIN)
            else -> 2.0f + ThreadLocalRandom.current().nextFloat() * 3.0f
        }

        val bearingDrift = (ThreadLocalRandom.current().nextFloat() * 2 - 1) * BEARING_DRIFT_MAX
        val newBearing = if (!wasInitialized) {
            ThreadLocalRandom.current().nextFloat() * 360f
        } else {
            ((lastBearing + bearingDrift) % 360f + 360f) % 360f
        }

        val accuracy = fakeConfig.accuracy + (ThreadLocalRandom.current().nextFloat() * 6 - 3f)

        lastLatitude = clampedLat
        lastLongitude = clampedLng
        lastAltitude = newAlt
        lastBearing = newBearing
        lastSpeed = newSpeed

        locationHistory.add(LocationEntry(clampedLat, clampedLng, newAlt, newSpeed, newBearing, System.currentTimeMillis()))
        while (locationHistory.size > MAX_HISTORY_SIZE) {
            locationHistory.removeAt(0)
        }

        val satCount = getSatellites().count { it.usedInFix }

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
                try {
                    val field = Location::class.java.getDeclaredField("mElapsedRealtimeNanos")
                    field.isAccessible = true
                    field.setLong(this, elapsedRealtimeNanos)
                } catch (_: Exception) {}
            }

            clearMockFlag()

            val extras = android.os.Bundle()
            extras.putInt("satellites", satCount)
            setExtras(extras)
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun Location.clearMockFlag() {
        try {
            val field = Location::class.java.getDeclaredField("mIsMock")
            field.isAccessible = true
            field.setBoolean(this, false)
        } catch (_: Exception) {}
    }

    fun isLocationMatch(lat: Double, lng: Double): Boolean {
        val current = getOrCreateFakeLocation()
        val distance = calculateDistance(current.latitude, current.longitude, lat, lng)
        return distance < 50.0
    }

    fun getLocationHistory(): List<LocationEntry> = locationHistory.toList()

    fun resetLocation() {
        cachedLocation = null
        lastCacheTime = 0L
        isLocationInitialized = false
        locationHistory.clear()
    }

    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    // ==================== 传感器状态 ====================

    fun isMoving(): Boolean {
        return try {
            getCurrentFakeLocation().speed > 0.5f
        } catch (_: Exception) { false }
    }

    fun getSpeed(): Float {
        return try {
            getCurrentFakeLocation().speed
        } catch (_: Exception) { 0f }
        }

    fun getBearing(): Float {
        return try {
            getCurrentFakeLocation().bearing
        } catch (_: Exception) { 0f }
    }

    // ==================== NMEA 生成 ====================

    fun generateNmeaGGA(location: ConfigManager.FakeLocation, timestamp: Long): String {
        val time = formatNmeaTime(timestamp)
        val lat = decimalToNmea(location.latitude, true)
        val latDir = getDirectionIndicator(location.latitude, true)
        val lon = decimalToNmea(location.longitude, false)
        val lonDir = getDirectionIndicator(location.longitude, false)
        val usedCount = getSatellites().count { it.usedInFix }
        val satellites = String.format(Locale.US, "%02d", usedCount)
        val hdop = "0.9"
        val altitude = String.format(Locale.US, "%.1f", location.altitude)
        val geoidHeight = "-1.0"

        val body = "GPGGA,$time,$lat,$latDir,$lon,$lonDir,$GPS_QUALITY_GPS,$satellites,$hdop,$altitude,M,$geoidHeight,M,,"
        return appendChecksum(body)
    }

    fun generateNmeaRMC(location: ConfigManager.FakeLocation, timestamp: Long): String {
        val time = formatNmeaTime(timestamp)
        val lat = decimalToNmea(location.latitude, true)
        val latDir = getDirectionIndicator(location.latitude, true)
        val lon = decimalToNmea(location.longitude, false)
        val lonDir = getDirectionIndicator(location.longitude, false)
        val speedKnots = String.format(Locale.US, "%.1f", location.speed * 1.94384)
        val bearing = String.format(Locale.US, "%.1f", location.bearing)
        val date = formatNmeaDate(timestamp)

        val body = "GPRMC,$time,A,$lat,$latDir,$lon,$lonDir,$speedKnots,$bearing,$date,0.0,E,A"
        return appendChecksum(body)
    }

    fun generateNmeaGSV(location: ConfigManager.FakeLocation, timestamp: Long): List<String> {
        val satellites = getSatellites()
        val sentences = mutableListOf<String>()
        val satsPerSentence = 4
        val totalMessages = (satellites.size + satsPerSentence - 1) / satsPerSentence

        for (msgIndex in 0 until totalMessages) {
            val startIdx = msgIndex * satsPerSentence
            val endIdx = minOf(startIdx + satsPerSentence, satellites.size)
            val msgNum = msgIndex + 1

            val sb = StringBuilder("GPGSV,$totalMessages,$msgNum,${satellites.size}")
            for (i in startIdx until endIdx) {
                val sat = satellites[i]
                sb.append(",${sat.svid},${sat.elevation.toInt()},${sat.azimuth.toInt()},${sat.cn0.toInt()}")
            }
            sentences.add(appendChecksum(sb.toString()))
        }

        return sentences
    }

    fun generateNmeaGPGSA(location: ConfigManager.FakeLocation, timestamp: Long): String {
        val usedSats = getSatellites().filter { it.usedInFix }
        val sb = StringBuilder("GPGSA,A,3")
        for (i in 0 until 12) {
            if (i < usedSats.size) {
                sb.append(",${usedSats[i].svid}")
            } else {
                sb.append(",")
            }
        }
        sb.append(",1.2,0.9,0.8")
        return appendChecksum(sb.toString())
    }

    fun generateAllNmeaSentences(location: ConfigManager.FakeLocation, timestamp: Long): List<String> {
        val sentences = mutableListOf<String>()
        sentences.add(generateNmeaGGA(location, timestamp))
        sentences.add(generateNmeaRMC(location, timestamp))
        sentences.addAll(generateNmeaGSV(location, timestamp))
        sentences.add(generateNmeaGPGSA(location, timestamp))
        return sentences
    }

    // ==================== NMEA 工具方法 ====================

    fun calculateChecksum(sentence: String): String {
        var checksum = 0
        val start = if (sentence.startsWith('$')) 1 else 0
        for (i in start until sentence.length) {
            val c = sentence[i]
            if (c == '*') break
            checksum = checksum xor c.code
        }
        return String.format("%02X", checksum and 0xFF)
    }

    fun appendChecksum(body: String): String {
        val sentence = "$$body"
        val checksum = calculateChecksum(sentence)
        return "$sentence*$checksum\r\n"
    }

    fun decimalToNmea(decimalDegrees: Double, isLat: Boolean): String {
        val absolute = Math.abs(decimalDegrees)
        val degrees = absolute.toInt()
        val minutes = (absolute - degrees) * 60.0
        val degWidth = if (isLat) 2 else 3
        return String.format(Locale.US, "%0${degWidth}d%07.4f", degrees, minutes)
    }

    fun getDirectionIndicator(decimalDegrees: Double, isLat: Boolean): String {
        return if (isLat) {
            if (decimalDegrees >= 0) "N" else "S"
        } else {
            if (decimalDegrees >= 0) "E" else "W"
        }
    }

    fun formatNmeaTime(millis: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = millis
        return String.format(
            Locale.US, "%02d%02d%02d.%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
            cal.get(java.util.Calendar.MILLISECOND) / 10
        )
    }

    fun formatNmeaDate(millis: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = millis
        return String.format(
            Locale.US, "%02d%02d%02d",
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.YEAR) % 100
        )
    }

    // ==================== 全局重置 ====================

    fun reset() {
        refreshSatellites()
        resetLocation()
    }
}
