package com.linstu.spectrometermobile.spectrometer

import kotlin.math.*

/**
 * 色度学计算 —— CIE 1931 XYZ、CCT (McCamy 近似)、Duv、峰值波长、
 * 以及 melanopic EDI（CIE S 026 简化实现）。
 * VB 原版依赖 System.Drawing 的 Color 转换，这里自己实现。
 */
object Colorimetry {

    // CIE 1931 2° 标准观察者 CMF（采样到 5nm，380..780）
    private val CMF_WAVELENGTHS = IntArray(81) { 380 + it * 5 }
    private val CMF_X = doubleArrayOf(
        0.0014,0.0028,0.0051,0.0093,0.0143,0.0232,0.0435,0.0776,0.1344,0.2140,
        0.2839,0.3483,0.3907,0.4147,0.4636,0.5213,0.5932,0.6698,0.7447,0.7987,
        0.8448,0.8800,0.9057,0.9204,0.9224,0.9163,0.9013,0.8790,0.8477,0.8051,
        0.7574,0.7076,0.6593,0.6152,0.5784,0.5547,0.5405,0.5432,0.5625,0.6017,
        0.6515,0.7101,0.7755,0.8441,0.9120,0.9747,1.0263,1.0622,1.0814,1.0793,
        1.0557,1.0134,0.9527,0.8784,0.7957,0.7094,0.6243,0.5435,0.4694,0.4023,
        0.3404,0.2862,0.2394,0.1990,0.1650,0.1368,0.1137,0.0946,0.0788,0.0657,
        0.0549,0.0460,0.0387,0.0327,0.0277,0.0235,0.0201,0.0172,0.0148,0.0128
    )
    private val CMF_Y = doubleArrayOf(
        0.0000,0.0001,0.0002,0.0004,0.0007,0.0014,0.0028,0.0056,0.0114,0.0203,
        0.0324,0.0480,0.0627,0.0752,0.0906,0.1096,0.1296,0.1510,0.1712,0.1868,
        0.1980,0.2060,0.2103,0.2119,0.2100,0.2043,0.1956,0.1835,0.1688,0.1516,
        0.1333,0.1154,0.0991,0.0849,0.0739,0.0672,0.0636,0.0642,0.0708,0.0834,
        0.1034,0.1301,0.1622,0.1996,0.2410,0.2855,0.3326,0.3822,0.4336,0.4854,
        0.5368,0.5873,0.6355,0.6801,0.7202,0.7549,0.7839,0.8072,0.8245,0.8364,
        0.8430,0.8441,0.8407,0.8328,0.8207,0.8049,0.7858,0.7640,0.7398,0.7137,
        0.6862,0.6578,0.6289,0.5999,0.5712,0.5431,0.5160,0.4900,0.4654,0.4423
    )
    private val CMF_Z = doubleArrayOf(
        0.0065,0.0126,0.0228,0.0412,0.0636,0.1030,0.1904,0.3389,0.5870,0.9193,
        1.1831,1.3930,1.5455,1.6340,1.7263,1.8145,1.8626,1.8780,1.8455,1.7604,
        1.6338,1.4843,1.3306,1.1760,1.0272,0.8886,0.7597,0.6454,0.5443,0.4558,
        0.3796,0.3132,0.2565,0.2091,0.1698,0.1358,0.1070,0.0820,0.0607,0.0435,
        0.0301,0.0200,0.0122,0.0067,0.0029,0.0008,0.0001,0.0000,0.0000,0.0000,
        0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,
        0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,
        0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000,0.0000
    )

    // melanopic 作用光谱 (CIE S 026, 归一化到 555nm 峰值 1.0)
    private val MELANO_WAVELENGTHS = IntArray(39) { 420 + it * 10 }
    private val MELANO_SENS = doubleArrayOf(
        0.000000,0.000010,0.000040,0.000120,0.000300,0.000630,0.001150,0.001900,
        0.002900,0.004070,0.005390,0.006800,0.008250,0.009770,0.011260,0.012720,
        0.014070,0.015320,0.016480,0.017480,0.018340,0.019020,0.019520,0.019880,
        0.020150,0.020350,0.020500,0.020620,0.020720,0.020810,0.020870,0.020920,
        0.020960,0.020990,0.021010,0.021020,0.021020,0.021020,0.021020
    )

