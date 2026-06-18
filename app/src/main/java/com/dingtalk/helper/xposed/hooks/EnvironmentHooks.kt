package com.dingtalk.helper.xposed.hooks

import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 环境隐藏 Hook
 * 负责隐藏 ROOT、Xposed 等环境特征
 *
 * 注意：文件系统和包列表隐藏由 AppHidingHooks 处理，
 * 此类专注于运行时环境检测绕过
 */
class EnvironmentHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:Environment"

        private val DANGEROUS_COMMANDS = setOf(
            "su", "which su", "whereis su", "magisk", "xposed"
        )

        private val XPOSED_KEYWORDS = setOf(
            "xposed", "lsposed", "edxposed", "riru", "lsplant"
        )

        private val HIDDEN_SETTINGS = setOf(
            "mock_location", "development_settings_enabled"
        )
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.log("$TAG: 开始注入环境隐藏 Hook")

        // 隐藏 ROOT 环境
        if (ConfigManager.isHideRootEnabled()) {
            hookRootDetection(lpparam)
        }

        // 隐藏 Xposed 框架
        if (ConfigManager.isHideXposedEnabled()) {
            hookXposedDetection(lpparam)
        }

        // 隐藏开发者选项
        hookDeveloperOptions(lpparam)
    }

    // ==================== ROOT 检测绕过 ====================

    private fun hookRootDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookRuntimeExec(lpparam)
        hookProcessBuilder(lpparam)
        HookUtils.log("$TAG: ROOT 检测 Hook 完成")
    }

    /**
     * Hook Runtime.exec - 拦截 su/magisk 命令
     */
    private fun hookRuntimeExec(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)

            // Hook exec(String)
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val command = param.args[0] as? String ?: return
                        if (isDangerousCommand(command)) {
                            param.args[0] = "echo 'not found'"
                            HookUtils.logDebug("$TAG: 阻止命令: $command")
                        }
                    }
                }
            )

            // Hook exec(String[])
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec", Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val commands = param.args[0] as? Array<*> ?: return
                        if (commands.any { isDangerousCommand(it?.toString() ?: "") }) {
                            param.args[0] = arrayOf("echo", "not found")
                            HookUtils.logDebug("$TAG: 阻止命令数组")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Runtime.exec Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook ProcessBuilder.start - 拦截 su/magisk 命令
     */
    private fun hookProcessBuilder(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pbClass = XposedHelpers.findClass("java.lang.ProcessBuilder", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                pbClass, "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val field = XposedHelpers.findField(param.thisObject.javaClass, "command")
                            val commands = field.get(param.thisObject) as? List<*> ?: return
                            if (commands.any { isDangerousCommand(it?.toString() ?: "") }) {
                                field.set(param.thisObject, listOf("echo", "not found"))
                                HookUtils.logDebug("$TAG: 阻止 ProcessBuilder 命令")
                            }
                        } catch (_: Exception) {}
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: ProcessBuilder Hook 失败: ${e.message}")
        }
    }

    // ==================== Xposed 检测绕过 ====================

    private fun hookXposedDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookClassForName(lpparam)
        hookStackTrace(lpparam)
        hookSystemProperty(lpparam)
        HookUtils.log("$TAG: Xposed 检测 Hook 完成")
    }

    /**
     * Hook Class.forName - 隐藏 Xposed 相关类
     */
    private fun hookClassForName(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val classClass = XposedHelpers.findClass("java.lang.Class", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                classClass, "forName", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        if (isXposedRelatedClass(className)) {
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
     * Hook Thread.getStackTrace - 过滤 Xposed 帧
     */
    private fun hookStackTrace(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val threadClass = XposedHelpers.findClass("java.lang.Thread", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                threadClass, "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val stackTrace = param.result as? Array<StackTraceElement> ?: return
                        param.result = stackTrace.filter { element ->
                            !isXposedRelatedClass(element.className)
                        }.toTypedArray()
                    }
                }
            )
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: StackTrace Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook System.getProperty - 隐藏 Xposed 属性
     */
    private fun hookSystemProperty(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val systemClass = XposedHelpers.findClass("java.lang.System", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                systemClass, "getProperty", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
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

    // ==================== 开发者选项隐藏 ====================

    private fun hookDeveloperOptions(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val settingsSecureClass = XposedHelpers.findClass(
                "android.provider.Settings\$Secure", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                settingsSecureClass, "getInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args[1] as? String ?: return
                        if (name in HIDDEN_SETTINGS) {
                            param.result = 0
                        }
                    }
                }
            )
            HookUtils.log("$TAG: 开发者选项检测 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 开发者选项检测 Hook 失败: ${e.message}")
        }
    }

    // ==================== 工具方法 ====================

    private fun isDangerousCommand(command: String): Boolean {
        return DANGEROUS_COMMANDS.any { command.contains(it, ignoreCase = true) }
    }

    private fun isXposedRelatedClass(className: String): Boolean {
        return XPOSED_KEYWORDS.any { className.contains(it, ignoreCase = true) }
    }
}
