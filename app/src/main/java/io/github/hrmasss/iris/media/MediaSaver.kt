package io.github.hrmasss.iris.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes captures through MediaStore, so no storage permission is needed on API 29+. */
object MediaSaver {

    private const val PHOTO_DIR = "Pictures/Iris"
    private const val VIDEO_DIR = "Movies/Iris"

    fun savePhoto(context: Context, bitmap: Bitmap): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IRIS_${stamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused the photo")
        try {
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    class PendingVideo(val uri: Uri, val descriptor: ParcelFileDescriptor)

    fun createVideo(context: Context): PendingVideo {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "IRIS_${stamp()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, VIDEO_DIR)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused the video")
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: run {
            resolver.delete(uri, null, null)
            error("Could not open the video file")
        }
        return PendingVideo(uri, pfd)
    }

    fun finishVideo(context: Context, video: PendingVideo, keep: Boolean) {
        runCatching { video.descriptor.close() }
        val resolver = context.contentResolver
        if (keep) {
            resolver.update(video.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } else {
            resolver.delete(video.uri, null, null)
        }
    }

    /**
     * Newest photo or video Iris saved, or null. Without a media permission MediaStore only
     * returns this app's own files, which is exactly the set we want.
     */
    fun latest(context: Context): Uri? = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to PHOTO_DIR,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI to VIDEO_DIR,
    ).mapNotNull { (collection, dir) -> newestIn(context, collection, dir) }
        .maxByOrNull { it.second }
        ?.first

    private fun newestIn(context: Context, collection: Uri, dir: String): Pair<Uri, Long>? =
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$dir%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) return null
            ContentUris.withAppendedId(collection, c.getLong(0)) to c.getLong(1)
        }

    fun thumbnail(context: Context, uri: Uri): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
