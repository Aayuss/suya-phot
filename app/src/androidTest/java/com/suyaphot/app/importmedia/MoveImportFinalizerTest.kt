package com.suyaphot.app.importmedia

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.database.entity.VaultJobEntity
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.JobType
import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.core.model.SourceDisposition
import com.suyaphot.app.domain.importmedia.ImportResult
import com.suyaphot.app.domain.importmedia.MoveImportFinalizer
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoveImportFinalizerTest {

    @Test
    fun directDeletionMarksEveryMoveDeletedOnlyAfterVaultSuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        try {
            db.vaultDao().insert(
                VaultEntity(
                    id = "vault",
                    kindCode = 0,
                    createdAt = 1L,
                    schemaVersion = 6,
                    pinEnvelope = byteArrayOf(1)
                )
            )

            val uris = listOf(
                Uri.parse("content://media/external/images/media/11"),
                Uri.parse("content://media/external/images/media/12")
            )

            val successes = uris.mapIndexed { index, uri ->
                val jobId = "job_$index"
                db.vaultJobDao().insert(
                    VaultJobEntity(
                        id = jobId,
                        vaultId = "vault",
                        typeCode = JobType.IMPORT.code,
                        stateCode = JobState.AWAITING_SOURCE_DELETE.code,
                        encryptedPayload = byteArrayOf(1),
                        progressCurrent = 1L,
                        progressTotal = 1L,
                        createdAt = 1L,
                        updatedAt = 1L,
                        errorCode = null,
                        sourceDispositionCode = SourceDisposition.PENDING_DELETE.code
                    )
                )
                ImportResult.Success(
                    jobId = jobId,
                    itemId = "item_$index",
                    uri = uri,
                    sha256Hex = "00".repeat(32),
                    mode = ImportMode.MOVE
                )
            }

            val deleted = mutableListOf<Uri>()
            val sourceDeletion = SourceDeletionCoordinator(
                context = context,
                deleteUri = { uri ->
                    deleted += uri
                    1
                },
                probeAbsent = { true }
            )

            val finalizer = MoveImportFinalizer(
                database = db,
                sourceDeletionCoordinator = sourceDeletion
            )

            val step = finalizer.begin("vault", successes)
            assertTrue(step is MoveImportFinalizer.Step.Completed)
            step as MoveImportFinalizer.Step.Completed
            assertEquals(2, step.deletedCount)
            assertEquals(0, step.retainedCount)
            assertEquals(0, step.failedCount)
            assertEquals(uris, deleted)

            successes.forEach { success ->
                val job = db.vaultJobDao().getById(success.jobId)
                requireNotNull(job)
                assertEquals(JobState.COMPLETED.code, job.stateCode)
                assertEquals(SourceDisposition.DELETED.code, job.sourceDispositionCode)
                assertEquals(null, job.errorCode)
            }
        } finally {
            db.close()
        }
    }
}
