package com.dingtalk.helper.xposed.hooks

import android.content.Context
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import com.dingtalk.helper.xposed.utils.StackTraceFilter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class DingTalkCompatHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:Compat"

        private const val DINGTALK_PACKAGE = Constants.DINGTALK_PACKAGE

        private val PROC_MAPS_KEYWORDS = setOf(
            "/proc/self/maps",
            "/proc/" + "self" + "/maps",
            "proc/self/map"
        )

        private val RISK_CONTROL_CLASS_KEYWORDS = setOf(
            "securitybody",
            "SecurityBody",
            "SecurityGuard",
            "lbswua",
            "ddsec",
            "riskcontrol",
            "RiskControl",
            "sgmain",
            "sgsecuritybody"
        )
    }

    data class DingTalkVersion(
        val versionName: String,
        val versionCode: Long,
        val major: Int,
        val minor: Int,
        val patch: Int
    )

    enum class CompatStrategy {
        LEGACY,
        STANDARD,
        MODERN
    }

    private var currentVersion: DingTalkVersion? = null
    private var currentStrategy: CompatStrategy = CompatStrategy.STANDARD

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != DINGTALK_PACKAGE) return

        HookUtils.log("$TAG: 开始注入钉钉版本适配 Hook")

        currentVersion = detectVersion(lpparam)
        currentStrategy = resolveStrategy(currentVersion)

        HookUtils.log(
            "$TAG: 钉钉版本=${currentVersion?.versionName ?: "unknown"}, " +
            "策略=$currentStrategy"
        )

        hookProcMapsAccess(lpparam)
        hookStackTraceFilter(lpparam)
        hookClassForNameDetection(lpparam)
        hookExitPrevention(lpparam)

        HookUtils.log("$TAG: 版本适配 Hook 注入完成")
    }

    private fun detectVersion(lpparam: XC_LoadPackage.LoadPackageParam): DingTalkVersion? {
        return try {
            val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                "currentActivityThread"
            )
            val context = XposedHelpers.callMethod(activityThread, "getSystemContext") as Context
            val pm = context.packageManager
            val packageInfo = pm.getPackageInfo(DINGTALK_PACKAGE, 0)
            val versionName = packageInfo.versionName ?: "0.0.0"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            val parts = versionName.split(".")
            val major = parts.getOrElse(0) { "0" }.toIntOrNull() ?: 0
            val minor = parts.getOrElse(1) { "0" }.toIntOrNull() ?: 0
            val patch = parts.getOrElse(2) { "0" }.toIntOrNull() ?: 0
            DingTalkVersion(versionName, versionCode, major, minor, patch)
        } catch (e: Exception) {
            HookUtils.log("$TAG: 获取钉钉版本失败: ${e.message}")
            null
        }
    }

    private fun resolveStrategy(version: DingTalkVersion?): CompatStrategy {
        if (version == null) return CompatStrategy.STANDARD
        return when {
            version.major < 7 -> CompatStrategy.LEGACY
            version.major == 7 && version.minor < 5 -> CompatStrategy.LEGACY
            version.major == 7 && version.minor in 5..8 -> CompatStrategy.STANDARD
            version.major >= 7 && version.minor > 8 -> CompatStrategy.MODERN
            version.major >= 8 -> CompatStrategy.MODERN
            else -> CompatStrategy.STANDARD
        }
    }

    // ==================== /proc/self/maps 访问拦截 ====================

    private fun hookProcMapsAccess(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookRuntimeExecBlockMaps(lpparam)
        hookProcessBuilderBlockMaps(lpparam)
    }

    private fun hookRuntimeExecBlockMaps(lpparam: XC_LoadPackage.LoadPackageParam) {
        val runtimeClass = try {
            XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)
        } catch (_: Exception) {
            HookUtils.logDebug("$TAG: 找不到 Runtime 类")
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmd = param.args[0] as? String ?: return
                        if (containsProcMapsReference(cmd)) {
                            param.throwable = java.io.IOException("Permission denied")
                            HookUtils.logDebug("$TAG: [Runtime.exec] 阻止读取 /proc/self/maps: $cmd")
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: Runtime.exec(String) Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Runtime.exec(String) Hook 失败: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec", Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmds = param.args[0] as? Array<*> ?: return
                        if (cmds.any { containsProcMapsReference(it?.toString() ?: "") }) {
                            param.args[0] = arrayOf("echo", "")
                            HookUtils.logDebug("$TAG: [Runtime.exec] 阻止读取 /proc/self/maps (数组)")
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: Runtime.exec(String[]) Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Runtime.exec(String[]) Hook 失败: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec",
                Array<String>::class.java, Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmds = param.args[0] as? Array<*> ?: return
                        if (cmds.any { containsProcMapsReference(it?.toString() ?: "") }) {
                            param.args[0] = arrayOf("echo", "")
                            HookUtils.logDebug("$TAG: [Runtime.exec] 阻止读取 /proc/self/maps (数组+envp)")
                        }
                    }
                }
            )
        } catch (_: Exception) {}

        try {
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec",
                Array<String>::class.java, Array<String>::class.java,
                java.io.File::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmds = param.args[0] as? Array<*> ?: return
                        if (cmds.any { containsProcMapsReference(it?.toString() ?: "") }) {
                            param.args[0] = arrayOf("echo", "")
                            HookUtils.logDebug("$TAG: [Runtime.exec] 阻止读取 /proc/self/maps (数组+envp+dir)")
                        }
                    }
                }
            )
        } catch (_: Exception) {}
    }

    private fun hookProcessBuilderBlockMaps(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pbClass = try {
            XposedHelpers.findClass("java.lang.ProcessBuilder", lpparam.classLoader)
        } catch (_: Exception) {
            HookUtils.logDebug("$TAG: 找不到 ProcessBuilder 类")
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                pbClass, "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val field = XposedHelpers.findField(param.thisObject.javaClass, "command")
                            val commands = field.get(param.thisObject) as? List<*> ?: return
                            val cmdStr = commands.joinToString(" ")
                            if (containsProcMapsReference(cmdStr)) {
                                field.set(param.thisObject, listOf("echo", ""))
                                HookUtils.logDebug("$TAG: [ProcessBuilder] 阻止读取 /proc/self/maps")
                            }
                        } catch (_: Exception) {}
                    }
                }
            )
            HookUtils.logDebug("$TAG: ProcessBuilder.start Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: ProcessBuilder.start Hook 失败: ${e.message}")
        }
    }

    private fun containsProcMapsReference(cmd: String): Boolean {
        return PROC_MAPS_KEYWORDS.any { cmd.contains(it, ignoreCase = true) }
    }

    // ==================== StackTrace 过滤 ====================

    private fun hookStackTraceFilter(lpparam: XC_LoadPackage.LoadPackageParam) {
        val strategy = currentStrategy

        try {
            val throwableClass = XposedHelpers.findClass("java.lang.Throwable", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                throwableClass, "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val trace = param.result as? Array<StackTraceElement> ?: return
                        val filtered = trace.filter { element ->
                            !StackTraceFilter.isXposedRelated(element)
                        }.toTypedArray()
                        if (filtered.size != trace.size) {
                            param.result = filtered
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                throwableClass, "fillInStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val traceField = XposedHelpers.findField(
                                param.thisObject.javaClass, "stackTrace"
                            )
                            val trace = traceField.get(param.thisObject) as? Array<StackTraceElement>
                                ?: return
                            val filtered = trace.filter { element ->
                                !StackTraceFilter.isXposedRelated(element)
                            }.toTypedArray()
                            if (filtered.size != trace.size) {
                                traceField.set(param.thisObject, filtered)
                            }
                        } catch (_: Exception) {}
                    }
                }
            )

            HookUtils.log("$TAG: StackTrace 过滤 Hook 完成 (策略=$strategy)")
        } catch (e: Exception) {
            HookUtils.log("$TAG: StackTrace 过滤 Hook 失败: ${e.message}")
        }
    }

    // ==================== Class.forName 风控类检测 ====================

    private fun hookClassForNameDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val classClass = XposedHelpers.findClass("java.lang.Class", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                classClass, "forName", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        if (isRiskControlClassProbe(className)) {
                            HookUtils.logDebug(
                                "$TAG: [Class.forName] 检测到风控类探测: $className"
                            )
                            when (currentStrategy) {
                                CompatStrategy.LEGACY -> {
                                    param.throwable = ClassNotFoundException(
                                        "Class not found: $className"
                                    )
                                }
                                CompatStrategy.STANDARD -> {
                                    param.args[0] = "java.lang.Object"
                                }
                                CompatStrategy.MODERN -> {
                                    param.throwable = ClassNotFoundException(
                                        "Class not found: $className"
                                    )
                                }
                            }
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                classClass, "forName",
                String::class.java, Boolean::class.javaPrimitiveType, ClassLoader::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        if (isRiskControlClassProbe(className)) {
                            HookUtils.logDebug(
                                "$TAG: [Class.forName] 检测到风控类探测(带ClassLoader): $className"
                            )
                            when (currentStrategy) {
                                CompatStrategy.LEGACY,
                                CompatStrategy.MODERN -> {
                                    param.throwable = ClassNotFoundException(
                                        "Class not found: $className"
                                    )
                                }
                                CompatStrategy.STANDARD -> {
                                    param.args[0] = "java.lang.Object"
                                }
                            }
                        }
                    }
                }
            )

            HookUtils.log("$TAG: Class.forName 风控类检测 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Class.forName Hook 失败: ${e.message}")
        }
    }

    private fun isRiskControlClassProbe(className: String): Boolean {
        return RISK_CONTROL_CLASS_KEYWORDS.any { className.contains(it, ignoreCase = true) }
    }

    // ==================== 阻止应用退出/自杀 ====================

    private fun hookExitPrevention(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookSystemExit(lpparam)
        hookProcessKill(lpparam)
    }

    private fun hookSystemExit(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val systemClass = XposedHelpers.findClass("java.lang.System", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                systemClass, "exit", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val code = param.args[0] as Int
                        val throwable = Throwable()
                        val callerInfo = throwable.stackTrace.getOrNull(2)?.let {
                            "${it.className}.${it.methodName}"
                        } ?: "unknown"
                        HookUtils.log(
                            "$TAG: 阻止 System.exit($code), 调用者: $callerInfo"
                        )
                        param.result = null
                    }
                }
            )
            HookUtils.logDebug("$TAG: System.exit Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: System.exit Hook 失败: ${e.message}")
        }
    }

    private fun hookProcessKill(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val processClass = XposedHelpers.findClass("android.os.Process", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                processClass, "killProcess", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pid = param.args[0] as Int
                        val myPid = android.os.Process.myPid()
                        if (pid == myPid) {
                            val throwable = Throwable()
                            val callerInfo = throwable.stackTrace.getOrNull(2)?.let {
                                "${it.className}.${it.methodName}"
                            } ?: "unknown"
                            HookUtils.log(
                                "$TAG: 阻止 Process.killProcess($pid) 自杀, 调用者: $callerInfo"
                            )
                            param.throwable = SecurityException(
                                "Process.killProcess() blocked by DingTalkHelper"
                            )
                        }
                    }
                }
            )
            HookUtils.logDebug("$TAG: Process.killProcess Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Process.killProcess Hook 失败: ${e.message}")
        }

        try {
            val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exit", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val code = param.args[0] as Int
                        HookUtils.log("$TAG: 阻止 Runtime.exit($code)")
                        param.throwable = SecurityException(
                            "Runtime.exit() blocked by DingTalkHelper"
                        )
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                runtimeClass, "halt", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val status = param.args[0] as Int
                        HookUtils.log("$TAG: 阻止 Runtime.halt($status)")
                        param.throwable = SecurityException(
                            "Runtime.halt() blocked by DingTalkHelper"
                        )
                    }
                }
            )
            HookUtils.logDebug("$TAG: Runtime.exit/halt Hook 完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Runtime.exit/halt Hook 失败: ${e.message}")
        }
    }

    // ==================== 版本信息查询 ====================

    fun getCurrentVersion(): DingTalkVersion? = currentVersion

    fun getCurrentStrategy(): CompatStrategy = currentStrategy
}
