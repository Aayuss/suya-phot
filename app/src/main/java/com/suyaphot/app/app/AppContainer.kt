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
import com.suyaphot.app.domain.gallery.GalleryRepository
import com.suyaphot.app.domain.gallery.VaultSearchIndex
import com.suyaphot.app.domain.importmedia.ImportCoordinator
import com.suyaphot.app.domain.importmedia.ImportRecoveryManager
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
import com.suyaphot.app.domain.restore.RestoreCoordinator
import com.suyaphot.app.domain.restore.RestoreRecoveryManager
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
    val galleryRepository: GalleryRepository by lazy { GalleryRepository(database.mediaItemDao()) }
    val vaultSearchIndex: VaultSearchIndex by lazy { VaultSearchIndex() }

    val sessionManager: SessionManager by lazy { SessionManager(preferences) }
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
            fileStore = vaultFileStore
        )
    }

    val importRecoveryManager: ImportRecoveryManager by lazy {
        ImportRecoveryManager(
            database = database,
            fileStore = vaultFileStore
        )
    }

    val sourceDeletionCoordinator: SourceDeletionCoordinator by lazy {
        SourceDeletionCoordinator(context)
    }

    val restoreCoordinator: RestoreCoordinator by lazy {
        RestoreCoordinator(
            context = context,
            sessionManager = sessionManager,
            database = database,
            mediaItemDao = database.mediaItemDao(),
            vaultCrypto = vaultCrypto,
            fileStore = vaultFileStore,
            conflictResolver = conflictResolver
        )
    }

    val restoreRecoveryManager: RestoreRecoveryManager by lazy {
        RestoreRecoveryManager(context, database, vaultFileStore)
    }

    val trashCoordinator: TrashCoordinator by lazy {
        TrashCoordinator(database, vaultFileStore, preferences)
    }

    val folderManager: FolderManager by lazy {
        FolderManager(
            sessionManager = sessionManager,
            folderDao = database.folderDao(),
            mediaItemDao = database.mediaItemDao(),
            database = database
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
}
