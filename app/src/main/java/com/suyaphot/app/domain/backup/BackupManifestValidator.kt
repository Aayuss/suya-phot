package com.suyaphot.app.domain.backup

import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.core.util.InternalId

data class BackupEntryKey(
    val typeCode: Byte,
    val itemId: String
)

data class ValidatedBackupManifest(
    val manifest: BackupManifest,
    val descriptorsByKey: Map<BackupEntryKey, BackupFileDescriptor>,
    val folderById: Map<String, BackupFolderEntry>,
    val lockById: Map<String, BackupFolderLockEntry>,
    val mediaById: Map<String, BackupMediaItemEntry>,
    val declaredBodyBytes: Long
)

object BackupManifestValidator {
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    private const val MAX_DEPTH = 20
    private const val MAX_CIPHER_SIZE = 100_000_000_000L // 100 GB cap per media item
    private const val MAX_THUMB_SIZE = 10_000_000L // 10 MB cap for thumb
    private const val MAX_PREVIEW_SIZE = 50_000_000L // 50 MB cap for preview

    private fun invalid(message: String, cause: Throwable? = null): Nothing {
        throw BackupException(BackupError.INVALID_ARCHIVE, message, cause)
    }

    private fun checkValidHex(hex: String, label: String, minBytes: Int = 0, maxBytes: Int = Int.MAX_VALUE) {
        if (hex.length % 2 != 0) {
            invalid("$label hex length must be even: ${hex.length}")
        }
        val byteLength = hex.length / 2
        if (byteLength < minBytes || byteLength > maxBytes) {
            invalid("$label byte length $byteLength outside permitted range [$minBytes, $maxBytes]")
        }
        for (i in hex.indices) {
            val c = hex[i]
            val isHex = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
            if (!isHex) {
                invalid("$label contains non-hex character '$c'")
            }
        }
    }

    fun validateManifest(manifest: BackupManifest, headerVersion: Int = manifest.version): ValidatedBackupManifest =
        validate(headerVersion, manifest)

