package com.suyaphot.app.importmedia

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.SensitiveKeyHandle
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.database.entity.VaultJobEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.JobType
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.model.SourceDisposition
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.importmedia.ImportResult
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
import com.suyaphot.app.domain.importmedia.VaultMoveFinalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultMoveFinalizerTest {

    @Test
    fun committedMoveUsesCanonicalMediaStoreUriAndMarksDeletedOnlyAfterDeleteSucceeds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val session = SessionManager(SecurityPreferences(context))
        val vaultId = "vault_move_test"
        val itemId = "item_move_test"
        val jobId = "job_move_test"
        val metaKey = ByteArray(32) { 0x22 }
        val capturedDeletes = mutableListOf<Uri>()

        try {
            db.vaultDao().insert(
                VaultEntity(
                    id = vaultId,
                    kindCode = VaultKind.REAL.code,
                    createdAt = 1,
                    schemaVersion = 6,
                    pinEnvelope = byteArrayOf(1)
                )
            )

            val metadataPlain = PrivateMediaMetadata(
                originalDisplayName = "photo.jpg",
                originalRelativePath = "DCIM/Camera/",
                originalMimeType = "image/jpeg",
                originalContentUri = "content://media/picker/0/com.android.providers.media.photopicker/media/123",
                dateTakenMs = null,
                dateModifiedMs = null,
                width = 100,
                height = 100,
                durationMs = null,
                orientation = 1,
                sourceVolume = "external_primary",
                sourceMediaStoreId = 123L,
                gpsWasAvailable = false,
                originalFileExtension = "jpg"
            ).serialize()

            val encryptedMetadata = try {
                Aead.encryptWithPrependedNonce(
                    metaKey,
                    metadataPlain,
                    itemId.toByteArray(Charsets.UTF_8)
                )
            } finally {
                metadataPlain.fill(0)
            }

            db.mediaItemDao().insert(
                MediaItemEntity(
                    id = itemId,
                    vaultId = vaultId,
                    folderId = null,
                    mediaTypeCode = 0,
                    encryptedMetadata = encryptedMetadata,
                    encryptedFileRelativePath = "$itemId.sph",
                    encryptedThumbRelativePath = null,
                    plaintextSize = 10,
                    cipherSize = 20,
                    sha256Hex = "aa",
                    importedAt = 1,
                    updatedAt = 1,
                    favorite = false,
                    deletedAt = null,
                    previousFolderId = null
                )
            )

            db.vaultJobDao().insert(
                VaultJobEntity(
                    id = jobId,
                    vaultId = vaultId,
                    typeCode = JobType.IMPORT.code,
                    stateCode = JobState.AWAITING_SOURCE_DELETE.code,
                    encryptedPayload = byteArrayOf(1),
                    progressCurrent = 10,
                    progressTotal = 10,
                    createdAt = 1,
                    updatedAt = 1,
                    errorCode = null,
                    sourceDispositionCode = SourceDisposition.PENDING_DELETE.code
                )
            )

            session.unlock(
                vaultId = vaultId,
                kind = VaultKind.REAL,
                masterKeyHandle = SensitiveKeyHandle(ByteArray(32) { 0x11 }),
                mediaSubkey = ByteArray(32) { 0x21 },
                metaSubkey = metaKey.copyOf(),
                thumbSubkey = ByteArray(32) { 0x23 }
            )

            val sourceDeletion = SourceDeletionCoordinator(
                context = context,
                deleteUri = { uri ->
                    capturedDeletes += uri
                    1
                },
                probeAbsent = { false }
            )
            val finalizer = VaultMoveFinalizer(db, sourceDeletion, session)
            val pickerUri = Uri.parse(
                "content://media/picker/0/com.android.providers.media.photopicker/media/123"
            )
            val result = finalizer.begin(
                listOf(
                    ImportResult.Success(
                        jobId = jobId,
                        itemId = itemId,
                        uri = pickerUri,
                        sha256Hex = "aa",
                        alreadyExisted = false,
                        mode = ImportMode.MOVE
                    )
                )
            )

            assertTrue(result is VaultMoveFinalizer.Result.Completed)
            val summary = (result as VaultMoveFinalizer.Result.Completed).summary
            assertEquals(1, summary.deletedCount)
            assertEquals(0, summary.retainedCount)
            assertEquals(
                Uri.parse("content://media/external_primary/images/media/123"),
                capturedDeletes.single()
            )

            val updatedJob = db.vaultJobDao().getJob(jobId)!!
            assertEquals(JobState.COMPLETED.code, updatedJob.stateCode)
            assertEquals(SourceDisposition.DELETED.code, updatedJob.sourceDispositionCode)
            assertEquals(null, updatedJob.errorCode)
        } finally {
            session.lock(com.suyaphot.app.domain.auth.LockReason.Explicit)
            metaKey.fill(0)
            db.close()
        }
    }
}
