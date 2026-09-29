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
    FOLDER_LOCK_RECOVERY_NOT_READY,
    FOLDER_LOCK_RECOVERY_CORRUPT,
    BACKUP_PENDING_LOCAL_CLEANUP,
    LEGACY_PROTECTED_FOLDER_NOT_PORTABLE,
    RESTORE_ALREADY_RUNNING,
    UNKNOWN;

    fun userFriendlyMessage(): String = when (this) {
        INCORRECT_RECOVERY_CODE -> "That Recovery Code does not match this backup."
        INVALID_RECOVERY_FORMAT -> "Recovery code must be exactly 26 Base32 characters."
        INVALID_ARCHIVE -> "This backup file is invalid or corrupted."
        UNSUPPORTED_VERSION -> "This backup was created by an unsupported version of Suya Phot."
        MISSING_MEDIA -> "Backup file is incomplete: one or more media files are missing."
        CORRUPT_MEDIA -> "The backup contains damaged media and cannot be restored safely."
        NOT_ENOUGH_SPACE -> "There is not enough device storage to restore this vault."
        DESTINATION_UNAVAILABLE -> "Could not write to destination file."
        SOURCE_UNAVAILABLE -> "Could not open source backup file."
        RESTORE_REQUIRES_EMPTY_VAULT -> "Restore is only available when no primary vault exists."
        RECOVERY_NOT_CONFIGURED -> "Recovery is not configured for this vault."
        VERIFICATION_FAILED -> "Backup was written but could not be verified. Do not rely on this file."
        FOLDER_LOCK_RECOVERY_NOT_READY -> "Some protected folders need one-time backup preparation."
        FOLDER_LOCK_RECOVERY_CORRUPT -> "A protected folder has damaged recovery information and cannot be backed up safely."
        BACKUP_PENDING_LOCAL_CLEANUP -> "Trash cleanup is still pending. Suya Phot will not create a backup while a permanent-delete operation is unfinished. Open Trash and let cleanup finish, or restart Suya Phot and try again."
        LEGACY_PROTECTED_FOLDER_NOT_PORTABLE -> "This older backup contains protected folders that cannot be safely unlocked on a new device. Open the original vault in the latest Suya Phot, unlock those folders once, and create a new backup."
        RESTORE_ALREADY_RUNNING -> "A restore operation is already in progress."
        UNKNOWN -> "An unexpected error occurred during backup operation."
    }
}

class BackupException(
    val error: BackupError,
    message: String? = null,
    cause: Throwable? = null
) : IllegalArgumentException(message ?: error.userFriendlyMessage(), cause)
