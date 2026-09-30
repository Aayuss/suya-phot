package com.suyaphot.app.gallery

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.SensitiveKeyHandle
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.media.ThumbnailGenerator
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.LockReason
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.gallery.EncryptedThumbnailRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailRepairTest {

    @Test
    fun missingEncryptedThumbnailIsRegeneratedFromVerifiedVaultOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val fileStore = VaultFileStore(context)
        val vaultCrypto = VaultCrypto()
        val session = SessionManager(SecurityPreferences(context))
        val access = FolderAccessManager(db, session)
        val vaultId = "vault_thumb_repair"
        val itemId = "item_thumb_repair"

        val masterKey = ByteArray(32) { 0x11 }
        val mediaKey = ByteArray(32) { 0x21 }
        val metaKey = ByteArray(32) { 0x22 }
        val thumbKey = ByteArray(32) { 0x23 }

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

            val bitmap = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888)
            val jpegOut = ByteArrayOutputStream()
            try {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, jpegOut))
            } finally {
                bitmap.recycle()
            }
            val plaintext = jpegOut.toByteArray()
            val mediaFile = fileStore.getMediaFile(vaultId, itemId)
            val verification = vaultCrypto.encryptStream(
                input = ByteArrayInputStream(plaintext),
                outputFile = mediaFile,
                mediaSubkey = mediaKey,
                itemId = itemId,
                isVideo = false,
                plaintextSize = plaintext.size.toLong()
            )
            plaintext.fill(0)

            val rawMetadata = PrivateMediaMetadata(
                originalDisplayName = "repair.jpg",
                originalRelativePath = "DCIM/Camera/",
                originalMimeType = "image/jpeg",
                originalContentUri = null,
                dateTakenMs = null,
                dateModifiedMs = null,
                width = 96,
                height = 72,
                durationMs = null,
                orientation = 1,
                sourceVolume = null,
                sourceMediaStoreId = null,
                gpsWasAvailable = false,
                originalFileExtension = "jpg"
            ).serialize()
            val encryptedMetadata = try {
                Aead.encryptWithPrependedNonce(
                    metaKey,
                    rawMetadata,
                    itemId.toByteArray(Charsets.UTF_8)
                )
            } finally {
                rawMetadata.fill(0)
            }

            db.mediaItemDao().insert(
                MediaItemEntity(
                    id = itemId,
                    vaultId = vaultId,
                    folderId = null,
                    mediaTypeCode = 0,
                    encryptedMetadata = encryptedMetadata,
                    encryptedFileRelativePath = mediaFile.name,
                    encryptedThumbRelativePath = null,
                    plaintextSize = verification.plaintextSize,
                    cipherSize = mediaFile.length(),
                    sha256Hex = verification.sha256.joinToString("") { "%02x".format(it) },
                    importedAt = 1,
                    updatedAt = 1,
                    favorite = false,
                    deletedAt = null,
                    previousFolderId = null
                )
            )

            session.unlock(
                vaultId = vaultId,
                kind = VaultKind.REAL,
                masterKeyHandle = SensitiveKeyHandle(masterKey.copyOf()),
                mediaSubkey = mediaKey.copyOf(),
                metaSubkey = metaKey.copyOf(),
                thumbSubkey = thumbKey.copyOf()
            )

            val repo = EncryptedThumbnailRepository(
                sessionManager = session,
                fileStore = fileStore,
                generator = ThumbnailGenerator(context),
                database = db,
                vaultCrypto = vaultCrypto,
                folderAccessManager = access
            )

            val thumbFile = fileStore.getThumbFile(vaultId, itemId)
            assertTrue(!thumbFile.exists())

            val repaired = repo.load(vaultId, itemId, revision = 1)
            assertNotNull(repaired)
            assertTrue(thumbFile.exists())
            assertTrue(thumbFile.length() > 0L)
        } finally {
            session.lock(LockReason.Explicit)
            masterKey.fill(0)
            mediaKey.fill(0)
            metaKey.fill(0)
            thumbKey.fill(0)
            fileStore.vaultDirPath(vaultId).deleteRecursively()
            db.close()
        }
    }
}
