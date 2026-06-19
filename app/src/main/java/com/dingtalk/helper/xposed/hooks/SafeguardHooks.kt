package com.dingtalk.helper.xposed.hooks

import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.data.FakeDataProvider
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * 钉钉安全 SDK (SafeGuard / SecurityGuard / lbswua / ddsec) Hook
 *
 * 基于逆向分析结果，精确拦截以下关键入口：
 *
 * 1. SafeGuardMain.setLocation(Location) - 将位置存入 argvMap["lc"]
 * 2. SafeGuardMain.getLocation() - 从 argvMap["lc"] 取出位置
 * 3. ISecurityBodyComponent.enterRiskScene() - 风险场景进入，传入位置列表
 * 4. LocationInterface 抽象方法 - getSimulatedBySoftware / getProducedByAccessory
 * 5. nr0.h() 中的 isFromMockProvider 检测
 * 6. CommandType.GET_ATTEND_SECURITY_DATA 返回值篡改
 *
 * 架构：
 * 钉钉 Java 层 → SafeGuardMain.setLocation(location)
 *              → argvMap.put("lc", location)
 *              → SafeGuardInterface.execCmd(GET_ATTEND_SECURITY_DATA)
 *              → JNI → libsafeguard.so
 *              → 读取 argvMap 中的位置
 *              → 采集设备信息/传感器
 *              → AES/SM4 加密
 *              → 返回 lbsWua + ddSig
 */
class SafeguardHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:Safeguard"

        private val SAFE_GUARD_PACKAGES = setOf(
            "com.alibaba.dingtalk.safeguard",
            "com.alibaba.wireless.security",
            "com.taobao.wireless.security"
        )

        // 已 hook 的 LocationInterface 实现类
        private val hookedLocationClasses = ConcurrentHashMap.newKeySet<String>()
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isEnabled()) return

        HookUtils.log("$TAG: 开始注入 SafeGuard 安全 SDK Hook")

        hookSafeGuardMain(lpparam)
        hookLocationInterface(lpparam)
        hookSecurityBodyComponent(lpparam)
        hookSecureSignatureComponent(lpparam)
        hookAntiCheatingHelper(lpparam)
        hookSafeGuardDispatcher(lpparam)

        HookUtils.log("$TAG: SafeGuard Hook 注入完成")
    }

    /**
     * Hook SafeGuardMain - 核心位置注入点
     *
     * SafeGuardMain.setLocation(Location) 将位置存入静态 HashMap argvMap["lc"]
     * SafeGuardMain.getLocation() 从 argvMap["lc"] 取出
     * native 层通过 getLocation() 读取位置，然后采集设备数据，加密生成 lbsWua/ddSec
     */
    private fun hookSafeGuardMain(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val safeGuardMainClass = XposedHelpers.findClass(
                "com.alibaba.dingtalk.safeguard.SafeGuardMain",
                lpparam.classLoader
            )

            // Hook setLocation(Location) - 在位置存入 argvMap 前替换为伪造位置
            XposedHelpers.findAndHookMethod(
                safeGuardMainClass,
                "setLocation",
                Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isFakeLocationEnabled()) return

                        try {
                            val originalLocation = param.args[0] as? Location ?: return
                            val fakeLocation = createFakeLocationForSafeguard()

                            HookUtils.logDebug("$TAG: setLocation 拦截: " +
                                "原始(${originalLocation.latitude}, ${originalLocation.longitude}) " +
                                "-> 伪造(${fakeLocation.latitude}, ${fakeLocation.longitude})")

                            param.args[0] = fakeLocation
                        } catch (e: Exception) {
                            HookUtils.logDebug("$TAG: setLocation 拦截失败: ${e.message}")
                        }
                    }
                }
            )

            // Hook getLocation() - 确保返回伪造位置（作为备份保险）
            XposedHelpers.findAndHookMethod(
                safeGuardMainClass,
                "getLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isFakeLocationEnabled()) return

                        try {
                            val fakeLocation = createFakeLocationForSafeguard()
                            param.result = fakeLocation
                            HookUtils.logDebug("$TAG: getLocation 已替换为伪造位置")
                        } catch (e: Exception) {
                            HookUtils.logDebug("$TAG: getLocation 替换失败: ${e.message}")
                        }
                    }
                }
            )

            // Hook getSecurityDataEx(CommandType) - 记录调用的 CommandType
            try {
                val commandTypeClass = XposedHelpers.findClass(
                    "com.alibaba.dingtalk.safeguard.CommandType",
                    lpparam.classLoader
                )
                XposedHelpers.findAndHookMethod(
                    safeGuardMainClass,
                    "getSecurityDataEx",
                    commandTypeClass,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val commandType = param.args[0]
                                val commandName = commandType?.toString() ?: "unknown"
                                HookUtils.logDebug("$TAG: getSecurityDataEx 被调用: $commandName")
                            } catch (_: Exception) {}
                        }
                    }
                )
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: getSecurityDataEx Hook 失败: ${e.message}")
            }

            HookUtils.log("$TAG: SafeGuardMain Hook 完成")
        } catch (e: ClassNotFoundException) {
            HookUtils.logDebug("$TAG: SafeGuardMain 类未找到 (可能混淆)")
            hookSafeGuardMainBySearch(lpparam)
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: SafeGuardMain Hook 失败: ${e.message}")
        }
    }

    /**
     * 当类名被混淆时，通过方法签名搜索 SafeGuardMain
     */
    private fun hookSafeGuardMainBySearch(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            HookUtils.log("$TAG: 尝试通过方法签名搜索 SafeGuardMain...")

            val classLoader = lpparam.classLoader
            val locationClass = Location::class.java

            // 遍历已加载的类，查找包含 setLocation(Location) 和 getLocation() 的静态方法
            // 这是 R8 混淆后仍保留的方法签名
            val classesToScan = listOf(
                "com.alibaba.dingtalk.safeguard.SafeGuardMain",
                "com.alibaba.dingtalk.safeguard.a",
                "com.alibaba.dingtalk.safeguard.b"
            )

            for (className in classesToScan) {
                try {
                    val clazz = XposedHelpers.findClass(className, classLoader)

                    // 检查是否有 setLocation 方法
                    val hasSetLocation = clazz.declaredMethods.any {
                        it.name == "setLocation" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == locationClass
                    }

                    if (hasSetLocation) {
                        HookUtils.log("$TAG: 找到 SafeGuardMain 类: $className")

                        XposedHelpers.findAndHookMethod(
                            clazz, "setLocation", locationClass,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (!ConfigManager.isFakeLocationEnabled()) return
                                    try {
                                        param.args[0] = createFakeLocationForSafeguard()
                                        HookUtils.logDebug("$TAG: [搜索模式] setLocation 已拦截")
                                    } catch (e: Exception) {
                                        HookUtils.logDebug("$TAG: [搜索模式] setLocation 拦截失败: ${e.message}")
                                    }
                                }
                            }
                        )

                        XposedHelpers.findAndHookMethod(
                            clazz, "getLocation",
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!ConfigManager.isFakeLocationEnabled()) return
                                    try {
                                        param.result = createFakeLocationForSafeguard()
                                    } catch (_: Exception) {}
                                }
                            }
                        )

                        HookUtils.log("$TAG: [搜索模式] SafeGuardMain Hook 完成")
                        return
                    }
                } catch (_: ClassNotFoundException) {
                    continue
                }
            }

            HookUtils.logDebug("$TAG: 未找到 SafeGuardMain 类")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: SafeGuardMain 搜索失败: ${e.message}")
        }
    }

    /**
     * Hook LocationInterface - native 层通过此接口读取位置属性
     *
     * 关键方法：
     * - getSimulatedBySoftware() → 返回 0 表示非软件模拟
     * - getProducedByAccessory() → 返回 0 表示非配件产生
     * - getLatitude/getLongitude/getAltitude 等
     *
     * 由于 LocationInterface 是抽象类，需要找到具体实现类进行 Hook
     */
    private fun hookLocationInterface(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locationInterfaceClass = XposedHelpers.findClass(
                "com.alibaba.dingtalk.safeguard.LocationInterface",
                lpparam.classLoader
            )

            // Hook 抽象类的默认方法（如果有）
            hookLocationInterfaceMethods(locationInterfaceClass)

            // 搜索 LocationInterface 的具体实现类
            hookLocationInterfaceImplementations(lpparam, locationInterfaceClass)

            HookUtils.log("$TAG: LocationInterface Hook 完成")
        } catch (e: ClassNotFoundException) {
            HookUtils.logDebug("$TAG: LocationInterface 类未找到 (可能混淆或不存在)")
            // 尝试搜索可能的混淆类名
            hookLocationInterfaceBySearch(lpparam)
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: LocationInterface Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook LocationInterface 的方法
     */
    private fun hookLocationInterfaceMethods(clazz: Class<*>) {
        // Hook getSimulatedBySoftware
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getSimulatedBySoftware",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        HookUtils.logDebug("$TAG: getSimulatedBySoftware -> 0 (非模拟)")
                        return 0
                    }
                }
            )
        } catch (_: Exception) {}

        // Hook getProducedByAccessory
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getProducedByAccessory",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return 0
                    }
                }
            )
        } catch (_: Exception) {}

        // Hook getLatitude
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getLatitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isFakeLocationEnabled()) return
                        try {
                            val fakeData = FakeDataProvider.getCurrentFakeLocation()
                            param.result = fakeData.latitude
                        } catch (_: Exception) {}
                    }
                }
            )
        } catch (_: Exception) {}

        // Hook getLongitude
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getLongitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isFakeLocationEnabled()) return
                        try {
                            val fakeData = FakeDataProvider.getCurrentFakeLocation()
                            param.result = fakeData.longitude
                        } catch (_: Exception) {}
                    }
                }
            )
        } catch (_: Exception) {}

        // Hook getAltitude
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getAltitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isFakeLocationEnabled()) return
                        try {
                            val fakeData = FakeDataProvider.getCurrentFakeLocation()
                            param.result = fakeData.altitude
                        } catch (_: Exception) {}
                    }
                }
            )
        } catch (_: Exception) {}

        // Hook getSpeed
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getSpeed",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isFakeLocationEnabled()) return
                        try {
                            val fakeData = FakeDataProvider.getCurrentFakeLocation()
                            param.result = fakeData.speed
                        } catch (_: Exception) {}
                    }
                }
            )
        } catch (_: Exception) {}
    }

    /**
     * 搜索并 Hook LocationInterface 的具体实现类
     */
    private fun hookLocationInterfaceImplementations(
        lpparam: XC_LoadPackage.LoadPackageParam,
        locationInterfaceClass: Class<*>
    ) {
        try {
            // 尝试找到 SafeGuardInterface.CppProxy 类
            val cppProxyClass = XposedHelpers.findClass(
                "com.alibaba.dingtalk.safeguard.SafeGuardInterface\$CppProxy",
                lpparam.classLoader
            )

            // Hook setLocationManager - 拦截传入的 LocationInterface 对象
            try {
                XposedHelpers.findAndHookMethod(
                    cppProxyClass,
                    "setLocationManager",
                    locationInterfaceClass,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            HookUtils.logDebug("$TAG: CppProxy.setLocationManager 被调用")
                            // 注意：这里不能替换 LocationInterface 对象，因为 native 层需要它
                            // 但我们可以 hook 传入的对象的方法
                            val locationManager = param.args[0] ?: return
                            hookLocationInterfaceInstance(locationManager.javaClass)
                        }
                    }
                )
                HookUtils.log("$TAG: CppProxy.setLocationManager Hook 完成")
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: CppProxy.setLocationManager Hook 失败: ${e.message}")
            }

            // Hook setLocationManagerExtra
            try {
                XposedHelpers.findAndHookMethod(
                    cppProxyClass,
                    "setLocationManagerExtra",
                    locationInterfaceClass,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            HookUtils.logDebug("$TAG: CppProxy.setLocationManagerExtra 被调用")
                            val locationManager = param.args[0] ?: return
                            hookLocationInterfaceInstance(locationManager.javaClass)
                        }
                    }
                )
                HookUtils.log("$TAG: CppProxy.setLocationManagerExtra Hook 完成")
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: CppProxy.setLocationManagerExtra Hook 失败: ${e.message}")
            }
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: CppProxy 搜索失败: ${e.message}")
        }
    }

    /**
     * Hook LocationInterface 的具体实例类
     */
    private fun hookLocationInterfaceInstance(clazz: Class<*>) {
        try {
            // 检查是否已经 hook 过
            val className = clazz.name
            if (className in hookedLocationClasses) return
            hookedLocationClasses.add(className)

            HookUtils.log("$TAG: 发现 LocationInterface 实现类: $className")

            // Hook getSimulatedBySoftware
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "getSimulatedBySoftware",
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any {
                            HookUtils.logDebug("$TAG: $className.getSimulatedBySoftware -> 0")
                            return 0
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook getProducedByAccessory
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "getProducedByAccessory",
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any {
                            return 0
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook getLatitude
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "getLatitude",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!ConfigManager.isFakeLocationEnabled()) return
                            try {
                                val fakeData = FakeDataProvider.getCurrentFakeLocation()
                                param.result = fakeData.latitude
                            } catch (_: Exception) {}
                        }
                    }
                )
            } catch (_: Exception) {}

            // Hook getLongitude
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "getLongitude",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!ConfigManager.isFakeLocationEnabled()) return
                            try {
                                val fakeData = FakeDataProvider.getCurrentFakeLocation()
                                param.result = fakeData.longitude
                            } catch (_: Exception) {}
                        }
                    }
                )
            } catch (_: Exception) {}

            HookUtils.log("$TAG: LocationInterface 实现类 Hook 完成: $className")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: LocationInterface 实现类 Hook 失败: ${e.message}")
        }
    }

    /**
     * 当 LocationInterface 类名被混淆时，通过方法签名搜索
     */
    private fun hookLocationInterfaceBySearch(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            HookUtils.log("$TAG: 尝试通过方法签名搜索 LocationInterface...")

            // 搜索包含 getSimulatedBySoftware 方法的类
            val classesToScan = listOf(
                "com.alibaba.dingtalk.safeguard.LocationInterface",
                "com.alibaba.dingtalk.safeguard.a",
                "com.alibaba.dingtalk.safeguard.b",
                "com.alibaba.dingtalk.safeguard.c"
            )

            for (className in classesToScan) {
                try {
                    val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                    // 检查是否有 getSimulatedBySoftware 方法
                    val hasMethod = clazz.declaredMethods.any {
                        it.name == "getSimulatedBySoftware" && it.parameterTypes.isEmpty()
                    }

                    if (hasMethod) {
                        HookUtils.log("$TAG: 找到 LocationInterface 类: $className")
                        hookLocationInterfaceMethods(clazz)
                        return
                    }
                } catch (_: ClassNotFoundException) {
                    continue
                }
            }

            HookUtils.logDebug("$TAG: 未找到 LocationInterface 类")
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: LocationInterface 搜索失败: ${e.message}")
        }
    }

    /**
     * Hook ISecurityBodyComponent - 阿里安全 SDK 的安全体组件
     *
     * enterRiskScene(int, HashMap) - 进入风险场景，HashMap 包含 "locations" -> List<Location>
     * getSecurityBodyDataEx(...) - 获取安全体数据（lbsWua 的核心）
     * leaveRiskScene(int) - 离开风险场景
     */
    private fun hookSecurityBodyComponent(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 尝试两个版本的 ISecurityBodyComponent
        val classNames = listOf(
            "com.alibaba.wireless.security.open.securitybody.ISecurityBodyComponent",
            "com.taobao.wireless.security.sdk.securitybody.ISecurityBodyComponent"
        )

        for (className in classNames) {
            try {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                // Hook enterRiskScene - 拦截传入的位置列表
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz,
                        "enterRiskScene",
                        Int::class.javaPrimitiveType,
                        java.util.HashMap::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!ConfigManager.isFakeLocationEnabled()) return

                                try {
                                    val riskScene = param.args[0] as? Int ?: return
                                    val map = param.args[1] as? HashMap<*, *> ?: return

                                    // 替换 locations 列表中的位置
                                    if (map.containsKey("locations")) {
                                        val fakeLocation = createFakeLocationForSafeguard()
                                        val fakeList = ArrayList<Location>()
                                        fakeList.add(fakeLocation)
                                        @Suppress("UNCHECKED_CAST")
                                        (map as HashMap<String, Any>)["locations"] = fakeList

                                        HookUtils.logDebug("$TAG: enterRiskScene($riskScene) 位置已替换")
                                    }
                                } catch (e: Exception) {
                                    HookUtils.logDebug("$TAG: enterRiskScene 拦截失败: ${e.message}")
                                }
                            }
                        }
                    )
                    HookUtils.log("$TAG: ISecurityBodyComponent.enterRiskScene Hook 完成 ($className)")
                } catch (e: Exception) {
                    HookUtils.logDebug("$TAG: enterRiskScene Hook 失败: ${e.message}")
                }

                // Hook getSecurityBodyDataEx - 可选：篡改返回值
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz,
                        "getSecurityBodyDataEx",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        java.util.HashMap::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val result = param.result as? String ?: return
                                HookUtils.logDebug("$TAG: getSecurityBodyDataEx 返回: " +
                                    "${result.take(50)}${if (result.length > 50) "..." else ""}")
                            }
                        }
                    )
                    HookUtils.log("$TAG: getSecurityBodyDataEx Hook 完成 ($className)")
                } catch (e: Exception) {
                    HookUtils.logDebug("$TAG: getSecurityBodyDataEx Hook 失败: ${e.message}")
                }

                break // 成功找到一个就够了
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
    }

    /**
     * Hook ISecureSignatureComponent - 签名生成组件
     *
     * signRequest(appKey, requestType, params) 生成 ddSig
     * appKey = "2049587" (钉钉), requestType = 19 (打卡)
     */
    private fun hookSecureSignatureComponent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classNames = listOf(
            "com.alibaba.wireless.security.open.securesignature.ISecureSignatureComponent",
            "com.taobao.wireless.security.sdk.securesignature.ISecureSignatureComponent"
        )

        for (className in classNames) {
            try {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                // Hook signRequest - 记录签名请求
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz,
                        "signRequest",
                        String::class.java,  // appKey
                        Int::class.javaPrimitiveType,  // requestType
                        java.util.HashMap::class.java,  // params
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val appKey = param.args[0] as? String ?: ""
                                    val requestType = param.args[1] as? Int ?: 0
                                    HookUtils.logDebug("$TAG: signRequest(appKey=$appKey, requestType=$requestType)")
                                } catch (_: Exception) {}
                            }
                        }
                    )
                    HookUtils.log("$TAG: ISecureSignatureComponent.signRequest Hook 完成 ($className)")
                } catch (e: Exception) {
                    HookUtils.logDebug("$TAG: signRequest Hook 失败: ${e.message}")
                }

                break
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
    }

    /**
     * Hook AntiCheatingHelper (nr0 类)
     *
     * nr0.h() 方法直接调用 locationD.isFromMockProvider() 并放入 JSON:
     * jSONObject.put("isMock", locationD.isFromMockProvider())
     *
     * nr0.j(location) 调用 SafeGuardMain.setLocation + getSecurityDataEx
     * nr0.l(context, location) 调用 ISecurityBodyComponent.enterRiskScene
     */
    private fun hookAntiCheatingHelper(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 尝试找到 nr0 类（AntiCheatingHelper）
        val classNames = listOf(
            "defpackage.nr0",
            "nr0"
        )

        for (className in classNames) {
            try {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                // 方法 h(Context) 返回 JSONObject，其中包含 "isMock" 字段
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz,
                        "h",
                        android.content.Context::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val jsonObject = param.result as? org.json.JSONObject ?: return

                                    // 修复所有可能的检测字段
                                    val fieldsToFix = listOf(
                                        "isMock", "mock", "is_mock",
                                        "isSimulated", "simulated",
                                        "isFromMockProvider"
                                    )
                                    var modified = false
                                    for (field in fieldsToFix) {
                                        if (jsonObject.has(field) && jsonObject.getBoolean(field)) {
                                            jsonObject.put(field, false)
                                            modified = true
                                            HookUtils.logDebug("$TAG: nr0.h() $field: true -> false")
                                        }
                                    }

                                    // 修复 riskScore/riskLevel
                                    if (jsonObject.has("riskScore")) {
                                        val score = jsonObject.optInt("riskScore", 0)
                                        if (score > 5) {
                                            jsonObject.put("riskScore", 3)
                                            modified = true
                                        }
                                    }
                                    if (jsonObject.has("riskLevel")) {
                                        val level = jsonObject.optString("riskLevel", "")
                                        if (level == "high" || level == "danger") {
                                            jsonObject.put("riskLevel", "low")
                                            modified = true
                                        }
                                    }

                                    if (modified) {
                                        param.result = jsonObject
                                    }
                                } catch (e: Exception) {
                                    HookUtils.logDebug("$TAG: nr0.h() 拦截失败: ${e.message}")
                                }
                            }
                        }
                    )
                    HookUtils.log("$TAG: nr0.h() (isMock 检测) Hook 完成")
                } catch (e: Exception) {
                    HookUtils.logDebug("$TAG: nr0.h() Hook 失败: ${e.message}")
                }

                // 方法 j(Location) - 调用 SafeGuardMain.setLocation + getSecurityDataEx
                // 替换位置参数，确保 native 层读取到伪造位置
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz,
                        "j",
                        Location::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!ConfigManager.isFakeLocationEnabled()) return
                                try {
                                    val originalLocation = param.args[0] as? Location ?: return
                                    val fakeLocation = createFakeLocationForSafeguard()
                                    param.args[0] = fakeLocation
                                    HookUtils.logDebug("$TAG: nr0.j() 位置已替换: " +
                                        "(${originalLocation.latitude}, ${originalLocation.longitude}) -> " +
                                        "(${fakeLocation.latitude}, ${fakeLocation.longitude})")
                                } catch (e: Exception) {
                                    HookUtils.logDebug("$TAG: nr0.j() 拦截失败: ${e.message}")
                                }
                            }
                        }
                    )
                    HookUtils.log("$TAG: nr0.j() Hook 完成")
                } catch (e: Exception) {
                    HookUtils.logDebug("$TAG: nr0.j() Hook 失败: ${e.message}")
                }

                // 方法 l(Context, Location) - 调用 ISecurityBodyComponent.enterRiskScene
                // 替换位置参数
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz,
                        "l",
                        android.content.Context::class.java,
                        Location::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!ConfigManager.isFakeLocationEnabled()) return
                                try {
                                    val location = param.args[1] as? Location ?: return
                                    val fakeLocation = createFakeLocationForSafeguard()
                                    param.args[1] = fakeLocation
                                    HookUtils.logDebug("$TAG: nr0.l() 位置已替换")
                                } catch (e: Exception) {
                                    HookUtils.logDebug("$TAG: nr0.l() 拦截失败: ${e.message}")
                                }
                            }
                        }
                    )
                    HookUtils.log("$TAG: nr0.l() Hook 完成")
                } catch (e: Exception) {
                    HookUtils.logDebug("$TAG: nr0.l() Hook 失败: ${e.message}")
                }

                break
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
    }

    /**
     * Hook SafeGuardDispatcher - 数据上报调度器
     *
     * 阻止或修改安全数据上报到服务器
     */
    private fun hookSafeGuardDispatcher(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classNames = listOf(
            "com.alibaba.dingtalk.safeguard.SafeGuardDispatcher",
            "com.alibaba.dingtalk.safeguard.dispatcher.SafeGuardDispatcher"
        )

        for (className in classNames) {
            try {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                // Hook sendBasicInfo - 基础数据上报
                try {
                    XposedHelpers.findAndHookMethod(
                        clazz,
                        "sendBasicInfo",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                HookUtils.logDebug("$TAG: SafeGuardDispatcher.sendBasicInfo 被调用")
                            }
                        }
                    )
                    HookUtils.log("$TAG: SafeGuardDispatcher.sendBasicInfo Hook 完成 ($className)")
                } catch (e: Exception) {
                    HookUtils.logDebug("$TAG: sendBasicInfo Hook 失败: ${e.message}")
                }

                break
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
    }

    /**
     * 创建用于 SafeGuard SDK 的伪造位置
     *
     * 与 LocationHooks 的区别：
     * - 不设置 extras（避免 native 层解析 extras 时发现异常）
     * - 确保所有 hasXxx 方法返回正确的值
     * - 时间戳使用当前时间
     * - 确保 isMock = false
     */
    private fun createFakeLocationForSafeguard(): Location {
        val fakeData = FakeDataProvider.getCurrentFakeLocation()

        return Location(LocationManager.GPS_PROVIDER).apply {
            latitude = fakeData.latitude
            longitude = fakeData.longitude
            altitude = fakeData.altitude
            speed = fakeData.speed
            bearing = fakeData.bearing
            accuracy = fakeData.accuracy
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()

            // 确保 mIsMock = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                isMock = false
            } else {
                try {
                    val field = Location::class.java.getDeclaredField("mIsMock")
                    field.isAccessible = true
                    field.setBoolean(this, false)
                } catch (_: Exception) {}
            }

            // 确保 hasXxx 返回 true
            try {
                val hasAltitudeField = Location::class.java.getDeclaredField("mHasAltitude")
                hasAltitudeField.isAccessible = true
                hasAltitudeField.setBoolean(this, true)
            } catch (_: Exception) {}

            try {
                val hasSpeedField = Location::class.java.getDeclaredField("mHasSpeed")
                hasSpeedField.isAccessible = true
                hasSpeedField.setBoolean(this, true)
            } catch (_: Exception) {}

            try {
                val hasBearingField = Location::class.java.getDeclaredField("mHasBearing")
                hasBearingField.isAccessible = true
                hasBearingField.setBoolean(this, true)
            } catch (_: Exception) {}

            try {
                val hasAccuracyField = Location::class.java.getDeclaredField("mHasAccuracy")
                hasAccuracyField.isAccessible = true
                hasAccuracyField.setBoolean(this, true)
            } catch (_: Exception) {}

            // 设置 extras 中的卫星数（避免 0 颗卫星的异常）
            val extras = android.os.Bundle()
            extras.putInt("satellites", FakeDataProvider.getSatellites().count { it.usedInFix })
            setExtras(extras)
        }
    }
}
