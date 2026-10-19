package io.github.hrmasss.iris.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaActionSound
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hrmasss.iris.R
import io.github.hrmasss.iris.camera.CameraCatalog
import io.github.hrmasss.iris.camera.CameraController
import io.github.hrmasss.iris.camera.CameraInfo
import io.github.hrmasss.iris.camera.Facing
import io.github.hrmasss.iris.camera.Look
import io.github.hrmasss.iris.camera.applyTo
import io.github.hrmasss.iris.data.Settings
import io.github.hrmasss.iris.data.SettingsRepository
import io.github.hrmasss.iris.media.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Mode { Photo, Video }

data class CameraUiState(
    val scanning: Boolean = true,
    val cameras: List<CameraInfo> = emptyList(),
    val active: CameraInfo? = null,
    /** True when no IR camera was found and the user has not picked another one yet. */
    val showFallback: Boolean = false,
    val previewing: Boolean = false,
    val look: Look = Look.Natural,
    val mode: Mode = Mode.Photo,
    val zoom: Float = 1f,
    val recordingSince: Long? = null,
    val countdown: Int = 0,
    val busy: Boolean = false,
    val lastUri: Uri? = null,
    val lastThumb: Bitmap? = null,
    val message: String? = null,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)
    val settings: StateFlow<Settings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val controller = CameraController(app).apply {
        listener = object : CameraController.Listener {
            override fun onPreviewStarted(info: CameraInfo) = _state.update { it.copy(previewing = true) }
            override fun onError(message: String) =
                _state.update { it.copy(message = app.getString(R.string.camera_error, message), previewing = false) }
        }
    }
    private val sound = MediaActionSound()

    private var texture: SurfaceTexture? = null
    private var resumed = false
    private var pendingVideo: MediaSaver.PendingVideo? = null
    private var captureJob: Job? = null

    /** Device rotation in degrees from the orientation sensor, set by the UI. */
    var deviceRotation = 0

    /** Supplies the current preview frame when a camera has no JPEG output. */
    var previewFrame: (() -> Bitmap?)? = null

    init {
        rescan()
        viewModelScope.launch { showLatest(null) }
    }

    /** Shows [uri] in the thumbnail, or the newest capture Iris saved earlier when it is null. */
    private suspend fun showLatest(uri: Uri?) {
        val app = getApplication<Application>()
        val target = uri ?: withContext(Dispatchers.IO) { MediaSaver.latest(app) } ?: return
        val thumb = withContext(Dispatchers.IO) { MediaSaver.thumbnail(app, target) }
        _state.update { it.copy(lastUri = target, lastThumb = thumb ?: it.lastThumb) }
    }

    fun rescan() {
        viewModelScope.launch {
            _state.update { it.copy(scanning = true) }
            val cameras = withContext(Dispatchers.IO) { CameraCatalog.scan(getApplication()) }
            val prefs = repo.settings.first()
            val chosen = CameraCatalog.pick(cameras, prefs.preferredCameraId.ifEmpty { null })
            _state.update {
                it.copy(
                    scanning = false,
                    cameras = cameras,
                    active = chosen,
                    showFallback = chosen == null,
                    look = prefs.defaultLook,
                )
            }
            reopen()
        }
    }

    fun choose(camera: CameraInfo) {
        if (_state.value.recordingSince != null) return
        _state.update { it.copy(active = camera, showFallback = false, zoom = 1f, previewing = false) }
        reopen()
    }

    fun showCameraList() = _state.update { it.copy(showFallback = true) }

    fun nextCamera() {
        val s = _state.value
        if (s.cameras.size < 2) return
        val index = s.cameras.indexOf(s.active)
        choose(s.cameras[(index + 1) % s.cameras.size])
    }

    fun setLook(look: Look) = _state.update { it.copy(look = look) }

    fun setMode(mode: Mode) {
        if (_state.value.recordingSince != null) return
        _state.update { it.copy(mode = mode) }
    }

    fun setZoom(ratio: Float) {
        val max = _state.value.active?.maxZoom ?: 1f
        val z = ratio.coerceIn(1f, max.coerceAtLeast(1f))
        _state.update { it.copy(zoom = z) }
        controller.setZoom(z)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun onSurface(surface: SurfaceTexture?) {
        texture = surface
        if (surface == null) controller.close() else reopen()
    }

    fun onResume() {
        resumed = true
        reopen()
    }

    fun onPause() {
        resumed = false
        if (_state.value.recordingSince != null) toggleRecording()
        captureJob?.cancel()
        _state.update { it.copy(countdown = 0, previewing = false) }
        controller.close()
    }

    private fun reopen() {
        val camera = _state.value.active ?: return
        val surface = texture ?: return
        if (!resumed || _state.value.showFallback) return
        _state.update { it.copy(previewing = false) }
        controller.open(camera, surface)
    }

    fun shutter() {
        when (_state.value.mode) {
            Mode.Photo -> takePhoto()
            Mode.Video -> toggleRecording()
        }
    }

    private fun takePhoto() {
        if (_state.value.busy || _state.value.countdown > 0) return
        captureJob = viewModelScope.launch {
            val prefs = settings.value
            for (left in prefs.timerSeconds downTo 1) {
                _state.update { it.copy(countdown = left) }
                delay(1000)
            }
            _state.update { it.copy(countdown = 0, busy = true) }
            if (prefs.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
            val camera = _state.value.active ?: return@launch
            val look = _state.value.look
            val rotation = deviceRotation
            val mirror = prefs.mirrorSelfies && camera.facing == Facing.Front
            controller.takePhoto { jpeg ->
                viewModelScope.launch { finishPhoto(jpeg, camera, look, rotation, prefs.bakeLook, mirror) }
            }
        }
    }

    /** [mirror] saves a front-camera photo mirrored, the way the preview showed it. */
    private suspend fun finishPhoto(jpeg: ByteArray?, camera: CameraInfo, look: Look, rotation: Int, bake: Boolean, mirror: Boolean) {
        val app = getApplication<Application>()
        val fallback = if (jpeg == null) previewFrame?.invoke() else null
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val upright = if (jpeg != null) {
                    val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("Bad JPEG")
                    val rotated = decoded.rotated(sensorToUpright(camera, rotation))
                    if (mirror) rotated.flipped() else rotated
                } else {
                    val frame = fallback ?: error("No frame")
                    // The preview is already upright for a portrait phone and mirrored for front cameras.
                    val keepMirror = mirror || camera.facing != Facing.Front
                    val oriented = if (keepMirror) frame else frame.flipped()
                    oriented.rotated(previewToUpright(camera, rotation, keepMirror))
                }
                val final = if (bake) look.applyTo(upright) else upright
                val uri = withContext(Dispatchers.IO) { MediaSaver.savePhoto(app, final) }
                uri to Bitmap.createScaledBitmap(final, 160, 160 * final.height / final.width.coerceAtLeast(1), true)
            }
        }
        result.onSuccess { (uri, thumb) ->
            _state.update { it.copy(busy = false, lastUri = uri, lastThumb = thumb) }
        }.onFailure { e ->
            _state.update { it.copy(busy = false, message = app.getString(R.string.save_failed, e.message ?: "")) }
        }
    }

    private fun toggleRecording() {
        val app = getApplication<Application>()
        val since = _state.value.recordingSince
        if (since != null) {
            val video = pendingVideo ?: return
            pendingVideo = null
            _state.update { it.copy(recordingSince = null, busy = true) }
            if (settings.value.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
            controller.stopRecording { ok ->
                MediaSaver.finishVideo(app, video, ok)
                _state.update { it.copy(busy = false) }
                if (ok) viewModelScope.launch { showLatest(video.uri) }
            }
            return
        }
        val camera = _state.value.active ?: return
        if (camera.videoSize == null || _state.value.busy) return
        val video = runCatching { MediaSaver.createVideo(app) }.getOrElse { e ->
            _state.update { it.copy(message = app.getString(R.string.save_failed, e.message ?: "")) }
            return
        }
        pendingVideo = video
        _state.update { it.copy(busy = true) }
        val mirror = settings.value.mirrorSelfies
        val keepMirror = mirror || camera.facing != Facing.Front
        controller.startRecording(video.descriptor.fileDescriptor, previewToUpright(camera, deviceRotation, keepMirror), mirror) { ok ->
            if (ok) {
                if (settings.value.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
                _state.update { it.copy(busy = false, recordingSince = SystemClock.elapsedRealtime()) }
            } else {
                pendingVideo = null
                MediaSaver.finishVideo(app, video, keep = false)
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun setPreferredCamera(id: String) = viewModelScope.launch { repo.setPreferredCamera(id) }
    fun setDefaultLook(look: Look) = viewModelScope.launch {
        repo.setDefaultLook(look)
        _state.update { it.copy(look = look) }
    }
    fun setShutterSound(on: Boolean) = viewModelScope.launch { repo.setShutterSound(on) }
    fun setGrid(on: Boolean) = viewModelScope.launch { repo.setGrid(on) }
    fun setTimer(seconds: Int) = viewModelScope.launch { repo.setTimer(seconds) }
    fun setBakeLook(on: Boolean) = viewModelScope.launch { repo.setBakeLook(on) }
    fun setMirrorSelfies(on: Boolean) = viewModelScope.launch { repo.setMirrorSelfies(on) }
    fun setVolumeKeysShoot(on: Boolean) = viewModelScope.launch { repo.setVolumeKeysShoot(on) }

    /** Hardware shutter from the volume keys. Returns true when the key press was used. */
    fun onVolumeKey(): Boolean {
        val s = _state.value
        if (!settings.value.volumeKeysShoot || s.showFallback || s.active == null) return false
        if (s.previewing && !s.busy && s.countdown == 0) shutter()
        return true
    }

    override fun onCleared() {
        controller.release()
        sound.release()
    }

    /**
     * Extra degrees to rotate a preview-oriented frame for the current hold. The preview is upright
     * for a portrait phone; a mirrored frame turns the other way from an unmirrored one.
     */
    private fun previewToUpright(camera: CameraInfo, device: Int, mirrored: Boolean): Int =
        if (camera.facing == Facing.Front && !mirrored) (360 - device) % 360 else device

    /** Degrees to rotate a raw sensor image so it is upright for the current hold. */
    private fun sensorToUpright(camera: CameraInfo, device: Int): Int =
        if (camera.facing == Facing.Front) (camera.sensorOrientation - device + 360) % 360
        else (camera.sensorOrientation + device) % 360

    private fun Bitmap.rotated(degrees: Int): Bitmap =
        if (degrees == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private fun Bitmap.flipped(): Bitmap =
        Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postScale(-1f, 1f) }, true)
}
