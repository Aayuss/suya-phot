package com.suyaphot.app.domain.backup

/**
 * Hard architectural limits for Suya Phot backup archive parsing and structural validation.
 */
object BackupLimits {
    const val MAX_MANIFEST_CIPHERTEXT_BYTES: Int = 10_000_000 // 10 MB
    const val MAX_FOLDERS: Int = 10_000
    const val MAX_LOCKS: Int = 10_000
    const val MAX_MEDIA_ITEMS_V2: Int = 50_000
    const val MAX_DESCRIPTORS_V2: Int = 150_000
    const val MAX_METADATA_CIPHERTEXT_BYTES: Int = 8192

    const val MAX_V1_THUMB_BYTES: Long = 5L * 1024 * 1024 // 5 MB
    const val MAX_V1_PREVIEW_BYTES: Long = 32L * 1024 * 1024 // 32 MB
    const val MAX_SINGLE_MEDIA_BYTES: Long = 100_000_000_000L // 100 GB cap per item
}
