package com.dingtalk.helper.xposed.utils

/**
 * StackTrace 过滤工具
 * 统一管理 Xposed/LSPosed 相关关键词，供 EnvironmentHooks 和 DingTalkCompatHooks 共用
 */
object StackTraceFilter {

    /**
     * Xposed/LSPosed 相关类名关键词（合并自 EnvironmentHooks 和 DingTalkCompatHooks）
     */
    val XPOSED_KEYWORDS = setOf(
        // Xposed 框架
        "de.robv.android.xposed",
        "XposedBridge",
        // LSPosed
        "lsposed",
        "LSPosed",
        "io.github.lsposed",
        "LSPosed-Bridge",
        // EdXposed
        "edxposed",
        "EdXposed",
        // Riru
        "riru",
        // LSPlant
        "lsplant",
        // DingTalkHelper 自身
        "com.dingtalk.helper.xposed"
    )

    /**
     * 检查 StackTraceElement 是否与 Xposed 相关
     */
    fun isXposedRelated(element: StackTraceElement): Boolean {
        return isXposedRelatedClass(element.className)
    }

    /**
     * 检查类名是否与 Xposed 相关
     */
    fun isXposedRelatedClass(className: String): Boolean {
        return XPOSED_KEYWORDS.any { className.contains(it, ignoreCase = true) }
    }

    /**
     * 过滤 StackTrace 中的 Xposed 相关帧
     */
    fun filterStackTrace(stackTrace: Array<StackTraceElement>): Array<StackTraceElement> {
        val filtered = stackTrace.filter { !isXposedRelated(it) }.toTypedArray()
        return if (filtered.size != stackTrace.size) filtered else stackTrace
    }
}
