package com.suyaphot.app.domain.importmedia

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
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
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { false } ?: true
        } catch (_: FileNotFoundException) {
            true
        } catch (_: Exception) {
            false
        }
    }
) {

    /**
     * Android Photo Picker returns mediated content://media/picker/... URIs. Those are excellent
     * read handles, but MediaStore deletion APIs expect the underlying media collection URI.
     * Resolve the picker handle back to the item's MediaStore identity when the provider exposes
     * _ID/VOLUME_NAME. If resolution is unavailable we safely fall back to the original URI and
     * let Android keep the source rather than risking deletion of the wrong item.
     */
    private fun canonicalDeleteUri(source: Uri): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            DocumentsContract.isDocumentUri(context, source)
        ) {
            val mediaUri = runCatching {
                MediaStore.getMediaUri(context, source)
            }.getOrNull()

            if (mediaUri != null) {
                return mediaUri
            }
        }

        // MediaStore picker URIs are intentionally read-only and cannot be converted with
        // undocumented path parsing. If one reaches this coordinator, return it unchanged:
        // deletion will fail safely and the UI will report that the public original remains.
        return source
    }

    enum class DeleteConsentMode { API29_RETRY_REQUIRED, API30_SYSTEM_DELETE_REQUEST }

    data class DeletionVerification(val deletedUris: List<Uri>, val retainedUris: List<Uri>)

    /** Consent grants permission on API 29; it does not itself perform the delete. */
    fun completeConsent(uris: List<Uri>, mode: DeleteConsentMode): DeletionVerification {
        val deleted = ArrayList<Uri>()
        val retained = ArrayList<Uri>()
        for (uri in uris) {
            val canonical = canonicalDeleteUri(uri)
            val rows = if (mode == DeleteConsentMode.API29_RETRY_REQUIRED) {
                try { deleteUri(canonical) }
                catch (_: Exception) { 0 }
            } else 0
            if (rows > 0 || probeAbsent(canonical)) deleted += uri else retained += uri
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
            val canonical = canonicalDeleteUri(uri)
            try {
                val rows = deleteUri(canonical)
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
                val canonicalUris = remainingUris.map(::canonicalDeleteUri)
                val pendingIntent = MediaStore.createDeleteRequest(resolver, canonicalUris)
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
