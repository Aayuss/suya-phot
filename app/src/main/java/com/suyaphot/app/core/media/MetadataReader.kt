package com.suyaphot.app.core.media

import android.content.ContentResolver
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import androidx.core.content.ContextCompat
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.util.SafeLog
import java.text.SimpleDateFormat
import java.util.Locale

data class ResolvedMediaSource(val sourceUri: Uri, val readUri: Uri)

class UnsupportedMediaException : IllegalArgumentException("Only image and video media are supported")

/**
 * Extracts comprehensive metadata from content URIs and media streams with fidelity.
 */
class MetadataReader(private val context: Context) {

    data class ExtractedSourceMetadata(
        val mediaType: MediaType,
        val size: Long,
        val metadata: PrivateMediaMetadata
    )

    fun resolve(rawUri: Uri): ResolvedMediaSource {
        val canReadOriginal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        // Android Photo Picker URIs are mediated by the picker even though their authority is
        // "media". Its provider rejects ?requireOriginal=1, including after permission grant.
        val isPickerUri = rawUri.pathSegments.firstOrNull() == "picker"
        val readUri = if (rawUri.authority == MediaStore.AUTHORITY && !isPickerUri && canReadOriginal) {
            runCatching { MediaStore.setRequireOriginal(rawUri) }.getOrDefault(rawUri)
        } else {
            rawUri
        }
        return ResolvedMediaSource(rawUri, readUri)
    }

    fun read(rawUri: Uri): ExtractedSourceMetadata = read(resolve(rawUri))

    fun read(source: ResolvedMediaSource): ExtractedSourceMetadata {
        val resolver = context.contentResolver
        val uri = source.readUri

        var displayName: String? = null
        var relPath: String? = null
        var mimeType: String? = resolver.getType(uri)
        var size: Long = -1L
        var dateTaken: Long? = null
        var dateModified: Long? = null
        var dateAdded: Long? = null
        var width: Int? = null
        var height: Int? = null
        var orientation: Int? = null
        var duration: Long? = null
        var mediaStoreId: Long? = null
        var volume: String? = null

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.VOLUME_NAME
        )

        // If source is a local Photo Picker URI, attempt querying the underlying MediaStore row first
        val pickerLocalId = run {
            val segments = source.sourceUri.pathSegments
            val isPicker = source.sourceUri.authority == MediaStore.AUTHORITY &&
                segments.size >= 4 &&
                (segments[0] == "picker" || segments[0] == "picker_get_content") &&
                segments.getOrNull(segments.size - 2) == "media"
            if (isPicker) segments.lastOrNull()?.toLongOrNull()?.takeIf { it > 0L } else null
        }

        if (pickerLocalId != null) {
            mediaStoreId = pickerLocalId
            val isVideoGuess = mimeType?.startsWith("video/") == true ||
                uri.lastPathSegment?.lowercase()?.let {
                    it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".mov") || it.endsWith(".3gp")
                } == true
            val canonicalCollection = if (isVideoGuess) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val canonicalUri = android.content.ContentUris.withAppendedId(canonicalCollection, pickerLocalId)

            try {
                resolver.query(canonicalUri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        if (nameCol != -1 && !cursor.isNull(nameCol)) displayName = cursor.getString(nameCol)

                        val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                        if (mimeCol != -1 && !cursor.isNull(mimeCol) && mimeType == null) mimeType = cursor.getString(mimeCol)

                        val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        if (pathCol != -1 && !cursor.isNull(pathCol)) relPath = cursor.getString(pathCol)

                        val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        if (sizeCol != -1 && !cursor.isNull(sizeCol)) size = cursor.getLong(sizeCol)

                        val addCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                        if (addCol != -1 && !cursor.isNull(addCol)) {
                            val sec = cursor.getLong(addCol)
                            if (sec > 0L) dateAdded = sec
                        }

                        val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                        if (modCol != -1 && !cursor.isNull(modCol)) {
                            val sec = cursor.getLong(modCol)
                            if (sec > 0L) dateModified = sec * 1000L
                        }

                        val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                        if (takenCol != -1 && !cursor.isNull(takenCol)) {
                            val ms = cursor.getLong(takenCol)
                            if (ms > 0L) dateTaken = ms
                        }

                        val volCol = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                        if (volCol != -1 && !cursor.isNull(volCol)) volume = cursor.getString(volCol)
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (idCol != -1 && !cursor.isNull(idCol)) {
                        val rowId = cursor.getLong(idCol)
                        if (rowId > 0L && mediaStoreId == null) {
                            mediaStoreId = rowId
                        }
                    }

                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameCol != -1 && !cursor.isNull(nameCol) && displayName == null) {
                        displayName = cursor.getString(nameCol)
                    }

                    val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    if (mimeCol != -1 && !cursor.isNull(mimeCol) && mimeType == null) {
                        mimeType = cursor.getString(mimeCol)
                    }

                    val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    if (pathCol != -1 && !cursor.isNull(pathCol) && relPath == null) {
                        relPath = cursor.getString(pathCol)
                    }

                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeCol != -1 && !cursor.isNull(sizeCol) && size <= 0L) {
                        size = cursor.getLong(sizeCol)
                    }

