package com.dingtalk.helper.xposed.hooks

import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream

/**
 * 反追踪 Hook 模块
 * 在 ART 层面隐藏 Xposed Hook 留下的痕迹
 *
 * 职责：
 * 1. Thread.getStackTrace 过滤 Xposed 帧（委托给 StackTraceFilter）
 * 2. Debug.isDebuggerConnected 返回 false
 * 3. Runtime.exec / ProcessBuilder 拦截探测命令
 * 4. BufferedReader /proc 读取行过滤
 *
 * 与 DeepHidingHooks 的分工：
 * - DeepHidingHooks：FileInputStream 构造拦截（/proc/self/maps 文件级）
 * - AntiTraceHooks：BufferedReader 行级过滤（/proc/self/maps等多路径）+ 调试器状态隐藏
 */
class AntiTraceHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:AntiTrace"

        // 需要拦截的 /proc 路径（通过 Runtime.exec / ProcessBuilder）
        private val PROC_SENSITIVE_PATHS = setOf(
            "/proc/self/maps",
            "/proc/self/smaps",
            "/proc/self/status",
            "/proc/self/wchan",
            "/proc/self/cmdline",
            "/proc/self/mountinfo",
            "/proc/thread-self/maps",
            "/proc/thread-self/status",
            // 新增：系统信息文件（模拟器检测常用）
            "/proc/cpuinfo",
            "/proc/meminfo",
            "/proc/version",
            "/proc/stat",
            "/proc/uptime",
            "/proc/diskstats",
            "/proc/net/tcp",
            "/proc/net/wifi",
            "/proc/tty/drivers"
        )

        // 敏感命令关键词（Runtime.exec / ProcessBuilder）
        private val SENSITIVE_EXEC_KEYWORDS = setOf(
            "cat /proc/self/maps",
            "cat /proc/self/status",
            "cat /proc/self/smaps",
            "cat /proc/self/wchan",
            "cat /proc/self/cmdline",
            "cat /proc/self/mountinfo",
            "cat /proc/self/task/",
            "ls /data/adb/",
            "ls /data/misc/",
            // 新增：系统信息命令
            "cat /proc/cpuinfo",
            "cat /proc/meminfo",
            "cat /proc/version",
            "cat /proc/stat",
            "cat /proc/uptime",
            "getprop",
            "getprop ro.hardware",
            "getprop ro.product.model",
            "getprop ro.kernel.qemu",
            "getprop ro.debuggable",
            "dumpsys",
            "pm list packages",
            "pm list installed"
        )

        // /proc/maps 行级过滤关键词
        private val PROC_LINE_KEYWORDS = setOf(
            "lsposed", "xposed", "riru", "lsplant",
            "edxposed", "magisk", "zygisk",
            "com.dingtalk.helper", "hidemyapplist",
            "liblsposed_art.so", "libmemudisk.so",
            "libxposed_art.so", "libsandhook.so",
            // 新增：更多 Hook 框架特征
            "libwhale.so", "libtiran.so", "liblspd.so", "libriru.so",
            "frida", "gadget", "substrate",
            "libdexposed", "libnativehook"
        )

        // /proc/cpuinfo 中的模拟器特征
        private val EMULATOR_CPU_KEYWORDS = setOf(
            "goldfish", "ranchu", "emulator", "qemu",
            "vbox", "genymotion", "intel", "amd",
            "x86", "x86_64", "i686"
        )

        // /proc/version 中的模拟器特征
        private val EMULATOR_VERSION_KEYWORDS = setOf(
            "goldfish", "ranchu", "emulator", "qemu",
            "vbox", "genymotion", "android-x86"
        )
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.log("$TAG: 开始注入反追踪 Hook")

        try {
            // 注意：Thread.getStackTrace 过滤由 EnvironmentHooks 负责，此处不重复
            hookDebugDetection(lpparam)
            hookRuntimeExecForProc(lpparam)
            hookProcessBuilderForProc(lpparam)
            hookBufferedReaderForProc(lpparam)
            hookFileInputStreamForProc(lpparam)
            // 新增：拦截系统信息文件读取
            hookProcSystemInfo(lpparam)
            hookGetpropCommand(lpparam)

            HookUtils.log("$TAG: 反追踪 Hook 注入完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 反追踪 Hook 注入失败: ${e.message}")
            if (Constants.DEBUG_MODE) {
                e.printStackTrace()
            }
        }
    }

    // ==================== Debug 检测隐藏 ====================

    /**
     * Hook Debug.isDebuggerConnected 系列方法
     * 隐藏调试器连接状态
     */
    private fun hookDebugDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val debugClass = XposedHelpers.findClass("android.os.Debug", lpparam.classLoader)

            // Hook Debug.isDebuggerConnected()
            XposedHelpers.findAndHookMethod(
                debugClass, "isDebuggerConnected",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )

            // Hook Debug.isDebuggerConnected() 备选签名
            try {
                XposedHelpers.findAndHookMethod(
                    debugClass, "isDebuggerConnected",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = false
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook Debug.getGlobalDebugger
            try {
                XposedHelpers.findAndHookMethod(
                    debugClass, "getGlobalDebugger",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = null
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook Debug.waitForDebugger
            try {
                XposedHelpers.findAndHookMethod(
                    debugClass, "waitForDebugger",
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any = false
                    }
                )
            } catch (_: Exception) {}

            // Hook Debug attach/detach 系列
            try {
                XposedHelpers.findAndHookMethod(
                    debugClass, "attachJvmtiAgent",
                    String::class.java, ClassLoader::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val agent = param.args[0] as? String ?: return
                            if (agent.contains("jdwp", ignoreCase = true) ||
                                agent.contains("debug", ignoreCase = true)) {
                                param.result = null
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.log("$TAG: Debug 检测隐藏完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Debug 检测隐藏失败: ${e.message}")
        }
    }

    // ==================== 3. Runtime.exec / ProcessBuilder 拦截 ====================

    /**
     * Hook Runtime.exec - 拦截读取 /proc/self/maps等 的命令
     */
    private fun hookRuntimeExecForProc(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)

            // exec(String)
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmd = param.args[0] as? String ?: return
                        if (isSensitiveExecCommand(cmd)) {
                            param.args[0] = "echo ''"
                            HookUtils.logDebug("$TAG: 阻止敏感 exec: $cmd")
                        }
                    }
                }
            )

            // exec(String[])
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec", Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmds = param.args[0] as? Array<*> ?: return
                        val joined = cmds.joinToString(" ")
                        if (isSensitiveExecCommand(joined)) {
                            param.args[0] = arrayOf("echo", "")
                            HookUtils.logDebug("$TAG: 阻止敏感 exec[]: $joined")
                        }
                    }
                }
            )

            // exec(String, String[])
            try {
                XposedHelpers.findAndHookMethod(
                    runtimeClass, "exec",
                    String::class.java, Array<String>::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val cmd = param.args[0] as? String ?: return
                            if (isSensitiveExecCommand(cmd)) {
                                param.args[0] = "echo ''"
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            // exec(String[], String[])
            try {
                XposedHelpers.findAndHookMethod(
                    runtimeClass, "exec",
                    Array<String>::class.java, Array<String>::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val cmds = param.args[0] as? Array<*> ?: return
                            val joined = cmds.joinToString(" ")
                            if (isSensitiveExecCommand(joined)) {
                                param.args[0] = arrayOf("echo", "")
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            // exec(String, String[], File)
            try {
                XposedHelpers.findAndHookMethod(
                    runtimeClass, "exec",
                    String::class.java, Array<String>::class.java, File::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val cmd = param.args[0] as? String ?: return
                            if (isSensitiveExecCommand(cmd)) {
                                param.args[0] = "echo ''"
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            // exec(String[], String[], File)
            try {
                XposedHelpers.findAndHookMethod(
                    runtimeClass, "exec",
                    Array<String>::class.java, Array<String>::class.java, File::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val cmds = param.args[0] as? Array<*> ?: return
                            val joined = cmds.joinToString(" ")
                            if (isSensitiveExecCommand(joined)) {
                                param.args[0] = arrayOf("echo", "")
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.log("$TAG: Runtime.exec /proc 拦截完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Runtime.exec Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook ProcessBuilder.start - 拦截读取 /proc/self/maps等 的命令
     */
    private fun hookProcessBuilderForProc(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pbClass = XposedHelpers.findClass("java.lang.ProcessBuilder", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                pbClass, "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val field = XposedHelpers.findField(param.thisObject.javaClass, "command")
                            val commands = field.get(param.thisObject) as? List<*> ?: return
                            val cmdStr = commands.joinToString(" ")
                            if (isSensitiveExecCommand(cmdStr)) {
                                field.set(param.thisObject, listOf("echo", ""))
                                HookUtils.logDebug("$TAG: 阻止 ProcessBuilder: $cmdStr")
                            }
                        } catch (_: Exception) {}
                    }
                }
            )

            HookUtils.log("$TAG: ProcessBuilder /proc 拦截完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: ProcessBuilder Hook 失败: ${e.message}")
        }
    }

    // ==================== 4. /proc 文件读取过滤 ====================

    /**
     * Hook BufferedReader.readLine - 过滤 /proc/self/maps等 行中的敏感关键词
     * 用于 native file read (NIO) 等绕过 FileInputStream 的场景
     */
    private fun hookBufferedReaderForProc(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val readerClass = BufferedReader::class.java
            val readingProc = ThreadLocal<Boolean>()

            XposedHelpers.findAndHookMethod(
                readerClass, "readLine",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (readingProc.get() == true) return
                        var line = param.result as? String ?: return

                        if (isProcLineSensitive(line)) {
                            readingProc.set(true)
                            try {
                                do {
                                    line = XposedBridge.invokeOriginalMethod(
                                        param.method, param.thisObject, param.args
                                    ) as? String ?: break
                                } while (isProcLineSensitive(line))
                                param.result = line
                            } finally {
                                readingProc.set(false)
                            }
                        }
                    }
                }
            )

            HookUtils.log("$TAG: BufferedReader /proc 过滤完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: BufferedReader 过滤失败: ${e.message}")
        }
    }

    /**
     * Hook FileInputStream 构造 - 拦截更多 /proc/self/maps等 路径
     * 补充 DeepHidingHooks 只拦截 /proc/self/maps 的不足
     */
    private fun hookFileInputStreamForProc(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fisClass = FileInputStream::class.java

            // Hook FileInputStream(String)
            XposedHelpers.findAndHookConstructor(
                fisClass, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = param.args[0] as? String ?: return
                        if (isProcSensitivePath(path)) {
                            param.throwable = java.io.FileNotFoundException("No such file: $path")
                        }
                    }
                }
            )

            // Hook FileInputStream(File)
            XposedHelpers.findAndHookConstructor(
                fisClass, File::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val file = param.args[0] as? File ?: return
                        if (isProcSensitivePath(file.absolutePath)) {
                            param.throwable = java.io.FileNotFoundException("No such file: ${file.absolutePath}")
                        }
                    }
                }
            )

            HookUtils.log("$TAG: FileInputStream /proc 拦截完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: FileInputStream /proc 拦截失败: ${e.message}")
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 检查命令是否为敏感 /proc 读取命令
     */
    private fun isSensitiveExecCommand(command: String): Boolean {
        val cmdLower = command.lowercase()
        return SENSITIVE_EXEC_KEYWORDS.any { cmdLower.contains(it) } ||
            PROC_SENSITIVE_PATHS.any { cmdLower.contains(it) }
    }

    /**
     * 检查路径是否为敏感 /proc 路径
     */
    private fun isProcSensitivePath(path: String): Boolean {
        val normalized = path.replace("//", "/")
        return PROC_SENSITIVE_PATHS.any { normalized.startsWith(it) } ||
            normalized.matches(Regex("/proc/\\d+/maps")) ||
            normalized.matches(Regex("/proc/\\d+/status")) ||
            normalized.matches(Regex("/proc/\\d+/smaps"))
    }

    /**
     * 检查 /proc 行是否包含敏感关键词
     */
    private fun isProcLineSensitive(line: String): Boolean {
        val lineLower = line.lowercase()
        return PROC_LINE_KEYWORDS.any { lineLower.contains(it) }
    }

    // ==================== 5. 系统信息文件拦截 ====================

    /**
     * 拦截 /proc/cpuinfo, /proc/meminfo, /proc/version 等系统信息文件读取
     * 这些文件可以被用来检测模拟器
     */
    private fun hookProcSystemInfo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fisClass = FileInputStream::class.java

            // Hook FileInputStream(String) - 拦截系统信息文件
            XposedHelpers.findAndHookConstructor(
                fisClass, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = param.args[0] as? String ?: return
                        if (isSystemInfoPath(path)) {
                            // 返回空文件而不是抛异常，避免应用崩溃
                            param.args[0] = "/dev/null"
                            HookUtils.logDebug("$TAG: 重定向系统信息文件: $path -> /dev/null")
                        }
                    }
                }
            )

            // Hook FileInputStream(File) - 拦截系统信息文件
            XposedHelpers.findAndHookConstructor(
                fisClass, File::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val file = param.args[0] as? File ?: return
                        if (isSystemInfoPath(file.absolutePath)) {
                            param.args[0] = File("/dev/null")
                            HookUtils.logDebug("$TAG: 重定向系统信息文件: ${file.absolutePath} -> /dev/null")
                        }
                    }
                }
            )

            // Hook BufferedReader.readLine - 过滤 /proc/cpuinfo 和 /proc/version 中的模拟器特征
            val readerClass = BufferedReader::class.java
            val readingSystemInfo = ThreadLocal<Boolean>()

            XposedHelpers.findAndHookMethod(
                readerClass, "readLine",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (readingSystemInfo.get() == true) return
                        var line = param.result as? String ?: return

                        // 检查是否包含模拟器特征
                        if (containsEmulatorKeyword(line)) {
                            readingSystemInfo.set(true)
                            try {
                                // 跳过包含模拟器特征的行
                                do {
                                    line = XposedBridge.invokeOriginalMethod(
                                        param.method, param.thisObject, param.args
                                    ) as? String ?: break
                                } while (containsEmulatorKeyword(line))
                                param.result = line
                            } finally {
                                readingSystemInfo.set(false)
                            }
                        }
                    }
                }
            )

            HookUtils.log("$TAG: 系统信息文件拦截完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 系统信息文件拦截失败: ${e.message}")
        }
    }

    /**
     * 拦截 getprop 命令输出
     * getprop 用于读取系统属性，可以检测模拟器
     */
    private fun hookGetpropCommand(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)

            // Hook exec(String) - 拦截 getprop 命令
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmd = param.args[0] as? String ?: return
                        if (cmd.contains("getprop")) {
                            // 替换为空命令
                            param.args[0] = "echo ''"
                            HookUtils.logDebug("$TAG: 阻止 getprop 命令: $cmd")
                        }
                    }
                }
            )

            // Hook exec(String[]) - 拦截 getprop 命令
            XposedHelpers.findAndHookMethod(
                runtimeClass, "exec", Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmds = param.args[0] as? Array<*> ?: return
                        val joined = cmds.joinToString(" ")
                        if (joined.contains("getprop")) {
                            param.args[0] = arrayOf("echo", "")
                            HookUtils.logDebug("$TAG: 阻止 getprop 命令: $joined")
                        }
                    }
                }
            )

            HookUtils.log("$TAG: getprop 命令拦截完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: getprop 命令拦截失败: ${e.message}")
        }
    }

    /**
     * 检查路径是否为系统信息文件
     */
    private fun isSystemInfoPath(path: String): Boolean {
        val normalized = path.replace("//", "/")
        return normalized == "/proc/cpuinfo" ||
                normalized == "/proc/meminfo" ||
                normalized == "/proc/version" ||
                normalized == "/proc/stat" ||
                normalized == "/proc/uptime" ||
                normalized == "/proc/diskstats" ||
                normalized == "/proc/net/tcp" ||
                normalized == "/proc/net/wifi" ||
                normalized == "/proc/tty/drivers"
    }

    /**
     * 检查文本是否包含模拟器关键词
     */
    private fun containsEmulatorKeyword(text: String): Boolean {
        val lower = text.lowercase()
        return EMULATOR_CPU_KEYWORDS.any { lower.contains(it) } ||
                EMULATOR_VERSION_KEYWORDS.any { lower.contains(it) }
    }
}
