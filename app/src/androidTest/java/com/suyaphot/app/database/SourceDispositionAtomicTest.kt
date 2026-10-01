package com.suyaphot.app.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.database.entity.VaultJobEntity
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.JobType
import com.suyaphot.app.core.model.SourceDisposition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceDispositionAtomicTest {

    private lateinit var db: SuyaDatabase
    private val vaultId = "vault_source_disp_test"

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()

        db.vaultDao().insert(
            VaultEntity(
                id = vaultId,
                kindCode = 0,
                createdAt = 1000L,
                schemaVersion = 6,
                pinEnvelope = ByteArray(16),
                recoveryEnvelope = null,
                biometricEnvelope = null,
                biometricIv = null,
                credentialTypeCode = 0
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testUpdateTerminalImportStateAtomicity() = runBlocking {
        val job = VaultJobEntity(
            id = "job_test_1",
            vaultId = vaultId,
            typeCode = JobType.IMPORT.code,
            stateCode = JobState.QUEUED.code,
            encryptedPayload = ByteArray(16),
            progressCurrent = 0L,
            progressTotal = 100L,
            createdAt = 1000L,
            updatedAt = 1000L,
            sourceDispositionCode = null,
            errorCode = null
        )
        db.vaultJobDao().insert(job)

        val now = 2000L
        val rowsAffected = db.vaultJobDao().updateTerminalImportState(
            id = "job_test_1",
            vaultId = vaultId,
            stateCode = JobState.COMPLETED.code,
            sourceDispositionCode = SourceDisposition.DELETED.code,
            errorCode = null,
            now = now
        )
        assertEquals(1, rowsAffected)

        val updated = db.vaultJobDao().getJob("job_test_1")
        assertNotNull(updated)
        assertEquals(JobState.COMPLETED.code, updated!!.stateCode)
        assertEquals(SourceDisposition.DELETED.code, updated.sourceDispositionCode)
        assertNull(updated.errorCode)
        assertEquals(now, updated.updatedAt)

        // Failure case with error code
        val nowFailed = 3000L
        val failRows = db.vaultJobDao().updateTerminalImportState(
            id = "job_test_1",
            vaultId = vaultId,
            stateCode = JobState.COMPLETED.code,
            sourceDispositionCode = SourceDisposition.DELETE_FAILED.code,
            errorCode = "SOURCE_DELETE_FAILED_VAULT_SAFE",
            now = nowFailed
        )
        assertEquals(1, failRows)

        val failUpdated = db.vaultJobDao().getJob("job_test_1")
        assertNotNull(failUpdated)
        assertEquals(SourceDisposition.DELETE_FAILED.code, failUpdated!!.sourceDispositionCode)
        assertEquals("SOURCE_DELETE_FAILED_VAULT_SAFE", failUpdated.errorCode)
        assertEquals(nowFailed, failUpdated.updatedAt)

        // Isolation: updating with wrong vaultId affects 0 rows
        val wrongVaultRows = db.vaultJobDao().updateTerminalImportState(
            id = "job_test_1",
            vaultId = "wrong_vault",
            stateCode = JobState.COMPLETED.code,
            sourceDispositionCode = SourceDisposition.DELETED.code,
            errorCode = null,
            now = 4000L
        )
        assertEquals(0, wrongVaultRows)
    }

    @Test
    fun testPurgeResolvedCompletedJobsFiltering() = runBlocking {
        val now = 10_000_000L
        val cutoff = now - (7 * 86_400_000L) // 7 days ago: negative in this test scale, let's use explicit timestamps
        val oldTimestamp = 1_000_000L
        val recentTimestamp = 9_000_000L
        val testCutoff = 5_000_000L

        fun insertJob(id: String, state: Int, disp: Int?, updated: Long) = runBlocking {
            db.vaultJobDao().insert(
                VaultJobEntity(
                    id = id,
                    vaultId = vaultId,
                    typeCode = JobType.IMPORT.code,
                    stateCode = state,
                    encryptedPayload = ByteArray(16),
                    progressCurrent = 100L,
                    progressTotal = 100L,
                    createdAt = updated,
                    updatedAt = updated,
                    sourceDispositionCode = disp,
                    errorCode = null
                )
            )
        }

        // Resolved & Old -> Purged, except legacy COPY jobs. NOT_APPLICABLE is
        // retained until the user explicitly finishes moving or keeps the public original.
        insertJob("job_not_applicable_old", JobState.COMPLETED.code, SourceDisposition.NOT_APPLICABLE.code, oldTimestamp)
        insertJob("job_deleted_old", JobState.COMPLETED.code, SourceDisposition.DELETED.code, oldTimestamp)
        insertJob("job_retained_user_old", JobState.COMPLETED.code, SourceDisposition.RETAINED_BY_USER.code, oldTimestamp)
        insertJob("job_null_disp_old", JobState.COMPLETED.code, null, oldTimestamp)

        // Unresolved -> Preserved even if old
        insertJob("job_pending_delete_old", JobState.COMPLETED.code, SourceDisposition.PENDING_DELETE.code, oldTimestamp)
        insertJob("job_retained_interruption_old", JobState.COMPLETED.code, SourceDisposition.RETAINED_AFTER_INTERRUPTION.code, oldTimestamp)
        insertJob("job_delete_failed_old", JobState.COMPLETED.code, SourceDisposition.DELETE_FAILED.code, oldTimestamp)

        // Active -> Preserved even if old
        insertJob("job_active_reading_old", JobState.READING_SOURCE.code, SourceDisposition.DELETED.code, oldTimestamp)

        // Resolved & Recent -> Preserved
        insertJob("job_deleted_recent", JobState.COMPLETED.code, SourceDisposition.DELETED.code, recentTimestamp)

        val purgedCount = db.vaultJobDao().purgeResolvedCompletedJobs(vaultId, testCutoff)
        assertEquals(3, purgedCount)

        assertNotNull(db.vaultJobDao().getJob("job_not_applicable_old"))
        assertNull(db.vaultJobDao().getJob("job_deleted_old"))
        assertNull(db.vaultJobDao().getJob("job_retained_user_old"))
        assertNull(db.vaultJobDao().getJob("job_null_disp_old"))

        assertNotNull(db.vaultJobDao().getJob("job_pending_delete_old"))
        assertNotNull(db.vaultJobDao().getJob("job_retained_interruption_old"))
        assertNotNull(db.vaultJobDao().getJob("job_delete_failed_old"))
        assertNotNull(db.vaultJobDao().getJob("job_active_reading_old"))
        assertNotNull(db.vaultJobDao().getJob("job_deleted_recent"))
    }
}