    data class Result(
        val x: Double, val y: Double,
        val cct: Double, val duv: Double,
        val peakNm: Double, val melanopicEdi: Double
    )

    /**
     * 从 SPD 计算色度学指标。
     * wavelengths / intensity 必须等长，intensity 为相对辐射强度。
     */
    fun calculate(wavelengths: DoubleArray, intensity: DoubleArray): Result? {
        if (wavelengths.size != intensity.size || wavelengths.size < 10) return null

        // 插值 CMF 到给定波长
        var X = 0.0; var Y = 0.0; var Z = 0.0
        for (i in wavelengths.indices) {
            val nm = wavelengths[i]
            if (nm < 380 || nm > 780) continue
            val xbar = interp(CMF_WAVELENGTHS, CMF_X, nm)
            val ybar = interp(CMF_WAVELENGTHS, CMF_Y, nm)
            val zbar = interp(CMF_WAVELENGTHS, CMF_Z, nm)
            X += intensity[i] * xbar
            Y += intensity[i] * ybar
            Z += intensity[i] * zbar
        }
        if (X + Y + Z <= 0) return null
        val x = X / (X + Y + Z)
        val y = Y / (X + Y + Z)

        // McCamy 近似 CCT
        val n = (x - 0.3320) / (0.1858 - y)
        val cct = 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33

        // Duv：到普朗克轨迹的近似距离（用 CCT 反推色度点）
        val planck = planckianChromaticity(cct)
        val duv = sqrt((x - planck.first) * (x - planck.first) + (y - planck.second) * (y - planck.second))

        // 峰值波长
        var peakIdx = 0
        for (i in intensity.indices) if (intensity[i] > intensity[peakIdx]) peakIdx = i
        val peakNm = wavelengths[peakIdx]

        // melanopic EDI（简化：以 Y 归一到 100 lx 等效）
        var mel = 0.0
        for (i in wavelengths.indices) {
            val nm = wavelengths[i]
            if (nm < 420 || nm > 780) continue
            mel += intensity[i] * interp(MELANO_WAVELENGTHS, MELANO_SENS, nm)
        }
        // D65 参考归一化系数（简化取常数），实际应按同法算 D65 SPD
        val melanopicEdi = if (Y > 0) mel / Y * 100.0 else 0.0

        return Result(x, y, cct, duv, peakNm, melanopicEdi)
    }

    private fun interp(xs: IntArray, ys: DoubleArray, x: Double): Double {
        if (x <= xs[0]) return ys[0]
        if (x >= xs[xs.size - 1]) return ys[ys.size - 1]
        for (i in 1 until xs.size) {
            if (x <= xs[i]) {
                val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
                return ys[i - 1] + t * (ys[i] - ys[i - 1])
            }
        }
        return ys[ys.size - 1]
    }

    /** 普朗克轨迹色度点近似 (Kim et al. 2004) */
    private fun planckianChromaticity(t: Double): Pair<Double, Double> {
        val tt = t.coerceIn(1000.0, 40000.0)
        val x = when {
            tt < 4000 -> -0.2661239e9 / tt.pow(3) - 0.2343589e6 / tt.pow(2) + 0.8776956e3 / tt + 0.179910
            else -> -3.0258469e9 / tt.pow(3) + 2.1070379e6 / tt.pow(2) + 0.2226347e3 / tt + 0.240390
        }
        val y = when {
            tt < 2222 -> -1.1063814 * x.pow(3) - 1.34811020 * x.pow(2) + 2.18555832 * x - 0.20219683
            tt < 4000 -> -0.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x.pow(3) - 5.87338670 * x.pow(2) + 3.75112997 * x - 0.37001483
        }
        return Pair(x, y)
    }

    /** 从 engine 的可见数组生成 (nm, intensity) 对 */
    fun fromEngine(engine: SpectrometerEngine): Result? {
        val n = engine.visibleSamples.size
        if (n < 10) return null
        val wl = DoubleArray(n)
        val inten = DoubleArray(n)
        for (i in 0 until n) {
            wl[i] = engine.nanometersMin + (engine.nanometersMax - engine.nanometersMin) *
                    (engine.sourceX0 + i).toDouble() / engine.sensorNumSamples
            inten[i] = engine.visibleSamples[i].toDouble().coerceAtLeast(0.0)
        }
        return calculate(wl, inten)
    }
}
