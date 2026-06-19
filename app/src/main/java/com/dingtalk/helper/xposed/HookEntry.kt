package com.dingtalk.helper.xposed

import android.content.Context
import com.dingtalk.helper.xposed.hooks.*
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import com.dingtalk.helper.utils.ConfigManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.dingtalk.helper.xposed.utils.HookLogger
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        const val TAG = Constants.LOG_PREFIX

        @Volatile var instance: HookEntry? = null

        @Volatile var moduleContext: Context? = null

        var isInitialized = false
            private set

        private var hookSuccessCount = 0
        private var hookFailCount = 0
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        instance = this
        HookUtils.log("$TAG: 模块已加载到 Zygote")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.DINGTALK_PACKAGE) {
            return
        }

        HookUtils.log("$TAG: 检测到钉钉应用加载")

        val totalStartTime = System.currentTimeMillis()

        hookSuccessCount = 0
        hookFailCount = 0

        try {
            initModuleContext(lpparam)

            if (!ConfigManager.isEnabled()) {
                HookUtils.log("$TAG: 模块未启用，跳过注入")
                return
            }

            executeHooksOptimized(lpparam)

            HookUtils.log("$TAG: Hook 完成 - 成功: $hookSuccessCount, 失败: $hookFailCount")
            isInitialized = true

        } catch (e: Exception) {
            HookUtils.log("$TAG: 初始化失败: ${e.message}")
            if (Constants.DEBUG_MODE) {
                e.printStackTrace()
            }
        } finally {
            val totalDuration = System.currentTimeMillis() - totalStartTime
            HookUtils.log("$TAG: 总启动耗时 ${totalDuration}ms")
        }
    }

    private fun executeHooksOptimized(lpparam: XC_LoadPackage.LoadPackageParam) {
        val startTime = System.currentTimeMillis()

        val syncHandlers = listOf(
            DeepHidingHooks(),
            AntiTraceHooks(),
            EnvironmentHooks(),
            EmulatorHooks(),
            AppHidingHooks(),
            AdvancedAntiDetectionHooks()
        )

        for (handler in syncHandlers) {
            val name = handler.javaClass.simpleName
            try {
                handler.hook(lpparam)
                hookSuccessCount++
                HookLogger.logSuccess(name)
            } catch (e: Exception) {
                hookFailCount++
                HookUtils.log("$TAG: $name 加载失败: ${e.message}")
                if (Constants.DEBUG_MODE) {
                    e.printStackTrace()
                }
            }
        }

        val syncDuration = System.currentTimeMillis() - startTime
        HookUtils.log("$TAG: 同步 Hook 完成 (${syncDuration}ms)")

        val pool = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "DTH-Init").apply { isDaemon = true }
        }

        val batch1 = listOf(
            LocationHooks(),
            WifiHooks(),
            CellHooks(),
            SensorHooks(),
            GnssHooks()
        )

        val batch2 = listOf(
            NmeaHooks(),
            FusedLocationHooks(),
            DingTalkCompatHooks()
        )

        val batch3 = listOf(
            DataCollectionInterceptor(),
            NativeHook(),
            RiskControlHooks(),
            RomCompatibilityHooks()
        )

        val latch = CountDownLatch(3)

        pool.submit {
            for (handler in batch1) {
                val name = handler.javaClass.simpleName
                try {
                    handler.hook(lpparam)
                    synchronized(this) { hookSuccessCount++ }
                    HookLogger.logSuccess(name)
                } catch (e: Exception) {
                    synchronized(this) { hookFailCount++ }
                    HookUtils.log("$TAG: $name 加载失败: ${e.message}")
                    if (Constants.DEBUG_MODE) {
                        e.printStackTrace()
                    }
                }
            }
            latch.countDown()
        }

        pool.submit {
            for (handler in batch2) {
                val name = handler.javaClass.simpleName
                try {
                    handler.hook(lpparam)
                    synchronized(this) { hookSuccessCount++ }
                    HookLogger.logSuccess(name)
                } catch (e: Exception) {
                    synchronized(this) { hookFailCount++ }
                    HookUtils.log("$TAG: $name 加载失败: ${e.message}")
                    if (Constants.DEBUG_MODE) {
                        e.printStackTrace()
                    }
                }
            }
            latch.countDown()
        }

        pool.submit {
            for (handler in batch3) {
                val name = handler.javaClass.simpleName
                try {
                    handler.hook(lpparam)
                    synchronized(this) { hookSuccessCount++ }
                    HookLogger.logSuccess(name)
                } catch (e: Exception) {
                    synchronized(this) { hookFailCount++ }
                    HookUtils.log("$TAG: $name 加载失败: ${e.message}")
                    if (Constants.DEBUG_MODE) {
                        e.printStackTrace()
                    }
                }
            }
            latch.countDown()
        }

        latch.await(30, TimeUnit.SECONDS)
        pool.shutdown()

        val duration = System.currentTimeMillis() - startTime
        HookUtils.log("$TAG: 所有 Hook 已注入完成 (耗时 ${duration}ms)")
    }

    private fun initModuleContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                "currentActivityThread"
            )

            val context = XposedHelpers.callMethod(activityThread, "getSystemContext") as Context
            moduleContext = context

            ConfigManager.init(context)

            HookUtils.log("$TAG: 模块上下文初始化成功")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 模块上下文初始化失败: ${e.message}")
            throw e
        }
    }

    interface HookHandler {
        fun hook(lpparam: XC_LoadPackage.LoadPackageParam)
    }
}
