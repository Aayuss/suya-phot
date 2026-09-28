package com.suyaphot.app.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.database.SuyaDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration1To2Test {
    private val databaseName = "migration-1-2-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SuyaDatabase::class.java
    )

    @Test
    fun migrate1To2PreservesSchema() {
        helper.createDatabase(databaseName, 1).close()
        helper.runMigrationsAndValidate(databaseName, 2, true, SuyaDatabase.MIGRATION_1_2).close()
    }
}
