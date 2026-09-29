package com.suyaphot.app.domain.backup

object BackupArchiveFormat {
    val MAGIC = byteArrayOf(0x53, 0x59, 0x50, 0x42) // "SYPB" (Suya Phot Backup)
    val END_MARKER = byteArrayOf(0x53, 0x59, 0x45, 0x44) // "SYED" (Suya End Data)
    const val CURRENT_VERSION = 1

    const val ENTRY_TYPE_MEDIA: Byte = 0
    const val ENTRY_TYPE_THUMB: Byte = 1
    const val ENTRY_TYPE_PREVIEW: Byte = 2

    const val KEK_INFO = "suya-phot-portable-backup-kek"
    const val MANIFEST_KEY_INFO = "suya-phot-backup-manifest-v1"

    const val RECOVERY_AAD = "suya-phot-portable-recovery-v1"
    const val MANIFEST_AAD = "suya-phot-backup-manifest-v1"

    const val BUFFER_SIZE = 128 * 1024 // 128 KB streaming buffer
}
