package com.suyaphot.app.database

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.database.entity.VaultJobEntity
import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.JobType
import com.suyaphot.app.core.model.SourceDisposition
import com.suyaphot.app.domain.importmedia.ImportResult
import com.suyaphot.app.domain.importmedia.MoveImportFinalizer
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoveImportFinalizerTest {

    private lateinit var db: SuyaDatabase
    private val vaultId = "vault_move_finalize"

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        db.vaultDao().insert(
            VaultEntity(
                id = vaultId,
                kindCode = 0,
                createdAt = 1L,
                schemaVersion = 6,
                pinEnvelope = ByteArray(16)
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertAwaitingJob(id: String) {
        db.vaultJobDao().insert(
            VaultJobEntity(
                id = id,
                vaultId = vaultId,
                typeCode = JobType.IMPORT.code,
                stateCode = JobState.AWAITING_SOURCE_DELETE.code,
                encryptedPayload = ByteArray(16),
                progressCurrent = 1,
                progressTotal = 1,
                createdAt = 1,
                updatedAt = 1,
                sourceDispositionCode = SourceDisposition.PENDING_DELETE.code,
                errorCode = null
            )
        )
    }

    private fun success(jobId: String, uri: Uri) = ImportResult.Success(
        jobId = jobId,
        itemId = "item_$jobId",
        uri = uri,
        sha256Hex = "00".repeat(32),
        alreadyExisted = false,
        mode = ImportMode.MOVE
    )

    @Test
    fun directSourceDeletionMarksMoveCompletedAndDeleted() = runBlocking {
        insertAwaitingJob("job_direct")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = SourceDeletionCoordinator(
            context = context,
            deleteUri = { 1 },
            probeAbsent = { false }
        )
        val finalizer = MoveImportFinalizer(db, coordinator)

        val result = finalizer.begin(
            vaultId,
            listOf(success("job_direct", Uri.parse("content://media/external/images/media/1")))
        )

        assertTrue(result is MoveImportFinalizer.BeginResult.Complete)
        val summary = (result as MoveImportFinalizer.BeginResult.Complete).summary
        assertEquals(1, summary.deleted)
        assertEquals(0, summary.retained)

        val job = db.vaultJobDao().getJob("job_direct")!!
        assertEquals(JobState.COMPLETED.code, job.stateCode)
        assertEquals(SourceDisposition.DELETED.code, job.sourceDispositionCode)
        assertEquals(null, job.errorCode)
    }

    @Test
    fun deniedDeleteConsentKeepsPublicOriginalAndRecordsUserChoice() = runBlocking {
        insertAwaitingJob("job_denied")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var deleteCalls = 0
        val coordinator = SourceDeletionCoordinator(
            context = context,
            deleteUri = { deleteCalls++; 1 },
            probeAbsent = { false }
        )
        val finalizer = MoveImportFinalizer(db, coordinator)
        val pending = listOf(
            success("job_denied", Uri.parse("content://media/external/images/media/2"))
        )

        val summary = finalizer.completeConsent(
            vaultId = vaultId,
            pending = pending,
            mode = SourceDeletionCoordinator.DeleteConsentMode.API29_RETRY_REQUIRED,
            approved = false
        )

        assertEquals(0, summary.deleted)
        assertEquals(1, summary.retained)
        assertEquals(0, deleteCalls)

        val job = db.vaultJobDao().getJob("job_denied")!!
        assertEquals(JobState.COMPLETED.code, job.stateCode)
        assertEquals(SourceDisposition.RETAINED_BY_USER.code, job.sourceDispositionCode)
        assertEquals(null, job.errorCode)
    }
}
