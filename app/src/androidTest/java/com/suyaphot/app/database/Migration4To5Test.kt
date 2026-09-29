package com.suyaphot.app.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.database.SuyaDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration4To5Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), SuyaDatabase::class.java
    )

    @Test fun existingFolderLockAddsRequiresCredentialResetDefaultZero() {
        val name = "migration-4-5-test"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO vaults (id, kindCode, createdAt, schemaVersion, pinEnvelope, recoveryEnvelope, biometricEnvelope, biometricIv, credentialTypeCode) VALUES ('v', 0, 1, 4, X'00', NULL, NULL, NULL, 0)")
            execSQL("INSERT INTO folders (id, vaultId, parentId, encryptedName, createdAt, updatedAt, coverMediaId, sortOrder, directHidden, effectiveHidden, lockId, effectiveProtected) VALUES ('f', 'v', NULL, X'01', 1, 1, NULL, 0, 0, 0, 'l', 1)")
            execSQL("INSERT INTO folder_locks (id, vaultId, folderId, credentialTypeCode, credentialEnvelope, biometricEnvelope, biometricIv, recoveryEnvelope, createdAt, updatedAt) VALUES ('l', 'v', 'f', 0, X'0203', X'04', X'05', X'06', 1, 1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, SuyaDatabase.MIGRATION_4_5).apply {
            query("SELECT credentialTypeCode, credentialEnvelope, biometricEnvelope, biometricIv, recoveryEnvelope, requiresCredentialReset FROM folder_locks WHERE id = 'l'").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
                assertEquals(2, it.getBlob(1).size)
                assertEquals(1, it.getBlob(2).size)
                assertEquals(1, it.getBlob(3).size)
                assertEquals(1, it.getBlob(4).size)
                assertEquals(0, it.getInt(5))
            }
            close()
        }
    }
}
