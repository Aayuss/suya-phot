package com.suyaphot.app.core.media

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.util.SafeLog
import java.io.InputStream

/**
 * Extracts comprehensive metadata from content URIs and media streams with fidelity.
 */
class MetadataReader(private val context: Context) {

    data class ExtractedSourceMetadata(
        val mediaType: MediaType,
        val size: Long,
        val metadata: PrivateMediaMetadata
    )

    fun read(rawUri: Uri): ExtractedSourceMetadata {
        val resolver = context.contentResolver
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && rawUri.authority == MediaStore.AUTHORITY) {
            runCatching { MediaStore.setRequireOriginal(rawUri) }.getOrDefault(rawUri)
        } else {
            rawUri
        }

        var displayName: String? = null
        var relPath: String? = null
        var mimeType: String? = resolver.getType(uri)
        var size: Long = -1L
        var dateTaken: Long? = null
        var dateModified: Long? = null
        var width: Int? = null
        var height: Int? = null
        var orientation: Int? = null
        var duration: Long? = null
        var mediaStoreId: Long? = null
        var volume: String? = null

        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        ).apply {
            add(MediaStore.MediaColumns.DATE_TAKEN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.VOLUME_NAME)
            }
        }.toTypedArray()

        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (idCol != -1) mediaStoreId = cursor.getLong(idCol)

                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameCol != -1) displayName = cursor.getString(nameCol)

                    val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    if (mimeCol != -1 && mimeType == null) mimeType = cursor.getString(mimeCol)

                    val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    if (pathCol != -1) relPath = cursor.getString(pathCol)

                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeCol != -1) size = cursor.getLong(sizeCol)

                    val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    if (modCol != -1) {
                        val sec = cursor.getLong(modCol)
                        if (sec > 0) dateModified = sec * 1000L
                    }

                    val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    if (takenCol != -1) {
                        val ms = cursor.getLong(takenCol)
                        if (ms > 0) dateTaken = ms
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val volCol = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                        if (volCol != -1) volume = cursor.getString(volCol)
                    }
                }
            }
        } catch (e: Exception) {
            SafeLog.w("MetadataReader", "Failed querying MediaColumns for uri: $uri", e)
        }

        // Fallback for displayName
        val finalDisplayName = displayName ?: uri.lastPathSegment ?: "media_${System.currentTimeMillis()}"
        val finalMimeType = mimeType ?: inferMimeTypeFromExtension(finalDisplayName)
        val isVideo = finalMimeType.startsWith("video/")

        // Determine actual size if query returned -1
        if (size <= 0) {
            try {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (ignored: Exception) {}
        }

        // Additional metadata via ExifInterface for images
        val additional = mutableMapOf<String, String>()
        var gpsAvailable = false

        if (!isVideo) {
            try {
                resolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    gpsAvailable = exif.latLong != null

                    val exifDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    if (exifDate != null) {
                        additional["ExifDate"] = exifDate
                    }

                    exif.getAttribute(ExifInterface.TAG_MAKE)?.let { additional["CameraMake"] = it }
                    exif.getAttribute(ExifInterface.TAG_MODEL)?.let { additional["CameraModel"] = it }
                    exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { additional["FocalLength"] = it }
                    exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { additional["ExposureTime"] = it }
                    exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { additional["FNumber"] = it }
                    exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { additional["ISO"] = it }

                    val exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    orientation = exifOrientation

                    val imgWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val imgHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    if (imgWidth > 0 && imgHeight > 0) {
                        width = imgWidth
                        height = imgHeight
                    }
                }
            } catch (e: Exception) {
                SafeLog.w("MetadataReader", "Error reading EXIF data", e)
            }
        } else {
            // Video attributes via MediaMetadataRetriever
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                orientation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let {
                    additional["VideoDate"] = it
                }
                retriever.release()
            } catch (e: Exception) {
                SafeLog.w("MetadataReader", "Error reading video metadata", e)
            }
        }

        val extension = finalDisplayName.substringAfterLast('.', "")

        val metadata = PrivateMediaMetadata(
            originalDisplayName = finalDisplayName,
            originalRelativePath = relPath,
            originalMimeType = finalMimeType,
            originalContentUri = uri.toString(),
            dateTakenMs = dateTaken ?: dateModified,
            dateModifiedMs = dateModified,
            width = width,
            height = height,
            durationMs = duration,
            orientation = orientation,
            sourceVolume = volume,
            sourceMediaStoreId = mediaStoreId,
            gpsWasAvailable = gpsAvailable,
            originalFileExtension = extension.ifEmpty { null },
            additional = additional
        )

        return ExtractedSourceMetadata(
            mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
            size = size,
            metadata = metadata
        )
    }

    private fun inferMimeTypeFromExtension(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        val mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (mapped != null) return mapped
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            else -> "application/octet-stream"
        }
    }
}
