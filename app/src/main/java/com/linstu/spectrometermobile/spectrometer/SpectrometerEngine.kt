package com.linstu.spectrometermobile.spectrometer

import kotlin.math.*

/**
 * 光谱仪核心处理引擎 —— 逐行移植 Theremino Spectrometer V5.0 VB.NET 源码。
 * 对应 Modules: Module_Spectrometer.vb / Module_Calibrations.vb
 *
 * 处理链（Annotations.txt 原文顺序）：
 *   ReceivedSamples
 *     → 辐照度校正 CorrectForIrradiance
 *     → 平均滤波 Array_AverageFiltered
 *     → 减 ADC 最小值 / 缩放
 *     → UP/DOWN 快慢滤波 Array_UpDownFiltered
 *     → 波长校准 Array_Calibrated (bin↔nm 分段线性插值)
 *     → 空间滤波 Array_SpatialFiltered (双向)
 *     → 背景扣除 / 参考归一化
 *     → VisibleSamples
 */
class SpectrometerEngine {

    // ------------------------- 传感器/采样参数 -------------------------
    var sensorNumSamples: Int = 1920
        private set

    var receivedSamples: FloatArray = floatArrayOf()
        private set
    var averageFiltered: FloatArray = floatArrayOf()
        private set
    var upDownFiltered: FloatArray = floatArrayOf()
        private set
    var calibrated: FloatArray = floatArrayOf()
        private set
    var spatialFiltered: FloatArray = floatArrayOf()
        private set
    var visibleSamples: FloatArray = floatArrayOf()
        private set

    var background: FloatArray = floatArrayOf()
        private set
    var reference: FloatArray = floatArrayOf()
        private set

    // ------------------------- 波形/显示参数 -------------------------
    var nanometersMin: Double = 380.0
    var nanometersMax: Double = 780.0
    val nanometersDelta: Double get() = nanometersMax - nanometersMin

    var sourceX0: Int = 0
    var sourceDX: Int = 0
    var maxPeakValue: Float = 0.1f
    var maxPeakValueIndex: Int = 0
    var maxVisibleValue: Float = 1000f
    var receivedValueMin: Float = 0f
    var receivedValueMax: Float = 0f

    // ------------------------- 可调参数（对应 Form1 控件）-------------------------
    var startX: Int = 0          // 千分数
    var endX: Int = 1000         // 千分数
    var startY: Int = 400        // 千分数
    var sizeY: Int = 200         // 千分数
    var flip: Boolean = false
    var spatialFilterLevel: Int = 0   // txt_SpatialAveraging: 0..10
    var risingSpeed: Int = 100        // txt_RisingSpeed
    var fallingSpeed: Int = 100       // txt_FallingSpeed
    var averageEnabled: Boolean = false
    var averageNumber: Int = Int.MAX_VALUE
    var averageCounter: Int = 1
    var valueMinSubtractor: Float = 0f
    var scaleCoeffTo65536: Float = 256f   // WebCam 模式固定 256
    var useColors: Boolean = true
    var showPeaks: Boolean = false
    var showDips: Boolean = false
    var logScale: Float = 1f

    // ------------------------- 校准点（bin ↔ nm）-------------------------
    private var calibBin = doubleArrayOf(1000.0, 2000.0)
    private var calibNm = doubleArrayOf(436.0, 546.0)

    fun setCalibration(bins: DoubleArray, nms: DoubleArray) {
        if (bins.size >= 2 && bins.size == nms.size) {
            calibBin = bins.copyOf()
            calibNm = nms.copyOf()
        }
    }

    fun getCalibrationBins(): DoubleArray = calibBin.copyOf()
    fun getCalibrationNm(): DoubleArray = calibNm.copyOf()

    fun calibBinToNm(bin: Double): Double = interpolate(calibBin, calibNm, bin)
    fun calibNmToBin(nm: Double): Double = interpolate(calibNm, calibBin, nm)

    /** 线性插值（及外推）—— 直接移植 Module_Calibrations.Interpolate */
    private fun interpolate(x: DoubleArray, y: DoubleArray, xValue: Double): Double {
        var i = 1
        while (i < x.size && x[i] < xValue) i++
        if (i >= x.size) i = x.size - 1
        return y[i - 1] + (xValue - x[i - 1]) / (x[i] - x[i - 1]) * (y[i] - y[i - 1])
    }

