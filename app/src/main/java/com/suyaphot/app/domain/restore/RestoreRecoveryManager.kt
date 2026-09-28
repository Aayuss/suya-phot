package com.suyaphot.app.domain.restore

import android.content.Context
import android.net.Uri
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class RestoreRecoveryManager(
    private val context: Context,
    private val database: SuyaDatabase,
    private val fileStore: VaultFileStore
) {
    suspend fun reconcile(session: VaultSession.Unlocked) = withContext(Dispatchers.IO) {
        val metaKey = session.metaSubkey.copyOf()
        try {
            for (job in database.restoreJobDao().getActiveForVault(session.vaultId)) {
                val phase = RestorePhase.entries.firstOrNull { it.code == job.phaseCode } ?: continue
                val uri = decryptUri(job.id, job.encryptedDestinationUri, metaKey)
                when (phase) {
                    RestorePhase.CREATED -> failBeforePublish(job.id)
                    RestorePhase.PUBLIC_PENDING_CREATED,
                    RestorePhase.WRITING,
                    RestorePhase.PLAINTEXT_VERIFIED -> {
                        uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                        failBeforePublish(job.id)
                    }
                    RestorePhase.PUBLIC_PUBLISHED,
                    RestorePhase.CLEANUP_PENDING -> reconcilePublished(job.id, job.mediaId, job.vaultId, job.move, uri)
                    RestorePhase.VAULT_MEDIA_REMOVED -> {
                        database.mediaItemDao().deleteForVault(job.mediaId, job.vaultId)
                        complete(job.id)
                    }
                    RestorePhase.DB_FINALIZED -> complete(job.id)
                    RestorePhase.COMPLETED,
                    RestorePhase.FAILED_BEFORE_PUBLISH -> Unit
                }
            }
        } finally {
            metaKey.fill(0)
        }
    }

    private fun decryptUri(jobId: String, encrypted: ByteArray?, metaKey: ByteArray): Uri? = runCatching {
        encrypted ?: return null
        val bytes = Aead.decryptWithPrependedNonce(
            metaKey, encrypted, "restore:$jobId:v1".toByteArray(Charsets.UTF_8)
        )
        try { Uri.parse(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
    }.getOrNull()

    private suspend fun reconcilePublished(jobId: String, mediaId: String, vaultId: String, move: Boolean, uri: Uri?) {
        if (!move) {
            complete(jobId)
            return
        }
        val item = database.mediaItemDao().getItemForVault(mediaId, vaultId)
        if (item != null && uri != null && !publicHashMatches(uri, item.sha256Hex)) {
            database.restoreJobDao().updatePhase(
                jobId, RestorePhase.CLEANUP_PENDING.code, null, System.currentTimeMillis(), "PUBLIC_REVERIFY_FAILED"
            )
            return
        }
        val media = fileStore.getMediaFile(vaultId, mediaId)
        if (media.exists() && !media.delete()) {
            database.restoreJobDao().updatePhase(
                jobId, RestorePhase.CLEANUP_PENDING.code, null, System.currentTimeMillis(), "PRIVATE_MEDIA_DELETE_FAILED"
            )
            return
        }
        runCatching { fileStore.getThumbFile(vaultId, mediaId).delete() }
        database.mediaItemDao().deleteForVault(mediaId, vaultId)
        complete(jobId)
    }

    private fun publicHashMatches(uri: Uri, expected: String): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input)
            val buffer = ByteArray(256 * 1024)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            } finally {
                buffer.fill(0)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }.getOrDefault(false)

    private suspend fun failBeforePublish(jobId: String) = database.restoreJobDao().updatePhase(
        jobId, RestorePhase.FAILED_BEFORE_PUBLISH.code, null, System.currentTimeMillis(), "INTERRUPTED_BEFORE_PUBLISH"
    )

    private suspend fun complete(jobId: String) = database.restoreJobDao().updatePhase(
        jobId, RestorePhase.COMPLETED.code, null, System.currentTimeMillis()
    )
}
