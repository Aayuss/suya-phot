package com.suyaphot.app.domain.gallery

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.folders.FolderAccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

data class PreparedShare(val uri: Uri, val mimeType: String)

class ShareCoordinator(
    private val context: Context,
    private val database: SuyaDatabase,
    private val sessionManager: SessionManager,
    private val folderAccessManager: FolderAccessManager,
    private val fileStore: VaultFileStore,
    private val crypto: VaultCrypto
) {
    suspend fun prepare(itemId: String, onProgress: ((Long, Long) -> Unit)? = null): PreparedShare? =
        withContext(Dispatchers.IO) {
            val lease = sessionManager.acquireOperationKeyLease() ?: return@withContext null
            try {
                val item = database.mediaItemDao().getItemForVault(itemId, lease.vaultId) ?: return@withContext null
                if (item.deletedAt != null) return@withContext null
                if (item.concealed && (item.folderId == null || !folderAccessManager.canOpen(lease.vaultId, item.folderId))) {
                    return@withContext null
                }
                val rawMeta = Aead.decryptWithPrependedNonce(lease.metaSubkey, item.encryptedMetadata, itemId.toByteArray())
                val metadata = try { PrivateMediaMetadata.deserialize(rawMeta) } finally { rawMeta.fill(0) }
                val mime = metadata.originalMimeType.takeIf {
                    if (item.mediaTypeCode == 1) it.startsWith("video/") else it.startsWith("image/")
                } ?: if (item.mediaTypeCode == 1) "video/mp4" else "image/jpeg"
                // A share is an extra plaintext copy, so fail before creating it when space is low.
                val temp = fileStore.createShareTempFile(metadata.originalFileExtension ?: if (item.mediaTypeCode == 1) "mp4" else "jpg")
                if ((temp.parentFile?.usableSpace ?: 0L) < item.plaintextSize + 16L * 1024 * 1024) return@withContext null
                var ready = false
                try {
                    val encrypted = fileStore.getMediaFile(lease.vaultId, itemId)
                    val result = crypto.decryptVerifiedToFile(encrypted, lease.mediaSubkey, itemId, temp, onProgress)
                    val hash = result.sha256.joinToString("") { "%02x".format(it) }
                    check(hash.equals(item.sha256Hex, ignoreCase = true) && result.plaintextSize == item.plaintextSize)
                    if (sessionManager.currentVaultId != lease.vaultId) return@withContext null
                    if (item.concealed && (item.folderId == null || !folderAccessManager.canOpen(lease.vaultId, item.folderId))) {
                        return@withContext null
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", temp)
                    ready = true
                    PreparedShare(uri, mime)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } finally {
                    // Keep only a fully verified file served through FileProvider.
                    if (!ready) temp.delete()
                }
            } finally { lease.close() }
        }
}
