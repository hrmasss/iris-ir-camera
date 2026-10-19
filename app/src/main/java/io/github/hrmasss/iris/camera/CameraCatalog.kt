package io.github.hrmasss.iris.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size

enum class SensorKind { NearInfrared, Monochrome, Color, Unknown }

enum class Facing { Front, Back, External, Unknown }

data class CameraInfo(
    val id: String,
    val facing: Facing,
    val sensorKind: SensorKind,
    /** False when the id is openable but missing from [CameraManager.getCameraIdList]. */
    val listed: Boolean,
    val sensorOrientation: Int,
    val previewSize: Size,
    val jpegSize: Size?,
    val videoSize: Size?,
    val maxZoom: Float,
    val zoomRatioRange: Range<Float>?,
    val activeArray: android.graphics.Rect?,
    val continuousAf: Boolean,
    val irScore: Int,
) {
    val isIr: Boolean get() = irScore >= IR_THRESHOLD

    companion object {
        const val IR_THRESHOLD = 50
    }
}

/**
 * Finds every camera the phone lets us open and ranks how likely each one is the infrared
 * face-unlock sensor.
 *
 * Qualcomm ROMs often leave the IR camera out of the public id list but still open it by id,
 * which is why ids are probed directly instead of trusting the list.
 */
object CameraCatalog {

    /** Highest numeric id probed beyond the public list. */
    private const val PROBE_LIMIT = 16

    /**
     * Devices where the IR camera is known to sit at a fixed id on AOSP-based ROMs.
     * Other ROMs can number cameras differently, which the scan still handles.
     */
    private val knownIrIds = mapOf(
        "beryllium" to "5", // Poco F1
        "dipper" to "5",    // Mi 8
        "ursa" to "5",      // Mi 8 Explorer
    )

    fun scan(context: Context): List<CameraInfo> {
        val manager = context.getSystemService(CameraManager::class.java)
        val listed = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val probed = (0 until PROBE_LIMIT).map(Int::toString)
        return (listed + probed).distinct()
            .mapNotNull { id -> describe(manager, id, id in listed) }
            .sortedWith(compareByDescending<CameraInfo> { it.irScore }.thenBy { !it.listed }.thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE })
    }

    fun pick(cameras: List<CameraInfo>, preferredId: String?): CameraInfo? =
        cameras.firstOrNull { it.id == preferredId } ?: cameras.firstOrNull { it.isIr }

    private fun describe(manager: CameraManager, id: String, listed: Boolean): CameraInfo? {
        val chars = try {
            manager.getCameraCharacteristics(id)
        } catch (_: Exception) {
            return null
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (previewSizes.isEmpty()) return null

        val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> Facing.Front
            CameraMetadata.LENS_FACING_BACK -> Facing.Back
            CameraMetadata.LENS_FACING_EXTERNAL -> Facing.External
            else -> Facing.Unknown
        }
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val sensorKind = when {
            chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ==
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> SensorKind.NearInfrared
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME in capabilities ||
                chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ==
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> SensorKind.Monochrome
            chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) != null -> SensorKind.Color
            else -> SensorKind.Unknown
        }

        var score = 0
        if (sensorKind == SensorKind.NearInfrared) score += 100
        if (knownIrIds[Build.DEVICE] == id) score += 80
        if (sensorKind == SensorKind.Monochrome && facing == Facing.Front) score += 50
        if (!listed && facing == Facing.Front && sensorKind != SensorKind.Color) score += 20

        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }
        val maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

        val previewSize = choosePreview(previewSizes)
        return CameraInfo(
            id = id,
            facing = facing,
            sensorKind = sensorKind,
            listed = listed,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            previewSize = previewSize,
            jpegSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.area },
            videoSize = chooseVideo(map.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty(), previewSize),
            maxZoom = zoomRange?.upper ?: maxDigitalZoom,
            zoomRatioRange = zoomRange,
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            continuousAf = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true,
            irScore = score,
        )
    }

    /** Largest 4:3 size that is cheap to preview, else the largest size up to 1080p. */
    private fun choosePreview(sizes: List<Size>): Size {
        val modest = sizes.filter { it.width <= 1920 && it.height <= 1440 }.ifEmpty { sizes }
        return modest.filter { it.width * 3 == it.height * 4 }.maxByOrNull { it.area }
            ?: modest.maxBy { it.area }
    }

    /**
     * Video frames the same shape as the preview, so a clip shows what the screen showed.
     * The short side is capped at 1080 to stay inside common H.264 encoder levels.
     */
    private fun chooseVideo(sizes: List<Size>, preview: Size): Size? {
        val fits = sizes.filter { minOf(it.width, it.height) <= 1080 && maxOf(it.width, it.height) <= 1920 }
        return fits.filter { it.width * preview.height == it.height * preview.width }.maxByOrNull { it.area }
            ?: fits.maxByOrNull { it.area }
    }

    private val Size.area: Int get() = width * height
}
