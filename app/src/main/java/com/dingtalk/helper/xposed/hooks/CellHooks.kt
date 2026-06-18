package com.dingtalk.helper.xposed.hooks

import android.os.Build
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.CellLocation
import android.telephony.NeighboringCellInfo
import android.telephony.TelephonyManager
import android.telephony.gsm.GsmCellLocation
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 基站信息伪造 Hook
 * 负责拦截和替换基站信息
 */
class CellHooks : HookEntry.HookHandler {

    companion object {
        private const val TAG = "${HookEntry.TAG}:Cell"
    }

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ConfigManager.isFakeCellEnabled()) {
            XposedBridge.log("$TAG: 基站伪造未启用，跳过")
            return
        }

        XposedBridge.log("$TAG: 开始注入基站伪造 Hook")

        // Hook TelephonyManager
        hookTelephonyManager(lpparam)

        // Hook PhoneInterfaceManager
        hookPhoneInterfaceManager(lpparam)

        // Hook TelephonyRegistry
        hookTelephonyRegistry(lpparam)
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
                        if (shouldHook(param)) {
                            val fakeCellLocation = createFakeGsmCellLocation()
                            param.result = fakeCellLocation
                            XposedBridge.log("$TAG: getCellLocation 已替换")
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
                        if (shouldHook(param)) {
                            val fakeCellInfoList = createFakeCellInfoList()
                            param.result = fakeCellInfoList
                            XposedBridge.log("$TAG: getAllCellInfo 已替换")
                        }
                    }
                }
            )

            // Hook getNeighboringCellInfo
            XposedHelpers.findAndHookMethod(
                telephonyManagerClass,
                "getNeighboringCellInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (shouldHook(param)) {
                            val fakeNeighboringList = createFakeNeighboringCellInfo()
                            param.result = fakeNeighboringList
                            XposedBridge.log("$TAG: getNeighboringCellInfo 已替换")
                        }
                    }
                }
            )

            // Hook getNetworkType
            XposedHelpers.findAndHookMethod(
                telephonyManagerClass,
                "getNetworkType",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return TelephonyManager.NETWORK_TYPE_LTE
                    }
                }
            )

            XposedBridge.log("$TAG: TelephonyManager Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: TelephonyManager Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook PhoneInterfaceManager
     */
    private fun hookPhoneInterfaceManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val phoneInterfaceClass = XposedHelpers.findClass(
                "com.android.phone.PhoneInterfaceManager",
                lpparam.classLoader
            )

            // Hook getCellLocation
            XposedHelpers.findAndHookMethod(
                phoneInterfaceClass,
                "getCellLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (shouldHook(param)) {
                            val fakeCellLocation = createFakeGsmCellLocation()
                            param.result = fakeCellLocation
                            XposedBridge.log("$TAG: PhoneInterfaceManager.getCellLocation 已替换")
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: PhoneInterfaceManager Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: PhoneInterfaceManager Hook 失败: ${e.message}")
        }
    }

    /**
     * Hook TelephonyRegistry
     */
    private fun hookTelephonyRegistry(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val telephonyRegistryClass = XposedHelpers.findClass(
                "com.android.server.TelephonyRegistry",
                lpparam.classLoader
            )

            // Hook notifyCellLocation
            XposedHelpers.findAndHookMethod(
                telephonyRegistryClass,
                "notifyCellLocation",
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (shouldHook(param)) {
                            val bundle = param.args[0] as android.os.Bundle
                            val fakeCellLocation = createFakeGsmCellLocation()
                            bundle.putInt("phone", 0)
                            bundle.putInt(" cid", fakeCellLocation.cid)
                            bundle.putInt("lac", fakeCellLocation.lac)
                            bundle.putInt("mcc", ConfigManager.getMcc())
                            bundle.putInt("mnc", ConfigManager.getMnc())
                            XposedBridge.log("$TAG: notifyCellLocation 已替换")
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: TelephonyRegistry Hook 完成")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: TelephonyRegistry Hook 失败: ${e.message}")
        }
    }

    /**
     * 创建伪造的 GsmCellLocation
     */
    private fun createFakeGsmCellLocation(): GsmCellLocation {
        val cellId = ConfigManager.getCellId()
        val lac = ConfigManager.getLac()
        val mcc = ConfigManager.getMcc()
        val mnc = ConfigManager.getMnc()

        return GsmCellLocation().apply {
            setLacAndCid(lac, cellId)
            // 设置 PSC (Primary Scrambling Code)
            try {
                val pscField = GsmCellLocation::class.java.getDeclaredField("mPsc")
                pscField.isAccessible = true
                pscField.setInt(this, 0)
            } catch (e: Exception) {
                // 忽略
            }
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

        // 创建 GSM 基站信息
        try {
            val cellIdentityGsm = CellIdentityGsm(cellId, lac, mcc, mnc)

            val cellInfoGsm = CellInfoGsm().apply {
                val identityField = CellInfoGsm::class.java.getDeclaredField("mCellIdentityGsm")
                identityField.isAccessible = true
                identityField.set(this, cellIdentityGsm)

                val registeredField = CellInfo::class.java.getDeclaredField("mRegistered")
                registeredField.isAccessible = true
                registeredField.setBoolean(this, true)

                val timestampField = CellInfo::class.java.getDeclaredField("mTimestamp")
                timestampField.isAccessible = true
                timestampField.setLong(this, System.currentTimeMillis() * 1000)
            }
            cellInfoList.add(cellInfoGsm)
        } catch (e: Exception) {
            XposedBridge.log("$TAG: 创建 GSM CellInfo 失败: ${e.message}")
        }

        // 创建 LTE 基站信息
        try {
            val cellIdentityLte = CellIdentityLte(cellId, 0, 0, mcc, mnc)

            val cellInfoLte = CellInfoLte().apply {
                val identityField = CellInfoLte::class.java.getDeclaredField("mCellIdentityLte")
                identityField.isAccessible = true
                identityField.set(this, cellIdentityLte)

                val registeredField = CellInfo::class.java.getDeclaredField("mRegistered")
                registeredField.isAccessible = true
                registeredField.setBoolean(this, true)

                val timestampField = CellInfo::class.java.getDeclaredField("mTimestamp")
                timestampField.isAccessible = true
                timestampField.setLong(this, System.currentTimeMillis() * 1000)
            }
            cellInfoList.add(cellInfoLte)
        } catch (e: Exception) {
            XposedBridge.log("$TAG: 创建 LTE CellInfo 失败: ${e.message}")
        }

        return cellInfoList
    }

    /**
     * 创建伪造的邻区信息
     */
    private fun createFakeNeighboringCellInfo(): List<NeighboringCellInfo> {
        val neighboringList = mutableListOf<NeighboringCellInfo>()

        try {
            val cellId = ConfigManager.getCellId()
            val lac = ConfigManager.getLac()

            // 创建邻区信息
            val neighboringCellInfo = NeighboringCellInfo(lac, cellId, "GSM")
            neighboringList.add(neighboringCellInfo)
        } catch (e: Exception) {
            XposedBridge.log("$TAG: 创建邻区信息失败: ${e.message}")
        }

        return neighboringList
    }

    /**
     * 判断是否需要 Hook
     */
    private fun shouldHook(param: XC_MethodHook.MethodHookParam): Boolean {
        if (!ConfigManager.isEnabled()) return false
        if (!ConfigManager.isFakeCellEnabled()) return false
        return true
    }
}