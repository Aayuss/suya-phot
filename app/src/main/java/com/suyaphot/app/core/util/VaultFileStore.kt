package com.suyaphot.app.core.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Manages physical storage paths and atomic file operations in app-private storage.
 */
class VaultFileStore(private val context: Context) {

    private val baseVaultDir: File
        get() = File(context.noBackupFilesDir, "vault")

    private val shareCacheDir: File
        get() = File(context.cacheDir, "share_cache").apply { mkdirs() }

    private val playbackCacheDir: File
        get() = File(context.cacheDir, "playback_cache").apply { mkdirs() }

    private val viewerCacheDir: File
        get() = File(context.cacheDir, "viewer_cache").apply { mkdirs() }

    private val intruderCaptureTempDir: File
        get() = File(context.cacheDir, "intruder_capture_tmp").apply { mkdirs() }

    private fun requireInternalId(value: String, label: String) {
        InternalId.requireValid(value, label)
    }

    fun createBackupRestoreStagingDir(): File {
        val root = File(context.noBackupFilesDir, "restore_staging").apply {
            check(exists() || mkdirs())
        }
        val dir = File(root, UUID.randomUUID().toString())
        check(dir.mkdir()) { "Could not create restore staging directory" }
        val rootCanonical = root.canonicalFile
        val dirCanonical = dir.canonicalFile
        check(dirCanonical.toPath().startsWith(rootCanonical.toPath())) {
            "Restore staging path escaped root"
        }
        return dirCanonical
    }

