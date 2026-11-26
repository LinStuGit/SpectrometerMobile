package com.linstu.spectrometermobile.spectrometer

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Camera2 采集封装 —— 替代 VB 版的 DirectShow SampleGrabber。
 * 支持 USB Camera（通过系统 Camera2），YUV_420_888 / YUY2 取 Y 平面亮度。
 */
class Camera2Capture(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)

    var cameraId: String? = null
    var previewSize: Size = Size(1920, 1080)
        private set
    var supportedSizes: List<Size> = emptyList()
        private set
    var isRunning: Boolean = false
        private set

    /** 每帧回调：Y 平面字节 + 宽高 + stride（直接喂 SpectrometerEngine.yPlaneToReceivedSamples） */
    var onFrame: ((ByteArray, Int, Int, Int) -> Unit)? = null

    fun openCamera(selectedId: String? = null) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList
        if (ids.isEmpty()) throw IllegalStateException("没有可用摄像头")

        cameraId = selectedId?.takeIf { ids.contains(it) } ?: ids.first()
        val characteristics = manager.getCameraCharacteristics(cameraId!!)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("无法读取摄像头能力")

        supportedSizes = map.getOutputSizes(ImageFormat.YUV_420_888).toList()
            .sortedByDescending { it.width * it.height }
        // 优先 1920x1080，否则取最大
        previewSize = supportedSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: supportedSizes.first()

        startBackgroundThread()

        if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("摄像头打开超时")
        }
        try {
            manager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    cameraOpenCloseLock.release()
                    createCaptureSession(map)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            throw e
        }
    }

    private fun createCaptureSession(map: StreamConfigurationMap) {
        val device = cameraDevice ?: return
        imageReader = ImageReader.newInstance(
            previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 3
        )
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                extractYPlane(image)?.let { (y, stride) ->
                    onFrame?.invoke(y, previewSize.width, previewSize.height, stride)
                }
            } finally {
                image.close()
            }
        }, backgroundHandler)

        val surface = imageReader!!.surface
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    // 连续自动对焦/自动曝光（可通过 setParameter 覆盖）
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                }
                session.setRepeatingRequest(request.build(), null, backgroundHandler)
                isRunning = true
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                isRunning = false
            }
        }, backgroundHandler)
    }

    /** 从 YUV_420_888 的 Y 平面取字节；Y 即亮度，省去 RGB 加权 */
    private fun extractYPlane(image: Image): Pair<ByteArray, Int>? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val size = rowStride * image.height
        val out = ByteArray(size)
        buffer.get(out)
        return if (pixelStride == 1) {
            Pair(out, rowStride)
        } else {
            // Y 平面通常是连续打包（pixelStride=1），此处兜底重排
            val compact = ByteArray(image.width * image.height)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    compact[y * image.width + x] = out[y * rowStride + x * pixelStride]
                }
            }
            Pair(compact, image.width)
        }
    }

    /** 设置摄像头参数（对应 VB 的 Form_VideoInControls） */
    fun setParameter(key: CaptureRequest.Key<*>, value: Any) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                val surface = imageReader?.surface ?: return@apply
                addTarget(surface)
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is Int -> set(key as CaptureRequest.Key<Int>, value)
                    is Float -> set(key as CaptureRequest.Key<Float>, value)
                    is Long -> set(key as CaptureRequest.Key<Long>, value)
                    is Boolean -> set(key as CaptureRequest.Key<Boolean>, value)
                    is Byte -> set(key as CaptureRequest.Key<Byte>, value)
                }
            }
            session.setRepeatingRequest(request.build(), null, backgroundHandler)
        } catch (e: Exception) {
            // 参数不被支持时忽略
        }
    }

    /** 曝光补偿（程度，非绝对时间）。范围由设备决定 */
    fun setExposureCompensation(value: Int) =
        setParameter(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, value)

    fun setAutoExposure(on: Boolean) = setParameter(
        CaptureRequest.CONTROL_AE_MODE,
        if (on) CameraMetadata.CONTROL_AE_MODE_ON else CameraMetadata.CONTROL_AE_MODE_OFF
    )

    fun setExposureTime(nanos: Long) = setParameter(CaptureRequest.SENSOR_EXPOSURE_TIME, nanos)

    fun setGain(value: Float) = setParameter(CaptureRequest.SENSOR_SENSITIVITY, value.toInt())

    /** 切换闪光灯（补光），某些 USB Camera 有效 */
    fun setTorch(on: Boolean) = setParameter(CaptureRequest.FLASH_MODE,
        if (on) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)

    fun close() {
        isRunning = false
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        imageReader = null
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** 列出所有摄像头的 ID + 名称前缀（供 UI 选择 USB Camera） */
        fun listCameras(context: Context): List<CameraInfo> {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return manager.cameraIdList.map { id ->
                val c = manager.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    else -> "外置/USB"
                }
                CameraInfo(id, "$facing ($id)")
            }
        }

        data class CameraInfo(val id: String, val label: String)
    }
}
