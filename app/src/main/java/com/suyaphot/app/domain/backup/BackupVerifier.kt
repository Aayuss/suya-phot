package com.suyaphot.app.domain.backup

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.HkdfSha256
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.util.InternalId
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.security.MessageDigest

data class BackupArchiveHeader(
    val version: Int,
    val salt: ByteArray,
    val recoveryEnvelope: ByteArray,
    val manifestNonce: ByteArray,
    val manifestCiphertext: ByteArray
)

data class BackupSummary(
    val archiveId: String,
    val version: Int,
    val createdAt: Long,
    val schemaVersion: Int,
    val vaultId: String,
    val vaultKindCode: Int,
    val folderCount: Int,
    val lockCount: Int,
    val mediaCount: Int,
    val totalPlaintextSize: Long
)

class BackupVerifier(private val keyManager: KeyManager) {

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Reads and parses only the header and encrypted manifest without consuming entries.
     */
    fun readHeader(dis: DataInputStream): BackupArchiveHeader {
        val magic = ByteArray(4)
        try {
            dis.readFully(magic)
        } catch (e: Exception) {
            throw BackupException(BackupError.INVALID_ARCHIVE, "Truncated backup archive", e)
        }
        if (!magic.contentEquals(BackupArchiveFormat.MAGIC)) {
            throw BackupException(BackupError.INVALID_ARCHIVE, "Not a valid Suya Phot backup file")
        }

        val version = dis.readInt()
        if (version !in BackupArchiveFormat.SUPPORTED_VERSIONS) {
            throw BackupException(BackupError.UNSUPPORTED_VERSION, "Unsupported backup version: $version")
        }

        val salt = ByteArray(16)
        dis.readFully(salt)

        val envLen = dis.readInt()
        if (envLen !in 48..256) {
            throw BackupException(BackupError.INVALID_ARCHIVE, "Invalid recovery envelope length: $envLen")
        }
        val recoveryEnvelope = ByteArray(envLen)
        dis.readFully(recoveryEnvelope)

        val manifestLen = dis.readInt()
        if (manifestLen !in 16..BackupLimits.MAX_MANIFEST_CIPHERTEXT_BYTES) {
            throw BackupException(BackupError.INVALID_ARCHIVE, "Invalid manifest length: $manifestLen")
        }
        val manifestNonce = ByteArray(12)
        dis.readFully(manifestNonce)
        val manifestCiphertext = ByteArray(manifestLen)
        dis.readFully(manifestCiphertext)

        return BackupArchiveHeader(
            version = version,
            salt = salt,
            recoveryEnvelope = recoveryEnvelope,
            manifestNonce = manifestNonce,
            manifestCiphertext = manifestCiphertext
        )
    }

    fun readHeader(inputStream: InputStream): BackupArchiveHeader {
        return readHeader(DataInputStream(inputStream))
    }

