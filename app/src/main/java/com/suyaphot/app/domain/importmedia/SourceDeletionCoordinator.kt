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
    probeAbsent: ((Uri) -> Boolean)? = null,
    val probePresence: (Uri) -> SourcePresence = probeAbsent?.let { legacy ->
        { uri: Uri -> if (legacy(uri)) SourcePresence.ABSENT else SourcePresence.PRESENT }
    } ?: { uri ->
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
                SourcePresence.ABSENT
            } else {
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                        SourcePresence.PRESENT
                    } ?: SourcePresence.PRESENT
                } catch (_: FileNotFoundException) {
                    SourcePresence.ABSENT
                } catch (_: SecurityException) {
                    SourcePresence.PRESENT
                } catch (_: Exception) {
                    SourcePresence.PRESENT
                }
            }
        } catch (_: FileNotFoundException) {
            SourcePresence.ABSENT
        } catch (_: IllegalArgumentException) {
            SourcePresence.ABSENT
        } catch (_: SecurityException) {
            SourcePresence.UNKNOWN
        } catch (_: Exception) {
            SourcePresence.UNKNOWN
        }
    },
    private val createBatchDeleteRequest: ((android.content.ContentResolver, Collection<Uri>) -> IntentSender)? = null
) {

    enum class SourcePresence { PRESENT, ABSENT, UNKNOWN }

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
            var presence = probePresence(uri)
            if (mode == DeleteConsentMode.API30_SYSTEM_DELETE_REQUEST && presence != SourcePresence.ABSENT) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {}
                presence = probePresence(uri)
            }
            if (rows > 0 || presence == SourcePresence.ABSENT) {
                deleted += uri
            } else {
                retained += uri
            }
        }
        return DeletionVerification(deleted, retained)
    }

    /**
     * Attempts direct deletion. If not permitted by scoped storage, returns an IntentSender
     * to prompt the user for deletion consent via ActivityResultContracts.StartIntentSenderForResult.
     * On API 30+, all items requiring consent are bundled into a single batch delete prompt.
     */
    fun deleteSources(uris: List<Uri>): DeletionOutcome {
        val resolver = context.contentResolver
        val distinctUris = uris.distinct()
        if (distinctUris.isEmpty()) {
            return DeletionOutcome.CompletedDirectly(emptyList())
        }

        val remainingUris = mutableListOf<Uri>()
        val deletedUris = mutableListOf<Uri>()

        // Try direct deletion first (works for files owned by this app or under legacy storage)
        for (uri in distinctUris) {
            try {
                val rows = deleteUri(uri)
                if (rows <= 0) {
                    remainingUris.add(uri)
                } else {
                    deletedUris.add(uri)
                }
            } catch (rse: RecoverableSecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // On API 30+, unowned items throw RecoverableSecurityException on direct delete.
                    // Accumulate all unowned items to request batch deletion consent in one prompt.
                    remainingUris.add(uri)
                } else {
                    // On API 29, Android has no batch createDeleteRequest API; prompt per-item.
                    return DeletionOutcome.RequiresUserConsent(
                        rse.userAction.actionIntent.intentSender,
                        listOf(uri),
                        deletedUris,
                        DeleteConsentMode.API29_RETRY_REQUIRED
                    )
                }
            } catch (se: SecurityException) {
                remainingUris.add(uri)
            } catch (e: Exception) {
                SafeLog.w("SourceDeletionCoordinator", "Could not directly delete a source item: $uri", e)
                remainingUris.add(uri)
            }
        }

        if (remainingUris.isEmpty()) {
            return DeletionOutcome.CompletedDirectly(deletedUris)
        }

        // On API 30+, request batch user permission via MediaStore.createDeleteRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mediaStoreUris = remainingUris.filter { it.authority == MediaStore.AUTHORITY }
            if (mediaStoreUris.isNotEmpty()) {
                return try {
                    val intentSender = createBatchDeleteRequest?.invoke(resolver, mediaStoreUris)
                        ?: MediaStore.createDeleteRequest(resolver, mediaStoreUris).intentSender
                    DeletionOutcome.RequiresUserConsent(
                        intentSender,
                        mediaStoreUris,
                        deletedUris,
                        DeleteConsentMode.API30_SYSTEM_DELETE_REQUEST
                    )
                } catch (e: Exception) {
                    SafeLog.e("SourceDeletionCoordinator", "Failed creating batch delete request", e)
                    DeletionOutcome.Failed(mediaStoreUris, "DELETE_REQUEST_FAILED", deletedUris)
                }
            } else {
                return DeletionOutcome.Failed(remainingUris, "NON_MEDIASTORE_URIS", deletedUris)
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
