package com.dingtalk.helper.xposed.hooks

import android.os.Build
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellLocation
import android.telephony.NeighboringCellInfo
import android.telephony.TelephonyManager
import android.telephony.gsm.GsmCellLocation
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 基站信息伪造 Hook
 * 负责拦截和替换基站信息
 */
class CellHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${Constants.LOG_PREFIX}:Cell"
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isFakeCellEnabled()) {
            HookUtils.logDebug("$TAG: 基站伪造未启用，跳过")
            return
        }

        HookUtils.log("$TAG: 开始注入基站伪造 Hook")

        // Hook TelephonyManager
        hookTelephonyManager(lpparam)
    }

    /**
     * Hook TelephonyManager 获取基站信息的方法
     */
    private fun hookTelephonyManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val telephonyManagerClass = XposedHelpers.findClass(
                "android.telephony.TelephonyManager",
                lpparam.classLoader
            )

            // Hook getCellLocation
            XposedHelpers.findAndHookMethod(
                telephonyManagerClass,
                "getCellLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (shouldHook()) {
                            param.result = createFakeGsmCellLocation()
                            HookUtils.logDebug("$TAG: getCellLocation 已替换")
                        }
                    }
                }
            )

            // Hook getAllCellInfo
            XposedHelpers.findAndHookMethod(
                telephonyManagerClass,
                "getAllCellInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (shouldHook()) {
                            param.result = createFakeCellInfoList()
                            HookUtils.logDebug("$TAG: getAllCellInfo 已替换")
                        }
                    }
                }
            )

            // Hook getNeighboringCellInfo (deprecated but still used)
            try {
                XposedHelpers.findAndHookMethod(
                    telephonyManagerClass,
                    "getNeighboringCellInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (shouldHook()) {
                                param.result = createFakeNeighboringCellInfo()
                                HookUtils.logDebug("$TAG: getNeighboringCellInfo 已替换")
                            }
                        }
                    }
                )
            } catch (e: NoSuchMethodError) {
                // API 29+ 已移除
            }

            HookUtils.log("$TAG: TelephonyManager Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: TelephonyManager Hook 失败: ${e.message}")
        }
    }

    /**
     * 创建伪造的 GsmCellLocation
     */
    private fun createFakeGsmCellLocation(): GsmCellLocation {
        val cellId = ConfigManager.getCellId()
        val lac = ConfigManager.getLac()

        return GsmCellLocation().apply {
            setLacAndCid(lac, cellId)
        }
    }

    /**
     * 创建伪造的 CellInfo 列表
     */
    private fun createFakeCellInfoList(): List<CellInfo> {
        val cellInfoList = mutableListOf<CellInfo>()
        val cellId = ConfigManager.getCellId()
        val lac = ConfigManager.getLac()
        val mcc = ConfigManager.getMcc()
        val mnc = ConfigManager.getMnc()
        val timestamp = System.currentTimeMillis() * 1000

        // 创建 GSM 基站信息
        try {
            val cellIdentityGsm = CellIdentityGsm(cellId, lac, mcc, mnc)
            val cellInfoGsm = CellInfoGsm().apply {
                setCellIdentity(cellIdentityGsm)
                setRegistered(true)
                setTimestamp(timestamp)
            }
            cellInfoList.add(cellInfoGsm)
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 创建 GSM CellInfo 失败: ${e.message}")
        }

        // 创建 LTE 基站信息
        try {
            val cellIdentityLte = CellIdentityLte(cellId, 0, 0, mcc, mnc)
            val cellInfoLte = CellInfoLte().apply {
                setCellIdentity(cellIdentityLte)
                setRegistered(true)
                setTimestamp(timestamp)
            }
            cellInfoList.add(cellInfoLte)
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 创建 LTE CellInfo 失败: ${e.message}")
        }

        return cellInfoList
    }

    /**
     * 创建伪造的邻区信息
     */
    private fun createFakeNeighboringCellInfo(): List<NeighboringCellInfo> {
        return try {
            val cellId = ConfigManager.getCellId()
            val lac = ConfigManager.getLac()
            listOf(NeighboringCellInfo(lac, cellId, "GSM"))
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 创建邻区信息失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 判断是否需要 Hook
     */
    private fun shouldHook(): Boolean {
        return ConfigManager.isEnabled() && ConfigManager.isFakeCellEnabled()
    }
}
