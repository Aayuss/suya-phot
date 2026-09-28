package com.suyaphot.app.core.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore

/**
 * Resolves filename conflicts when restoring media into public MediaStore.
 */
class ConflictResolver(private val resolver: ContentResolver) {

    /**
     * Resolves a safe display name that doesn't conflict with existing media in the target relative path.
     * E.g. "IMG_123.jpg" -> "IMG_123 (1).jpg".
     */
    fun resolveName(
        desiredName: String,
        targetRelativePath: String?,
        collectionUri: Uri
    ): String {
        val baseName = desiredName.substringBeforeLast('.')
        val extension = desiredName.substringAfterLast('.', "")
        val dotExt = if (extension.isNotEmpty()) ".$extension" else ""

        var candidate = desiredName
        var counter = 1

        while (existsInMediaStore(candidate, targetRelativePath, collectionUri)) {
            candidate = "$baseName ($counter)$dotExt"
            counter++
        }

        return candidate
    }

    private fun existsInMediaStore(
        displayName: String,
        targetRelativePath: String?,
        collectionUri: Uri
    ): Boolean {
        val selection = if (targetRelativePath != null) {
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        } else {
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        }
        val args = if (targetRelativePath != null) {
            arrayOf(displayName, targetRelativePath)
        } else {
            arrayOf(displayName)
        }

        return try {
            resolver.query(
                collectionUri,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                args,
                null
            )?.use { cursor ->
                cursor.count > 0
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
