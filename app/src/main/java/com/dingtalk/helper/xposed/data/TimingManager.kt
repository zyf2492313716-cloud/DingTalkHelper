package com.dingtalk.helper.xposed.data

import com.dingtalk.helper.xposed.utils.HookLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized timing coordinator for all time-based operations.
 * Replaces independent ScheduledExecutorService instances across
 * LocationHooks, NmeaHooks, GnssHooks, and FusedLocationHooks.
 *
 * All periodic tasks share a single daemon thread scheduler named "DTH-Timing",
 * ensuring callbacks fire at aligned tick boundaries for consistency.
 */
object TimingManager {

    private const val TAG = "DTH-TimingManager"

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "DTH-Timing").apply { isDaemon = true }
        }

    private val activeTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()

    private val tickCounter = AtomicLong(0L)

    init {
        scheduler.scheduleAtFixedRate({
            tickCounter.incrementAndGet()
        }, 0, 1, TimeUnit.SECONDS)
    }

    fun getTick(): Long = tickCounter.get()

    fun startLocationUpdates(key: String, intervalMs: Long, callback: (Long) -> Unit) {
        cancelTask(key)
        val interval = maxOf(intervalMs, 1000L)
        val future = scheduler.scheduleAtFixedRate({
            try {
                callback(tickCounter.get())
            } catch (e: Exception) {
                HookLogger.logDebug(TAG, "Location update failed: ${e.message}")
            }
        }, 100, interval, TimeUnit.MILLISECONDS)
        activeTasks[key] = future
    }

    fun startNmeaUpdates(key: String, intervalMs: Long, callback: (Long) -> Unit) {
        cancelTask(key)
        val interval = maxOf(intervalMs, 1000L)
        val future = scheduler.scheduleAtFixedRate({
            try {
                callback(tickCounter.get())
            } catch (e: Exception) {
                HookLogger.logDebug(TAG, "NMEA update failed: ${e.message}")
            }
        }, 100, interval, TimeUnit.MILLISECONDS)
        activeTasks[key] = future
    }

    fun startGnssStatusUpdates(key: String, intervalMs: Long, callback: (Long) -> Unit) {
        cancelTask(key)
        val interval = maxOf(intervalMs, 1000L)
        val future = scheduler.scheduleAtFixedRate({
            try {
                callback(tickCounter.get())
            } catch (e: Exception) {
                HookLogger.logDebug(TAG, "GNSS status update failed: ${e.message}")
            }
        }, 100, interval, TimeUnit.MILLISECONDS)
        activeTasks[key] = future
    }

    fun startNavigationMessageUpdates(key: String, intervalMs: Long, callback: (Long) -> Unit) {
        cancelTask(key)
        val interval = maxOf(intervalMs, 1000L)
        val future = scheduler.scheduleAtFixedRate({
            try {
                callback(tickCounter.get())
            } catch (e: Exception) {
                HookLogger.logDebug(TAG, "Navigation message update failed: ${e.message}")
            }
        }, 1000, interval, TimeUnit.MILLISECONDS)
        activeTasks[key] = future
    }

    fun scheduleOnce(key: String, delayMs: Long, callback: () -> Unit) {
        cancelTask(key)
        val future = scheduler.schedule({
            try {
                callback()
            } catch (e: Exception) {
                HookLogger.logDebug(TAG, "One-shot task failed: ${e.message}")
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        activeTasks[key] = future
    }

    fun cancelTask(key: String) {
        activeTasks.remove(key)?.cancel(false)
    }

    fun cancelAll() {
        activeTasks.values.forEach { it.cancel(false) }
        activeTasks.clear()
    }

    fun activeTaskCount(): Int = activeTasks.size
}