    fun addTrimmingPoint(bin: Double, nm: Double) {
        var insert = calibBin.size
        for (i in calibBin.indices) {
            if (bin <= calibBin[i]) { insert = i; break }
        }
        calibBin = calibBin.copyOf(calibBin.size + 1)
        calibNm = calibNm.copyOf(calibNm.size + 1)
        for (i in calibBin.size - 1 downTo insert + 1) {
            calibBin[i] = calibBin[i - 1]
            calibNm[i] = calibNm[i - 1]
        }
        calibBin[insert] = bin
        calibNm[insert] = nm
    }

    fun removeCalibrationPoint(index: Int) {
        if (calibBin.size <= 2) return
        calibBin = calibBin.filterIndexed { i, _ -> i != index }.toDoubleArray()
        calibNm = calibNm.filterIndexed { i, _ -> i != index }.toDoubleArray()
    }

    fun editCalibrationNm(index: Int, v: Double) {
        val max = if (index < calibNm.size - 1) calibNm[index + 1] - 0.25 else 2000.0
        val min = if (index > 0) calibNm[index - 1] + 0.25 else 0.0
        calibNm[index] = v.coerceIn(min, max)
    }

    fun editCalibrationBin(index: Int, v: Double) {
        val max = if (index < calibBin.size - 1) calibBin[index + 1] - 1 else sensorNumSamples.toDouble()
        val min = if (index > 0) calibBin[index - 1] + 1 else 0.0
        calibBin[index] = v.coerceIn(min, max)
    }

    /** 归一化校准数组到图像宽度 —— 对应 NormalizeArrayTo(Calib_BIN, oldN, newN) */
    fun normalizeCalibrationToSamples(newN: Int) {
        val from = sensorNumSamples.toDouble()
        val k = newN / from
        for (i in calibBin.indices) calibBin[i] *= k
        sensorNumSamples = newN
    }

    // ------------------------- 数组初始化 -------------------------
    fun initArrays(nSamples: Int) {
        sensorNumSamples = nSamples
        receivedSamples = FloatArray(nSamples)
        averageFiltered = FloatArray(nSamples)
        upDownFiltered = FloatArray(nSamples)
        calibrated = FloatArray(nSamples)
        spatialFiltered = FloatArray(nSamples)
        averageCounter = 1
    }

    fun resetAllData() {
        initArrays(sensorNumSamples)
        background = floatArrayOf()
        reference = floatArrayOf()
        averageCounter = 1
    }

    /** 根据 WebCam 图像尺寸设置采集区域参数 —— 对应 Spectrometer_SetSourceParams */
    fun setSourceParams(imageWidth: Int, imageHeight: Int) {
        if (imageWidth != sensorNumSamples) {
            normalizeCalibrationToSamples(imageWidth)
            initArrays(imageWidth)
        }
        sourceX0 = (imageWidth * startX) / 1000
        sourceDX = imageWidth - sourceX0 + (imageWidth * (endX - 1000)) / 1000
        if (sourceX0 + sourceDX > imageWidth) sourceDX = imageWidth - sourceX0
        if (sourceDX <= 0) { sourceX0 += sourceDX - 1; sourceDX = 1 }
        if (sourceX0 < 0) sourceX0 = 0

        val srcDY = max(1, (imageHeight * sizeY) / 1000)
        // 对应 SrcY0 = SrcH - SrcH*StartY/1000 - SrcDY
        // 这里只记录区域，取带由调用方处理

        nanometersMin = max(0.0, calibBinToNm(0.0))
        nanometersMax = calibBinToNm(sensorNumSamples.toDouble())
        if (nanometersMax > 4000) nanometersMax = 4000.0
        if (nanometersMax < nanometersMin + 10) nanometersMax = nanometersMin + 10

        visibleSamples = FloatArray(sourceDX)
    }

