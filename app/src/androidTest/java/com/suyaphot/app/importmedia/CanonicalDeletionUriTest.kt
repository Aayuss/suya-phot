package com.suyaphot.app.importmedia

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.domain.importmedia.canonicalDeletionUri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanonicalDeletionUriTest {

    private fun metadata(
        volume: String?,
        id: Long?
    ) = PrivateMediaMetadata(
        originalDisplayName = "photo.jpg",
        originalRelativePath = "DCIM/Camera/",
        originalMimeType = "image/jpeg",
        originalContentUri = "content://media/picker/0/com.android.providers.media.photopicker/media/42",
        dateTakenMs = null,
        dateModifiedMs = null,
        width = 100,
        height = 100,
        durationMs = null,
        orientation = 1,
        sourceVolume = volume,
        sourceMediaStoreId = id,
        gpsWasAvailable = false,
        originalFileExtension = "jpg"
    )

    @Test
    fun imagePickerUriResolvesToCanonicalMediaStoreRowForDeletion() {
        val fallback = Uri.parse(
            "content://media/picker/0/com.android.providers.media.photopicker/media/42"
        )

        val resolved = canonicalDeletionUri(
            fallback = fallback,
            mediaType = MediaType.IMAGE,
            metadata = metadata("external_primary", 42L)
        )

        assertEquals(
            "content://media/external_primary/images/media/42",
            resolved.toString()
        )
    }

    @Test
    fun videoPickerUriResolvesToCanonicalMediaStoreRowForDeletion() {
        val fallback = Uri.parse("content://media/picker/video/99")
        val meta = metadata("external_primary", 99L).copy(
            originalDisplayName = "clip.mp4",
            originalMimeType = "video/mp4",
            originalFileExtension = "mp4"
        )

        val resolved = canonicalDeletionUri(
            fallback = fallback,
            mediaType = MediaType.VIDEO,
            metadata = meta
        )

        assertEquals(
            "content://media/external_primary/video/media/99",
            resolved.toString()
        )
    }

    @Test
    fun missingMediaStoreIdentityFallsBackToOriginalUri() {
        val fallback = Uri.parse("content://example.provider/document/abc")

        assertEquals(
            fallback,
            canonicalDeletionUri(
                fallback = fallback,
                mediaType = MediaType.IMAGE,
                metadata = metadata(null, null)
            )
        )
    }
}