    fun validate(headerVersion: Int, manifest: BackupManifest): ValidatedBackupManifest {
        // 1. Version checks
        if (headerVersion != manifest.version) {
            throw BackupException(
                BackupError.UNSUPPORTED_VERSION,
                "Header version ($headerVersion) does not match manifest version (${manifest.version})"
            )
        }
        if (manifest.version !in BackupArchiveFormat.SUPPORTED_VERSIONS) {
            throw BackupException(
                BackupError.UNSUPPORTED_VERSION,
                "Unsupported backup archive version: ${manifest.version}"
            )
        }

        // 2. Vault kind validation: REAL vault only
        if (manifest.vaultKindCode != VaultKind.REAL.code) {
            invalid("Unsupported vault kind in backup: ${manifest.vaultKindCode}")
        }

        // 3. Strict internal IDs
        try {
            InternalId.requireValid(manifest.archiveId, "archive ID")
            InternalId.requireValid(manifest.vaultId, "vault ID")
        } catch (e: Exception) {
            invalid("Invalid archive or vault ID", e)
        }

        // 4. Folders validation
        val folderMap = LinkedHashMap<String, BackupFolderEntry>(manifest.folders.size)
        for (f in manifest.folders) {
            try {
                InternalId.requireValid(f.id, "folder ID")
                if (f.parentId != null) {
                    InternalId.requireValid(f.parentId, "parent folder ID")
                    if (f.parentId == f.id) {
                        invalid("Folder ${f.id} cannot be its own parent")
                    }
                }
                if (f.lockId != null) {
                    InternalId.requireValid(f.lockId, "folder lock ID")
                }
            } catch (e: Exception) {
                invalid("Malformed folder ID: ${e.message}", e)
            }

            checkValidHex(f.encryptedNameHex, "folder ${f.id} encryptedNameHex", minBytes = 1, maxBytes = 2048)

            if (folderMap.put(f.id, f) != null) {
                invalid("Duplicate folder ID in manifest: ${f.id}")
            }
        }

        // Folder cycle and depth check (O(N) with visited tracking)
        for (f in manifest.folders) {
            var curr = f.parentId
            var depth = 0
            val visited = HashSet<String>()
            visited.add(f.id)
            while (curr != null) {
                if (depth >= MAX_DEPTH) {
                    invalid("Folder hierarchy exceeds maximum depth $MAX_DEPTH at folder ${f.id}")
                }
                if (!visited.add(curr)) {
                    invalid("Folder cycle detected at folder ${f.id} encountering $curr")
                }
                val parent = folderMap[curr] ?: invalid("Folder ${f.id} references missing parent $curr")
                curr = parent.parentId
                depth++
            }
        }

        // 5. Folder Locks validation
        val lockMap = LinkedHashMap<String, BackupFolderLockEntry>(manifest.folderLocks.size)
        val folderToLock = HashMap<String, String>()
        for (l in manifest.folderLocks) {
            try {
                InternalId.requireValid(l.id, "lock ID")
                InternalId.requireValid(l.folderId, "lock folder ID")
            } catch (e: Exception) {
                invalid("Malformed lock ID: ${e.message}", e)
            }

            val folder = folderMap[l.folderId]
                ?: invalid("Folder lock ${l.id} references non-existent folder ${l.folderId}")

            if (folderToLock.put(l.folderId, l.id) != null) {
                invalid("Multiple locks declared for folder ${l.folderId}")
            }

            if (folder.lockId != l.id) {
                invalid("Folder ${folder.id} lockId (${folder.lockId}) does not match lock ${l.id}")
            }

            if (l.credentialTypeCode !in 0..1) {
                invalid("Unknown lock credential type code ${l.credentialTypeCode} for lock ${l.id}")
            }

            // Credential envelope: nonce (12) + tag (16) minimum
            checkValidHex(l.credentialEnvelopeHex, "lock ${l.id} credentialEnvelopeHex", minBytes = 28, maxBytes = 1024)

            // V2 portable recovery envelope is required for every lock
            if (manifest.version >= BackupArchiveFormat.VERSION_2) {
                val recoveryHex = l.recoveryEnvelopeHex
                if (recoveryHex.isNullOrBlank()) {
                    invalid("Folder lock ${l.id} lacks required portable recovery envelope")
                }
                checkValidHex(recoveryHex, "lock ${l.id} recoveryEnvelopeHex", minBytes = 28, maxBytes = 1024)
            } else if (l.recoveryEnvelopeHex != null) {
                checkValidHex(l.recoveryEnvelopeHex, "lock ${l.id} recoveryEnvelopeHex", minBytes = 28, maxBytes = 1024)
            }

            if (lockMap.put(l.id, l) != null) {
                invalid("Duplicate lock ID in manifest: ${l.id}")
            }
        }

        // Verify that every folder declaring a lockId has a corresponding lock entry
        for (f in manifest.folders) {
            if (f.lockId != null && !lockMap.containsKey(f.lockId)) {
                invalid("Folder ${f.id} references non-existent lock ${f.lockId}")
            }
        }

        // 6. Media Items validation
        val mediaMap = LinkedHashMap<String, BackupMediaItemEntry>(manifest.mediaItems.size)
        for (m in manifest.mediaItems) {
            try {
                InternalId.requireValid(m.id, "media ID")
                if (m.folderId != null) {
                    InternalId.requireValid(m.folderId, "media folder ID")
                }
                if (m.previousFolderId != null) {
                    InternalId.requireValid(m.previousFolderId, "media previousFolderId")
                }
            } catch (e: Exception) {
                invalid("Malformed media or folder ID in media item ${m.id}: ${e.message}", e)
            }

            if (m.mediaTypeCode != MediaType.IMAGE.code && m.mediaTypeCode != MediaType.VIDEO.code) {
                invalid("Unknown media type code ${m.mediaTypeCode} for item ${m.id}")
            }

            if (m.folderId != null && !folderMap.containsKey(m.folderId)) {
                invalid("Media item ${m.id} references non-existent folder ${m.folderId}")
            }

            if (!SHA256_PATTERN.matches(m.sha256Hex)) {
                invalid("Invalid sha256Hex '${m.sha256Hex}' for media item ${m.id}")
            }

            if (m.plaintextSize < 0 || m.plaintextSize > MAX_CIPHER_SIZE) {
                invalid("Invalid plaintextSize ${m.plaintextSize} for media item ${m.id}")
            }

            if (m.cipherSize < m.plaintextSize || m.cipherSize > MAX_CIPHER_SIZE) {
                invalid("Invalid cipherSize ${m.cipherSize} for media item ${m.id}")
            }

            checkValidHex(m.encryptedMetadataHex, "media ${m.id} encryptedMetadataHex", minBytes = 16, maxBytes = 8192)

            if (mediaMap.put(m.id, m) != null) {
                invalid("Duplicate media item ID in manifest: ${m.id}")
            }
        }

        // 7. Descriptors validation (for V2+)
        val descriptorsByKey = LinkedHashMap<BackupEntryKey, BackupFileDescriptor>()
        var declaredBytes = 0L

        if (manifest.version >= BackupArchiveFormat.VERSION_2) {
            for (desc in manifest.descriptors) {
                try {
                    InternalId.requireValid(desc.itemId, "descriptor item ID")
                } catch (e: Exception) {
                    invalid("Malformed descriptor item ID: ${desc.itemId}", e)
                }

                if (desc.typeCode !in listOf(
                        BackupArchiveFormat.ENTRY_TYPE_MEDIA,
                        BackupArchiveFormat.ENTRY_TYPE_THUMB,
                        BackupArchiveFormat.ENTRY_TYPE_PREVIEW
                    )
                ) {
                    invalid("Unknown descriptor entry type ${desc.typeCode} for item ${desc.itemId}")
                }

                val media = mediaMap[desc.itemId]
                    ?: invalid("Descriptor references non-existent media item ${desc.itemId}")

                val key = BackupEntryKey(desc.typeCode, desc.itemId)
                if (descriptorsByKey.put(key, desc) != null) {
                    invalid("Duplicate descriptor for key ($key)")
                }

                val maxAllowed = when (desc.typeCode) {
                    BackupArchiveFormat.ENTRY_TYPE_MEDIA -> MAX_CIPHER_SIZE
                    BackupArchiveFormat.ENTRY_TYPE_THUMB -> MAX_THUMB_SIZE
                    BackupArchiveFormat.ENTRY_TYPE_PREVIEW -> MAX_PREVIEW_SIZE
                    else -> MAX_CIPHER_SIZE
                }

                if (desc.cipherLength <= 0 || desc.cipherLength > maxAllowed) {
                    invalid("Invalid descriptor cipherLength ${desc.cipherLength} for entry ($key)")
                }

                if (!SHA256_PATTERN.matches(desc.cipherSha256Hex)) {
                    invalid("Invalid cipherSha256Hex in descriptor for entry ($key)")
                }

                try {
                    declaredBytes = Math.addExact(declaredBytes, desc.cipherLength)
                } catch (e: ArithmeticException) {
                    invalid("Declared body bytes overflow arithmetic bounds", e)
                }
            }

            // Completeness check for V2: every media must have exactly one MEDIA descriptor
            for (m in manifest.mediaItems) {
                val mediaKey = BackupEntryKey(BackupArchiveFormat.ENTRY_TYPE_MEDIA, m.id)
                val mediaDesc = descriptorsByKey[mediaKey]
                    ?: invalid("Missing required MEDIA descriptor for item ${m.id}")

                if (mediaDesc.cipherLength != m.cipherSize) {
                    invalid("MEDIA descriptor cipherLength (${mediaDesc.cipherLength}) != item cipherSize (${m.cipherSize}) for ${m.id}")
                }

                val thumbKey = BackupEntryKey(BackupArchiveFormat.ENTRY_TYPE_THUMB, m.id)
                if (m.hasThumb) {
                    if (!descriptorsByKey.containsKey(thumbKey)) {
                        invalid("Item ${m.id} declared hasThumb=true but lacks THUMB descriptor")
                    }
                } else {
                    if (descriptorsByKey.containsKey(thumbKey)) {
                        invalid("Item ${m.id} declared hasThumb=false but has unexpected THUMB descriptor")
                    }
                }

                val previewKey = BackupEntryKey(BackupArchiveFormat.ENTRY_TYPE_PREVIEW, m.id)
                if (m.hasPreview) {
                    if (!descriptorsByKey.containsKey(previewKey)) {
                        invalid("Item ${m.id} declared hasPreview=true but lacks PREVIEW descriptor")
                    }
                } else {
                    if (descriptorsByKey.containsKey(previewKey)) {
                        invalid("Item ${m.id} declared hasPreview=false but has unexpected PREVIEW descriptor")
                    }
                }
            }

            // Verify that no extra orphan descriptors exist
            var expectedDescCount = 0
            for (m in manifest.mediaItems) {
                expectedDescCount++ // media
                if (m.hasThumb) expectedDescCount++
                if (m.hasPreview) expectedDescCount++
            }
            if (descriptorsByKey.size != expectedDescCount) {
                invalid("Descriptor count mismatch: expected $expectedDescCount, found ${descriptorsByKey.size}")
            }
        } else {
            // V1 legacy archive fallback calculation
            for (m in manifest.mediaItems) {
                try {
                    val entryOverhead = 1L + 2L + m.id.toByteArray(Charsets.UTF_8).size.toLong() + 8L + 32L
                    val totalEntryBytes = Math.addExact(m.cipherSize, entryOverhead)
                    declaredBytes = Math.addExact(declaredBytes, totalEntryBytes)
                } catch (e: ArithmeticException) {
                    invalid("Declared body bytes overflow arithmetic bounds", e)
                }
            }
        }

        return ValidatedBackupManifest(
            manifest = manifest,
            descriptorsByKey = descriptorsByKey,
            folderById = folderMap,
            lockById = lockMap,
            mediaById = mediaMap,
            declaredBodyBytes = declaredBytes
        )
    }
}
