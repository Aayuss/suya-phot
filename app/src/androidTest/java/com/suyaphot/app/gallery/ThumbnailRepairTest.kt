package com.suyaphot.app.gallery

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.crypto.SensitiveKeyHandle
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.media.ThumbnailGenerator
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.gallery.EncryptedThumbnailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ThumbnailRepairTest {

    @Test
    fun missingOrCorruptThumbIsRebuiltFromVerifiedVaultOriginal(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val fileStore = VaultFileStore(context)
        val crypto = VaultCrypto()
        val session = SessionManager(
            SecurityPreferences(context),
            CoroutineScope(Dispatchers.Unconfined)
        )
        val generator = ThumbnailGenerator(context)
        val repo = EncryptedThumbnailRepository(
            sessionManager = session,
            fileStore = fileStore,
            generator = generator,
            database = db,
            vaultCrypto = crypto
        )

        val vaultId = "thumb_repair_" + UUID.randomUUID().toString().replace("-", "")
        val mediaId = UUID.randomUUID().toString()
        val masterKey = ByteArray(32) { i -> (i + 7).toByte() }
        val mediaKey = crypto.deriveMediaSubkey(masterKey)
        val metaKey = crypto.deriveMetaSubkey(masterKey)
        val thumbKey = crypto.deriveThumbSubkey(masterKey)

        try {
            session.unlock(
                vaultId = vaultId,
                kind = VaultKind.REAL,
                masterKeyHandle = SensitiveKeyHandle(masterKey.copyOf()),
                mediaSubkey = mediaKey,
                metaSubkey = metaKey,
                thumbSubkey = thumbKey
            )

            val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(30, 120, 210))
            val jpeg = ByteArrayOutputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
                out.toByteArray()
            }
            bitmap.recycle()

            val encryptedFile = fileStore.getMediaFile(vaultId, mediaId)
            val verification = crypto.encryptStream(
                input = ByteArrayInputStream(jpeg),
                outputFile = encryptedFile,
                mediaSubkey = mediaKey,
                itemId = mediaId,
                isVideo = false,
                plaintextSize = jpeg.size.toLong()
            )
            val sha = verification.sha256.joinToString("") { "%02x".format(it) }

            db.mediaItemDao().insert(
                MediaItemEntity(
                    id = mediaId,
                    vaultId = vaultId,
                    folderId = null,
                    mediaTypeCode = 0,
                    encryptedMetadata = byteArrayOf(1),
                    encryptedFileRelativePath = encryptedFile.name,
                    encryptedThumbRelativePath = null,
                    plaintextSize = jpeg.size.toLong(),
                    cipherSize = encryptedFile.length(),
                    sha256Hex = sha,
                    importedAt = 1L,
                    updatedAt = 1L,
                    favorite = false,
                    deletedAt = null,
                    previousFolderId = null,
                    dateTakenMs = null,
                    encryptedPreviewRelativePath = null,
                    cleanupStateCode = 0,
                    concealed = false
                )
            )

            // Simulate the broken state observed in the emulator: an unusable derivative exists.
            val thumbFile = fileStore.getThumbFile(vaultId, mediaId)
            thumbFile.parentFile?.mkdirs()
            thumbFile.writeBytes(byteArrayOf(9, 8, 7, 6, 5))

            val repaired = repo.load(vaultId, mediaId, revision = 1L)
            assertNotNull("Grid loader should lazily rebuild a usable encrypted thumbnail", repaired)
            assertTrue("Repaired encrypted thumbnail should exist", thumbFile.exists())
            assertTrue("Repaired thumbnail must no longer be the tiny corrupt placeholder", thumbFile.length() > 5L)

            val row = db.mediaItemDao().getItemForVault(mediaId, vaultId)
            assertTrue(!row?.encryptedThumbRelativePath.isNullOrBlank())
        } finally {
            session.lock()
            db.close()
            fileStore.vaultDirPath(vaultId).deleteRecursively()
            masterKey.fill(0)
            mediaKey.fill(0)
            metaKey.fill(0)
            thumbKey.fill(0)
        }
    }
}