    // ------------------------- 从摄像头帧提取光谱 -------------------------
    /**
     * Bitmap_To_ReceivedSamples 移植：
     * 对水平带[y0, y0+dy) 每一列求 0.299R+0.587G+0.114B 的加权平均。
     * pixels 为 ARGB/ABGR 打包的 IntArray，格式可用 argbOrder 指定。
     */
    fun bitmapToReceivedSamples(
        pixels: IntArray, width: Int, height: Int,
        y0: Int, dy: Int,
        argbOrder: ArgbOrder = ArgbOrder.RGB
    ) {
        if (pixels.size < width * height) return
        initArraysIfNeeded(width)
        receivedValueMax = 0f
        receivedValueMin = Float.MAX_VALUE
        for (x in 0 until width) {
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var pos = y0 * width + x
            for (y in 0 until dy) {
                val px = pixels[pos]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                sumR += r; sumG += g; sumB += b
                pos += width
            }
            val (rr, gg, bb) = when (argbOrder) {
                ArgbOrder.RGB -> Triple(sumR, sumG, sumB)
                ArgbOrder.BGR -> Triple(sumB, sumG, sumR)
            }
            val v = (rr * 0.299 + gg * 0.587 + bb * 0.114).toFloat()
            receivedSamples[x] = v
            if (v > receivedValueMax) receivedValueMax = v
            if (v < receivedValueMin) receivedValueMin = v
        }
    }

    enum class ArgbOrder { RGB, BGR }

    /** 从 YUV420 的 Y 平面提取 —— Y 本身就是亮度加权，无需再算 RGB */
    fun yPlaneToReceivedSamples(yPlane: ByteArray, width: Int, height: Int, y0: Int, dy: Int, stride: Int) {
        initArraysIfNeeded(width)
        receivedValueMax = 0f
        receivedValueMin = Float.MAX_VALUE
        for (x in 0 until width) {
            var sum = 0.0
            for (y in 0 until dy) {
                val v = (yPlane[(y0 + y) * stride + x].toInt() and 0xFF).toDouble()
                sum += v
            }
            val avg = (sum / dy).toFloat()
            receivedSamples[x] = avg
            if (avg > receivedValueMax) receivedValueMax = avg
            if (avg < receivedValueMin) receivedValueMin = avg
        }
    }

    private fun initArraysIfNeeded(n: Int) {
        if (sensorNumSamples != n || receivedSamples.size != n) initArrays(n)
    }

    // ------------------------- 辐照度校正 -------------------------
    /** 对应 CorrectForIrradiance：按系数表逐点乘修正。系数为空则不改。 */
    fun correctForIrradiance(coeffs: FloatArray?) {
        if (coeffs == null || coeffs.size != receivedSamples.size) return
        for (i in receivedSamples.indices) receivedSamples[i] *= coeffs[i]
    }

    // ------------------------- 主处理流水线 -------------------------
    /**
     * Spectrometer_ProcessReceivedSamples 移植。
     * irradianceCoeffs 可为 null。
     */
    fun processReceivedSamples(irradianceCoeffs: FloatArray? = null) {
        correctForIrradiance(irradianceCoeffs)

        val kSpeedUP = (risingSpeed / 100.0).pow(1.5).toFloat()
        val kSpeedDW = (fallingSpeed / 100.0).pow(1.5).toFloat()

        for (i in 0 until sensorNumSamples) {
            var v = receivedSamples[i] * scaleCoeffTo65536
            if (averageEnabled && averageCounter > 1) averageFiltered[i] += v
            else averageFiltered[i] = v
        }

        for (i in 0 until sensorNumSamples) {
            var v = averageFiltered[i] / averageCounter
            v -= valueMinSubtractor
            if (v < 0f) v = 0f
            val k = if (v > upDownFiltered[i]) kSpeedUP else kSpeedDW
            upDownFiltered[i] += (v - upDownFiltered[i]) * k
        }

        if (averageEnabled) {
            averageCounter++
            if (averageCounter > averageNumber) averageCounter = 1
        }

        showSpectrumGraph()
    }

    /** 校准 + 空间滤波 + 背景/参考 + 可见数组 —— 对应 ShowSpectrumGraph 前半段 */
    fun showSpectrumGraph() {
        upDownToCalibrated()
        prepareVisibleSamples()
    }

    /** Array_UpDownFiltered_To_Array_Calibrated 移植 */
    private fun upDownToCalibrated() {
        if (calibrated.size != upDownFiltered.size) calibrated = FloatArray(upDownFiltered.size)
        for (i in upDownFiltered.indices) {
            val nm = nanometersMin + nanometersDelta * i / upDownFiltered.size
            var bin = calibNmToBin(nm).toInt()
            if (bin < 0) bin = 0
            if (bin > upDownFiltered.size - 1) bin = upDownFiltered.size - 1
            calibrated[i] = upDownFiltered[bin]
        }
    }

