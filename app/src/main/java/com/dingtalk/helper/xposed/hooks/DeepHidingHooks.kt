package com.dingtalk.helper.xposed.hooks

import android.content.pm.PackageManager
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
 * 深度隐藏 Hook 模块
 *
 * 职责（按检测面分层）：
 * 1. /proc/self/maps - 拦截文件读取，过滤敏感映射行
 * 2. LSPosed 目录    - 隐藏 /data/adb/lspd 等路径
 * 3. Magisk 目录     - 隐藏 /data/adb/magisk 等路径
 * 4. Debug 检测      - 隐藏调试器连接状态
 * 5. RunningProcesses - 过滤 LSPosed/Magisk 进程
 *
 * 注意：
 * - ClassLoader.loadClass Hook 由 EnvironmentHooks 负责
 * - PackageManager Hook 由 AppHidingHooks / AdvancedAntiDetectionHooks 负责
 * - Thread.getStackTrace Hook 由 EnvironmentHooks 负责
 * - System.getProperty Hook 由 EnvironmentHooks 负责
 *
 * 参考项目：Shamiko、Hide-My-Applist、MagiskHidePropsConf
 */
class DeepHidingHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:DeepHiding"

        // Magisk 相关包名（补充）
        private val MAGISK_PACKAGES = setOf(
            "com.topjohnwu.magisk",
            "com.topjohnwu.magiskelta",
            "com.topjohnwu.magisk.app",
            "io.github.vvb2060.magisk",
            "io.github.huskydg.magisk"
        )

        // 需要隐藏的目录路径前缀
        // 注意：不包含自身包名路径，避免隐藏自己导致反检测
        private val HIDDEN_DIR_PREFIXES = listOf(
            "/data/adb/lspd",
            "/data/misc/lspd",
            "/data/adb/magisk",
            "/data/adb/modules",
            "/data/adb/magisk.db",
            "/data/adb/magisk.img",
            // Riru 相关
            "/data/adb/riru",
            "/data/misc/riru",
            // Zygisk 相关
            "/data/adb/zygisk"
        )

        // /proc/maps 中需要过滤的关键词（扩展版，包含 .so 库特征）
        private val PROC_MAPS_KEYWORDS = setOf(
            "lsposed", "xposed", "riru", "lsplant",
            "edxposed", "magisk", "zygisk",
            "com.dingtalk.helper", "hidemyapplist",
            // .so 库特征
            "liblsposed_art.so", "libmemudisk.so",
            "libxposed_art.so", "libsandhook.so",
            "libwhale.so", "libtiran.so",
            "liblspd.so", "libriru.so"
        )

        // 需要拦截的 /proc/self/maps等路径
        private val PROC_SELF_PATHS = setOf(
            "/proc/self/maps",
            "/proc/self/smaps",
            "/proc/self/status",
            "/proc/self/wchan",
            "/proc/self/cmdline",
            "/proc/self/mountinfo",
            "/proc/thread-self/maps",
            "/proc/thread-self/status"
        )

        // Magisk 相关路径关键词（用于文件路径检查）
        private val MAGISK_PATH_KEYWORDS = setOf(
            "/data/adb/magisk", "/sbin/magisk",
            "magisk.img", "magisk.db",
            "/data/adb/modules"
        )

        // Runtime.exec 命令拦截的正则：精确匹配 su 命令（单词边界）
        private val SU_COMMAND_REGEX = Regex("\\bsu\\b")
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.log("$TAG: 开始注入深度隐藏 Hook")

        try {
            // /proc/self/maps 拦截（独有）
            hookProcMaps(lpparam)
            // LSPosed 目录隐藏（独有）
            hookLSPosedDetection(lpparam)
            // Magisk 目录隐藏（独有）
            hookMagiskDetection(lpparam)
            // 调试器隐藏（独有）
            hookDebugDetection(lpparam)

            HookUtils.log("$TAG: 深度隐藏 Hook 注入完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 深度隐藏 Hook 注入失败: ${e.message}")
            if (Constants.DEBUG_MODE) {
                e.printStackTrace()
            }
        }
    }

    // ==================== /proc/self/maps等 隐藏 ====================

    /**
     * 拦截对 /proc/self/maps等 的读取
     * 通过 FileInputStream + BufferedReader 双层过滤
     */
    private fun hookProcMaps(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookFileInputStreamForMaps(lpparam)
        hookBufferedReaderForMaps(lpparam)
    }

    /**
     * Hook FileInputStream 构造函数
     * 对 /proc/self/maps 直接抛出 FileNotFoundException
     */
    private fun hookFileInputStreamForMaps(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fisClass = FileInputStream::class.java

            // Hook FileInputStream(String)
            XposedHelpers.findAndHookConstructor(
                fisClass, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = param.args[0] as? String ?: return
                        if (isProcMapsPath(path)) {
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
                        if (isProcMapsPath(file.absolutePath)) {
                            param.throwable = java.io.FileNotFoundException("No such file: ${file.absolutePath}")
                        }
                    }
                }
            )

            HookUtils.log("$TAG: FileInputStream /proc/maps 拦截完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: FileInputStream /proc/maps 拦截失败: ${e.message}")
        }
    }

    /**
     * Hook BufferedReader.readLine
     * 读取 /proc/maps 时过滤敏感行
     *
     * 修复 C5：使用循环读取替代单次 invokeOriginalMethod，避免无限递归
     * 当检测到敏感行时，持续读取直到找到非敏感行或 EOF
     */
    private fun hookBufferedReaderForMaps(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val readerClass = BufferedReader::class.java
            val readingProcMaps = ThreadLocal<Boolean>()

            XposedHelpers.findAndHookMethod(
                readerClass, "readLine",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (readingProcMaps.get() == true) return
                        var line = param.result as? String ?: return

                        if (isProcMapsSensitiveLine(line)) {
                            readingProcMaps.set(true)
                            try {
                                // 循环读取，跳过所有连续的敏感行，直到找到非敏感行或 EOF
                                do {
                                    line = XposedBridge.invokeOriginalMethod(
                                        param.method, param.thisObject, param.args
                                    ) as? String ?: break
                                } while (isProcMapsSensitiveLine(line))
                                param.result = line
                            } finally {
                                readingProcMaps.set(false)
                            }
                        }
                    }
                }
            )

            HookUtils.log("$TAG: BufferedReader /proc/maps 过滤完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: BufferedReader /proc/maps 过滤失败: ${e.message}")
        }
    }

    // ==================== LSPosed 目录隐藏 ====================

    /**
     * 隐藏 LSPosed 相关目录和文件
     */
    private fun hookLSPosedDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fileClass = File::class.java
            val hiddenDirs = listOf(
                "/data/adb/lspd", "/data/misc/lspd",
                "/data/data/org.lsposed.manager", "/data/data/io.github.lsposed.manager"
            )

            // Hook File.exists()
            XposedHelpers.findAndHookMethod(
                fileClass, "exists",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val path = (param.thisObject as File).absolutePath
                        if (hiddenDirs.any { path.startsWith(it) }) return false
                        return XposedBridge.invokeOriginalMethod(
                            param.method, param.thisObject, param.args
                        )
                    }
                }
            )

            // Hook File.listFiles()
            XposedHelpers.findAndHookMethod(
                fileClass, "listFiles",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dir = (param.thisObject as File).absolutePath
                        if (!dir.startsWith("/data/adb") && !dir.startsWith("/data/misc")) return
                        val files = param.result as? Array<File> ?: return
                        param.result = files.filter { file ->
                            val name = file.name.lowercase()
                            name != "lspd" && !name.contains("lsposed")
                        }.toTypedArray()
                    }
                }
            )

            // 隐藏 LSPosed 相关包名（PackageManager）
            val lsposedPkgs = setOf(
                "org.lsposed.manager", "io.github.lsposed.manager"
            )
            val pmClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                pmClass, "getPackageInfo",
                String::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val pkgName = param.args[0] as? String ?: return
                        if (pkgName in lsposedPkgs) {
                            param.throwable = PackageManager.NameNotFoundException("Not found")
                        }
                    }
                }
            )

            HookUtils.log("$TAG: LSPosed 隐藏完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: LSPosed 隐藏失败: ${e.message}")
        }
    }

    // ==================== Magisk 目录隐藏 ====================

    /**
     * 隐藏 Magisk 相关目录、文件和命令
     */
    private fun hookMagiskDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fileClass = File::class.java

            // Hook File.exists() - 隐藏 Magisk 路径
            XposedHelpers.findAndHookMethod(
                fileClass, "exists",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val path = (param.thisObject as File).absolutePath
                        if (isMagiskPath(path)) return false
                        return XposedBridge.invokeOriginalMethod(
                            param.method, param.thisObject, param.args
                        )
                    }
                }
            )

            // Hook File.listFiles() - 隐藏 su 和 Magisk 相关文件
            XposedHelpers.findAndHookMethod(
                fileClass, "listFiles",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dir = (param.thisObject as File).absolutePath
                        if (dir != "/system/bin" && dir != "/system/xbin" &&
                            dir != "/sbin" && dir != "/data/adb") return
                        val files = param.result as? Array<File> ?: return
                        param.result = files.filter { file ->
                            val name = file.name.lowercase()
                            name != "su" && name != "magisk" && name != "magiskhide" &&
                                name != "magiskpolicy" && name != "magisk.img" &&
                                name != "magisk.db" && name != "modules"
                        }.toTypedArray()
                    }
                }
            )

            // 隐藏 Magisk 相关包名
            val pmClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                pmClass, "getPackageInfo",
                String::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val pkgName = param.args[0] as? String ?: return
                        if (pkgName in MAGISK_PACKAGES) {
                            param.throwable = PackageManager.NameNotFoundException("Not found")
                        }
                    }
                }
            )

            // Hook Runtime.exec - 拦截 su/magisk 命令
            // M10 修复：使用 Regex("\bsu\b") 替代 contains("su")，避免误拦截 "sudo" 等
            try {
                val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    runtimeClass, "exec", String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val command = param.args[0] as? String ?: return
                            val cmdLower = command.lowercase()
                            if (SU_COMMAND_REGEX.containsMatchIn(cmdLower) ||
                                cmdLower.contains("magisk") ||
                                cmdLower.contains("/data/adb")) {
                                param.args[0] = "echo ''"
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook ActivityManager.getRunningAppProcesses - 过滤 Magisk 进程
            try {
                val amClass = android.app.ActivityManager::class.java
                XposedHelpers.findAndHookMethod(
                    amClass, "getRunningAppProcesses",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val processes = param.result as? List<*> ?: return
                            param.result = processes.filter { proc ->
                                val processName = try {
                                    XposedHelpers.getObjectField(proc, "processName") as? String ?: ""
                                } catch (_: Exception) { "" }
                                !processName.lowercase().contains("magisk")
                            }
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.log("$TAG: Magisk 隐藏完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Magisk 隐藏失败: ${e.message}")
        }
    }

    // ==================== Debug 检测隐藏 ====================

    /**
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

            // Hook Debug.isDebuggerConnected() 的另一种签名
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

            // Hook Debug.getGlobalDebugger()
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

            HookUtils.log("$TAG: Debug 检测隐藏完成")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: Debug 检测隐藏失败: ${e.message}")
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 检查路径是否为敏感 /proc/self/maps等 路径
     */
    private fun isProcMapsPath(path: String): Boolean {
        val normalizedPath = path.replace("//", "/")
        return PROC_SELF_PATHS.any { normalizedPath.startsWith(it) } ||
               normalizedPath.matches(Regex("/proc/\\d+/maps")) ||
               normalizedPath.matches(Regex("/proc/\\d+/status")) ||
               normalizedPath.matches(Regex("/proc/\\d+/smaps"))
    }

    /**
     * 检查 /proc/maps 行是否包含敏感关键词
     */
    private fun isProcMapsSensitiveLine(line: String): Boolean {
        val lineLower = line.lowercase()
        return PROC_MAPS_KEYWORDS.any { lineLower.contains(it) }
    }

    /**
     * 检查路径是否为 Magisk 相关
     */
    private fun isMagiskPath(path: String): Boolean {
        return MAGISK_PATH_KEYWORDS.any { path.contains(it) } ||
               path.contains("/sbin/su") || path.contains("/system/bin/su") ||
               path.contains("/system/xbin/su")
    }
}
