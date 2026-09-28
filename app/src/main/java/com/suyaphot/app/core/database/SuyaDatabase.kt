package com.suyaphot.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.suyaphot.app.core.database.dao.FolderDao
import com.suyaphot.app.core.database.dao.IntruderEventDao
import com.suyaphot.app.core.database.dao.MediaItemDao
import com.suyaphot.app.core.database.dao.VaultDao
import com.suyaphot.app.core.database.dao.VaultJobDao
import com.suyaphot.app.core.database.dao.RestoreJobDao
import com.suyaphot.app.core.database.entity.FolderEntity
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
        MediaItemEntity::class,
        VaultJobEntity::class,
        IntruderEventEntity::class,
        RestoreJobEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class SuyaDatabase : RoomDatabase() {

    abstract fun vaultDao(): VaultDao
    abstract fun folderDao(): FolderDao
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
                    .addMigrations(MIGRATION_1_2)
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
    }
}
