package com.dingtalk.helper.xposed.hooks

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * 应用列表隐藏 Hook
 * 借鉴 Hide-My-Applist 项目
 * 防止钉钉检测到虚拟定位相关应用
 *
 * 职责：
 * - PackageManager 查询过滤
 * - 文件系统路径隐藏
 * - Intent 解析过滤
 * - /proc 文件系统隐藏
 */
class AppHidingHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:AppHiding"
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.log("$TAG: 开始注入应用列表隐藏 Hook")

        hookPackageManager(lpparam)
        hookFileSystem(lpparam)
        hookIntentResolution(lpparam)
        hookProcFileSystem(lpparam)
    }

    /**
     * Hook PackageManager - 过滤已安装应用列表
     */
    private fun hookPackageManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pmClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager", lpparam.classLoader
            )

            // Hook getInstalledPackages
            XposedHelpers.findAndHookMethod(
                pmClass, "getInstalledPackages", Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val packages = param.result as? List<*> ?: return
                        param.result = packages.filter { pkg ->
                            val pn = HookUtils.getFieldValueSafely(pkg!!, "packageName") as? String ?: ""
                            pn !in Constants.HIDDEN_PACKAGES
                        }
                    }
                }
            )

            // Hook getInstalledApplications
            XposedHelpers.findAndHookMethod(
                pmClass, "getInstalledApplications", Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val apps = param.result as? List<*> ?: return
                        param.result = apps.filter { app ->
                            val pn = HookUtils.getFieldValueSafely(app!!, "packageName") as? String ?: ""
                            pn !in Constants.HIDDEN_PACKAGES
                        }
                    }
                }
            )

            // Hook getPackageInfo (单个包查询)
            XposedHelpers.findAndHookMethod(
                pmClass, "getPackageInfo",
                String::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as? String ?: return
                        if (packageName in Constants.HIDDEN_PACKAGES) {
                            param.throwable = PackageManager.NameNotFoundException(
                                "Package $packageName not found"
                            )
                        }
                    }
                }
            )

            // Hook getApplicationInfo
            XposedHelpers.findAndHookMethod(
                pmClass, "getApplicationInfo",
                String::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as? String ?: return
                        if (packageName in Constants.HIDDEN_PACKAGES) {
                            param.throwable = PackageManager.NameNotFoundException(
                                "Package $packageName not found"
                            )
                        }
                    }
                }
            )

            // Hook queryIntentActivities - 过滤 Intent 查询结果
            XposedHelpers.findAndHookMethod(
                pmClass, "queryIntentActivities",
                android.content.Intent::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activities = param.result as? List<*> ?: return
                        param.result = activities.filter { resolveInfo ->
                            try {
                                val activityInfo = XposedHelpers.getObjectField(resolveInfo, "activityInfo")
                                val pn = XposedHelpers.getObjectField(activityInfo, "packageName") as? String ?: ""
                                pn !in Constants.HIDDEN_PACKAGES
                            } catch (_: Exception) {
                                true // 保留无法判断的
                            }
                        }
                    }
                }
            )

            HookUtils.log("$TAG: PackageManager Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: PackageManager Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook 文件系统 - 隐藏敏感路径
     */
    private fun hookFileSystem(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fileClass = XposedHelpers.findClass("java.io.File", lpparam.classLoader)

            // Hook listFiles
            XposedHelpers.findAndHookMethod(
                fileClass, "listFiles",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dir = (param.thisObject as File).absolutePath
                        if (!shouldFilterDirectory(dir)) return

                        val files = param.result as? Array<File> ?: return
                        param.result = files.filter { !shouldHideFile(it.absolutePath) }.toTypedArray()
                    }
                }
            )

            // Hook exists
            XposedHelpers.findAndHookMethod(
                fileClass, "exists",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val path = (param.thisObject as File).absolutePath
                        if (shouldHideFile(path)) return false
                        return XposedBridge.invokeOriginalMethod(
                            param.method, param.thisObject, param.args
                        )
                    }
                }
            )

            HookUtils.log("$TAG: 文件系统 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 文件系统 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Intent 解析
     */
    private fun hookIntentResolution(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val intentClass = XposedHelpers.findClass(
                "android.content.Intent", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                intentClass, "resolveActivity",
                PackageManager::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result ?: return
                        try {
                            val pn = XposedHelpers.getObjectField(result, "packageName") as? String ?: ""
                            if (pn in Constants.HIDDEN_PACKAGES) {
                                param.result = null
                            }
                        } catch (_: Exception) {}
                    }
                }
            )

            HookUtils.log("$TAG: Intent 解析 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: Intent 解析 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook /proc 文件系统 - 过滤 Xposed/Magisk 库路径
     */
    private fun hookProcFileSystem(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val readerClass = XposedHelpers.findClass(
                "java.io.BufferedReader", lpparam.classLoader
            )

            // 使用计数器防止无限递归
            val readingCounter = ThreadLocal<Int>()

            XposedHelpers.findAndHookMethod(
                readerClass, "readLine",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val counter = readingCounter.get() ?: 0
                        if (counter > 0) return // 防止递归

                        val line = param.result as? String ?: return
                        if (line.contains("lsposed", ignoreCase = true) ||
                            line.contains("xposed", ignoreCase = true) ||
                            line.contains("magisk", ignoreCase = true) ||
                            line.contains("riru", ignoreCase = true)) {

                            readingCounter.set(counter + 1)
                            try {
                                param.result = XposedBridge.invokeOriginalMethod(
                                    param.method, param.thisObject, param.args
                                )
                            } finally {
                                readingCounter.set(counter)
                            }
                        }
                    }
                }
            )

            HookUtils.log("$TAG: /proc 文件系统 Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: /proc 文件系统 Hook 失败: ${e.message}")
        }
    }

    // ==================== 工具方法 ====================

    private fun shouldFilterDirectory(dir: String): Boolean {
        return dir.startsWith("/data/data") ||
               dir.startsWith("/data/adb") ||
               dir.startsWith("/data/misc")
    }

    private fun shouldHideFile(path: String): Boolean {
        return Constants.HIDDEN_PACKAGES.any { path.contains(it) } ||
               Constants.HIDDEN_PATH_PREFIXES.any { path.startsWith(it) }
    }
}
