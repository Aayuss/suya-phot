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
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.IntruderEventEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.database.entity.VaultJobEntity

@Database(
    entities = [
        VaultEntity::class,
        FolderEntity::class,
        MediaItemEntity::class,
        VaultJobEntity::class,
        IntruderEventEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SuyaDatabase : RoomDatabase() {

    abstract fun vaultDao(): VaultDao
    abstract fun folderDao(): FolderDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun vaultJobDao(): VaultJobDao
    abstract fun intruderEventDao(): IntruderEventDao

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
                    .build()
                    .also { instance = it }
            }
        }
    }
}