    /** Add_SpatialFilter 移植：双向一阶低通 */
    private fun addSpatialFilter() {
        if (spatialFiltered.size != calibrated.size) spatialFiltered = FloatArray(calibrated.size)
        val kFilter = 0.1f + 0.9f * ((10 - spatialFilterLevel) / 10.0f)
        var v = 0f
        for (i in calibrated.indices) {
            val vnew = calibrated[i]
            v += (vnew - v) * kFilter
            spatialFiltered[i] = v / 2f
        }
        v = 0f
        for (i in calibrated.indices.reversed()) {
            val vnew = calibrated[i]
            v += (vnew - v) * kFilter
            spatialFiltered[i] += v / 2f
        }
    }

    fun setBackground() { background = spatialFiltered.copyOf() }
    fun resetBackground() { background = floatArrayOf() }

    fun setReference() {
        reference = spatialFiltered.copyOf()
        for (i in reference.indices) if (reference[i] < 200f) reference[i] = 99999f
    }
    fun resetReference() { reference = floatArrayOf() }

    private fun addBackground() {
        if (background.isEmpty()) return
        val n = minOf(spatialFiltered.size, background.size)
        for (i in 0 until n) {
            var v = spatialFiltered[i] - background[i]
            if (v < 0f) v = 0f
            spatialFiltered[i] = v
        }
    }

    private fun addReference() {
        if (reference.isEmpty()) return
        var maxRef = 0.1f
        val n = minOf(visibleSamples.size, spatialFiltered.size - sourceX0)
        for (i in 0 until n) {
            val v = spatialFiltered[i + sourceX0]
            if (v > maxRef) maxRef = v
        }
        val m = minOf(spatialFiltered.size, reference.size)
        for (i in 0 until m) {
            var v = spatialFiltered[i] * maxRef
            if (reference[i] > 0f) v /= reference[i]
            spatialFiltered[i] = v
        }
    }

    private fun prepareVisibleSamples() {
        if (spatialFiltered.isEmpty()) return
        addSpatialFilter()
        addBackground()
        addReference()
        maxPeakValue = 0.1f
        val n = minOf(visibleSamples.size, spatialFiltered.size - sourceX0)
        for (i in 0 until n) {
            val v = spatialFiltered[i + sourceX0]
            visibleSamples[i] = v
            if (v > maxPeakValue) { maxPeakValue = v; maxPeakValueIndex = i }
        }
        maxVisibleValue = max(1000f, maxPeakValue)
    }

    // ------------------------- 坐标换算 -------------------------
    fun valueToY(value: Double, bottom: Int, top: Int = 15): Double =
        bottom - value / maxVisibleValue * (bottom - top)

    fun yToValue(y: Double, bottom: Int, top: Int = 15): Double =
        (bottom - y) * maxVisibleValue / (bottom - top)

    fun binToX(bin: Int, destLeft: Int, destWidth: Int): Int =
        destLeft + (bin * destWidth / max(1, sensorNumSamples - 1))

    fun xToBin(x: Int, destLeft: Int, destWidth: Int): Int =
        ((x - destLeft) * (sensorNumSamples - 1) / max(1, destWidth))

    fun xToNanometers(x: Int, destLeft: Int, destWidth: Int): Double {
        val nmStart = nanometersMin + nanometersDelta * sourceX0 / sensorNumSamples
        val nmEnd = nanometersMax - nanometersDelta * (1 - (sourceX0 + sourceDX - 1).toDouble() / sensorNumSamples)
        return nmStart + (nmEnd - nmStart) * (x - destLeft).toDouble() / max(1, destWidth)
    }

    fun nanometersToX(nm: Double, destLeft: Int, destWidth: Int): Int {
        val nmStart = nanometersMin + nanometersDelta * sourceX0 / sensorNumSamples
        val nmEnd = nanometersMax - nanometersDelta * (1 - (sourceX0 + sourceDX - 1).toDouble() / sensorNumSamples)
        return destLeft + ((nm - nmStart) / max(0.0001, nmEnd - nmStart) * destWidth).toInt()
    }

