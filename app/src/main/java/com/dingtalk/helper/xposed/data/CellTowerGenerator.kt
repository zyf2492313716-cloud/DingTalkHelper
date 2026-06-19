package com.dingtalk.helper.xposed.data

import java.security.MessageDigest

/**
 * 基站塔生成器
 *
 * 根据 GPS 坐标确定性地生成仿真基站信息：
 * - 1 个服务小区（registered=true，信号强）
 * - 3~5 个邻区（registered=false，信号弱）
 * - 混合 GSM / LTE / NR(5G) 制式
 * - CellID / LAC 在各制式合法范围内
 * - 信号强度与"模拟距离"正相关
 */
object CellTowerGenerator {

    // ==================== 公开数据结构 ====================

    enum class CellType { GSM, LTE, NR }

    data class FakeCell(
        val cellType: CellType,
        val registered: Boolean,
        val cellId: Int,
        val lac: Int,
        val tac: Int,
        val mcc: Int,
        val mnc: Int,
        val rssi: Int,
        val pci: Int,
        val earfcn: Int,
        val bandwidth: Int = 0
    )

    data class FakeCellBundle(
        val serving: FakeCell,
        val neighbors: List<FakeCell>,
        val mcc: Int,
        val mnc: Int
    )

    // ==================== 常量 ====================

    private const val GSM_CID_MAX = 65535
    private const val GSM_LAC_MAX = 65535
    private const val LTE_CID_MAX = 268435455
    private const val LTE_TAC_MAX = 65535
    private const val NR_CID_MAX = 268435455
    private const val NR_TAC_MAX = 65535

    private const val PCI_MAX_GSM = 0
    private const val PCI_MAX_LTE = 503
    private const val PCI_MAX_NR = 1007

    private const val EARFCN_GSM_MIN = 1
    private const val EARFCN_GSM_MAX = 124
    private const val EARFCN_LTE_MIN = 0
    private const val EARFCN_LTE_MAX = 262143
    private const val EARFCN_NR_MIN = 0
    private const val EARFCN_NR_MAX = 3279165

    private const val SERVING_RSSI_MIN = -80
    private const val SERVING_RSSI_MAX = -60
    private const val NEIGHBOR_RSSI_MIN = -110
    private const val NEIGHBOR_RSSI_MAX = -82

    // ==================== 主入口 ====================

    /**
     * 根据 GPS 坐标生成完整的基站数据包
     */
    fun generate(lat: Double, lng: Double, mcc: Int = 460, mnc: Int = 0): FakeCellBundle {
        val hash = coordinateHash(lat, lng)
        val servingCellId = deriveServingCellId(hash, mcc, mnc)
        val servingLac = deriveLac(hash)
        val servingTac = deriveTac(hash)
        val servingPci = derivePci(hash, PCI_MAX_LTE)
        val servingEarfcn = deriveEarfcn(hash)
        val carrierOffset = (hash[8].toInt() and 0xFF).mod(3)
        val finalMnc = if (mnc == 0) {
            when (carrierOffset) {
                0 -> 0   // CMCC
                1 -> 1   // CU
                else -> 11 // CT
            }
        } else mnc

        val servingRssi = randomInRange(hash, SERVING_RSSI_MIN, SERVING_RSSI_MAX)

        val servingCellTypes = listOf(CellType.LTE, CellType.NR, CellType.GSM)
        val servingType = servingCellTypes[(hash[9].toInt() and 0xFF).mod(servingCellTypes.size)]

        val servingCell = FakeCell(
            cellType = servingType,
            registered = true,
            cellId = servingCellId,
            lac = servingLac,
            tac = servingTac,
            mcc = mcc,
            mnc = finalMnc,
            rssi = servingRssi,
            pci = servingPci,
            earfcn = servingEarfcn
        )

        val neighborCount = 3 + (hash[10].toInt() and 0xFF).mod(3) // 3~5
        val neighbors = generateNeighbors(
            hash = hash,
            servingCellId = servingCellId,
            servingLac = servingLac,
            servingTac = servingTac,
            mcc = mcc,
            mnc = finalMnc,
            servingRssi = servingRssi,
            count = neighborCount
        )

        return FakeCellBundle(
            serving = servingCell,
            neighbors = neighbors,
            mcc = mcc,
            mnc = finalMnc
        )
    }

    // ==================== 邻区生成 ====================

    private fun generateNeighbors(
        hash: ByteArray,
        servingCellId: Int,
        servingLac: Int,
        servingTac: Int,
        mcc: Int,
        mnc: Int,
        servingRssi: Int,
        count: Int
    ): List<FakeCell> {
        val result = mutableListOf<FakeCell>()
        val neighborTypes = listOf(
            CellType.LTE, CellType.LTE, CellType.LTE,
            CellType.GSM, CellType.GSM,
            CellType.NR
        )

        for (i in 0 until count) {
            val nHash = neighborHash(hash, i)
            val nType = neighborTypes[(nHash[0].toInt() and 0xFF).mod(neighborTypes.size)]

            val nCellId = deriveNeighborCellId(nHash, servingCellId, nType)
            val nLac = deriveNeighborLac(nHash, servingLac)
            val nTac = deriveNeighborTac(nHash, servingTac)
            val nPci = derivePci(nHash, when (nType) {
                CellType.GSM -> PCI_MAX_GSM
                CellType.LTE -> PCI_MAX_LTE
                CellType.NR -> PCI_MAX_NR
            })
            val nEarfcn = deriveNeighborEarfcn(nHash, nType)
            val nRssi = randomInRange(nHash, NEIGHBOR_RSSI_MIN, NEIGHBOR_RSSI_MAX)
                .coerceAtMost(servingRssi - 2)

            result.add(
                FakeCell(
                    cellType = nType,
                    registered = false,
                    cellId = nCellId,
                    lac = nLac,
                    tac = nTac,
                    mcc = mcc,
                    mnc = mnc,
                    rssi = nRssi,
                    pci = nPci,
                    earfcn = nEarfcn
                )
            )
        }
        return result
    }

