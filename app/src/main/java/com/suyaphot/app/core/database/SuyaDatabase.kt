package com.suyaphot.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.suyaphot.app.core.database.dao.FolderDao
import com.suyaphot.app.core.database.dao.FolderLockDao
import com.suyaphot.app.core.database.dao.IntruderEventDao
import com.suyaphot.app.core.database.dao.MediaItemDao
import com.suyaphot.app.core.database.dao.VaultDao
import com.suyaphot.app.core.database.dao.VaultJobDao
import com.suyaphot.app.core.database.dao.RestoreJobDao
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.FolderLockEntity
import com.suyaphot.app.core.database.entity.IntruderEventEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.database.entity.VaultJobEntity
import com.suyaphot.app.core.database.entity.RestoreJobEntity
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        VaultEntity::class,
        FolderEntity::class,
        FolderLockEntity::class,
        MediaItemEntity::class,
        VaultJobEntity::class,
        IntruderEventEntity::class,
        RestoreJobEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class SuyaDatabase : RoomDatabase() {

    abstract fun vaultDao(): VaultDao
    abstract fun folderDao(): FolderDao
    abstract fun folderLockDao(): FolderLockDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun vaultJobDao(): VaultJobDao
    abstract fun intruderEventDao(): IntruderEventDao
    abstract fun restoreJobDao(): RestoreJobDao

    companion object {
        private const val DB_NAME = "suya_phot_vault.db"

        @Volatile
        private var instance: SuyaDatabase? = null

        fun create(context: Context): SuyaDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SuyaDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN dateTakenMs INTEGER")
                db.execSQL("ALTER TABLE media_items ADD COLUMN encryptedPreviewRelativePath TEXT")
                db.execSQL("ALTER TABLE media_items ADD COLUMN cleanupStateCode INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS restore_jobs (
                        id TEXT NOT NULL PRIMARY KEY,
                        vaultId TEXT NOT NULL,
                        mediaId TEXT NOT NULL,
                        phaseCode INTEGER NOT NULL,
                        encryptedDestinationUri BLOB,
                        move INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        safeErrorCode TEXT,
                        FOREIGN KEY(vaultId) REFERENCES vaults(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_restore_jobs_vaultId ON restore_jobs(vaultId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_restore_jobs_mediaId ON restore_jobs(mediaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_restore_jobs_phaseCode ON restore_jobs(phaseCode)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vaults ADD COLUMN credentialTypeCode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN directHidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN effectiveHidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN lockId TEXT")
                db.execSQL("ALTER TABLE folders ADD COLUMN effectiveProtected INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_items ADD COLUMN concealed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_effectiveHidden ON folders(effectiveHidden)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_effectiveProtected ON folders(effectiveProtected)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_lockId ON folders(lockId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_vaultId_concealed_deletedAt ON media_items(vaultId, concealed, deletedAt)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS folder_locks (
                        id TEXT NOT NULL PRIMARY KEY,
                        vaultId TEXT NOT NULL,
                        folderId TEXT NOT NULL,
                        credentialTypeCode INTEGER NOT NULL,
                        credentialEnvelope BLOB NOT NULL,
                        biometricEnvelope BLOB,
                        biometricIv BLOB,
                        relockPolicyCode INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(vaultId) REFERENCES vaults(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(folderId) REFERENCES folders(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_folder_locks_folderId ON folder_locks(folderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folder_locks_vaultId ON folder_locks(vaultId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS folder_locks_v4 (
                    id TEXT NOT NULL PRIMARY KEY,
                    vaultId TEXT NOT NULL,
                    folderId TEXT NOT NULL,
                    credentialTypeCode INTEGER NOT NULL,
                    credentialEnvelope BLOB NOT NULL,
                    biometricEnvelope BLOB,
                    biometricIv BLOB,
                    recoveryEnvelope BLOB,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(vaultId) REFERENCES vaults(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(folderId) REFERENCES folders(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent())
                db.execSQL("""INSERT INTO folder_locks_v4 (
                    id, vaultId, folderId, credentialTypeCode, credentialEnvelope,
                    biometricEnvelope, biometricIv, recoveryEnvelope, createdAt, updatedAt
                ) SELECT id, vaultId, folderId, credentialTypeCode, credentialEnvelope,
                    biometricEnvelope, biometricIv, NULL, createdAt, updatedAt FROM folder_locks""".trimIndent())
                db.execSQL("DROP TABLE folder_locks")
                db.execSQL("ALTER TABLE folder_locks_v4 RENAME TO folder_locks")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_folder_locks_folderId ON folder_locks(folderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folder_locks_vaultId ON folder_locks(vaultId)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folder_locks ADD COLUMN requiresCredentialReset INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
