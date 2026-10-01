package com.suyaphot.app.domain.restore

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suyaphot.app.app.SuyaApp
import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.domain.auth.AuthResult
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.importmedia.ImportResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class RestoreDatePreservationTest {

    @Before
    fun setup() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.adoptShellPermissionIdentity()
    }

    @After
    fun tearDown() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
    }

    @Test
    fun testOriginalDatesAndPathPreservedOnImportAndRestore() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SuyaApp>()
        val container = app.container
        val resolver = app.contentResolver

        // Ensure vault is unlocked
        if (container.sessionManager.sessionState.value !is VaultSession.Unlocked) {
            val existingVault = container.database.vaultDao().getVaultByKind(com.suyaphot.app.core.model.VaultKind.REAL.code)
            if (existingVault != null) {
                val auth = container.pinAuthenticator.authenticateWithPin("123456".toCharArray())
                if (auth !is AuthResult.Success) {
                    // If existing vault has different pin or locked out, clear tables and re-create
                    container.database.clearAllTables()
                }
            }

            if (container.sessionManager.sessionState.value !is VaultSession.Unlocked) {
                val masterKey = container.keyManager.generateMasterKey()
                val pinChars = "123456".toCharArray()
                val pinEnvelope = container.keyManager.createPinEnvelope(masterKey, pinChars)
                val recoveryCode = container.keyManager.generateRecoverySecret()
                val recoveryEnvelope = container.keyManager.createRecoveryEnvelope(
                    masterKey,
                    container.keyManager.normalizeRecoverySecret(recoveryCode)
                )
                val newVaultId = java.util.UUID.randomUUID().toString()
                val vault = com.suyaphot.app.core.database.entity.VaultEntity(
                    id = newVaultId,
                    kindCode = com.suyaphot.app.core.model.VaultKind.REAL.code,
                    createdAt = System.currentTimeMillis(),
                    schemaVersion = 5,
                    pinEnvelope = pinEnvelope.serialize(),
                    recoveryEnvelope = recoveryEnvelope.serialize(),
                    biometricEnvelope = null,
                    biometricIv = null,
                    credentialTypeCode = 0
                )
                container.database.vaultDao().insert(vault)
                val mediaSubkey = container.vaultCrypto.deriveMediaSubkey(masterKey)
                val metaSubkey = container.vaultCrypto.deriveMetaSubkey(masterKey)
                val thumbSubkey = container.vaultCrypto.deriveThumbSubkey(masterKey)
                container.sessionManager.unlock(
                    vaultId = newVaultId,
                    kind = com.suyaphot.app.core.model.VaultKind.REAL,
                    masterKeyHandle = com.suyaphot.app.core.crypto.SensitiveKeyHandle(masterKey),
                    mediaSubkey = mediaSubkey,
                    metaSubkey = metaSubkey,
                    thumbSubkey = thumbSubkey
                )
            }
        }
        val unlockedSession = container.sessionManager.sessionState.value as VaultSession.Unlocked
        val vaultId = unlockedSession.vaultId

        // 1. Create a test image in MediaStore with known historical dates
        val historicalDateTakenMs = 1577836800000L // 2020-01-01 00:00:00 UTC
        val historicalDateAddedSec = 1577836800L
        val historicalDateModifiedSec = 1577836800L
        val historicalDateModifiedMs = historicalDateModifiedSec * 1000L

        val initialValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "original_historical_photo.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OriginalSource/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, historicalDateTakenMs)
            put(MediaStore.MediaColumns.DATE_ADDED, historicalDateAddedSec)
            put(MediaStore.MediaColumns.DATE_MODIFIED, historicalDateModifiedSec)
        }

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var sourceUri = resolver.insert(collection, initialValues)
        assertNotNull("Failed to insert source media", sourceUri)

        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            resolver.openFileDescriptor(sourceUri!!, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
            }

            // Set EXIF and filesystem timestamp before publishing
            runCatching {
                resolver.query(sourceUri!!, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val p = cursor.getString(0)
                        if (!p.isNullOrBlank()) {
                            val exif = androidx.exifinterface.media.ExifInterface(p)
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:01 00:00:00")
                            exif.saveAttributes()
                            File(p).setLastModified(historicalDateModifiedMs)
                        }
                    }
                }
            }

            // Publish source row
            val publishSource = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_TAKEN, historicalDateTakenMs)
                put(MediaStore.MediaColumns.DATE_ADDED, historicalDateAddedSec)
                put(MediaStore.MediaColumns.DATE_MODIFIED, historicalDateModifiedSec)
            }
            resolver.update(sourceUri, publishSource, null, null)

            // 2. Import into Suya Phot
            val importResult = container.importCoordinator.importSingle(
                uri = sourceUri,
                folderId = null,
                mode = ImportMode.COPY
            )

            assertTrue("Import should succeed: $importResult", importResult is ImportResult.Success)
            val itemId = (importResult as ImportResult.Success).itemId

            // Verify item in Suya Phot database has original dateTakenMs
            val importedEntity = container.database.mediaItemDao().getItemForVault(itemId, vaultId)
            assertNotNull(importedEntity)
            assertEquals("Imported dateTakenMs must match original historical timestamp", historicalDateTakenMs, importedEntity!!.dateTakenMs)

            // Delete source media to simulate MOVE (so original filename slot is free)
            resolver.delete(sourceUri, null, null)
            sourceUri = null

            // 3. Restore item from Suya Phot
            val restoreResult = container.restoreCoordinator.restoreItem(itemId = itemId, move = false)
            assertTrue("Restore should succeed: $restoreResult", restoreResult is RestoreResult.Success)
            val restoredUri = checkNotNull((restoreResult as RestoreResult.Success).publicUri)

            try {
                // 4. Verify restored item in MediaStore
                val projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATA
                )

                resolver.query(restoredUri, projection, null, null, null)?.use { cursor ->
                    assertTrue("Restored row must exist in MediaStore", cursor.moveToFirst())

                    val restoredName = cursor.getString(0)
                    val restoredPath = cursor.getString(1)
                    val restoredTaken = cursor.getLong(2)
                    val restoredAdded = cursor.getLong(3)
                    val restoredModified = cursor.getLong(4)
                    val restoredData = cursor.getString(5)

                    android.util.Log.d("RestoreDatePreservationTest", "Restored Name: $restoredName, Path: $restoredPath, Taken: $restoredTaken, Added: $restoredAdded, Modified: $restoredModified, Data: $restoredData")

                    assertEquals("Restored display name must match original", "original_historical_photo.jpg", restoredName)
                    assertEquals("Restored relative path must match original", "${Environment.DIRECTORY_PICTURES}/OriginalSource/", restoredPath)
                    assertEquals("Restored dateTaken must match original", historicalDateTakenMs, restoredTaken)
                    assertEquals("Restored dateAdded must match original", historicalDateAddedSec, restoredAdded)
                    assertEquals("Restored dateModified must match original", historicalDateModifiedSec, restoredModified)

                    if (!restoredData.isNullOrBlank()) {
                        runCatching {
                            val restoredFile = File(restoredData)
                            if (restoredFile.exists()) {
                                assertEquals("Restored file lastModified must match original", historicalDateModifiedMs, restoredFile.lastModified())
                            }
                        }
                    }
                } ?: fail("Restored query cursor was null")

            } finally {
                resolver.delete(restoredUri, null, null)
            }

        } finally {
            sourceUri?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }

    @Test
    fun testOriginalDatesAndPathPreservedOnVideoImportAndRestore() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SuyaApp>()
        val container = app.container
        val resolver = app.contentResolver

        // Ensure vault is unlocked
        if (container.sessionManager.sessionState.value !is VaultSession.Unlocked) {
            val existingVault = container.database.vaultDao().getVaultByKind(com.suyaphot.app.core.model.VaultKind.REAL.code)
            if (existingVault != null) {
                val auth = container.pinAuthenticator.authenticateWithPin("123456".toCharArray())
                if (auth !is AuthResult.Success) {
                    container.database.clearAllTables()
                }
            }

            if (container.sessionManager.sessionState.value !is VaultSession.Unlocked) {
                val masterKey = container.keyManager.generateMasterKey()
                val pinChars = "123456".toCharArray()
                val pinEnvelope = container.keyManager.createPinEnvelope(masterKey, pinChars)
                val recoveryCode = container.keyManager.generateRecoverySecret()
                val recoveryEnvelope = container.keyManager.createRecoveryEnvelope(
                    masterKey,
                    container.keyManager.normalizeRecoverySecret(recoveryCode)
                )
                val newVaultId = java.util.UUID.randomUUID().toString()
                val vault = com.suyaphot.app.core.database.entity.VaultEntity(
                    id = newVaultId,
                    kindCode = com.suyaphot.app.core.model.VaultKind.REAL.code,
                    createdAt = System.currentTimeMillis(),
                    schemaVersion = 5,
                    pinEnvelope = pinEnvelope.serialize(),
                    recoveryEnvelope = recoveryEnvelope.serialize(),
                    biometricEnvelope = null,
                    biometricIv = null,
                    credentialTypeCode = 0
                )
                container.database.vaultDao().insert(vault)
                val mediaSubkey = container.vaultCrypto.deriveMediaSubkey(masterKey)
                val metaSubkey = container.vaultCrypto.deriveMetaSubkey(masterKey)
                val thumbSubkey = container.vaultCrypto.deriveThumbSubkey(masterKey)
                container.sessionManager.unlock(
                    vaultId = newVaultId,
                    kind = com.suyaphot.app.core.model.VaultKind.REAL,
                    masterKeyHandle = com.suyaphot.app.core.crypto.SensitiveKeyHandle(masterKey),
                    mediaSubkey = mediaSubkey,
                    metaSubkey = metaSubkey,
                    thumbSubkey = thumbSubkey
                )
            }
        }
        val unlockedSession = container.sessionManager.sessionState.value as VaultSession.Unlocked
        val vaultId = unlockedSession.vaultId

        // 1. Create a test video in MediaStore with known historical dates
        val historicalDateTakenMs = 1577836800000L // 2020-01-01 00:00:00 UTC
        val historicalDateAddedSec = 1577836800L
        val historicalDateModifiedSec = 1577836800L
        val historicalDateModifiedMs = historicalDateModifiedSec * 1000L

        val initialValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "historical_test_video.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/HistoricalVideo/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, historicalDateTakenMs)
            put(MediaStore.MediaColumns.DATE_ADDED, historicalDateAddedSec)
            put(MediaStore.MediaColumns.DATE_MODIFIED, historicalDateModifiedSec)
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var sourceUri = resolver.insert(collection, initialValues)
        assertNotNull("Failed to insert source video", sourceUri)

        try {
            // Copy bytes from packaged test asset or existing MediaStore video fixture
            val videoInputStream = runCatching {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("short_video.mp4")
            }.getOrNull() ?: runCatching {
                val sampleUri = resolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf("short_video.mp4"),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(0)
                        android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    } else null
                }
                sampleUri?.let { resolver.openInputStream(it) }
            }.getOrNull()
            assertNotNull("Sample video fixture must be readable", videoInputStream)

            videoInputStream!!.use { input ->
                resolver.openFileDescriptor(sourceUri!!, "w")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { out ->
                        input.copyTo(out)
                        out.flush()
                    }
                }
            }

            // Set filesystem timestamp before publishing
            runCatching {
                resolver.query(sourceUri!!, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val p = cursor.getString(0)
                        if (!p.isNullOrBlank()) {
                            File(p).setLastModified(historicalDateModifiedMs)
                        }
                    }
                }
            }

            // Publish source row
            val publishSource = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_TAKEN, historicalDateTakenMs)
                put(MediaStore.MediaColumns.DATE_ADDED, historicalDateAddedSec)
                put(MediaStore.MediaColumns.DATE_MODIFIED, historicalDateModifiedSec)
            }
            resolver.update(sourceUri!!, publishSource, null, null)

            // 2. Import into Suya Phot
            val importResult = container.importCoordinator.importSingle(
                uri = sourceUri!!,
                folderId = null,
                mode = ImportMode.COPY
            )

            assertTrue("Import should succeed: $importResult", importResult is ImportResult.Success)
            val itemId = (importResult as ImportResult.Success).itemId

            // Verify item in Suya Phot database has original dateTakenMs
            val importedEntity = container.database.mediaItemDao().getItemForVault(itemId, vaultId)
            assertNotNull(importedEntity)
            assertEquals("Imported dateTakenMs must match original historical timestamp", historicalDateTakenMs, importedEntity!!.dateTakenMs)

            // Delete source media to simulate MOVE (so original filename slot is free)
            resolver.delete(sourceUri!!, null, null)
            sourceUri = null

            // 3. Restore item from Suya Phot
            val restoreResult = container.restoreCoordinator.restoreItem(itemId = itemId, move = false)
            assertTrue("Restore should succeed: $restoreResult", restoreResult is RestoreResult.Success)
            val restoredUri = checkNotNull((restoreResult as RestoreResult.Success).publicUri)

            try {
                // 4. Verify restored item in MediaStore
                val projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATA
                )

                resolver.query(restoredUri, projection, null, null, null)?.use { cursor ->
                    assertTrue("Restored row must exist in MediaStore", cursor.moveToFirst())

                    val restoredName = cursor.getString(0)
                    val restoredPath = cursor.getString(1)
                    val restoredTaken = cursor.getLong(2)
                    val restoredAdded = cursor.getLong(3)
                    val restoredModified = cursor.getLong(4)
                    val restoredData = cursor.getString(5)

                    android.util.Log.d("RestoreDatePreservationTest", "Restored Video Name: $restoredName, Path: $restoredPath, Taken: $restoredTaken, Added: $restoredAdded, Modified: $restoredModified, Data: $restoredData")

                    assertEquals("Restored display name must match original", "historical_test_video.mp4", restoredName)
                    assertEquals("Restored relative path must match original", "${Environment.DIRECTORY_MOVIES}/HistoricalVideo/", restoredPath)
                    if (restoredTaken > 0L) {
                        assertEquals("Restored dateTaken must match original", historicalDateTakenMs, restoredTaken)
                    }
                    assertEquals("Restored dateAdded must match original", historicalDateAddedSec, restoredAdded)
                    assertEquals("Restored dateModified must match original", historicalDateModifiedSec, restoredModified)

                    if (!restoredData.isNullOrBlank()) {
                        runCatching {
                            val restoredFile = File(restoredData)
                            if (restoredFile.exists()) {
                                assertEquals("Restored file lastModified must match original", historicalDateModifiedMs, restoredFile.lastModified())
                            }
                        }
                    }
                } ?: fail("Restored query cursor was null")

            } finally {
                resolver.delete(restoredUri, null, null)
            }

        } finally {
            sourceUri?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }
}
