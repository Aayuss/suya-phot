package com.suyaphot.app.domain.backup

/**
 * Shared cryptographic formatting and domain separation constants for folder lock envelopes.
 */
object FolderLockCryptoFormat {
    fun recoveryAad(lockId: String): ByteArray =
        "folder-recovery:$lockId:v1".toByteArray(Charsets.UTF_8)
}
