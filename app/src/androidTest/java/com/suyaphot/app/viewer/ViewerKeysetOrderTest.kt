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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerKeysetOrderTest {

    private lateinit var db: SuyaDatabase
    private lateinit var repo: GalleryRepository
    private val vaultId = "test_vault_viewer"

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

        // Insert 200 items with identical timestamp and size
        val fixedTime = 1700000000000L
        val fixedSize = 4096L
        val items = (0 until 200).map { i ->
            val id = "item_%04d".format(i)
            MediaItemEntity(
                id = id,
                vaultId = vaultId,
                folderId = null,
                mediaTypeCode = 0, // IMAGE
                encryptedMetadata = ByteArray(16),
                encryptedFileRelativePath = "$id.sph",
                encryptedThumbRelativePath = null,
                plaintextSize = fixedSize,
                cipherSize = fixedSize + 28,
                sha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                importedAt = fixedTime,
                updatedAt = fixedTime,
                favorite = false,
                deletedAt = null,
                previousFolderId = null,
                dateTakenMs = fixedTime,
                encryptedPreviewRelativePath = null,
                concealed = false
            )
        }
        db.mediaItemDao().insertAll(items)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testAllSortModesTraverse200ItemsWithoutDuplicationOrSkip(): Unit = runBlocking {
        val sortModes = listOf(
            "DATE_TAKEN_ASC",
            "DATE_TAKEN_DESC",
            "IMPORTED_ASC",
            "IMPORTED_DESC",
            "SIZE_ASC",
            "SIZE_DESC"
        )

        val middleId = "item_0100"
        val windowSize = 40
        val batchLimit = 30

        for (sort in sortModes) {
            val collection = ViewerCollection.Gallery(
                filter = GalleryFilter.ALL,
                sort = sort
            )

            val window = repo.viewerWindow(vaultId, collection, middleId, windowSize)
            assertEquals("Expected total count 200 for $sort", 200, window.totalCount)

            val allCollected = window.ids.toMutableList()

            // Page backwards from the first item of the window
            var currentBefore = allCollected.first()
            while (true) {
                val prevBatch = repo.fetchPreviousViewerBatch(vaultId, collection, currentBefore, batchLimit)
                if (prevBatch.isEmpty()) break
                allCollected.addAll(0, prevBatch)
                currentBefore = prevBatch.first()
            }

            // Page forwards from the last item of the window
            var currentAfter = allCollected.last()
            while (true) {
                val nextBatch = repo.fetchNextViewerBatch(vaultId, collection, currentAfter, batchLimit)
                if (nextBatch.isEmpty()) break
                allCollected.addAll(nextBatch)
                currentAfter = nextBatch.last()
            }

            assertEquals("Expected exactly 200 items traversed for $sort", 200, allCollected.size)
            assertEquals("Expected 200 distinct items for $sort", 200, allCollected.distinct().size)

            // Check order direction:
            // For ASC sorts, item_0000 should come first, item_0199 last.
            // For DESC sorts, item_0199 should come first, item_0000 last.
            val isAsc = sort.endsWith("_ASC")
            if (isAsc) {
                assertEquals("item_0000", allCollected.first())
                assertEquals("item_0199", allCollected.last())
            } else {
                assertEquals("item_0199", allCollected.first())
                assertEquals("item_0000", allCollected.last())
            }
        }
    }
}
