package com.suyaphot.app.importmedia

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suyaphot.app.app.SuyaApp
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.AuthResult
import com.suyaphot.app.domain.auth.LockReason
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.feature.importmedia.ShareReceiverActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DirectShareImportTest {

    private lateinit var app: SuyaApp
    private var testVaultId: String = ""

    @Before
    fun setup() = runBlocking {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.adoptShellPermissionIdentity()
        app = ApplicationProvider.getApplicationContext()
        val container = app.container

        // Ensure primary vault exists
        val existingVault = container.database.vaultDao().getVaultByKind(VaultKind.REAL.code)
        if (existingVault == null) {
            val masterKey = container.keyManager.generateMasterKey()
            val pinChars = "123456".toCharArray()
            val pinEnvelope = container.keyManager.createPinEnvelope(masterKey, pinChars)
            val recoveryCode = container.keyManager.generateRecoverySecret()
            val recoveryEnvelope = container.keyManager.createRecoveryEnvelope(
                masterKey,
                container.keyManager.normalizeRecoverySecret(recoveryCode)
            )
            val newVaultId = UUID.randomUUID().toString()
            val vault = VaultEntity(
                id = newVaultId,
                kindCode = VaultKind.REAL.code,
                createdAt = System.currentTimeMillis(),
                schemaVersion = 5,
                pinEnvelope = pinEnvelope.serialize(),
                recoveryEnvelope = recoveryEnvelope.serialize(),
                biometricEnvelope = null,
                biometricIv = null,
                credentialTypeCode = 0
            )
            container.database.vaultDao().insert(vault)
            testVaultId = newVaultId
        } else {
            testVaultId = existingVault.id
        }

        // Lock session initially to test locked share staging
        container.sessionManager.lock(LockReason.Explicit)
    }

    @After
    fun tearDown() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
    }

    private suspend fun unlockVault() {
        val container = app.container
        val auth = container.pinAuthenticator.authenticateWithPin("123456".toCharArray())
        assertTrue("Vault must unlock with PIN 123456", auth is AuthResult.Success)
    }

    @Test
    fun testPendingShareStagedWhileLockedAndIngestedOnUnlock() = runBlocking {
        val container = app.container
        val resolver = app.contentResolver

        val historicalDateTakenMs = 1577836800000L // 2020-01-01 00:00:00 GMT
        val historicalDateModifiedSec = 1577836800L
        val historicalDateAddedSec = 1577836700L

        val initialValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "share_test_img_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DirectShareTest/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, historicalDateTakenMs)
            put(MediaStore.MediaColumns.DATE_ADDED, historicalDateAddedSec)
            put(MediaStore.MediaColumns.DATE_MODIFIED, historicalDateModifiedSec)
        }
        val mediaUri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), initialValues)
        assertNotNull("Failed to insert MediaStore test image", mediaUri)

        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.setPixel(0, 0, (System.currentTimeMillis() and 0xFFFFFF).toInt() or 0xFF000000.toInt())
            resolver.openFileDescriptor(mediaUri!!, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
            }

            runCatching {
                resolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val p = cursor.getString(0)
                        if (!p.isNullOrBlank()) {
                            val exif = ExifInterface(p)
                            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:01 00:00:00")
                            exif.saveAttributes()
                            File(p).setLastModified(historicalDateModifiedSec * 1000L)
                        }
                    }
                }
            }

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_TAKEN, historicalDateTakenMs)
                put(MediaStore.MediaColumns.DATE_ADDED, historicalDateAddedSec)
                put(MediaStore.MediaColumns.DATE_MODIFIED, historicalDateModifiedSec)
            }
            resolver.update(mediaUri, publishValues, null, null)

            // Verify vault is LOCKED
            assertTrue("Vault must be locked", container.sessionManager.sessionState.value is VaultSession.Locked)

            val countBefore = container.database.mediaItemDao().getAllActiveOnce(testVaultId).size

            // 2. Stage via PendingShareManager while locked
            val staged = container.pendingShareManager.stageSharedMedia(listOf(mediaUri))
            assertEquals("Must stage 1 shared media item", 1, staged.stagedCount)
            assertEquals("Must have 1 deletion target", 1, staged.deletionTargets.size)
            assertTrue("Must have pending shares", container.pendingShareManager.hasPendingShares())

            // 3. Unlock vault: AppContainer automatically ingests pending shares upon unlock
            unlockVault()
            val session = container.sessionManager.sessionState.value as VaultSession.Unlocked

            // Wait for automatic ingestion to complete
            var ingestedAutomatically = false
            for (i in 0 until 50) {
                if (!container.pendingShareManager.hasPendingShares()) {
                    ingestedAutomatically = true
                    break
                }
                delay(100)
            }
            assertTrue("Pending shares must be automatically ingested upon unlock", ingestedAutomatically)

            // 4. Verify item exists in database under root folder (folderId = null)
            val allItems = container.database.mediaItemDao().getAllActiveOnce(session.vaultId)
            assertEquals("Total item count must increase by 1", countBefore + 1, allItems.size)
            val imported = allItems.find { it.plaintextSize > 0L && it.folderId == null && it.dateTakenMs == historicalDateTakenMs }
            assertNotNull("Imported media item must exist in root folder with original dateTakenMs", imported)
            assertEquals("Date taken must be preserved", historicalDateTakenMs, imported!!.dateTakenMs)
            assertNull("Item must be in root folder (folderId == null)", imported.folderId)
        } finally {
            runCatching { resolver.delete(mediaUri!!, null, null) }
        }
    }

    @Test
    fun testShareReceiverActivityDirectShareSavesToRootWithoutPrompt() = runBlocking {
        val container = app.container
        val resolver = app.contentResolver

        val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val exifDateStr = "2021:06:15 12:30:00"
        val historicalDateTakenMs = sdf.parse(exifDateStr)!!.time
        val historicalDateModifiedSec = historicalDateTakenMs / 1000L
        val historicalDateAddedSec = historicalDateModifiedSec - 50L

        val initialValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "share_act_img_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DirectShareTest/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, historicalDateTakenMs)
            put(MediaStore.MediaColumns.DATE_ADDED, historicalDateAddedSec)
            put(MediaStore.MediaColumns.DATE_MODIFIED, historicalDateModifiedSec)
        }
        val mediaUri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), initialValues)
        assertNotNull(mediaUri)

        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            bitmap.setPixel(0, 0, (System.currentTimeMillis() and 0xFFFFFF).toInt() or 0xFF000000.toInt())
            resolver.openFileDescriptor(mediaUri!!, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
            }

            runCatching {
                resolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val p = cursor.getString(0)
                        if (!p.isNullOrBlank()) {
                            val exif = ExifInterface(p)
                            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDateStr)
                            exif.saveAttributes()
                            File(p).setLastModified(historicalDateModifiedSec * 1000L)
                        }
                    }
                }
            }

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_TAKEN, historicalDateTakenMs)
                put(MediaStore.MediaColumns.DATE_ADDED, historicalDateAddedSec)
                put(MediaStore.MediaColumns.DATE_MODIFIED, historicalDateModifiedSec)
            }
            resolver.update(mediaUri, publishValues, null, null)

            // Ensure vault is locked before sharing
            container.sessionManager.lock(LockReason.Explicit)
            val countBefore = container.database.mediaItemDao().getAllActiveOnce(testVaultId).size

            // Launch ShareReceiverActivity via Intent explicitly targeting ShareReceiverActivity
            val intent = Intent(Intent.ACTION_SEND).apply {
                setClass(app, ShareReceiverActivity::class.java)
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, mediaUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val scenario = ActivityScenario.launch<ShareReceiverActivity>(intent)

            // Activity finishes quickly on its own
            for (i in 0 until 50) {
                if (scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.DESTROYED)) {
                    break
                }
                delay(100)
            }
            scenario.close()

            // Verify that media was safely staged
            assertTrue("Item must be staged in pending shares", container.pendingShareManager.hasPendingShares())

            // Now unlock vault: AppContainer automatically ingests upon unlock
            unlockVault()
            val session = container.sessionManager.sessionState.value as VaultSession.Unlocked

            var ingestedAutomatically = false
            for (i in 0 until 50) {
                if (!container.pendingShareManager.hasPendingShares()) {
                    ingestedAutomatically = true
                    break
                }
                delay(100)
            }
            assertTrue("Pending shares must be automatically ingested upon unlock", ingestedAutomatically)

            val items = container.database.mediaItemDao().getAllActiveOnce(session.vaultId)
            assertEquals("Total item count must increase by 1", countBefore + 1, items.size)
            val matched = items.find { it.dateTakenMs == historicalDateTakenMs && it.folderId == null }
            assertNotNull("Shared item must be imported to vault root", matched)
            assertNull("Target folder must be root (null)", matched!!.folderId)
        } finally {
            runCatching { resolver.delete(mediaUri!!, null, null) }
        }
    }
}
