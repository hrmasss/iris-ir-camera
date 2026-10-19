package io.github.hrmasss.iris.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.io.FileDescriptor
import java.util.concurrent.Executor

/**
 * Thin Camera2 wrapper: one camera, a preview, still capture and video.
 * Every Camera2 call runs on a private handler thread; listener calls arrive on that thread too.
 */
class CameraController(context: Context) {

    interface Listener {
        fun onPreviewStarted(info: CameraInfo)
        fun onError(message: String)
    }

    var listener: Listener? = null

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("iris-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var info: CameraInfo? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var renderer: VideoRenderer? = null
    private var zoom = 1f

    val isRecording: Boolean get() = recorder != null

    @SuppressLint("MissingPermission")
    fun open(camera: CameraInfo, texture: SurfaceTexture) = handler.post {
        closeInternal()
        info = camera
        zoom = 1f
        texture.setDefaultBufferSize(camera.previewSize.width, camera.previewSize.height)
        previewSurface = Surface(texture)
        reader = camera.jpegSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 2) }
        try {
            manager.openCamera(camera.id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    startSession(withRecorder = false)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device == camera) device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device == camera) device = null
                    listener?.onError(describeDeviceError(error))
                }
            })
        } catch (e: Exception) {
            listener?.onError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun close() = handler.post { closeInternal() }

    fun release() {
        handler.post {
            closeInternal()
            thread.quitSafely()
        }
    }

    fun setZoom(ratio: Float) = handler.post {
        val camera = info ?: return@post
        zoom = ratio.coerceIn(1f, camera.maxZoom.coerceAtLeast(1f))
        startRepeating(if (recorder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
    }

    /**
     * Captures one still. [onResult] gets the JPEG bytes, or null when this camera has no JPEG
     * output (some IR streams only offer YUV); callers then fall back to the preview frame.
     */
    fun takePhoto(onResult: (ByteArray?) -> Unit) = handler.post {
        val reader = reader
        val session = session
        val device = device
        if (reader == null || session == null || device == null || recorder != null) {
            onResult(null)
            return@post
        }
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                val buffer = image.planes[0].buffer
                onResult(ByteArray(buffer.remaining()).also(buffer::get))
            }
            r.setOnImageAvailableListener(null, null)
        }, handler)
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                previewSurface?.let(::addTarget)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                applyCommon(this)
            }
            session.capture(request.build(), null, handler)
        } catch (e: Exception) {
            reader.setOnImageAvailableListener(null, null)
            onResult(null)
        }
    }

    /**
     * Starts recording. Frames go through [VideoRenderer] so the clip matches the preview;
     * [mirror] keeps front-camera footage mirrored like the preview. [rotation] is the extra
     * rotation for how the phone is held, relative to portrait.
     */
    fun startRecording(output: FileDescriptor, rotation: Int, mirror: Boolean, onStarted: (Boolean) -> Unit) = handler.post {
        val camera = info
        val size = camera?.videoSize
        if (camera == null || size == null || device == null) {
            onStarted(false)
            return@post
        }
        // The renderer outputs upright portrait frames, so a landscape sensor size is swapped.
        val portrait = if (camera.sensorOrientation % 180 != 0) Size(size.height, size.width) else size
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else @Suppress("DEPRECATION") MediaRecorder()
        val newRenderer = try {
            newRecorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(output)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(portrait.width, portrait.height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(size.width * size.height * 6)
                setOrientationHint(rotation)
                prepare()
            }
            VideoRenderer(newRecorder.surface, size, unmirror = camera.facing == Facing.Front && !mirror, handler)
        } catch (e: Exception) {
            newRecorder.release()
            listener?.onError(e.message ?: "Recorder failed")
            onStarted(false)
            return@post
        }
        recorder = newRecorder
        renderer = newRenderer
        startSession(withRecorder = true) { ok ->
            if (ok) {
                runCatching { newRecorder.start() }.onFailure {
                    releaseRecording()
                    startSession(withRecorder = false)
                    onStarted(false)
                    return@startSession
                }
                newRenderer.encoding = true
            } else {
                releaseRecording()
            }
            onStarted(ok)
        }
    }

    /** Stops recording. [onStopped] gets false when the clip was too short to be valid. */
    fun stopRecording(onStopped: (Boolean) -> Unit) = handler.post {
        val current = recorder ?: run {
            onStopped(false)
            return@post
        }
        runCatching { session?.stopRepeating() }
        renderer?.encoding = false
        val ok = runCatching { current.stop() }.isSuccess
        releaseRecording()
        startSession(withRecorder = false)
        onStopped(ok)
    }

    private fun releaseRecording() {
        recorder?.release()
        recorder = null
        renderer?.release()
        renderer = null
    }

    private fun startSession(withRecorder: Boolean, onReady: ((Boolean) -> Unit)? = null) {
        val device = device ?: return
        val preview = previewSurface ?: return
        session?.close()
        session = null
        val surfaces = buildList {
            add(preview)
            if (withRecorder) renderer?.inputSurface?.let(::add) else reader?.surface?.let(::add)
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (this@CameraController.device != device) return
                session = s
                startRepeating(if (withRecorder) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
                info?.let { listener?.onPreviewStarted(it) }
                onReady?.invoke(true)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                listener?.onError("Could not configure the camera session")
                onReady?.invoke(false)
            }
        }
        try {
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    surfaces.map(::OutputConfiguration),
                    executor,
                    callback,
                ),
            )
        } catch (e: Exception) {
            listener?.onError(e.message ?: "Session failed")
            onReady?.invoke(false)
        }
    }

    private fun startRepeating(template: Int) {
        val device = device ?: return
        val session = session ?: return
        try {
            val request = device.createCaptureRequest(template).apply {
                previewSurface?.let(::addTarget)
                if (template == CameraDevice.TEMPLATE_RECORD) renderer?.inputSurface?.let(::addTarget)
                applyCommon(this)
            }
            session.setRepeatingRequest(request.build(), null, handler)
        } catch (e: Exception) {
            listener?.onError(e.message ?: "Preview failed")
        }
    }

    private fun applyCommon(builder: CaptureRequest.Builder) {
        val camera = info ?: return
        if (camera.continuousAf) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (zoom <= 1f) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && camera.zoomRatioRange != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            camera.activeArray?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, cropFor(it, zoom)) }
        }
    }

    private fun cropFor(active: Rect, ratio: Float): Rect {
        val w = (active.width() / ratio).toInt()
        val h = (active.height() / ratio).toInt()
        val left = active.left + (active.width() - w) / 2
        val top = active.top + (active.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun closeInternal() {
        recorder?.let { runCatching { it.stop() } }
        session?.close()
        session = null
        device?.close()
        device = null
        releaseRecording()
        reader?.close()
        reader = null
        previewSurface?.release()
        previewSurface = null
    }

    private fun describeDeviceError(error: Int) = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "camera is in use by another app"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "camera disabled by policy"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "camera device failed"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera service failed"
        else -> "error $error"
    }
}
