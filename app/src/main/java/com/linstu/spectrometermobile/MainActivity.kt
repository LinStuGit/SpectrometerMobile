package com.linstu.spectrometermobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.linstu.spectrometermobile.spectrometer.*
import com.linstu.spectrometermobile.ui.SpectrumView
import kotlinx.coroutines.*
import java.io.*
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var engine: SpectrometerEngine
    private lateinit var camera: Camera2Capture
    private lateinit var spectrumView: SpectrumView
    private lateinit var tvStats: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastValidFrame = 0L
    private var validCount = 0
    private var skippedCount = 0
    private var darkThreshold = 8f

    private lateinit var etStartX: EditText
    private lateinit var etEndX: EditText
    private lateinit var etStartY: EditText
    private lateinit var etSizeY: EditText
    private lateinit var etSpatialFilter: EditText
    private lateinit var etRisingSpeed: EditText
    private lateinit var etFallingSpeed: EditText
    private lateinit var etAverageNumber: EditText
    private lateinit var cbAverage: CheckBox
    private lateinit var cbFlip: CheckBox
    private lateinit var cbColors: CheckBox
    private lateinit var cbPeaks: CheckBox
    private lateinit var cbTorch: CheckBox
    private lateinit var etCalib: EditText
    private lateinit var spinnerCamera: Spinner
    private lateinit var seekExposure: SeekBar
    private lateinit var tvExposure: TextView
    private lateinit var seekGain: SeekBar
    private lateinit var tvGain: TextView

    companion object {
        private const val REQ_CAMERA = 1001
        private const val REQ_SAVE = 1002
        private const val REQ_LOAD = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = SpectrometerEngine()
        camera = Camera2Capture(this)
        spectrumView = findViewById(R.id.spectrumView)
        spectrumView.engine = engine
        tvStats = findViewById(R.id.tvStats)

        bindViews()
        setupCameraSpinner()
        setupButtons()
        requestPermissions()
    }

    private fun bindViews() {
        etStartX = findViewById(R.id.etStartX)
        etEndX = findViewById(R.id.etEndX)
        etStartY = findViewById(R.id.etStartY)
        etSizeY = findViewById(R.id.etSizeY)
        etSpatialFilter = findViewById(R.id.etSpatialFilter)
        etRisingSpeed = findViewById(R.id.etRisingSpeed)
        etFallingSpeed = findViewById(R.id.etFallingSpeed)
        etAverageNumber = findViewById(R.id.etAverageNumber)
        cbAverage = findViewById(R.id.cbAverage)
        cbFlip = findViewById(R.id.cbFlip)
        cbColors = findViewById(R.id.cbColors)
        cbPeaks = findViewById(R.id.cbPeaks)
        cbTorch = findViewById(R.id.cbTorch)
        etCalib = findViewById(R.id.etCalib)
        spinnerCamera = findViewById(R.id.spinnerCamera)
        seekExposure = findViewById(R.id.seekExposure)
        tvExposure = findViewById(R.id.tvExposure)
        seekGain = findViewById(R.id.seekGain)
        tvGain = findViewById(R.id.tvGain)
    }

    private fun setupCameraSpinner() {
        val cams = Camera2Capture.listCameras(this)
        val labels = cams.map { it.label }
        spinnerCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinnerCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (cams.isNotEmpty()) {
                    camera.cameraId = cams[pos].id
                    Toast.makeText(this@MainActivity, "已选: ${cams[pos].label}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun requestPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_CAMERA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnRun).setOnClickListener { startCapture() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopCapture() }
        findViewById<Button>(R.id.btnBackground).setOnClickListener {
            engine.setBackground()
            toast("背景已记录 (${engine.background.size} 点)")
        }
        findViewById<Button>(R.id.btnReference).setOnClickListener {
            engine.setReference()
            toast("参考已记录")
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSpectrum() }
        findViewById<Button>(R.id.btnLoad).setOnClickListener { loadSpectrum() }
        findViewById<Button>(R.id.btnApplyCalib).setOnClickListener { applyCalibration() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }

        seekExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvExposure.text = "曝光补偿: ${p - 10}"
                if (fromUser) camera.setExposureCompensation(p - 10)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvGain.text = "增益: $p"
                if (fromUser) camera.setGain(p.toFloat())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        cbTorch.setOnCheckedChangeListener { _, checked -> camera.setTorch(checked) }
    }

    private fun applyParameters() {
        try {
            engine.startX = etStartX.text.toString().toIntOrNull() ?: 0
            engine.endX = etEndX.text.toString().toIntOrNull() ?: 1000
            engine.startY = etStartY.text.toString().toIntOrNull() ?: 400
            engine.sizeY = etSizeY.text.toString().toIntOrNull() ?: 200
            engine.spatialFilterLevel = etSpatialFilter.text.toString().toIntOrNull() ?: 0
            engine.risingSpeed = etRisingSpeed.text.toString().toIntOrNull() ?: 100
            engine.fallingSpeed = etFallingSpeed.text.toString().toIntOrNull() ?: 100
            engine.averageEnabled = cbAverage.isChecked
            engine.averageNumber = maxOf(1, etAverageNumber.text.toString().toIntOrNull() ?: 10)
            engine.flip = cbFlip.isChecked
            engine.useColors = cbColors.isChecked
            spectrumView.showPeaks = cbPeaks.isChecked
            applyCalibration()
        } catch (e: Exception) {
            toast("参数错误: ${e.message}")
        }
    }

    private fun applyCalibration() {
        try {
            val pairs = etCalib.text.toString().split(";").mapNotNull { part ->
                val ab = part.split("|")
                if (ab.size == 2) {
                    val b = ab[0].trim().toDoubleOrNull()
                    val n = ab[1].trim().toDoubleOrNull()
                    if (b != null && n != null) Pair(b, n) else null
                } else null
            }
            if (pairs.size >= 2) {
                engine.setCalibration(
                    pairs.map { it.first }.toDoubleArray(),
                    pairs.map { it.second }.toDoubleArray()
                )
                toast("校准已应用: ${pairs.size} 点")
            } else {
                toast("校准格式: bin|nm;bin|nm")
            }
        } catch (e: Exception) {
            toast("校准错误: ${e.message}")
        }
    }

    private fun startCapture() {
        applyParameters()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(); return
        }
        try {
            camera.onFrame = { y, w, h, stride ->
                // 黑帧检测（抗闪烁）：取中央水平带
                val y0 = h * engine.startY / 1000
                val dy = maxOf(1, h * engine.sizeY / 1000)
                var bright = 0.0
                var count = 0
                for (yy in y0 until minOf(h, y0 + dy) step 8) {
                    for (xx in 0 until w step 16) {
                        bright += (y[yy * stride + xx].toInt() and 0xFF)
                        count++
                    }
                }
                val avgBright = if (count > 0) bright / count else 0.0
                if (avgBright < darkThreshold) {
                    skippedCount++
                    return@onFrame
                }
                validCount++
                lastValidFrame = System.currentTimeMillis()

                engine.yPlaneToReceivedSamples(y, w, h, y0, dy, stride)
                engine.processReceivedSamples()

                scope.launch {
                    spectrumView.invalidate()
                    updateStats()
                }
            }
            camera.openCamera(camera.cameraId)
            toast("采集已开始")
        } catch (e: Exception) {
            toast("启动失败: ${e.message}")
        }
    }

    private fun stopCapture() {
        camera.close()
        toast("采集已停止")
    }

    private fun updateStats() {
        val r = Colorimetry.fromEngine(engine)
        val sb = StringBuilder()
        sb.append("有效帧 $validCount / 跳过 $skippedCount  ")
        sb.append("峰值 ${engine.maxPeakValue.toInt()} @ ")
        sb.append("%.1fnm".format(engine.xToNanometers(engine.maxPeakValueIndex,
            spectrumView.width - 90 - 20, spectrumView.width - 90 - 20)))
        if (r != null) {
            sb.append("  CCT ${r.cct.toInt()}K  Duv %.3f".format(r.duv))
            sb.append("  melEDI ${r.melanopicEdi.toInt()}")
        }
        tvStats.text = sb.toString()
    }

    private fun saveSpectrum() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "spectrum_${System.currentTimeMillis()}.txt")
        }
        startActivityForResult(intent, REQ_SAVE)
    }

    private fun loadSpectrum() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_LOAD)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri: Uri = data.data!!
        try {
            if (requestCode == REQ_SAVE) {
                contentResolver.openOutputStream(uri)?.use { it.write(engine.getSpectrumText().toByteArray()) }
                toast("光谱已保存")
            } else if (requestCode == REQ_LOAD) {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (engine.loadSpectrumText(text)) {
                    spectrumView.invalidate()
                    toast("光谱已载入")
                } else toast("文件格式不对")
            }
        } catch (e: Exception) {
            toast("文件错误: ${e.message}")
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etNmMin = view.findViewById<EditText>(R.id.etNmMin)
        val etNmMax = view.findViewById<EditText>(R.id.etNmMax)
        val etAdcMin = view.findViewById<EditText>(R.id.etAdcMin)
        val etScale = view.findViewById<EditText>(R.id.etScale)
        etNmMin.setText("%.1f".format(engine.nanometersMin))
        etNmMax.setText("%.1f".format(engine.nanometersMax))
        etAdcMin.setText(engine.valueMinSubtractor.toInt().toString())
        etScale.setText(engine.scaleCoeffTo65536.toInt().toString())
        AlertDialog.Builder(this)
            .setTitle("更多设置")
            .setView(view)
            .setPositiveButton("应用") { _, _ ->
                engine.nanometersMin = etNmMin.text.toString().toDoubleOrNull() ?: engine.nanometersMin
                engine.nanometersMax = etNmMax.text.toString().toDoubleOrNull() ?: engine.nanometersMax
                engine.valueMinSubtractor = etAdcMin.text.toString().toFloatOrNull() ?: engine.valueMinSubtractor
                engine.scaleCoeffTo65536 = etScale.text.toString().toFloatOrNull() ?: engine.scaleCoeffTo65536
                spectrumView.invalidate()
                toast("设置已应用")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        camera.close()
    }
}
