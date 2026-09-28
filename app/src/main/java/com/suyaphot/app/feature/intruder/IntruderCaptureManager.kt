package com.suyaphot.app.feature.intruder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.IntruderKeyProvider
import com.suyaphot.app.core.database.dao.IntruderEventDao
import com.suyaphot.app.core.database.dao.VaultDao
import com.suyaphot.app.core.database.entity.IntruderEventEntity
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Handles silent CameraX front-camera capture upon failed PIN threshold.
 * Encrypts images and details with a dedicated non-exportable Keystore AES-GCM key.
 */
class IntruderCaptureManager(
    private val context: Context,
    private val fileStore: VaultFileStore,
    private val intruderEventDao: IntruderEventDao,
    private val vaultDao: VaultDao,
    private val intruderKeyProvider: IntruderKeyProvider
) {
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Attempts to capture an intruder selfie using the front camera.
     * Encrypts the photo and deletes the temporary file immediately.
     */
    suspend fun captureIntruderPhoto(
        lifecycleOwner: LifecycleOwner,
        failureReason: String
    ) = withContext(Dispatchers.Main) {
        if (!hasCameraPermission()) {
            SafeLog.d("IntruderCaptureManager", "Camera permission not granted; logging event without photo")
            logEventWithoutPhoto(failureReason)
            return@withContext
        }

        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                    if (!cameraProvider.hasCamera(cameraSelector)) {
                        SafeLog.w("IntruderCaptureManager", "No front camera available")
                        return@addListener
                    }

                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(android.view.Surface.ROTATION_0)
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                    val tempFile = fileStore.createIntruderCaptureTempFile()
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                cameraProvider.unbindAll()
                                operationScope.launch {
                                    try {
                                        encryptAndSaveIntruder(tempFile, failureReason)
                                    } catch (t: Throwable) {
                                        SafeLog.e("IntruderCaptureManager", "Error in async intruder task", t)
                                    }
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                cameraProvider.unbindAll()
                                if (tempFile.exists()) tempFile.delete()
                                SafeLog.e("IntruderCaptureManager", "Intruder picture capture failed", exception)
                            }
                        }
                    )
                } catch (e: Exception) {
                    SafeLog.e("IntruderCaptureManager", "Error binding CameraX for intruder selfie", e)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            SafeLog.e("IntruderCaptureManager", "Failed obtaining CameraProvider", e)
        }
    }

    private suspend fun getRealVaultId(): String? =
        vaultDao.getVaultByKind(VaultKind.REAL.code)?.id

    private suspend fun encryptAndSaveIntruder(tempFile: File, failureReason: String) = withContext(Dispatchers.IO) {
        try {
            val eventId = UUID.randomUUID().toString()
            val rawBytes = tempFile.readBytes()
            tempFile.delete() // Promptly delete plaintext temp file

            val key = intruderKeyProvider.getOrCreateKey()
            val aad = "suya-phot:intruder:v1:$eventId".toByteArray(Charsets.UTF_8)

            val encryptedImageBytes = Aead.encryptWithPrependedNonce(
                key = key,
                plaintext = rawBytes,
                aad = aad
            )

            val encryptedDetails = Aead.encryptWithPrependedNonce(
                key = key,
                plaintext = failureReason.toByteArray(Charsets.UTF_8),
                aad = aad
            )

            val realVaultId = getRealVaultId() ?: run {
                rawBytes.fill(0)
                SafeLog.w("IntruderCaptureManager", "Real vault unavailable; intruder event not recorded")
                return@withContext
            }
            val destFile = fileStore.getSecurityFile(realVaultId, eventId)
            destFile.parentFile?.mkdirs()
            val staging = File(destFile.parentFile, "${destFile.name}.writing")
            try {
                check(!destFile.exists()) { "Intruder event ciphertext already exists" }
                FileOutputStream(staging).use { output ->
                    output.write(encryptedImageBytes)
                    output.flush()
                    output.fd.sync()
                }
                check(staging.renameTo(destFile)) { "Could not commit intruder ciphertext" }
            } finally {
                rawBytes.fill(0)
                encryptedImageBytes.fill(0)
                staging.delete()
            }

            val event = IntruderEventEntity(
                id = eventId,
                realVaultId = realVaultId,
                createdAt = System.currentTimeMillis(),
                reasonCode = 1,
                encryptedImageRelativePath = destFile.name,
                encryptedDetails = encryptedDetails
            )
            intruderEventDao.insert(event)
            SafeLog.d("IntruderCaptureManager", "Intruder photo saved")
        } catch (e: Exception) {
            SafeLog.e("IntruderCaptureManager", "Error encrypting intruder photo", e)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private suspend fun logEventWithoutPhoto(reason: String) = withContext(Dispatchers.IO) {
        val eventId = UUID.randomUUID().toString()
        val realVaultId = getRealVaultId() ?: return@withContext
        val key = intruderKeyProvider.getOrCreateKey()
        val aad = "suya-phot:intruder:v1:$eventId".toByteArray(Charsets.UTF_8)
        val encryptedDetails = Aead.encryptWithPrependedNonce(
            key = key,
            plaintext = reason.toByteArray(Charsets.UTF_8),
            aad = aad
        )

        val event = IntruderEventEntity(
            id = eventId,
            realVaultId = realVaultId,
            createdAt = System.currentTimeMillis(),
            reasonCode = 1,
            encryptedImageRelativePath = null,
            encryptedDetails = encryptedDetails
        )
        intruderEventDao.insert(event)
    }
}
