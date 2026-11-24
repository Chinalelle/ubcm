package com.kang.ubcm

/**
 * `des.js` 的**逐行移植** —— 它不是标准 DES，别用 javax.crypto 替换。
 *
 * 为什么不能直接用 `DES/ECB`：拿经典测试向量（key `133457799BBCDFF1`、
 * 明文 `0123456789ABCDEF`）去跑，标准 DES 得 `85E813540F0AB405`，
 * 而这个 `des.js` 得 `CC38B78305003643`。它的表（IP/PC-1/PC-2/E/S/P/FP）
 * 逐项看都是标准的，但整体结果与标准 DES 不同 —— 是个**自洽的非标准变体**。
 * 学校后端用的就是同一套，所以只能照抄，不能"改进"。
 *
 * 这段代码看起来丑（位数组 + 魔数表），但它**只能是这个样子**：
 * 换成更漂亮的实现 = 换成标准 DES = 登录立刻废。
 *
 * 正确性由 `DesLegacyTest` 用原版 `des.js` 在 node 里的输出逐字节对拍，
 * 以及经典向量交叉确认"它确实不是标准 DES"。
 */
object DesLegacy {

    /** 8 个 S 盒，每个 4 行 × 16 列，共 512 个数（s1..s8 顺序扁平化） */
    private val SBOX = intArrayOf(
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
    )

    /** PC-2：56 位密钥 → 48 位轮密钥 */
    private val PC2 = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    /** P 置换：32 → 32 */
    private val P = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24,
    )

    /** 逆初始置换 FP：64 → 64 */
    private val FP = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24,
    )

    /** 轮左移位数 */
    private val LOOP = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    private const val HEX = "0123456789ABCDEF"

    // ---------- 位数组基础 ----------

    /**
     * `strToBt`：最多 4 个 UTF-16 字符 → 64 位（每字符 16 bit 大端，不足补 0）。
     * 原实现 `parseInt(k/pow) % 2` 等价于取第 (15-j) 位。
     */
    fun strToBt(s: String): IntArray {
        val bt = IntArray(64)
        for (i in 0 until minOf(4, s.length)) {
            val c = s[i].code
            for (j in 0 until 16) bt[i * 16 + j] = (c ushr (15 - j)) and 1
        }
        return bt
    }

    /** `hexToBt64`：只给测试喂经典向量用 */
    fun hexToBt64(hex: String): IntArray {
        val bt = IntArray(64)
        for (i in 0 until 16) {
            val v = HEX.indexOf(hex[i].uppercaseChar()).coerceAtLeast(0)
            for (j in 0 until 4) bt[i * 4 + j] = (v ushr (3 - j)) and 1
        }
        return bt
    }

    /** `initPermute` —— 照搬原实现的两层循环 + 步进访问顺序 */
    fun initPermute(d: IntArray): IntArray {
        val out = IntArray(64)
        var m = 1
        var n = 0
        for (i in 0 until 4) {
            var k = 0
            for (j in 7 downTo 0) {
                out[i * 8 + k] = d[j * 8 + m]
                out[i * 8 + k + 32] = d[j * 8 + n]
                k++
            }
            m += 2
            n += 2
        }
        return out
    }

    /** `expandPermute`：32 → 48 */
    fun expandPermute(r: IntArray): IntArray {
        val out = IntArray(48)
        for (i in 0 until 8) {
            out[i * 6 + 0] = if (i == 0) r[31] else r[i * 4 - 1]
            out[i * 6 + 1] = r[i * 4 + 0]
            out[i * 6 + 2] = r[i * 4 + 1]
            out[i * 6 + 3] = r[i * 4 + 2]
            out[i * 6 + 4] = r[i * 4 + 3]
            out[i * 6 + 5] = if (i == 7) r[0] else r[i * 4 + 4]
        }
        return out
    }

    fun xor(a: IntArray, b: IntArray): IntArray = IntArray(a.size) { a[it] xor b[it] }

    /** `sBoxPermute`：48 → 32。行 = 首尾两位，列 = 中间四位（与原实现一致） */
    fun sBoxPermute(e: IntArray): IntArray {
        val out = IntArray(32)
        for (m in 0 until 8) {
            val row = e[m * 6 + 0] * 2 + e[m * 6 + 5]
            val col = e[m * 6 + 1] * 8 + e[m * 6 + 2] * 4 + e[m * 6 + 3] * 2 + e[m * 6 + 4]
            val num = SBOX[m * 64 + row * 16 + col]
            out[m * 4 + 0] = (num shr 3) and 1
            out[m * 4 + 1] = (num shr 2) and 1
            out[m * 4 + 2] = (num shr 1) and 1
            out[m * 4 + 3] = num and 1
        }
        return out
    }

    /** `pPermute` */
    fun pPermute(s: IntArray): IntArray = IntArray(32) { s[P[it]] }

    /** `finallyPermute` */
    fun finallyPermute(e: IntArray): IntArray = IntArray(64) { e[FP[it]] }

    /**
     * `generateKeys`：64 位 → 16 组 48 位轮密钥。
     * 初始置换（PC-1）在原实现里是 `key[8k+i]` 两层循环拼的，照搬。
     */
    fun generateKeys(keyByte: IntArray): Array<IntArray> {
        val key = IntArray(56)
        for (i in 0 until 7) {
            var k = 7
            for (j in 0 until 8) {
                key[i * 8 + j] = keyByte[8 * k + i]
                k--
            }
        }
        val keys = Array(16) { IntArray(48) }
        for (i in 0 until 16) {
            repeat(LOOP[i]) {
                val tempLeft = key[0]
                val tempRight = key[28]
                for (k in 0 until 27) {
                    key[k] = key[k + 1]
                    key[28 + k] = key[29 + k]
                }
                key[27] = tempLeft
                key[55] = tempRight
            }
            for (m in 0 until 48) keys[i][m] = key[PC2[m]]
        }
        return keys
    }

    /** `enc`：单块加密，64 位进 64 位出 */
    fun enc(dataByte: IntArray, keyByte: IntArray): IntArray {
        val keys = generateKeys(keyByte)
        val ip = initPermute(dataByte)
        var left = IntArray(32) { ip[it] }
        var right = IntArray(32) { ip[32 + it] }
        for (round in 0 until 16) {
            val prevLeft = left
            left = right
            right = xor(pPermute(sBoxPermute(xor(expandPermute(right), keys[round]))), prevLeft)
        }
        val finalData = IntArray(64)
        for (i in 0 until 32) {
            finalData[i] = right[i]
            finalData[32 + i] = left[i]
        }
        return finallyPermute(finalData)
    }

    /** `bt64ToHex`：64 位 → 16 个大写 hex */
    fun bt64ToHex(bt: IntArray): String {
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            var v = 0
            for (j in 0 until 4) v = v * 2 + bt[i * 4 + j]
            sb.append(HEX[v])
        }
        return sb.toString()
    }

    /**
     * `strEnc(data, k1, k2, k3)`：按 **4 字符**切块，每块依次用 k1/k2/k3
     * 做三次单 DES（E-E-E，不是 3DES 的 EDE）。
     */
    fun strEnc(data: String, k1: String, k2: String, k3: String): String {
        if (data.isEmpty()) return ""
        val keys = listOf(strToBt(k1), strToBt(k2), strToBt(k3))
        val sb = StringBuilder()
        var off = 0
        while (off < data.length) {
            var blk = strToBt(data.substring(off, minOf(off + 4, data.length)))
            for (k in keys) blk = enc(blk, k)
            sb.append(bt64ToHex(blk))
            off += 4
        }
        return sb.toString()
    }
}