                    val addCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    if (addCol != -1 && !cursor.isNull(addCol) && dateAdded == null) {
                        val sec = cursor.getLong(addCol)
                        if (sec > 0L) dateAdded = sec
                    }

                    val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    if (modCol != -1 && !cursor.isNull(modCol) && dateModified == null) {
                        val sec = cursor.getLong(modCol)
                        if (sec > 0L) dateModified = sec * 1000L
                    }

                    val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    if (takenCol != -1 && !cursor.isNull(takenCol) && dateTaken == null) {
                        val ms = cursor.getLong(takenCol)
                        if (ms > 0L) dateTaken = ms
                    }

                    val volCol = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                    if (volCol != -1 && !cursor.isNull(volCol) && volume == null) {
                        volume = cursor.getString(volCol)
                    }
                }
            }
        } catch (e: Exception) {
            SafeLog.w("MetadataReader", "Source metadata query failed")
        }

        // Fallback for displayName
        val finalDisplayName = displayName ?: uri.lastPathSegment ?: "media_${System.currentTimeMillis()}"
        val finalMimeType = mimeType ?: inferMimeTypeFromExtension(finalDisplayName)
        val mediaType = when {
            finalMimeType.startsWith("image/") -> MediaType.IMAGE
            finalMimeType.startsWith("video/") -> MediaType.VIDEO
            else -> throw UnsupportedMediaException()
        }
        val isVideo = mediaType == MediaType.VIDEO

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
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    if (exifDate != null) {
                        additional["ExifDate"] = exifDate
                        if (dateTaken == null) {
                            dateTaken = parseDateStringToEpochMs(exifDate)
                        }
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
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                orientation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                val videoDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                if (videoDate != null) {
                    additional["VideoDate"] = videoDate
                    if (dateTaken == null) {
                        dateTaken = parseDateStringToEpochMs(videoDate)
                    }
                }
            } catch (e: Exception) {
                SafeLog.w("MetadataReader", "Error reading video metadata", e)
            } finally {
                runCatching { retriever.release() }
            }
        }

        val extension = finalDisplayName.substringAfterLast('.', "")

        // Full date reconciliation across all date sources
        val finalDateTaken = dateTaken
            ?: dateModified
            ?: (dateAdded?.let { it * 1000L })
            ?: System.currentTimeMillis()

        val finalDateModified = dateModified
            ?: dateTaken
            ?: (dateAdded?.let { it * 1000L })
            ?: System.currentTimeMillis()

        val finalDateAdded = dateAdded
            ?: (finalDateModified / 1000L)

        additional["dateAddedSec"] = finalDateAdded.toString()
        additional["dateModifiedMs"] = finalDateModified.toString()
        additional["dateTakenMs"] = finalDateTaken.toString()
        relPath?.let { additional["originalRelativePath"] = it }
        finalDisplayName.let { additional["originalDisplayName"] = it }

        val metadata = PrivateMediaMetadata(
            originalDisplayName = finalDisplayName,
            originalRelativePath = relPath,
            originalMimeType = finalMimeType,
            originalContentUri = uri.toString(),
            dateTakenMs = finalDateTaken,
            dateModifiedMs = finalDateModified,
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
            mediaType = mediaType,
            size = size,
            metadata = metadata
        )
    }

    private fun parseDateStringToEpochMs(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        val formats = arrayOf(
            "yyyy:MM:dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss",
            "yyyyMMddHHmmss"
        )
        for (format in formats) {
            val epoch = runCatching {
                val sdf = SimpleDateFormat(format, Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                sdf.parse(dateStr.trim())?.time
            }.getOrNull()
            if (epoch != null && epoch > 0L) return epoch
        }
        return null
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
