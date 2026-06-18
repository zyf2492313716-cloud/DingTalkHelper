package com.dingtalk.helper.xposed.utils

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook 工具类
 * 提供通用的 Hook 辅助方法
 */
object HookUtils {

    private const val TAG = "DingTalkHelper"

    /**
     * 安全地查找并 Hook 方法
     * @return true 如果 Hook 成功
     */
    fun hookMethodSafely(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        callback: Any,
        vararg parameterTypes: Any
    ): Boolean {
        return try {
            val clazz = XposedHelpers.findClass(className, classLoader)
            XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypes, callback)
            true
        } catch (e: ClassNotFoundException) {
            false
        } catch (e: NoSuchMethodError) {
            false
        } catch (e: Exception) {
            log("$TAG: Hook $className.$methodName 失败: ${e.message}")
            false
        }
    }

    /**
     * 尝试多个类名查找类
     */
    fun findClassByNames(
        classNames: List<String>,
        classLoader: ClassLoader
    ): Class<*>? {
        for (name in classNames) {
            try {
                return XposedHelpers.findClass(name, classLoader)
            } catch (e: ClassNotFoundException) {
                continue
            }
        }
        return null
    }

    /**
     * 尝试 Hook 多个可能的方法名
     */
    fun hookMethodByNames(
        clazz: Class<*>,
        methodNames: List<String>,
        callback: Any,
        vararg parameterTypes: Any
    ): String? {
        for (name in methodNames) {
            try {
                XposedHelpers.findAndHookMethod(clazz, name, *parameterTypes, callback)
                return name
            } catch (e: NoSuchMethodError) {
                continue
            }
        }
        return null
    }

    /**
     * 安全地设置字段值
     */
    fun setFieldValueSafely(obj: Any, fieldName: String, value: Any?): Boolean {
        return try {
            val field = XposedHelpers.findField(obj.javaClass, fieldName)
            field.set(obj, value)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 安全地获取字段值
     */
    fun getFieldValueSafely(obj: Any, fieldName: String): Any? {
        return try {
            val field = XposedHelpers.findField(obj.javaClass, fieldName)
            field.get(obj)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 通过反射调用方法
     */
    fun callMethodSafely(obj: Any, methodName: String, vararg args: Any?): Any? {
        return try {
            XposedHelpers.callMethod(obj, methodName, *args)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 日志输出
     */
    fun log(message: String) {
        XposedBridge.log(message)
    }

    /**
     * 仅在调试模式输出日志
     */
    fun logDebug(message: String) {
        // 可以通过配置控制是否输出调试日志
        if (Constants.DEBUG_MODE) {
            XposedBridge.log("$TAG:DEBUG: $message")
        }
    }

    /**
     * 获取调用者包名
     */
    fun getCallerPackageName(): String {
        return try {
            val uid = android.os.Binder.getCallingUid()
            val context = com.dingtalk.helper.xposed.HookEntry.moduleContext
            context?.packageManager?.getNameForUid(uid) ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}