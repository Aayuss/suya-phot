package com.suyaphot.app.domain.backup

enum class BackupError {
    INCORRECT_RECOVERY_CODE,
    INVALID_RECOVERY_FORMAT,
    INVALID_ARCHIVE,
    UNSUPPORTED_VERSION,
    MISSING_MEDIA,
    CORRUPT_MEDIA,
    NOT_ENOUGH_SPACE,
    DESTINATION_UNAVAILABLE,
    SOURCE_UNAVAILABLE,
    RESTORE_REQUIRES_EMPTY_VAULT,
    RECOVERY_NOT_CONFIGURED,
    VERIFICATION_FAILED,
    UNKNOWN;

    fun userFriendlyMessage(): String = when (this) {
        INCORRECT_RECOVERY_CODE -> "That Recovery Code does not match this vault."
        INVALID_RECOVERY_FORMAT -> "Recovery code must be exactly 26 Base32 characters."
        INVALID_ARCHIVE -> "Invalid or corrupted backup archive file."
        UNSUPPORTED_VERSION -> "This backup was created by an unsupported version of Suya Phot."
        MISSING_MEDIA -> "Backup file is incomplete: one or more media files are missing."
        CORRUPT_MEDIA -> "Encrypted media could not be verified or is corrupted."
        NOT_ENOUGH_SPACE -> "Not enough storage space on device."
        DESTINATION_UNAVAILABLE -> "Could not write to destination file."
        SOURCE_UNAVAILABLE -> "Could not open source backup file."
        RESTORE_REQUIRES_EMPTY_VAULT -> "A vault already exists on this device.\n\nFor safety, Suya Phot will not overwrite an existing vault during restore. Export the current vault first and restore the backup on a fresh installation or another device."
        RECOVERY_NOT_CONFIGURED -> "Recovery is not configured for this vault."
        VERIFICATION_FAILED -> "Backup was written but could not be verified. Do not rely on this file."
        UNKNOWN -> "An unexpected error occurred during backup operation."
    }
}

class BackupException(
    val error: BackupError,
    message: String? = null,
    cause: Throwable? = null
) : IllegalArgumentException(message ?: error.userFriendlyMessage(), cause)
