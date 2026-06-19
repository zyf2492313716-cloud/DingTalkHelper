package com.dingtalk.helper.xposed.hooks

import android.os.Build
import android.telephony.CellInfo
import android.telephony.NeighboringCellInfo
import android.telephony.gsm.GsmCellLocation
import com.dingtalk.helper.utils.ConfigManager
import com.dingtalk.helper.xposed.HookEntry
import com.dingtalk.helper.xposed.data.CellTowerGenerator
import com.dingtalk.helper.xposed.utils.Constants
import com.dingtalk.helper.xposed.utils.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 基站信息伪造 Hook
 *
 * 拦截并替换 TelephonyManager 中所有基站相关 API：
 * - getAllCellInfo()       -> 返回 1 serving + 3~5 neighbors 的完整 CellInfo 列表
 * - getCellLocation()      -> 返回包含 serving cell 信息的 GsmCellLocation
 * - getNeighboringCellInfo() -> 返回邻区 NeighboringCellInfo 列表
 * - getDataState()         -> 返回 CONNECTED
 *
 * 服务小区和邻区由 CellTowerGenerator 根据 GPS 坐标确定性生成，
 * 信号强度与"模拟距离"正相关。
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
        hookTelephonyManager(lpparam)
    }

    // ==================== TelephonyManager Hook ====================

    private fun hookTelephonyManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val tmClass = XposedHelpers.findClass(
                "android.telephony.TelephonyManager",
                lpparam.classLoader
            )

            hookGetAllCellInfo(tmClass)
            hookGetCellLocation(tmClass)
            hookGetNeighboringCellInfo(tmClass)
            hookGetDataState(tmClass)

            HookUtils.log("$TAG: TelephonyManager Hook 完成")
        } catch (e: Exception) {
            HookUtils.log("$TAG: TelephonyManager Hook 失败: ${e.message}")
        }
    }

    // ==================== getAllCellInfo ====================

    private fun hookGetAllCellInfo(tmClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            tmClass,
            "getAllCellInfo",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!shouldHook()) return
                    param.result = buildCellInfoList()
                    HookUtils.logDebug("$TAG: getAllCellInfo 已替换")
                }
            }
        )
    }

    private fun buildCellInfoList(): List<CellInfo> {
        val correlated = ConfigManager.getCorrelatedCellInfo()
        val lat = ConfigManager.getLatitude()
        val lng = ConfigManager.getLongitude()
        val bundle = CellTowerGenerator.generate(lat, lng, correlated.mcc, correlated.mnc)

        val result = mutableListOf<CellInfo>()
        val timestamp = System.currentTimeMillis() * 1000

        // Serving cell
        createCellInfo(bundle.serving, timestamp)?.let { result.add(it) }

        // Neighbor cells
        for (neighbor in bundle.neighbors) {
            createCellInfo(neighbor, timestamp)?.let { result.add(it) }
        }

        return result
    }

    // ==================== getCellLocation ====================

    private fun hookGetCellLocation(tmClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            tmClass,
            "getCellLocation",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!shouldHook()) return
                    param.result = buildGsmCellLocation()
                    HookUtils.logDebug("$TAG: getCellLocation 已替换")
                }
            }
        )
    }

    private fun buildGsmCellLocation(): GsmCellLocation {
        val correlated = ConfigManager.getCorrelatedCellInfo()
        return GsmCellLocation().apply {
            setLacAndCid(correlated.lac, correlated.cellId)
        }
    }

    // ==================== getNeighboringCellInfo ====================

    private fun hookGetNeighboringCellInfo(tmClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                tmClass,
                "getNeighboringCellInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldHook()) return
                        param.result = buildNeighboringCellInfoList()
                        HookUtils.logDebug("$TAG: getNeighboringCellInfo 已替换")
                    }
                }
            )
        } catch (e: NoSuchMethodError) {
            HookUtils.logDebug("$TAG: getNeighboringCellInfo 不存在（API 29+），跳过")
        }
    }

    private fun buildNeighboringCellInfoList(): List<NeighboringCellInfo> {
        val correlated = ConfigManager.getCorrelatedCellInfo()
        val neighbors = correlated.neighborCells
        if (neighbors.isEmpty()) return emptyList()

        val result = mutableListOf<NeighboringCellInfo>()
        for (n in neighbors) {
            try {
                // NeighboringCellInfo(int lac, int cid, String radioType)
                val radioType = when (n.cellType) {
                    CellTowerGenerator.CellType.GSM -> "GSM"
                    CellTowerGenerator.CellType.LTE -> "LTE"
                    CellTowerGenerator.CellType.NR -> "LTE"
                }
                val obj = XposedHelpers.newInstance(
                    Class.forName("android.telephony.NeighboringCellInfo"),
                    n.lac, n.cellId, radioType
                )
                // Set RSSI if available (API 17+)
                if (Build.VERSION.SDK_INT >= 17) {
                    XposedHelpers.callMethod(obj, "setRssi", n.rssi)
                }
                result.add(obj as NeighboringCellInfo)
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: 创建 NeighboringCellInfo 失败: ${e.message}")
            }
        }
        return result
    }

    // ==================== getDataState ====================

    private fun hookGetDataState(tmClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            tmClass,
            "getDataState",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!shouldHook()) return
                    // TelephonyManager.DATA_CONNECTED = 2
                    param.result = 2
                    HookUtils.logDebug("$TAG: getDataState -> CONNECTED")
                }
            }
        )
    }

    // ==================== CellInfo 构建 ====================

    private fun createCellInfo(
        cell: CellTowerGenerator.FakeCell,
        timestamp: Long
    ): CellInfo? {
        return when (cell.cellType) {
            CellTowerGenerator.CellType.GSM -> createGsmCellInfo(cell, timestamp)
            CellTowerGenerator.CellType.LTE -> createLteCellInfo(cell, timestamp)
            CellTowerGenerator.CellType.NR -> createNrCellInfo(cell, timestamp)
        }
    }

    private fun createGsmCellInfo(
        cell: CellTowerGenerator.FakeCell,
        timestamp: Long
    ): CellInfo? {
        return try {
            val identityClass = Class.forName("android.telephony.CellIdentityGsm")
            val identity = createGsmIdentity(cell, identityClass)
            val infoClass = Class.forName("android.telephony.CellInfoGsm")
            val info = XposedHelpers.newInstance(infoClass)
            XposedHelpers.callMethod(info, "setCellIdentity", identity)
            XposedHelpers.callMethod(info, "setRegistered", cell.registered)
            XposedHelpers.callMethod(info, "setTimestamp", timestamp)
            setCellInfoRssi(info as CellInfo, cell.rssi)
            info as CellInfo
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 创建 GSM CellInfo 失败: ${e.message}")
            null
        }
    }

    private fun createGsmIdentity(
        cell: CellTowerGenerator.FakeCell,
        clazz: Class<*>
    ): Any {
        // CellIdentityGsm(int lac, int cid, int mcc, int mnc) on API 28
        // CellIdentityGsm(int lac, int cid, String mccStr, String mncStr) on API 29+
        return try {
            XposedHelpers.newInstance(
                clazz,
                cell.lac,
                cell.cellId,
                cell.mcc.toString(),
                cell.mnc.toString()
            )
        } catch (e: Throwable) {
            // Fallback: int parameters (API 28)
            XposedHelpers.newInstance(clazz, cell.lac, cell.cellId, cell.mcc, cell.mnc)
        }
    }

    private fun createLteCellInfo(
        cell: CellTowerGenerator.FakeCell,
        timestamp: Long
    ): CellInfo? {
        return try {
            val identityClass = Class.forName("android.telephony.CellIdentityLte")
            val identity = createLteIdentity(cell, identityClass)
            val infoClass = Class.forName("android.telephony.CellInfoLte")
            val info = XposedHelpers.newInstance(infoClass)
            XposedHelpers.callMethod(info, "setCellIdentity", identity)
            XposedHelpers.callMethod(info, "setRegistered", cell.registered)
            XposedHelpers.callMethod(info, "setTimestamp", timestamp)
            setCellInfoRssi(info as CellInfo, cell.rssi)
            info as CellInfo
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 创建 LTE CellInfo 失败: ${e.message}")
            null
        }
    }

    private fun createLteIdentity(
        cell: CellTowerGenerator.FakeCell,
        clazz: Class<*>
    ): Any {
        // CellIdentityLte(int ci, int pci, int tac, String mccStr, String mncStr) on API 29+
        // CellIdentityLte(int ci, int earfcn, int pci, int tac, int[] bandwidths, String mcc, String mnc) on API 30+
        return try {
            XposedHelpers.newInstance(
                clazz,
                cell.cellId,
                cell.pci,
                cell.tac,
                cell.mcc.toString(),
                cell.mnc.toString()
            )
        } catch (e: Throwable) {
            try {
                // Fallback: try with earfcn and bandwidths
                XposedHelpers.newInstance(
                    clazz,
                    cell.cellId,
                    cell.earfcn,
                    cell.pci,
                    cell.tac,
                    intArrayOf(),
                    cell.mcc.toString(),
                    cell.mnc.toString()
                )
            } catch (e2: Throwable) {
                // Last resort: try int parameters (API 28)
                XposedHelpers.newInstance(clazz, cell.cellId, cell.earfcn, cell.pci, cell.tac, cell.mcc, cell.mnc)
            }
        }
    }

    private fun createNrCellInfo(
        cell: CellTowerGenerator.FakeCell,
        timestamp: Long
    ): CellInfo? {
        // CellInfoNr only exists on API 29+
        if (Build.VERSION.SDK_INT < 29) {
            // Fallback: return LTE cell for pre-29 devices
            return createLteCellInfo(
                cell.copy(cellType = CellTowerGenerator.CellType.LTE),
                timestamp
            )
        }
        return try {
            val identityClass = Class.forName("android.telephony.CellIdentityNr")
            val identity = createNrIdentity(cell, identityClass)
            val infoClass = Class.forName("android.telephony.CellInfoNr")
            val info = XposedHelpers.newInstance(infoClass)
            XposedHelpers.callMethod(info, "setCellIdentity", identity)
            XposedHelpers.callMethod(info, "setRegistered", cell.registered)
            XposedHelpers.callMethod(info, "setTimestamp", timestamp)
            setCellInfoRssi(info as CellInfo, cell.rssi)
            info as CellInfo
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 创建 NR CellInfo 失败: ${e.message}")
            createLteCellInfo(
                cell.copy(cellType = CellTowerGenerator.CellType.LTE),
                timestamp
            )
        }
    }

    private fun createNrIdentity(
        cell: CellTowerGenerator.FakeCell,
        clazz: Class<*>
    ): Any {
        // CellIdentityNr(int nci, int pci, int tac, String mccStr, String mncStr) on API 30+
        return try {
            XposedHelpers.newInstance(
                clazz,
                cell.cellId,
                cell.pci,
                cell.tac,
                cell.mcc.toString(),
                cell.mnc.toString()
            )
        } catch (e: Throwable) {
            // Fallback: try different parameter sets
            try {
                XposedHelpers.newInstance(
                    clazz,
                    cell.cellId,
                    cell.pci,
                    cell.tac,
                    cell.mcc,
                    cell.mnc
                )
            } catch (e2: Throwable) {
                XposedHelpers.newInstance(clazz)
            }
        }
    }

    // ==================== RSSI ====================

    /**
     * CellInfoGsm/CellInfoLte/CellInfoNr 都有 setCellSignalStrength 方法，
     * 但其参数类型因版本而异，使用反射统一设置。
     */
    private fun setCellInfoRssi(info: CellInfo, rssi: Int) {
        try {
            when (info) {
                is android.telephony.CellInfoGsm -> {
                    val css = createGsmSignalStrength(rssi)
                    if (css != null) {
                        XposedHelpers.callMethod(info, "setCellSignalStrength", css)
                    }
                }
                is android.telephony.CellInfoLte -> {
                    val css = createLteSignalStrength(rssi)
                    if (css != null) {
                        XposedHelpers.callMethod(info, "setCellSignalStrength", css)
                    }
                }
                else -> {
                    // For CellInfoNr and others, try generic approach
                    val css = createLteSignalStrength(rssi)
                    if (css != null) {
                        try {
                            XposedHelpers.callMethod(info, "setCellSignalStrength", css)
                        } catch (_: Throwable) { }
                    }
                }
            }
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 设置 RSSI 失败: ${e.message}")
        }
    }

    private fun createGsmSignalStrength(rssi: Int): Any? {
        return try {
            val clazz = Class.forName("android.telephony.CellSignalStrengthGsm")
            XposedHelpers.newInstance(clazz).also { css ->
                // Set dbm field via reflection
                setSignalField(css, rssi)
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun createLteSignalStrength(rssi: Int): Any? {
        return try {
            val clazz = Class.forName("android.telephony.CellSignalStrengthLte")
            XposedHelpers.newInstance(clazz).also { css ->
                setSignalField(css, rssi)
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 通过反射设置 CellSignalStrength 的 dBm 值。
     * 不同 Android 版本字段名可能不同，逐个尝试。
     */
    private fun setSignalField(css: Any, dbm: Int) {
        val dbmFieldNames = listOf("mDbm", "mRsrp", "dbm")
        for (name in dbmFieldNames) {
            try {
                val field = css.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.setInt(css, dbm)
                return
            } catch (_: NoSuchFieldException) { }
        }
    }

    // ==================== 工具方法 ====================

    private fun shouldHook(): Boolean {
        return ConfigManager.isEnabled() && ConfigManager.isFakeCellEnabled()
    }
}
