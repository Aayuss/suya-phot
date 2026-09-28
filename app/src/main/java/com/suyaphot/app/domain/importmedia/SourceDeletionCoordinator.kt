package com.suyaphot.app.domain.importmedia

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.suyaphot.app.core.util.SafeLog

/**
 * Coordinates safe deletion of source files from public MediaStore.
 */
class SourceDeletionCoordinator(private val context: Context) {

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
                val rows = resolver.delete(uri, null, null)
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
                    deletedUris
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
                DeletionOutcome.RequiresUserConsent(pendingIntent.intentSender, remainingUris, deletedUris)
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
            val deletedUris: List<Uri>
        ) : DeletionOutcome
        data class Failed(val uris: List<Uri>, val reason: String, val deletedUris: List<Uri>) : DeletionOutcome
    }
}