    // ==================== 各字段推导 ====================

    private fun deriveServingCellId(hash: ByteArray, mcc: Int, mnc: Int): Int {
        val base = ((hash[0].toInt() and 0xFF) shl 24
            or (hash[1].toInt() and 0xFF) shl 16
            or (hash[2].toInt() and 0xFF) shl 8
            or (hash[3].toInt() and 0xFF))

        return when {
            mnc == 0 -> (base.mod(LTE_CID_MAX - 1000) + 1001).coerceIn(1, LTE_CID_MAX)
            mnc == 1 -> (base.mod(GSM_CID_MAX - 1000) + 1001).coerceIn(1, GSM_CID_MAX)
            else -> (base.mod(NR_CID_MAX - 1000) + 1001).coerceIn(1, NR_CID_MAX)
        }
    }

    private fun deriveLac(hash: ByteArray): Int {
        return ((hash[4].toInt() and 0xFF) shl 8 or (hash[5].toInt() and 0xFF))
            .coerceIn(1, GSM_LAC_MAX)
    }

    private fun deriveTac(hash: ByteArray): Int {
        return ((hash[6].toInt() and 0xFF) shl 8 or (hash[7].toInt() and 0xFF))
            .coerceIn(1, LTE_TAC_MAX)
    }

    private fun derivePci(hash: ByteArray, max: Int): Int {
        if (max == 0) return 0
        return ((hash[11].toInt() and 0xFF) shl 8 or (hash[12].toInt() and 0xFF))
            .mod(max + 1)
    }

    private fun deriveEarfcn(hash: ByteArray): Int {
        val choice = (hash[13].toInt() and 0xFF).mod(3)
        return when (choice) {
            0 -> randomInRange(hash, EARFCN_GSM_MIN, EARFCN_GSM_MAX)
            1 -> randomInRange(hash, EARFCN_LTE_MIN, 1000) + 1000
            else -> randomInRange(hash, EARFCN_NR_MIN, 5000) + 5000
        }
    }

    private fun deriveNeighborCellId(nHash: ByteArray, servingCellId: Int, type: CellType): Int {
        val max = when (type) {
            CellType.GSM -> GSM_CID_MAX
            CellType.LTE -> LTE_CID_MAX
            CellType.NR -> NR_CID_MAX
        }
        val offset = ((nHash[1].toInt() and 0x7F) shl 8 or (nHash[2].toInt() and 0xFF)).mod(5000) + 1
        val sign = if (nHash[0].toInt() and 0x01 == 0) 1 else -1
        val raw = servingCellId + sign * offset
        return raw.coerceIn(1, max)
    }

    private fun deriveNeighborLac(nHash: ByteArray, servingLac: Int): Int {
        val offset = ((nHash[3].toInt() and 0x0F)).mod(3)
        val raw = servingLac + offset
        return raw.coerceIn(1, GSM_LAC_MAX)
    }

    private fun deriveNeighborTac(nHash: ByteArray, servingTac: Int): Int {
        val offset = ((nHash[4].toInt() and 0x0F)).mod(3)
        val raw = servingTac + offset
        return raw.coerceIn(1, LTE_TAC_MAX)
    }

    private fun deriveNeighborEarfcn(nHash: ByteArray, type: CellType): Int {
        return when (type) {
            CellType.GSM -> randomInRange(nHash, EARFCN_GSM_MIN, EARFCN_GSM_MAX)
            CellType.LTE -> randomInRange(nHash, EARFCN_LTE_MIN + 1000, EARFCN_LTE_MIN + 5000)
            CellType.NR -> randomInRange(nHash, EARFCN_NR_MIN + 5000, EARFCN_NR_MIN + 10000)
        }
    }

    // ==================== 哈希工具 ====================

    private fun coordinateHash(lat: Double, lng: Double): ByteArray {
        val input = String.format("%.6f,%.6f", lat, lng)
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).copyOf(16)
    }

    private fun neighborHash(servingHash: ByteArray, index: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = servingHash.copyOf() + byteArrayOf(index.toByte())
        return digest.digest(input).copyOf(16)
    }

    private fun randomInRange(hash: ByteArray, min: Int, max: Int): Int {
        val range = max - min + 1
        if (range <= 0) return min
        val value = ((hash[14].toInt() and 0xFF) shl 8 or (hash[15].toInt() and 0xFF))
        return min + value.mod(range)
    }
}
