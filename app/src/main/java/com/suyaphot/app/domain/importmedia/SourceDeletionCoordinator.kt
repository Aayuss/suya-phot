package com.suyaphot.app.domain.importmedia

import android.content.ContentResolver
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

        // Try direct deletion first (works for files owned by this app or under legacy storage)
        for (uri in uris) {
            try {
                val rows = resolver.delete(uri, null, null)
                if (rows <= 0) {
                    remainingUris.add(uri)
                }
            } catch (se: SecurityException) {
                remainingUris.add(uri)
            } catch (e: Exception) {
                SafeLog.w("SourceDeletionCoordinator", "Could not directly delete URI: $uri", e)
                remainingUris.add(uri)
            }
        }

        if (remainingUris.isEmpty()) {
            return DeletionOutcome.CompletedDirectly
        }

        // On API 30+, request user permission via MediaStore.createDeleteRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val pendingIntent = MediaStore.createDeleteRequest(resolver, remainingUris)
                DeletionOutcome.RequiresUserConsent(pendingIntent.intentSender, remainingUris)
            } catch (e: Exception) {
                SafeLog.e("SourceDeletionCoordinator", "Failed creating delete request", e)
                DeletionOutcome.Failed(remainingUris, e.message ?: "Failed creating delete request")
            }
        }

        return DeletionOutcome.Failed(remainingUris, "Direct deletion not permitted on this Android version")
    }

    sealed interface DeletionOutcome {
        data object CompletedDirectly : DeletionOutcome
        data class RequiresUserConsent(val intentSender: IntentSender, val uris: List<Uri>) : DeletionOutcome
        data class Failed(val uris: List<Uri>, val reason: String) : DeletionOutcome
    }
}
