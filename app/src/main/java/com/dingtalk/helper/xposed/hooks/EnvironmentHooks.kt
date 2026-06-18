package com.dingtalk.helper.xposed.hooks

import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.BufferedReader
import java.io.FileReader

/**
 * 环境隐藏 Hook
 * 负责隐藏 ROOT、Xposed 等环境特征
 */
class EnvironmentHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:Environment"
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.log("$TAG: 开始注入环境隐藏 Hook")

        // 隐藏 ROOT
        if (ConfigManager.isHideRootEnabled()) {
            hookRootDetection(lpparam)
        }

        // 隐藏 Xposed
        if (ConfigManager.isHideXposedEnabled()) {
            hookXposedDetection(lpparam)
        }

        // 隐藏分身环境
        hookVirtualAppDetection(lpparam)

        // 隐藏开发者选项
        hookDeveloperOptions(lpparam)

        // 隐藏 Magisk
        hookMagiskDetection(lpparam)
    }

    /**
     * Hook ROOT 检测
     */
    private fun hookRootDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook File.exists 检测 ROOT 文件
            hookFileExists(lpparam)

            // Hook Runtime.exec 检测 su 命令
            hookRuntimeExec(lpparam)

            // Hook ProcessBuilder
            hookProcessBuilder(lpparam)

            // Hook which 命令
            hookWhichCommand(lpparam)

            HookUtils.log("$TAG: ROOT 检测 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: ROOT 检测 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook File.exists
     */
    private fun hookFileExists(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fileClass = XposedHelpers.findClass("java.io.File", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                fileClass,
                "exists",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val path = (param.thisObject as File).absolutePath

                        // 检查是否是隐藏路径
                        if (isHiddenPath(path)) {
                            return false
                        }

                        return XposedBridge.invokeOriginalMethod(
                            param.method, param.thisObject, param.args
                        )
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: File.exists Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Runtime.exec
     */
    private fun hookRuntimeExec(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)

            // Hook exec(String)
            XposedHelpers.findAndHookMethod(
                runtimeClass,
                "exec",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val command = param.args[0] as String
                        if (isDangerousCommand(command)) {
                            HookUtils.logDebug("$TAG: 阻止命令: $command")
                            param.args[0] = "echo 'not found'"
                        }
                    }
                }
            )

            // Hook exec(String[])
            XposedHelpers.findAndHookMethod(
                runtimeClass,
                "exec",
                Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val commands = param.args[0] as Array<String>
                        if (commands.any { isDangerousCommand(it) }) {
                            HookUtils.logDebug("$TAG: 阻止命令数组")
                            param.args[0] = arrayOf("echo", "not found")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Runtime.exec Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook ProcessBuilder
     */
    private fun hookProcessBuilder(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val processBuilderClass = XposedHelpers.findClass(
                "java.lang.ProcessBuilder",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                processBuilderClass,
                "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val field = XposedHelpers.findField(param.thisObject.javaClass, "command")
                            val commands = field.get(param.thisObject) as List<String>

                            if (commands.any { isDangerousCommand(it) }) {
                                HookUtils.logDebug("$TAG: 阻止 ProcessBuilder 命令")
                                field.set(param.thisObject, listOf("echo", "not found"))
                            }
                        } catch (e: Exception) {
                            // 忽略
                        }
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: ProcessBuilder Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook which 命令
     */
    private fun hookWhichCommand(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                runtimeClass,
                "exec",
                Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val commands = param.args[0] as Array<String>
                        if (commands.contains("which") && commands.any { it == "su" }) {
                            // 返回空结果
                            param.args[0] = arrayOf("echo", "")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * Hook Xposed 检测
     */
    private fun hookXposedDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook Class.forName
            hookClassForName(lpparam)

            // Hook StackTrace
            hookStackTrace(lpparam)

            // Hook System.getProperty
            hookSystemProperty(lpparam)

            HookUtils.log("$TAG: Xposed 检测 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Xposed 检测 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Class.forName
     */
    private fun hookClassForName(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val classClass = XposedHelpers.findClass("java.lang.Class", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                classClass,
                "forName",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as String
                        if (isXposedRelatedClass(className)) {
                            // 抛出 ClassNotFoundException
                            param.throwable = ClassNotFoundException("Class not found: $className")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Class.forName Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook StackTrace
     */
    private fun hookStackTrace(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val threadClass = XposedHelpers.findClass("java.lang.Thread", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                threadClass,
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val stackTrace = param.result as Array<StackTraceElement>
                        val filtered = stackTrace.filter { element ->
                            !isXposedRelatedClass(element.className)
                        }.toTypedArray()
                        param.result = filtered
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: StackTrace Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook System.getProperty
     */
    private fun hookSystemProperty(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val systemClass = XposedHelpers.findClass("java.lang.System", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                systemClass,
                "getProperty",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as String
                        if (key.contains("xposed", true) || key.contains("lsposed", true)) {
                            param.result = null
                        }
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: System.getProperty Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook 分身应用检测
     */
    private fun hookVirtualAppDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val packageManagerClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                packageManagerClass,
                "getInstalledPackages",
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val packages = param.result as List<*>
                        val filtered = packages.filter { pkg ->
                            val packageName = HookUtils.callMethodSafely(pkg!!, "packageName") as? String ?: ""
                            !Constants.HIDDEN_PACKAGES.contains(packageName)
                        }
                        param.result = filtered
                    }
                }
            )

            HookUtils.log("$TAG: 分身应用检测 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 分身应用检测 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook 开发者选项检测
     */
    private fun hookDeveloperOptions(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val settingsSecureClass = XposedHelpers.findClass(
                "android.provider.Settings\$Secure",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                settingsSecureClass,
                "getInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args[1] as String
                        when (name) {
                            "development_settings_enabled",
                            "mock_location" -> {
                                param.result = 0
                            }
                        }
                    }
                }
            )

            HookUtils.log("$TAG: 开发者选项检测 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 开发者选项检测 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Magisk 检测
     */
    private fun hookMagiskDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook BufferedReader 读取 /proc/self/mounts
            val bufferedReaderClass = XposedHelpers.findClass(
                "java.io.BufferedReader",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                bufferedReaderClass,
                "readLine",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val line = param.result as? String ?: return
                        if (line.contains("magisk", true) ||
                            line.contains("lsposed", true) ||
                            line.contains("riru", true)) {
                            // 跳过这行，读取下一行
                            param.result = XposedBridge.invokeOriginalMethod(
                                param.method, param.thisObject, param.args
                            )
                        }
                    }
                }
            )

            HookUtils.log("$TAG: Magisk 检测 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Magisk 检测 Hook 失败: ${e.message}")
        }
    }

    /**
     * 判断是否是隐藏路径
     */
    private fun isHiddenPath(path: String): Boolean {
        return Constants.ROOT_PATHS.any { path.contains(it) } ||
                Constants.MAGISK_PATHS.any { path.startsWith(it) } ||
                Constants.XPOSED_PATHS.any { path.contains(it) }
    }

    /**
     * 判断是否是危险命令
     */
    private fun isDangerousCommand(command: String): Boolean {
        val dangerousKeywords = listOf("su", "which su", "whereis su", "magisk", "xposed")
        return dangerousKeywords.any { command.contains(it, true) }
    }

    /**
     * 判断是否是 Xposed 相关类
     */
    private fun isXposedRelatedClass(className: String): Boolean {
        val xposedKeywords = listOf("xposed", "lsposed", "edxposed", "riru", "lsplant")
        return xposedKeywords.any { className.contains(it, true) }
    }
}