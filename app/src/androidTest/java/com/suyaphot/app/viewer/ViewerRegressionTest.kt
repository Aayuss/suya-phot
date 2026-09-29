package com.suyaphot.app.viewer

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.gallery.GalleryFilter
import com.suyaphot.app.domain.gallery.GalleryRepository
import com.suyaphot.app.domain.gallery.ViewerCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerRegressionTest {

    private lateinit var db: SuyaDatabase
    private lateinit var repo: GalleryRepository
    private val vaultId = "test_vault_viewer_regression"

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val session = SessionManager(SecurityPreferences(context), CoroutineScope(Dispatchers.Unconfined))
        val access = FolderAccessManager(db, session, CoroutineScope(Dispatchers.Unconfined))
        repo = GalleryRepository(db.mediaItemDao(), access)

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
    fun testSearchWindowCursorAbsoluteStart() = runBlocking {
        // Create 300 items in DB
        val items = (0 until 300).map { i ->
            val id = "item_%04d".format(i)
            MediaItemEntity(
                id = id,
                vaultId = vaultId,
                folderId = null,
                mediaTypeCode = 0,
                encryptedMetadata = ByteArray(16),
                encryptedFileRelativePath = "$id.sph",
                encryptedThumbRelativePath = null,
                plaintextSize = 1024L,
                cipherSize = 1052L,
                sha256Hex = "sha_%04d".format(i),
                importedAt = 1000L + i,
                updatedAt = 1000L + i,
                favorite = false,
                deletedAt = null,
                previousFolderId = null,
                dateTakenMs = null,
                encryptedPreviewRelativePath = null,
                concealed = false,
                cleanupStateCode = 0
            )
        }
        for (item in items) {
            db.mediaItemDao().insert(item)
        }

        val allIds = items.map { it.id }
        val targetId = "item_0200"

        val window = repo.viewerWindow(
            vaultId = vaultId,
            collection = ViewerCollection.Gallery(
                filter = GalleryFilter.ALL,
                sort = "IMPORTED_DESC",
                searchIds = allIds
            ),
            aroundId = targetId,
            windowSize = 100
        )

        // absoluteStart must not be 0; around index is 200, so start = 200 - 50 = 150
        assertEquals(150, window.absoluteStart)
        assertTrue(targetId in window.ids)
    }

    @Test
    fun testStaleCandidateChunkingInSearchPreventsPrematureEmptyBatch() = runBlocking {
        // Suppose a search index has 400 candidate IDs:
        // IDs 0..249 do NOT exist in DB (stale / deleted),
        // IDs 250..299 exist in DB.
        val searchCandidateIds = (0 until 400).map { "cand_%04d".format(it) }

        for (i in 250 until 300) {
            val id = "cand_%04d".format(i)
            db.mediaItemDao().insert(
                MediaItemEntity(
                    id = id,
                    vaultId = vaultId,
                    folderId = null,
                    mediaTypeCode = 0,
                    encryptedMetadata = ByteArray(16),
                    encryptedFileRelativePath = "$id.sph",
                    encryptedThumbRelativePath = null,
                    plaintextSize = 1024L,
                    cipherSize = 1052L,
                    sha256Hex = "sha_%04d".format(i),
                    importedAt = 1000L + i,
                    updatedAt = 1000L + i,
                    favorite = false,
                    deletedAt = null,
                    previousFolderId = null,
                    dateTakenMs = null,
                    encryptedPreviewRelativePath = null,
                    concealed = false,
                    cleanupStateCode = 0
                )
            )
        }

        // Forward search: starting after cand_0000, requesting 10 items.
        // Old logic checked subList(1, 1 + 20) -> 0 visible -> emptyList (bug).
        // New logic chunks in 200 candidates and scans until 10 visible items found.
        val nextBatch = repo.fetchNextViewerBatch(
            vaultId = vaultId,
            collection = ViewerCollection.Gallery(
                filter = GalleryFilter.ALL,
                sort = "IMPORTED_DESC",
                searchIds = searchCandidateIds
            ),
            afterId = "cand_0000",
            limit = 10
        )

        assertEquals(10, nextBatch.size)
        assertEquals("cand_0250", nextBatch[0])
        assertEquals("cand_0259", nextBatch[9])

        // Backward search: starting before cand_0350, requesting 10 items.
        // Candidate items 300..349 are missing, items 250..299 exist.
        val prevBatch = repo.fetchPreviousViewerBatch(
            vaultId = vaultId,
            collection = ViewerCollection.Gallery(
                filter = GalleryFilter.ALL,
                sort = "IMPORTED_DESC",
                searchIds = searchCandidateIds
            ),
            beforeId = "cand_0350",
            limit = 10
        )

        assertEquals(10, prevBatch.size)
        // Must be in natural ascending list order ending at cand_0299
        assertEquals("cand_0290", prevBatch[0])
        assertEquals("cand_0299", prevBatch[9])
    }

    @Test
    fun testFolderAndTrashQueriesContainIdDescTieBreaker() = runBlocking {
        val sameTimestamp = 1700000000000L
        val itemA = MediaItemEntity(
            id = "item_A",
            vaultId = vaultId,
            folderId = "folder_1",
            mediaTypeCode = 0,
            encryptedMetadata = ByteArray(16),
            encryptedFileRelativePath = "item_A.sph",
            encryptedThumbRelativePath = null,
            plaintextSize = 1000L,
            cipherSize = 1028L,
            sha256Hex = "shaA",
            importedAt = sameTimestamp,
            updatedAt = sameTimestamp,
            favorite = false,
            deletedAt = sameTimestamp,
            previousFolderId = null,
            dateTakenMs = null,
            encryptedPreviewRelativePath = null,
            concealed = false,
            cleanupStateCode = 0
        )
        val itemB = itemA.copy(id = "item_B", sha256Hex = "shaB", encryptedFileRelativePath = "item_B.sph")
        val itemC = itemA.copy(id = "item_C", sha256Hex = "shaC", encryptedFileRelativePath = "item_C.sph")

        db.mediaItemDao().insert(itemA)
        db.mediaItemDao().insert(itemB)
        db.mediaItemDao().insert(itemC)

        // Restore itemA/B/C to active in folder_1
        db.mediaItemDao().restoreFromTrashForVault(vaultId, "item_A", "folder_1", false, sameTimestamp)
        db.mediaItemDao().restoreFromTrashForVault(vaultId, "item_B", "folder_1", false, sameTimestamp)
        db.mediaItemDao().restoreFromTrashForVault(vaultId, "item_C", "folder_1", false, sameTimestamp)

        val activeList = db.mediaItemDao().getActiveIdsInFolder(vaultId, "folder_1")
        assertEquals(listOf("item_C", "item_B", "item_A"), activeList)
    }
}
