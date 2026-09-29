package com.suyaphot.app.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.database.SuyaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration5To6Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), SuyaDatabase::class.java
    )

    @Test fun existingJobAddsSourceDispositionCodeDefaultNull() {
        val name = "migration-5-6-test"
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO vaults (id, kindCode, createdAt, schemaVersion, pinEnvelope, recoveryEnvelope, biometricEnvelope, biometricIv, credentialTypeCode) VALUES ('v', 0, 1, 5, X'00', NULL, NULL, NULL, 0)")
            execSQL("INSERT INTO jobs (id, vaultId, typeCode, stateCode, encryptedPayload, progressCurrent, progressTotal, createdAt, updatedAt, errorCode) VALUES ('j1', 'v', 0, 7, X'0102', 1, 1, 100, 200, NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 6, true, SuyaDatabase.MIGRATION_5_6).apply {
            query("SELECT id, sourceDispositionCode FROM jobs WHERE id = 'j1'").use {
                it.moveToFirst()
                assertEquals("j1", it.getString(0))
                assertNull(it.getString(1))
            }
            execSQL("UPDATE jobs SET sourceDispositionCode = 4 WHERE id = 'j1'")
            query("SELECT sourceDispositionCode FROM jobs WHERE id = 'j1'").use {
                it.moveToFirst()
                assertEquals(4, it.getInt(0))
            }
            close()
        }
    }
}
