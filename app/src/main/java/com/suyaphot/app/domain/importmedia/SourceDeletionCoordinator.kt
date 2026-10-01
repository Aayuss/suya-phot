package com.suyaphot.app.domain.importmedia

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.suyaphot.app.core.util.SafeLog
import java.io.FileNotFoundException

/**
 * Coordinates safe deletion of source files from public MediaStore.
 */
class SourceDeletionCoordinator(
    private val context: Context,
    private val deleteUri: (Uri) -> Int = { context.contentResolver.delete(it, null, null) },
    private val probeAbsent: (Uri) -> Boolean = { uri ->
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null
            )
            val exists = cursor?.use { it.moveToFirst() } ?: false
            if (!exists) {
                true
            } else {
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { false } ?: true
                } catch (_: FileNotFoundException) {
                    true
                } catch (_: Exception) {
                    false
                }
            }
        } catch (_: FileNotFoundException) {
            true
        } catch (_: IllegalArgumentException) {
            true
        } catch (_: SecurityException) {
            // When an item is deleted from MediaStore, querying or opening it throws SecurityException because URI permission is revoked
            true
        } catch (_: Exception) {
            false
        }
    }
) {

    enum class DeleteConsentMode { API29_RETRY_REQUIRED, API30_SYSTEM_DELETE_REQUEST }

    data class DeletionVerification(val deletedUris: List<Uri>, val retainedUris: List<Uri>)

    /** Consent grants permission on API 29; it does not itself perform the delete. */
    fun completeConsent(uris: List<Uri>, mode: DeleteConsentMode): DeletionVerification {
        val deleted = ArrayList<Uri>()
        val retained = ArrayList<Uri>()
        for (uri in uris) {
            val rows = if (mode == DeleteConsentMode.API29_RETRY_REQUIRED) {
                try { deleteUri(uri) }
                catch (_: Exception) { 0 }
            } else 0
            if (rows > 0 || probeAbsent(uri)) deleted += uri else retained += uri
        }
        return DeletionVerification(deleted, retained)
    }

    /**
     * Attempts direct deletion. If not permitted by scoped storage, returns an IntentSender
     * to prompt the user for deletion consent via ActivityResultContracts.StartIntentSenderForResult.
     */
    fun deleteSources(uris: List<Uri>): DeletionOutcome {
        val resolver = context.contentResolver
        val remainingUris = mutableListOf<Uri>()
        val deletedUris = mutableListOf<Uri>()

        // Try direct deletion first (works for files owned by this app or under legacy storage)
        for (uri in uris) {
            try {
                val rows = deleteUri(uri)
                if (rows <= 0) {
                    remainingUris.add(uri)
                } else {
                    deletedUris.add(uri)
                }
            } catch (rse: RecoverableSecurityException) {
                // API 29 per-item user consent
                return DeletionOutcome.RequiresUserConsent(
                    rse.userAction.actionIntent.intentSender,
                    listOf(uri),
                    deletedUris,
                    DeleteConsentMode.API29_RETRY_REQUIRED
                )
            } catch (se: SecurityException) {
                remainingUris.add(uri)
            } catch (e: Exception) {
                SafeLog.w("SourceDeletionCoordinator", "Could not directly delete a source item")
                remainingUris.add(uri)
            }
        }

        if (remainingUris.isEmpty()) {
            return DeletionOutcome.CompletedDirectly(deletedUris)
        }

        // On API 30+, request user permission via MediaStore.createDeleteRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val pendingIntent = MediaStore.createDeleteRequest(resolver, remainingUris)
                DeletionOutcome.RequiresUserConsent(
                    pendingIntent.intentSender, remainingUris, deletedUris,
                    DeleteConsentMode.API30_SYSTEM_DELETE_REQUEST
                )
            } catch (e: Exception) {
                SafeLog.e("SourceDeletionCoordinator", "Failed creating delete request", e)
                DeletionOutcome.Failed(remainingUris, "DELETE_REQUEST_FAILED", deletedUris)
            }
        }

        return DeletionOutcome.Failed(remainingUris, "DIRECT_DELETE_NOT_PERMITTED", deletedUris)
    }

    sealed interface DeletionOutcome {
        data class CompletedDirectly(val deletedUris: List<Uri>) : DeletionOutcome
        data class RequiresUserConsent(
            val intentSender: IntentSender,
            val uris: List<Uri>,
            val deletedUris: List<Uri>,
            val mode: DeleteConsentMode
        ) : DeletionOutcome
        data class Failed(val uris: List<Uri>, val reason: String, val deletedUris: List<Uri>) : DeletionOutcome
    }
}
