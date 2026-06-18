package com.dingtalk.helper.xposed.hooks

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * 应用列表隐藏 Hook
 * 借鉴 Hide-My-Applist 项目
 * 防止钉钉检测到虚拟定位相关应用
 */
class AppHidingHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${HookEntry.TAG}:AppHiding"

        // 需要隐藏的应用包名
        private val HIDDEN_PACKAGES = setOf(
            // 虚拟定位相关
            "com.dingtalk.helper",
            "com.noobexon.xposedfakelocation",
            "com.ella.portal",
            "com.github.fakelocation",
            "com.csgo.fkgps",

            // Xposed/LSPosed 相关
            "org.lsposed.manager",
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager",
            "io.github.lsposed.manager",

            // Magisk 相关
            "com.topjohnwu.magisk",

            // 分身应用相关
            "com.lbe.parallel",
            "io.virtualapp",
            "com.excean.dualaid",
            "com.ludashi.dualspace",
            "com.parallel.space.lite"
        )

        // 需要隐藏的文件路径
        private val HIDDEN_PATHS = setOf(
            "/data/adb/magisk",
            "/data/adb/modules",
            "/data/adb/lspd",
            "/data/misc/lspd",
            "/data/data/org.lsposed.manager",
            "/data/data/com.topjohnwu.magisk"
        )
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG: 开始注入应用列表隐藏 Hook")

        // Hook PackageManager 查询
        hookPackageManager(lpparam)

        // Hook 文件系统访问
        hookFileSystem(lpparam)

        // Hook Intent 解析
        hookIntentResolution(lpparam)

        // Hook /proc 文件系统
        hookProcFileSystem(lpparam)
    }

    /**
     * Hook PackageManager 相关方法
     */
    private fun hookPackageManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pmClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader
            )

            // Hook getInstalledPackages
            XposedHelpers.findAndHookMethod(
                pmClass,
                "getInstalledPackages",
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val flags = param.args[0] as Int
                        val packages = param.result as List<PackageInfo>
                        param.result = filterPackages(packages)
                        XposedBridge.log("$TAG: getInstalledPackages 已过滤")
                    }
                }
            )

            // Hook getInstalledApplications
            XposedHelpers.findAndHookMethod(
                pmClass,
                "getInstalledApplications",
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val apps = param.result as List<ApplicationInfo>
                        param.result = filterApplications(apps)
                        XposedBridge.log("$TAG: getInstalledApplications 已过滤")
                    }
                }
            )

            // Hook getPackageInfo (单个包查询)
            XposedHelpers.findAndHookMethod(
                pmClass,
                "getPackageInfo",
                String::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as String
                        if (HIDDEN_PACKAGES.contains(packageName)) {
                            // 抛出 NameNotFoundException
                            param.throwable = PackageManager.NameNotFoundException(
                                "Package $packageName not found"
                            )
                            XposedBridge.log("$TAG: 隐藏包查询: $packageName")
                        }
                    }
                }
            )

            // Hook getApplicationInfo
            XposedHelpers.findAndHookMethod(
                pmClass,
                "getApplicationInfo",
                String::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as String
                        if (HIDDEN_PACKAGES.contains(packageName)) {
                            param.throwable = PackageManager.NameNotFoundException(
                                "Package $packageName not found"
                            )
                            XposedBridge.log("$TAG: 隐藏应用信息查询: $packageName")
                        }
                    }
                }
            )

            // Hook queryIntentActivities
            XposedHelpers.findAndHookMethod(
                pmClass,
                "queryIntentActivities",
                android.content.Intent::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activities = param.result as List<*>
                        param.result = activities.filter { activity ->
                            val packageName = activity.javaClass
                                .getDeclaredField("activityInfo")
                                .apply { isAccessible = true }
                                .get(activity)
                                .javaClass
                                .getDeclaredField("packageName")
                                .apply { isAccessible = true }
                                .get(activity) as String
                            !HIDDEN_PACKAGES.contains(packageName)
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: PackageManager Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: PackageManager Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook 文件系统访问
     */
    private fun hookFileSystem(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fileClass = XposedHelpers.findClass("java.io.File", lpparam.classLoader)

            // Hook listFiles
            XposedHelpers.findAndHookMethod(
                fileClass,
                "listFiles",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dir = (param.thisObject as File).absolutePath
                        val files = param.result as? Array<File> ?: return

                        // 过滤隐藏路径下的文件
                        if (shouldFilterDirectory(dir)) {
                            param.result = files.filter { file ->
                                !shouldHideFile(file.absolutePath)
                            }.toTypedArray()
                        }
                    }
                }
            )

            // Hook exists
            XposedHelpers.findAndHookMethod(
                fileClass,
                "exists",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val path = (param.thisObject as File).absolutePath
                        if (shouldHideFile(path)) {
                            return false
                        }
                        return XposedBridge.invokeOriginalMethod(
                            param.method, param.thisObject, param.args
                        )
                    }
                }
            )

            XposedBridge.log("$TAG: 文件系统 Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: 文件系统 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook Intent 解析
     */
    private fun hookIntentResolution(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val intentClass = XposedHelpers.findClass(
                "android.content.Intent",
                lpparam.classLoader
            )

            // Hook resolveActivity
            XposedHelpers.findAndHookMethod(
                intentClass,
                "resolveActivity",
                PackageManager::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result
                        if (result != null) {
                            val packageName = result.javaClass
                                .getDeclaredField("packageName")
                                .apply { isAccessible = true }
                                .get(result) as String
                            if (HIDDEN_PACKAGES.contains(packageName)) {
                                param.result = null
                            }
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: Intent 解析 Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Intent 解析 Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook /proc 文件系统
     * 隐藏进程信息
     */
    private fun hookProcFileSystem(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook BufferedReader 读取 /proc/self/maps
            val readerClass = XposedHelpers.findClass(
                "java.io.BufferedReader",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                readerClass,
                "readLine",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val line = param.result as? String ?: return
                        // 过滤 Xposed/Magisk 相关的库路径
                        if (line.contains("lsposed") ||
                            line.contains("xposed") ||
                            line.contains("magisk") ||
                            line.contains("riru")) {
                            // 跳过这行，读取下一行
                            param.result = XposedBridge.invokeOriginalMethod(
                                param.method, param.thisObject, param.args
                            )
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: /proc 文件系统 Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: /proc 文件系统 Hook 失败: ${e.message}")
        }
    }

    /**
     * 过滤包列表
     */
    private fun filterPackages(packages: List<PackageInfo>): List<PackageInfo> {
        return packages.filter { !HIDDEN_PACKAGES.contains(it.packageName) }
    }

    /**
     * 过滤应用列表
     */
    private fun filterApplications(apps: List<ApplicationInfo>): List<ApplicationInfo> {
        return apps.filter { !HIDDEN_PACKAGES.contains(it.packageName) }
    }

    /**
     * 判断是否应该过滤目录
     */
    private fun shouldFilterDirectory(dir: String): Boolean {
        return dir.contains("/data/data") ||
               dir.contains("/data/adb") ||
               dir.contains("/data/misc")
    }

    /**
     * 判断是否应该隐藏文件
     */
    private fun shouldHideFile(path: String): Boolean {
        return HIDDEN_PACKAGES.any { path.contains(it) } ||
               HIDDEN_PATHS.any { path.startsWith(it) }
    }
}