    /**
     * Unwraps master key and decrypts the backup manifest using the user's Recovery Code.
     * Returns a Pair of BackupManifest and decrypted master key bytes (caller MUST zero master key).
     */
    fun decryptManifestAndMasterKey(
        dis: DataInputStream,
        recoveryCodeInput: String
    ): Pair<BackupManifest, ByteArray> {
        val header = readHeader(dis)

        if (!keyManager.isValidRecoverySecret(recoveryCodeInput)) {
            throw BackupException(
                BackupError.INVALID_RECOVERY_FORMAT,
                "Recovery code must be exactly 26 Base32 characters."
            )
        }

        val normalized = keyManager.normalizeRecoverySecret(recoveryCodeInput)
        val secretBytes = normalized.toByteArray(Charsets.UTF_8)
        val recoveryKek = ByteArray(32)
        val masterKey: ByteArray
        try {
            val derived = HkdfSha256.derive(
                ikm = secretBytes,
                salt = header.salt,
                info = BackupArchiveFormat.KEK_INFO.toByteArray(Charsets.UTF_8),
                length = 32
            )
            System.arraycopy(derived, 0, recoveryKek, 0, 32)
            derived.fill(0)

            if (header.recoveryEnvelope.size < 12 + 16) {
                throw BackupException(BackupError.INVALID_ARCHIVE, "Corrupt recovery envelope")
            }
            val envNonce = header.recoveryEnvelope.copyOfRange(0, 12)
            val envCiphertext = header.recoveryEnvelope.copyOfRange(12, header.recoveryEnvelope.size)

            masterKey = try {
                Aead.decrypt(
                    keyBytes = recoveryKek,
                    nonce = envNonce,
                    aad = BackupArchiveFormat.RECOVERY_AAD.toByteArray(Charsets.UTF_8),
                    ciphertext = envCiphertext
                )
            } catch (e: Exception) {
                throw BackupException(
                    BackupError.INCORRECT_RECOVERY_CODE,
                    "Incorrect Recovery Code or corrupt backup header",
                    e
                )
            }
        } finally {
            secretBytes.fill(0)
            recoveryKek.fill(0)
        }

        val manifestKey = ByteArray(32)
        val manifestJsonString: String
        try {
            val derived = HkdfSha256.derive(
                ikm = masterKey,
                salt = header.salt,
                info = BackupArchiveFormat.MANIFEST_KEY_INFO.toByteArray(Charsets.UTF_8),
                length = 32
            )
            System.arraycopy(derived, 0, manifestKey, 0, 32)
            derived.fill(0)

            val manifestBytes = try {
                Aead.decrypt(
                    keyBytes = manifestKey,
                    nonce = header.manifestNonce,
                    aad = BackupArchiveFormat.MANIFEST_AAD.toByteArray(Charsets.UTF_8),
                    ciphertext = header.manifestCiphertext
                )
            } catch (e: Exception) {
                masterKey.fill(0)
                throw BackupException(BackupError.CORRUPT_MEDIA, "Failed to decrypt backup manifest", e)
            }
            try {
                manifestJsonString = manifestBytes.toString(Charsets.UTF_8)
            } finally {
                manifestBytes.fill(0)
            }
        } finally {
            manifestKey.fill(0)
        }

        val manifest = try {
            BackupManifest.fromJsonString(manifestJsonString)
        } catch (e: Exception) {
            masterKey.fill(0)
            throw BackupException(BackupError.INVALID_ARCHIVE, "Malformed backup manifest JSON", e)
        }

        try {
            BackupManifestValidator.validate(header.version, manifest)
        } catch (e: Exception) {
            masterKey.fill(0)
            throw e
        }

        return Pair(manifest, masterKey)
    }

    /**
     * Inspects only the header and manifest without full media verification.
     * Use before PIN setup or for metadata summary.
     */
    fun inspectManifest(inputStream: InputStream, recoveryCodeInput: String): BackupSummary {
        val dis = DataInputStream(BufferedInputStream(inputStream, BackupArchiveFormat.BUFFER_SIZE))
        val (manifest, masterKey) = decryptManifestAndMasterKey(dis, recoveryCodeInput)
        masterKey.fill(0)

        val totalPlaintext = manifest.mediaItems.sumOf { it.plaintextSize }
        return BackupSummary(
            archiveId = manifest.archiveId,
            version = manifest.version,
            createdAt = manifest.createdAt,
            schemaVersion = manifest.schemaVersion,
            vaultId = manifest.vaultId,
            vaultKindCode = manifest.vaultKindCode,
            folderCount = manifest.folders.size,
            lockCount = manifest.folderLocks.size,
            mediaCount = manifest.mediaItems.size,
            totalPlaintextSize = totalPlaintext
        )
    }

    fun verifyAndInspect(inputStream: InputStream, recoveryCodeInput: String): BackupSummary =
        inspectManifest(inputStream, recoveryCodeInput)

