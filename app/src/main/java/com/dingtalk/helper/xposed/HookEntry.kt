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

/**
 * Xposed 模块入口
 * 负责加载所有 Hook 并分发到对应的应用
 */
class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        const val TAG = Constants.LOG_PREFIX

        // 模块实例
        var instance: HookEntry? = null

        // 模块上下文
        var moduleContext: Context? = null

        // 模块是否已初始化
        var isInitialized = false
            private set

        // Hook 统计
        private var hookSuccessCount = 0
        private var hookFailCount = 0
    }

    // Hook 处理器列表（按优先级排序）
    private val hookHandlers by lazy {
        listOf(
            // 第一层：环境隐藏（最先加载）
            EnvironmentHooks(),
            AppHidingHooks(),

            // 第二层：ROM 兼容性
            RomCompatibilityHooks(),

            // 第三层：定位数据伪造
            LocationHooks(),
            GnssHooks(),
            WifiHooks(),
            CellHooks(),
            SensorHooks(),

            // 第四层：风控数据拦截（最后加载）
            RiskControlHooks()
        )
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        instance = this
        HookUtils.log("$TAG: 模块已加载到 Zygote")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只处理钉钉应用
        if (lpparam.packageName != Constants.DINGTALK_PACKAGE) {
            return
        }

        HookUtils.log("$TAG: 检测到钉钉应用加载")

        // 重置统计
        hookSuccessCount = 0
        hookFailCount = 0

        try {
            // 获取模块上下文
            initModuleContext(lpparam)

            // 检查模块是否启用
            if (!ConfigManager.isEnabled()) {
                HookUtils.log("$TAG: 模块未启用，跳过注入")
                return
            }

            // 执行所有 Hook 处理器
            executeHooks(lpparam)

            // 输出统计信息
            HookUtils.log("$TAG: Hook 完成 - 成功: $hookSuccessCount, 失败: $hookFailCount")
            isInitialized = true

        } catch (e: Exception) {
            HookUtils.log("$TAG: 初始化失败: ${e.message}")
            if (Constants.DEBUG_MODE) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 执行所有 Hook 处理器
     */
    private fun executeHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        val startTime = System.currentTimeMillis()

        hookHandlers.forEach { handler ->
            val handlerName = handler.javaClass.simpleName
            try {
                handler.hook(lpparam)
                hookSuccessCount++
                HookUtils.logDebug("$handlerName 加载成功")
            } catch (e: Exception) {
                hookFailCount++
                HookUtils.log("$TAG: $handlerName 加载失败: ${e.message}")
                if (Constants.DEBUG_MODE) {
                    e.printStackTrace()
                }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        HookUtils.log("$TAG: 所有 Hook 已注入完成 (耗时 ${duration}ms)")
    }

    /**
     * 初始化模块上下文
     */
    private fun initModuleContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                "currentActivityThread"
            )

            val context = XposedHelpers.callMethod(activityThread, "getSystemContext") as Context
            moduleContext = context

            // 初始化配置管理器
            ConfigManager.init(context)

            HookUtils.log("$TAG: 模块上下文初始化成功")
        } catch (e: Exception) {
            HookUtils.log("$TAG: 模块上下文初始化失败: ${e.message}")
            throw e
        }
    }

    /**
     * Hook 处理器接口
     */
    interface HookHandler {
        /**
         * 执行 Hook
         * @param lpparam 加载包参数
         * @throws Exception 如果 Hook 失败
         */
        fun hook(lpparam: XC_LoadPackage.LoadPackageParam)
    }
}