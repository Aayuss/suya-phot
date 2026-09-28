package com.suyaphot.app.app

import android.content.Context
import com.suyaphot.app.core.crypto.KeyManager
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
import com.suyaphot.app.domain.importmedia.ImportCoordinator
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
import com.suyaphot.app.domain.restore.RestoreCoordinator
import com.suyaphot.app.feature.intruder.IntruderCaptureManager
import com.suyaphot.app.feature.shizuku.ShizukuManager

/**
 * Lightweight, zero-overhead manual dependency container.
 */
class AppContainer(val context: Context) {

    val database: SuyaDatabase by lazy { SuyaDatabase.create(context) }
    val preferences: SecurityPreferences by lazy { SecurityPreferences(context) }
    val keyManager: KeyManager by lazy { KeyManager(context) }
    val vaultCrypto: VaultCrypto by lazy { VaultCrypto() }
    val vaultFileStore: VaultFileStore by lazy { VaultFileStore(context) }
    val metadataReader: MetadataReader by lazy { MetadataReader(context) }
    val thumbnailGenerator: ThumbnailGenerator by lazy { ThumbnailGenerator(context) }
    val conflictResolver: ConflictResolver by lazy { ConflictResolver(context.contentResolver) }

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
            mediaItemDao = database.mediaItemDao(),
            vaultJobDao = database.vaultJobDao(),
            metadataReader = metadataReader,
            thumbnailGenerator = thumbnailGenerator,
            vaultCrypto = vaultCrypto,
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
            mediaItemDao = database.mediaItemDao(),
            vaultCrypto = vaultCrypto,
            fileStore = vaultFileStore,
            conflictResolver = conflictResolver
        )
    }

    val folderManager: FolderManager by lazy {
        FolderManager(
            sessionManager = sessionManager,
            folderDao = database.folderDao(),
            mediaItemDao = database.mediaItemDao()
        )
    }

    val intruderCaptureManager: IntruderCaptureManager by lazy {
        IntruderCaptureManager(
            context = context,
            fileStore = vaultFileStore,
            intruderEventDao = database.intruderEventDao(),
            sessionManager = sessionManager
        )
    }

    val shizukuManager: ShizukuManager by lazy {
        ShizukuManager(context)
    }
}
