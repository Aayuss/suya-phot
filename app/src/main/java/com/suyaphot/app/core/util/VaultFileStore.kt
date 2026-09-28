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

    fun getVaultDir(vaultId: String): File {
        return File(baseVaultDir, vaultId).apply { mkdirs() }
    }

    fun getPartialFile(vaultId: String, jobId: String): File {
        val dir = File(getVaultDir(vaultId), "partial").apply { mkdirs() }
        return File(dir, "$jobId.partial")
    }

    fun getMediaFile(vaultId: String, itemId: String): File {
        val prefix = if (itemId.length >= 2) itemId.substring(0, 2) else "xx"
        val dir = File(File(getVaultDir(vaultId), "media"), prefix).apply { mkdirs() }
        return File(dir, "$itemId.sph")
    }

    fun getThumbFile(vaultId: String, itemId: String): File {
        val prefix = if (itemId.length >= 2) itemId.substring(0, 2) else "xx"
        val dir = File(File(getVaultDir(vaultId), "thumbs"), prefix).apply { mkdirs() }
        return File(dir, "$itemId.sth")
    }

    fun getSecurityFile(vaultId: String, eventId: String): File {
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
                try {
                    partialFile.inputStream().use { input ->
                        FileOutputStream(finalFile).use { output ->
                            input.copyTo(output)
                            output.flush()
                            output.fd.sync()
                        }
                    }
                    partialFile.delete()
                    true
                } catch (ex: Exception) {
                    SafeLog.e("VaultFileStore", "Failed to commit partial file to ${finalFile.name}", ex)
                    false
                }
            }
        } catch (e: Exception) {
            SafeLog.e("VaultFileStore", "Failed to move partial file to ${finalFile.name}", e)
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
        val ext = if (extension.startsWith(".")) extension else ".$extension"
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
        val ext = if (extension.startsWith(".")) extension else ".$extension"
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

    /**
     * Calculates the total storage size used by a vault on disk (media + thumbs + security).
     */
    fun getVaultStorageBytes(vaultId: String): Long {
        val dir = File(baseVaultDir, vaultId)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
}
