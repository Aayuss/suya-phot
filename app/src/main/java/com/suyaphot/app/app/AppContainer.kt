package com.suyaphot.app.app

import android.content.Context
import com.suyaphot.app.core.crypto.AndroidKeystoreIntruderKeyProvider
import com.suyaphot.app.core.crypto.AndroidKeystorePepperProvider
import com.suyaphot.app.core.crypto.IntruderKeyProvider
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.crypto.PepperProvider
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.media.ConflictResolver
import com.suyaphot.app.core.media.MetadataReader
import com.suyaphot.app.core.media.ThumbnailGenerator
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.PinAuthenticator
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.folders.FolderManager
import com.suyaphot.app.domain.folders.FolderPrivacyCoordinator
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.folders.FolderLockManager
import com.suyaphot.app.domain.gallery.GalleryRepository
import com.suyaphot.app.domain.gallery.EncryptedThumbnailRepository
import com.suyaphot.app.domain.gallery.ShareCoordinator
import com.suyaphot.app.domain.gallery.VaultSearchIndex
import com.suyaphot.app.domain.importmedia.ImportCoordinator
import com.suyaphot.app.domain.importmedia.ImportRecoveryManager
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
import com.suyaphot.app.domain.importmedia.VaultMoveFinalizer
import com.suyaphot.app.domain.restore.RestoreCoordinator
import com.suyaphot.app.domain.restore.RestoreRecoveryManager
import com.suyaphot.app.domain.backup.BackupVerifier
import com.suyaphot.app.domain.backup.VaultBackupExporter
import com.suyaphot.app.domain.backup.VaultBackupImporter
import com.suyaphot.app.domain.trash.TrashCoordinator
import com.suyaphot.app.feature.intruder.IntruderCaptureManager

/**
 * Lightweight, zero-overhead manual dependency container.
 */
class AppContainer(val context: Context) {

    val database: SuyaDatabase by lazy { SuyaDatabase.create(context) }
    val preferences: SecurityPreferences by lazy { SecurityPreferences(context) }
    val pepperProvider: PepperProvider by lazy { AndroidKeystorePepperProvider() }
    val keyManager: KeyManager by lazy { KeyManager(context, pepperProvider) }
    val intruderKeyProvider: IntruderKeyProvider by lazy { AndroidKeystoreIntruderKeyProvider() }
    val vaultCrypto: VaultCrypto by lazy { VaultCrypto() }
    val vaultFileStore: VaultFileStore by lazy { VaultFileStore(context) }
    val metadataReader: MetadataReader by lazy { MetadataReader(context) }
    val thumbnailGenerator: ThumbnailGenerator by lazy { ThumbnailGenerator(context) }
    val conflictResolver: ConflictResolver by lazy { ConflictResolver(context.contentResolver) }
    val galleryRepository: GalleryRepository by lazy { GalleryRepository(database.mediaItemDao(), folderAccessManager) }
    val vaultSearchIndex: VaultSearchIndex by lazy { VaultSearchIndex() }
    val encryptedThumbnailRepository: EncryptedThumbnailRepository by lazy {
        EncryptedThumbnailRepository(
            sessionManager = sessionManager,
            fileStore = vaultFileStore,
            generator = thumbnailGenerator,
            database = database,
            vaultCrypto = vaultCrypto,
            folderAccessManager = folderAccessManager
        )
    }
    val shareCoordinator: ShareCoordinator by lazy {
        ShareCoordinator(context, database, sessionManager, folderAccessManager, vaultFileStore, vaultCrypto)
    }
    val folderPrivacyCoordinator: FolderPrivacyCoordinator by lazy {
        FolderPrivacyCoordinator(database) {
            folderAccessManager.onPrivacyMutation()
            encryptedThumbnailRepository.clear()
            vaultSearchIndex.clear()
        }
    }

    val sessionManager: SessionManager by lazy { SessionManager(preferences) }
    val folderAccessManager: FolderAccessManager by lazy { FolderAccessManager(database, sessionManager) }
    val folderLockManager: FolderLockManager by lazy {
        FolderLockManager(database, keyManager, sessionManager, folderAccessManager, folderPrivacyCoordinator, context)
    }
    val pinAuthenticator: PinAuthenticator by lazy {
        PinAuthenticator(
            vaultDao = database.vaultDao(),
            keyManager = keyManager,
            vaultCrypto = vaultCrypto,
            sessionManager = sessionManager,
            preferences = preferences
        )
    }

    val importCoordinator: ImportCoordinator by lazy {
        ImportCoordinator(
            context = context,
            sessionManager = sessionManager,
            database = database,
            mediaItemDao = database.mediaItemDao(),
            vaultJobDao = database.vaultJobDao(),
            metadataReader = metadataReader,
            thumbnailGenerator = thumbnailGenerator,
            vaultCrypto = vaultCrypto,
            fileStore = vaultFileStore,
            folderAccessManager = folderAccessManager
        )
    }

    val importRecoveryManager: ImportRecoveryManager by lazy {
        ImportRecoveryManager(
            database = database,
            fileStore = vaultFileStore,
            vaultCrypto = vaultCrypto
        )
    }

    val sourceDeletionCoordinator: SourceDeletionCoordinator by lazy {
        SourceDeletionCoordinator(context)
    }

    val vaultMoveFinalizer: VaultMoveFinalizer by lazy {
        VaultMoveFinalizer(
            database = database,
            sourceDeletionCoordinator = sourceDeletionCoordinator,
            sessionManager = sessionManager,
            metadataReader = metadataReader
        )
    }

    val restoreCoordinator: RestoreCoordinator by lazy {
        RestoreCoordinator(
            context = context,
            sessionManager = sessionManager,
            database = database,
            mediaItemDao = database.mediaItemDao(),
            vaultCrypto = vaultCrypto,
            fileStore = vaultFileStore,
            conflictResolver = conflictResolver,
            folderAccessManager = folderAccessManager
        )
    }

    val restoreRecoveryManager: RestoreRecoveryManager by lazy {
        RestoreRecoveryManager(context, database, vaultFileStore)
    }

    val trashCoordinator: TrashCoordinator by lazy {
        TrashCoordinator(database, vaultFileStore, preferences, folderAccessManager, sessionManager)
    }

    val folderManager: FolderManager by lazy {
        FolderManager(
            sessionManager = sessionManager,
            folderDao = database.folderDao(),
            mediaItemDao = database.mediaItemDao(),
            database = database,
            privacyCoordinator = folderPrivacyCoordinator,
            accessManager = folderAccessManager
        )
    }

    val intruderCaptureManager: IntruderCaptureManager by lazy {
        IntruderCaptureManager(
            context = context,
            fileStore = vaultFileStore,
            intruderEventDao = database.intruderEventDao(),
            vaultDao = database.vaultDao(),
            intruderKeyProvider = intruderKeyProvider
        )
    }

    val backupVerifier: BackupVerifier by lazy {
        BackupVerifier(keyManager)
    }

    val vaultBackupExporter: VaultBackupExporter by lazy {
        VaultBackupExporter(database, vaultFileStore, keyManager, sessionManager, vaultCrypto)
    }

    val vaultBackupImporter: VaultBackupImporter by lazy {
        VaultBackupImporter(
            context = context,
            database = database,
            fileStore = vaultFileStore,
            keyManager = keyManager,
            privacyCoordinator = folderPrivacyCoordinator,
            vaultCrypto = vaultCrypto,
            sessionManager = sessionManager,
            accessManager = folderAccessManager
        )
    }
}