    fun clearStaleBackupRestoreStaging() {
        val root = File(context.noBackupFilesDir, "restore_staging")
        if (root.exists()) {
            root.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    child.deleteRecursively()
                } else {
                    child.delete()
                }
            }
        }
    }

    fun clearOrphanVaultDirs(activeVaultIds: Set<String>) {
        if (baseVaultDir.exists()) {
            baseVaultDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name !in activeVaultIds) {
                    SafeLog.w("VaultFileStore", "Cleaning orphan vault directory: ${dir.name}")
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun safeExtension(extension: String): String {
        val normalized = extension.removePrefix(".").lowercase()
        return normalized.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }?.let { ".$it" } ?: ".bin"
    }

    fun getVaultDir(vaultId: String): File {
        requireInternalId(vaultId, "vault id")
        return File(baseVaultDir, vaultId).apply { mkdirs() }
    }

    fun getPartialFile(vaultId: String, jobId: String): File {
        requireInternalId(jobId, "job id")
        val dir = File(getVaultDir(vaultId), "partial").apply { mkdirs() }
        return File(dir, "$jobId.partial")
    }

    fun getMediaFile(vaultId: String, itemId: String): File {
        requireInternalId(itemId, "item id")
        val prefix = if (itemId.length >= 2) itemId.substring(0, 2) else "xx"
        val dir = File(File(getVaultDir(vaultId), "media"), prefix).apply { mkdirs() }
        return File(dir, "$itemId.sph")
    }

    fun getThumbFile(vaultId: String, itemId: String): File {
        requireInternalId(itemId, "item id")
        val prefix = if (itemId.length >= 2) itemId.substring(0, 2) else "xx"
        val dir = File(File(getVaultDir(vaultId), "thumbs"), prefix).apply { mkdirs() }
        return File(dir, "$itemId.sth")
    }

    fun getPreviewFile(vaultId: String, itemId: String): File {
        requireInternalId(itemId, "item id")
        val prefix = if (itemId.length >= 2) itemId.substring(0, 2) else "xx"
        val dir = File(File(getVaultDir(vaultId), "previews"), prefix).apply { mkdirs() }
        return File(dir, "$itemId.spr")
    }

    fun getSecurityFile(vaultId: String, eventId: String): File {
        requireInternalId(eventId, "event id")
        val dir = File(getVaultDir(vaultId), "security").apply { mkdirs() }
        return File(dir, "$eventId.sph")
    }

    /**
     * Atomically commits a partial file to the final destination using atomic rename.
     * Never overwrites existing final ciphertext.
     */
    fun commitPartial(partialFile: File, finalFile: File): Boolean {
        require(partialFile.exists()) { "Partial file does not exist: ${partialFile.absolutePath}" }
        check(!finalFile.exists()) { "Refusing to overwrite existing vault ciphertext: ${finalFile.absolutePath}" }
        finalFile.parentFile?.mkdirs()

        return try {
            Files.move(
                partialFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
            true
        } catch (e: AtomicMoveNotSupportedException) {
            if (partialFile.renameTo(finalFile)) {
                true
            } else {
                val copyTemp = File(finalFile.parentFile, "${finalFile.name}.copying")
                try {
                    check(!copyTemp.exists()) { "Ciphertext copy staging path already exists" }
                    partialFile.inputStream().use { input ->
                        FileOutputStream(copyTemp).use { output ->
                            input.copyTo(output)
                            output.flush()
                            output.fd.sync()
                        }
                    }
                    check(copyTemp.length() == partialFile.length()) { "Ciphertext staging size mismatch" }
                    check(copyTemp.renameTo(finalFile)) { "Could not commit copied ciphertext" }
                    if (!partialFile.delete()) {
                        SafeLog.w("VaultFileStore", "Committed ciphertext but stale partial remains")
                    }
                    true
                } catch (ex: Exception) {
                    runCatching { copyTemp.delete() }
                    SafeLog.e("VaultFileStore", "Failed to commit ciphertext staging file")
                    false
                }
            }
        } catch (e: Exception) {
            SafeLog.e("VaultFileStore", "Failed to move ciphertext staging file")
            false
        }
    }

    /**
     * Cleans up orphaned .partial files.
     */
    fun cleanPartialFiles(vaultId: String) {
        val partialDir = File(getVaultDir(vaultId), "partial")
        if (partialDir.exists() && partialDir.isDirectory) {
            partialDir.listFiles()?.forEach { file ->
                file.delete()
            }
        }
    }

    /**
     * Creates a temporary decrypted file in private cache for outgoing sharing via FileProvider.
     */
    fun createShareTempFile(extension: String): File {
        val ext = safeExtension(extension)
        val filename = "share_${UUID.randomUUID()}$ext"
        return File(shareCacheDir, filename)
    }

    /**
     * Clears all temporary share cache files.
     */
    fun clearShareCache() {
        if (shareCacheDir.exists() && shareCacheDir.isDirectory) {
            shareCacheDir.listFiles()?.forEach { file ->
                file.delete()
            }
        }
    }

    /**
     * Creates a temporary file in dedicated playback cache.
     */
    fun createPlaybackTempFile(extension: String): File {
        val ext = safeExtension(extension)
        val filename = "playback_${UUID.randomUUID()}$ext"
        return File(playbackCacheDir, filename)
    }

    /**
     * Clears all temporary video playback cache files.
     */
    fun clearPlaybackCache() {
        if (playbackCacheDir.exists() && playbackCacheDir.isDirectory) {
            playbackCacheDir.listFiles()?.forEach { file ->
                file.delete()
            }
        }
    }

    fun createViewerTempFile(itemId: String, extension: String): File {
        requireInternalId(itemId, "item id")
        return File(viewerCacheDir, "viewer_${itemId}_${UUID.randomUUID()}${safeExtension(extension)}")
    }

    fun createIntruderCaptureTempFile(): File =
        File(intruderCaptureTempDir, "capture_${UUID.randomUUID()}.jpg")

    fun clearEphemeralPlaintextCaches() {
        listOf(shareCacheDir, playbackCacheDir, viewerCacheDir, intruderCaptureTempDir).forEach { dir ->
            dir.listFiles()?.forEach { file -> file.delete() }
        }
    }

    /**
     * Calculates the total storage size used by a vault on disk (media + thumbs + security).
     */
    fun getVaultStorageBytes(vaultId: String): Long {
        val dir = File(baseVaultDir, vaultId)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
}
