package com.suyaphot.app.core.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.suyaphot.app.core.model.MediaType

/**
 * Resolves arbitrary media URIs (Photo Picker, Google Photos ContentProvider,
 * DocumentsProvider, generic file-sharing providers) to canonical MediaStore URIs.
 */
object CanonicalMediaResolver {

    fun resolveCanonicalMediaStoreUri(
        context: Context,
        rawUri: Uri,
        mimeType: String? = null
    ): Uri? {
        val scheme = rawUri.scheme ?: return null
        if (scheme != "content") return null

        val authority = rawUri.authority ?: return null

        // 1. Direct MediaStore URI or Photo Picker
        if (authority == MediaStore.AUTHORITY) {
            val segments = rawUri.pathSegments
            val isPicker = segments.size >= 4 &&
                (segments[0] == "picker" || segments[0] == "picker_get_content")
            if (!isPicker) {
                return rawUri
            }

            // Photo Picker URI: extract local ID if from a local provider
            if (isLocalPhotoPickerProvider(segments)) {
                val pickerLocalId = segments.lastOrNull()?.toLongOrNull()?.takeIf { it > 0L }
                if (pickerLocalId != null) {
                    val isVideo = isVideoHeuristic(rawUri, mimeType)
                    val collection = if (isVideo) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                    return ContentUris.withAppendedId(collection, pickerLocalId)
                }
            }
            return null
        }

        // 2. Google Photos ContentProvider URI (contains embedded URL-encoded MediaStore URI)
        if (authority == "com.google.android.apps.photos.contentprovider") {
            val decoded = runCatching { Uri.decode(rawUri.toString()) }.getOrDefault(rawUri.toString())
            val regex = Regex("""content://media/(?:external|external_primary)/(images|video)/media/(\d+)""")
            val match = regex.find(decoded)
            if (match != null) {
                val (type, idStr) = match.destructured
                val id = idStr.toLongOrNull()
                if (id != null && id > 0L) {
                    val collection = if (type == "video") {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                    return ContentUris.withAppendedId(collection, id)
                }
            }
        }

        // 3. Storage Access Framework / DocumentsProvider URI
        if (runCatching { DocumentsContract.isDocumentUri(context, rawUri) }.getOrDefault(false)) {
            val docMediaUri = runCatching { MediaStore.getMediaUri(context, rawUri) }.getOrNull()
            if (docMediaUri != null && docMediaUri.authority == MediaStore.AUTHORITY) {
                return docMediaUri
            }
            val docId = runCatching { DocumentsContract.getDocumentId(rawUri) }.getOrNull()
            if (docId != null) {
                val idStr = docId.substringAfterLast(':')
                val id = idStr.toLongOrNull()
                if (id != null && id > 0L) {
                    val isVideo = docId.startsWith("video:") || isVideoHeuristic(rawUri, mimeType)
                    val collection = if (isVideo) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                    return ContentUris.withAppendedId(collection, id)
                }
            }
        }

        // 4. Catalog fallback: Match by displayName & size in MediaStore
        return findInCatalog(context, rawUri, mimeType)
    }

    fun isLocalPhotoPickerProvider(segments: List<String>): Boolean {
        val provider = if (segments.size >= 5) segments[2] else if (segments.size >= 4) segments[1] else null
        if (provider == null || provider == "media") return true
        if (provider.startsWith("com.example.") || provider.contains("cloud") || provider.contains("drive")) {
            return false
        }
        return provider == "com.android.providers.media.photopicker" ||
            provider == "com.google.android.providers.media.module" ||
            provider == "com.android.providers.media" ||
            provider.endsWith(".providers.media.photopicker") ||
            provider.endsWith(".providers.media.module") ||
            provider.endsWith(".providers.media")
    }

    fun isVideoHeuristic(uri: Uri, mimeType: String?): Boolean {
        if (mimeType?.startsWith("video/") == true) return true
        val name = uri.lastPathSegment?.lowercase() ?: return false
        return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov") ||
            name.endsWith(".3gp") || name.endsWith(".webm") || name.endsWith(".avi")
    }

    private fun findInCatalog(context: Context, rawUri: Uri, mimeType: String?): Uri? {
        val (displayName, size) = queryDisplayNameAndSize(context, rawUri)
        val name = displayName?.takeIf { it.isNotBlank() } ?: return null
        val isVideo = isVideoHeuristic(rawUri, mimeType)

        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )

        return runCatching {
            val selection = if (size > 0L) {
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
            } else {
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            }
            val selectionArgs = if (size > 0L) arrayOf(name, size.toString()) else arrayOf(name)

            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                var bestId: Long? = null
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val relPath = if (pathCol != -1 && !cursor.isNull(pathCol)) cursor.getString(pathCol) else ""
                    if (!relPath.contains("Suya Phot Restored")) {
                        bestId = id
                        break
                    } else if (bestId == null) {
                        bestId = id
                    }
                }
                bestId?.let { id ->
                    val base = if (isVideo) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                    ContentUris.withAppendedId(base, id)
                }
            }
        }.getOrNull()
    }

    private fun queryDisplayNameAndSize(context: Context, uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size: Long = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameCol != -1 && !cursor.isNull(nameCol)) {
                        name = cursor.getString(nameCol)
                    }
                    val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeCol != -1 && !cursor.isNull(sizeCol)) {
                        size = cursor.getLong(sizeCol)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.lastPathSegment?.substringAfterLast('/')
        }
        return Pair(name, size)
    }
}