    /** 峰值面积（对应 CalculatePeakArea）：可见区间内超过 50% 峰值的样本和 */
    fun calculatePeakArea(): Double {
        if (visibleSamples.isEmpty()) return 0.0
        val threshold = maxPeakValue * 0.5
        var area = 0.0
        for (v in visibleSamples) if (v >= threshold) area += v.toDouble()
        return area
    }

    /** 找所有峰（简化对应 MarkAllPeaks）：局部极大且超过峰值 10% */
    fun findPeaks(): List<Int> {
        val peaks = mutableListOf<Int>()
        val threshold = maxPeakValue * 0.1f
        for (i in 1 until visibleSamples.size - 1) {
            if (visibleSamples[i] >= visibleSamples[i - 1] &&
                visibleSamples[i] >= visibleSamples[i + 1] &&
                visibleSamples[i] >= threshold
            ) peaks.add(i)
        }
        return peaks
    }

    /** 波长 → 近似 RGB 颜色（对应 WavelengthToColor，用于彩色光谱填充） */
    fun wavelengthToColor(nm: Double): Int {
        val r: Int; val g: Int; val b: Int
        when {
            nm < 380 -> { r = 0; g = 0; b = 0 }
            nm < 440 -> { r = -(nm - 440).toInt(); g = 0; b = 255 }
            nm < 490 -> { r = 0; g = (nm - 440).toInt(); b = 255 }
            nm < 510 -> { r = 0; g = 255; b = -(nm - 510).toInt() }
            nm < 580 -> { r = (nm - 510).toInt(); g = 255; b = 0 }
            nm < 645 -> { r = 255; g = -(nm - 645).toInt(); b = 0 }
            nm <= 780 -> { r = 255; g = 0; b = 0 }
            else -> { r = 0; g = 0; b = 0 }
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 生成 Theremino 兼容的光谱文本（对应 GetSpectrumText） */
    fun getSpectrumText(): String {
        val sb = StringBuilder()
        sb.append("Sensor;WebCam\r\n")
        sb.append("-----------------------\r\n")
        sb.append("Rec.Samples\t${sensorNumSamples}\r\n")
        sb.append("Average\t${averageNumber}${if (averageEnabled) "" else " INACTIVE"}\r\n")
        sb.append("Spatial avg.\t$spatialFilterLevel\r\n")
        sb.append("RisingSpeed\t$risingSpeed\r\n")
        sb.append("FallingSpeed\t$fallingSpeed\r\n")
        sb.append("NanometersMax\t${"%.2f".format(nanometersMax)}\r\n")
        sb.append("NanometersMin\t${"%.2f".format(nanometersMin)}\r\n")
        sb.append("Peak Area\t${"%.1f".format(calculatePeakArea())}\r\n")
        sb.append("-----------------------\r\n")
        sb.append("Nanometers\tIntensity\r\n")
        sb.append("-----------------------\r\n")
        val k = (nanometersMax - nanometersMin) / max(1, spatialFiltered.size - 1)
        for (i in spatialFiltered.indices) {
            sb.append("%.2f\t%12.1f\r\n".format(nanometersMin + i * k, spatialFiltered[i]))
        }
        return sb.toString()
    }

    /** 从 Theremino 光谱文本载入（对应 Spectrometer_SetSpectrumTextFromFile） */
    fun loadSpectrumText(text: String): Boolean {
        val lines = text.lines()
        val dataStart = lines.indexOfFirst { it.startsWith("Nanometers") && it.contains("Intensity") }
        if (dataStart < 0) return false
        val values = mutableListOf<Float>()
        for (i in dataStart + 1 until lines.size) {
            val parts = lines[i].split("\t", " ", ",").filter { it.isNotBlank() }
            if (parts.size < 2) continue
            val intensity = parts[1].toFloatOrNull() ?: continue
            values.add(intensity)
        }
        if (values.size < 10) return false
        initArrays(values.size)
        for (i in values.indices) {
            upDownFiltered[i] = values[i]
            calibrated[i] = values[i]
            spatialFiltered[i] = values[i]
        }
        visibleSamples = spatialFiltered.copyOf()
        sourceDX = values.size
        maxPeakValue = visibleSamples.maxOrNull() ?: 0.1f
        maxVisibleValue = max(1000f, maxPeakValue)
        return true
    }
}
