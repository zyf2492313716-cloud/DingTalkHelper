package com.dingtalk.helper.xposed.utils

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 精确日志系统
 * 记录每个 Hook 的成功/失败/性能，便于定位问题
 */
object HookLogger {
    private const val TAG = Constants.LOG_PREFIX

    /**
     * 日志级别
     */
    enum class Level { DEBUG, INFO, WARN, ERROR, FATAL }

    /**
     * 日志条目
     */
    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val exception: Throwable? = null,
        val stackTrace: String? = null
    )

    /**
     * 日志缓存（用于崩溃时上报），使用 ConcurrentLinkedDeque 保证线程安全
     */
    private val logBuffer = ConcurrentLinkedDeque<LogEntry>()
    private const val MAX_BUFFER_SIZE = 1000

    /**
     * 记录 Hook 成功
     */
    fun logSuccess(moduleName: String) {
        log(Level.INFO, moduleName, "Hook 成功")
    }

    /**
     * 记录 Hook 失败（带异常）
     */
    fun logFailure(moduleName: String, exception: Exception) {
        log(Level.ERROR, moduleName, "Hook 失败: ${exception.message}", exception)
    }

    /**
     * 记录 Hook 失败（带消息）
     */
    fun logFailure(moduleName: String, message: String) {
        log(Level.ERROR, moduleName, message)
    }

    /**
     * 记录 Hook 失败（带消息和异常）
     */
    fun logFailure(moduleName: String, message: String, exception: Exception) {
        log(Level.ERROR, moduleName, message, exception)
    }

    /**
     * 记录性能警告（耗时超过阈值）
     */
    fun logPerformance(moduleName: String, durationMs: Long) {
        if (durationMs > 100) {
            log(Level.WARN, moduleName, "Hook 耗时过长: ${durationMs}ms")
        }
    }

    /**
     * 记录信息
     */
    fun logInfo(moduleName: String, message: String) {
        log(Level.INFO, moduleName, message)
    }

    /**
     * 记录调试信息
     */
    fun logDebug(moduleName: String, message: String) {
        if (Constants.DEBUG_MODE) {
            log(Level.DEBUG, moduleName, message)
        }
    }

    /**
     * 记录警告
     */
    fun logWarn(moduleName: String, message: String) {
        log(Level.WARN, moduleName, message)
    }

    /**
     * 核心日志方法
     */
    private fun log(level: Level, tag: String, message: String, exception: Exception? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            exception = exception,
            stackTrace = exception?.stackTraceToString()
        )

        // 添加到缓存，超出限制时移除最旧的
        logBuffer.addLast(entry)
        while (logBuffer.size > MAX_BUFFER_SIZE) {
            logBuffer.pollFirst()
        }

        // 输出到 Xposed 日志
        val logMessage = "[$tag] $message"
        when (level) {
            Level.DEBUG -> HookUtils.logDebug(logMessage)
            Level.INFO -> HookUtils.log("$TAG: $logMessage")
            Level.WARN -> HookUtils.log("$TAG:WARN: $logMessage")
            Level.ERROR -> HookUtils.log("$TAG:ERROR: $logMessage")
            Level.FATAL -> HookUtils.log("$TAG:FATAL: $logMessage")
        }

        // 在 DEBUG 模式下输出堆栈
        if (Constants.DEBUG_MODE && exception != null) {
            exception.printStackTrace()
        }
    }

    /**
     * 获取最近的日志（用于调试）
     */
    fun getRecentLogs(count: Int = 100): List<LogEntry> {
        return logBuffer.toList().takeLast(count)
    }

    /**
     * 获取所有日志
     */
    fun getAllLogs(): List<LogEntry> {
        return logBuffer.toList()
    }

    /**
     * 清空日志缓存
     */
    fun clearLogs() {
        logBuffer.clear()
    }

    /**
     * 获取错误计数
     */
    fun getErrorCount(): Int {
        return logBuffer.count { it.level == Level.ERROR || it.level == Level.FATAL }
    }

    /**
     * 获取指定级别的日志
     */
    fun getLogsByLevel(level: Level): List<LogEntry> {
        return logBuffer.filter { it.level == level }
    }

    /**
     * 格式化日志为字符串（用于导出）
     */
    fun formatLogs(): String {
        return logBuffer.joinToString("\n") { entry ->
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date(entry.timestamp))
            val exceptionStr = entry.exception?.let { "\n${it.stackTraceToString()}" } ?: ""
            "[$time][${entry.level}][${entry.tag}] ${entry.message}$exceptionStr"
        }
    }
}
