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
import com.suyaphot.app.core.database.dao.IntruderEventDao
import com.suyaphot.app.core.database.entity.IntruderEventEntity
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Handles silent CameraX front-camera capture upon failed PIN threshold.
 */
class IntruderCaptureManager(
    private val context: Context,
    private val fileStore: VaultFileStore,
    private val intruderEventDao: IntruderEventDao,
    private val sessionManager: SessionManager
) {

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

                    val tempFile = File(context.cacheDir, "temp_intruder_${UUID.randomUUID()}.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                CoroutineScope(Dispatchers.IO).launch {
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

    private suspend fun encryptAndSaveIntruder(tempFile: File, failureReason: String) = withContext(Dispatchers.IO) {
        try {
            val eventId = UUID.randomUUID().toString()
            val rawBytes = tempFile.readBytes()
            tempFile.delete() // Promptly delete plaintext temp file

            // Fallback key if vault is currently locked during intruder attempt
            val dummyKey = "suya-phot-intruder-safety-key-256".toByteArray(Charsets.UTF_8).copyOf(32)
            val encryptedBytes = Aead.encryptWithPrependedNonce(
                keyBytes = dummyKey,
                plaintext = rawBytes,
                aad = eventId.toByteArray(Charsets.UTF_8)
            )

            val destFile = fileStore.getSecurityFile("global_security", eventId)
            destFile.parentFile?.mkdirs()
            destFile.writeBytes(encryptedBytes)

            val event = IntruderEventEntity(
                id = eventId,
                createdAt = System.currentTimeMillis(),
                failureType = failureReason,
                encryptedImageRelativePath = destFile.name,
                encryptedDetails = failureReason.toByteArray(Charsets.UTF_8)
            )
            intruderEventDao.insert(event)
            SafeLog.d("IntruderCaptureManager", "Intruder photo saved: $eventId")
        } catch (e: Exception) {
            SafeLog.e("IntruderCaptureManager", "Error encrypting intruder photo", e)
        }
    }

    private suspend fun logEventWithoutPhoto(reason: String) = withContext(Dispatchers.IO) {
        val eventId = UUID.randomUUID().toString()
        val event = IntruderEventEntity(
            id = eventId,
            createdAt = System.currentTimeMillis(),
            failureType = reason,
            encryptedImageRelativePath = null,
            encryptedDetails = reason.toByteArray(Charsets.UTF_8)
        )
        intruderEventDao.insert(event)
    }
}
