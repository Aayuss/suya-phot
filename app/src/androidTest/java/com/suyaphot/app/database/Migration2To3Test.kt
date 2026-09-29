package com.suyaphot.app.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.database.SuyaDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration2To3Test {
    private val databaseName = "migration-2-3-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SuyaDatabase::class.java
    )

    @Test
    fun migrate2To3PreservesSchema() {
        helper.createDatabase(databaseName, 2).apply {
            execSQL("INSERT INTO vaults (id, kindCode, createdAt, schemaVersion, pinEnvelope, recoveryEnvelope, biometricEnvelope, biometricIv) VALUES ('v', 0, 1, 1, X'00', NULL, NULL, NULL)")
            execSQL("INSERT INTO folders (id, vaultId, parentId, encryptedName, createdAt, updatedAt, coverMediaId, sortOrder) VALUES ('f', 'v', NULL, X'01', 1, 1, NULL, 0)")
            execSQL("""INSERT INTO media_items (id, vaultId, folderId, mediaTypeCode, encryptedMetadata, encryptedFileRelativePath,
                encryptedThumbRelativePath, plaintextSize, cipherSize, sha256Hex, importedAt, updatedAt, favorite, deletedAt,
                previousFolderId, dateTakenMs, encryptedPreviewRelativePath, cleanupStateCode)
                VALUES ('m', 'v', 'f', 0, X'02', 'm.sph', NULL, 1, 1, 'hash', 1, 1, 0, NULL, NULL, NULL, NULL, 0)""".trimIndent())
            close()
        }
        helper.runMigrationsAndValidate(databaseName, 3, true, SuyaDatabase.MIGRATION_2_3).apply {
            query("SELECT credentialTypeCode FROM vaults WHERE id = 'v'").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            query("SELECT directHidden, effectiveHidden, effectiveProtected FROM folders WHERE id = 'f'").use {
                it.moveToFirst(); assertEquals(0, it.getInt(0)); assertEquals(0, it.getInt(1)); assertEquals(0, it.getInt(2))
            }
            query("SELECT concealed FROM media_items WHERE id = 'm'").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            close()
        }
    }
}