    /**
     * Full post-write verifier that checks header, manifest, and every body entry
     * against authenticated descriptors without writing to permanent storage.
     */
    fun verifyFullArchive(inputStream: InputStream, recoveryCodeInput: String): BackupSummary {
        val dis = DataInputStream(BufferedInputStream(inputStream, BackupArchiveFormat.BUFFER_SIZE))
        val (manifest, masterKey) = decryptManifestAndMasterKey(dis, recoveryCodeInput)
        masterKey.fill(0)

        val validated = BackupManifestValidator.validate(manifest.version, manifest)
        val seenEntries = HashSet<BackupEntryKey>()

        val buffer = ByteArray(BackupArchiveFormat.BUFFER_SIZE)
        var reachedEnd = false

        while (!reachedEnd) {
            val nextByte = dis.read()
            if (nextByte == -1) {
                throw BackupException(BackupError.INVALID_ARCHIVE, "Unexpected end of file before end marker")
            }

            if (nextByte.toByte() == BackupArchiveFormat.END_MARKER[0]) {
                val remainingEnd = ByteArray(3)
                dis.readFully(remainingEnd)
                val fullEnd = byteArrayOf(nextByte.toByte(), remainingEnd[0], remainingEnd[1], remainingEnd[2])
                if (!fullEnd.contentEquals(BackupArchiveFormat.END_MARKER)) {
                    throw BackupException(BackupError.INVALID_ARCHIVE, "Corrupted archive termination marker")
                }
                reachedEnd = true
                break
            }

            val entryType = nextByte.toByte()
            val idLength = dis.readShort().toInt() and 0xFFFF
            if (idLength !in 1..256) {
                throw BackupException(BackupError.INVALID_ARCHIVE, "Invalid entry ID length: $idLength")
            }
            val idBytes = ByteArray(idLength)
            dis.readFully(idBytes)
            val entryId = idBytes.toString(Charsets.UTF_8)
            val entryLength = dis.readLong()
            if (entryLength < 0 || entryLength > BackupLimits.MAX_SINGLE_MEDIA_BYTES) {
                throw BackupException(BackupError.INVALID_ARCHIVE, "Invalid entry body length: $entryLength")
            }

            if (!InternalId.isValid(entryId)) {
                throw BackupException(BackupError.INVALID_ARCHIVE, "Invalid entry ID format: $entryId")
            }

            val key = BackupEntryKey(entryType, entryId)
            if (!seenEntries.add(key)) {
                throw BackupException(BackupError.INVALID_ARCHIVE, "Duplicate entry in backup: type=$entryType, id=$entryId")
            }

            val expectedDesc = validated.descriptorsByKey[key]
            if (manifest.version >= BackupArchiveFormat.VERSION_2) {
                if (expectedDesc == null) {
                    throw BackupException(
                        BackupError.INVALID_ARCHIVE,
                        "Body entry ($entryType, $entryId) was not declared in authenticated descriptors"
                    )
                }
                if (expectedDesc.cipherLength != entryLength) {
                    throw BackupException(
                        BackupError.CORRUPT_MEDIA,
                        "Declared body length ${expectedDesc.cipherLength} != entry length $entryLength for $entryId"
                    )
                }
            } else {
                // P0-C: Strict V1 body grammar
                if (entryType !in listOf(
                        BackupArchiveFormat.ENTRY_TYPE_MEDIA,
                        BackupArchiveFormat.ENTRY_TYPE_THUMB,
                        BackupArchiveFormat.ENTRY_TYPE_PREVIEW
                    )
                ) {
                    throw BackupException(BackupError.INVALID_ARCHIVE, "Unknown V1 entry type: $entryType")
                }

                val media = validated.mediaById[entryId]
                    ?: throw BackupException(BackupError.INVALID_ARCHIVE, "Undeclared V1 media item ID: $entryId")

                when (entryType) {
                    BackupArchiveFormat.ENTRY_TYPE_MEDIA -> {
                        if (entryLength != media.cipherSize) {
                            throw BackupException(
                                BackupError.CORRUPT_MEDIA,
                                "Declared V1 cipher size ${media.cipherSize} != entry length $entryLength for $entryId"
                            )
                        }
                    }
                    BackupArchiveFormat.ENTRY_TYPE_THUMB -> {
                        if (!media.hasThumb) {
                            throw BackupException(BackupError.INVALID_ARCHIVE, "Undeclared V1 thumbnail for $entryId")
                        }
                        if (entryLength > BackupLimits.MAX_V1_THUMB_BYTES) {
                            throw BackupException(BackupError.INVALID_ARCHIVE, "Oversized V1 thumbnail for $entryId: $entryLength")
                        }
                    }
                    BackupArchiveFormat.ENTRY_TYPE_PREVIEW -> {
                        if (!media.hasPreview) {
                            throw BackupException(BackupError.INVALID_ARCHIVE, "Undeclared V1 preview for $entryId")
                        }
                        if (entryLength > BackupLimits.MAX_V1_PREVIEW_BYTES) {
                            throw BackupException(BackupError.INVALID_ARCHIVE, "Oversized V1 preview for $entryId: $entryLength")
                        }
                    }
                }
            }

            // Stream body and compute SHA-256
            val digest = MessageDigest.getInstance("SHA-256")
            var remaining = entryLength
            while (remaining > 0L) {
                val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                val read = dis.read(buffer, 0, toRead)
                if (read == -1) {
                    throw BackupException(BackupError.INVALID_ARCHIVE, "Premature EOF in entry body for $entryId")
                }
                digest.update(buffer, 0, read)
                remaining -= read
            }

            val computedSha256 = digest.digest().toHex()
            val trailingSha = ByteArray(32)
            dis.readFully(trailingSha)
            val trailingShaHex = trailingSha.toHex()

            if (!computedSha256.equals(trailingShaHex, ignoreCase = true)) {
                throw BackupException(BackupError.CORRUPT_MEDIA, "Body SHA-256 mismatch for entry $entryId")
            }

            if (expectedDesc != null) {
                if (!expectedDesc.cipherSha256Hex.equals(computedSha256, ignoreCase = true)) {
                    throw BackupException(
                        BackupError.CORRUPT_MEDIA,
                        "Authenticated descriptor SHA-256 mismatch for entry $entryId"
                    )
                }
            }
        }

        // Verify all declared descriptors were actually seen
        if (manifest.version >= BackupArchiveFormat.VERSION_2) {
            val expectedKeys = validated.descriptorsByKey.keys
            if (seenEntries != expectedKeys) {
                throw BackupException(
                    BackupError.INVALID_ARCHIVE,
                    "Body entries do not match authenticated descriptors"
                )
            }
        } else {
            for (m in manifest.mediaItems) {
                val requiredKey = BackupEntryKey(BackupArchiveFormat.ENTRY_TYPE_MEDIA, m.id)
                if (requiredKey !in seenEntries) {
                    throw BackupException(
                        BackupError.MISSING_MEDIA,
                        "Missing required media body for item ${m.id}"
                    )
                }
            }
        }

        // Check trailing bytes
        if (dis.read() != -1) {
            throw BackupException(BackupError.INVALID_ARCHIVE, "Unexpected trailing bytes after end marker")
        }

        val totalPlaintext = manifest.mediaItems.sumOf { it.plaintextSize }
        return BackupSummary(
            archiveId = manifest.archiveId,
            version = manifest.version,
            createdAt = manifest.createdAt,
            schemaVersion = manifest.schemaVersion,
            vaultId = manifest.vaultId,
            vaultKindCode = manifest.vaultKindCode,
            folderCount = manifest.folders.size,
            lockCount = manifest.folderLocks.size,
            mediaCount = manifest.mediaItems.size,
            totalPlaintextSize = totalPlaintext
        )
    }
}
