package com.suyaphot.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "suya_phot_settings")

/**
 * Manages non-sensitive user preferences via DataStore.
 * (Sensitive crypto material is stored in Room vault envelopes, not preferences).
 */
class SecurityPreferences(private val context: Context) {

    companion object {
        private val KEY_AUTO_LOCK_TIMEOUT_MS = longPreferencesKey("auto_lock_timeout_ms")
        private val KEY_LOCK_ON_SCREEN_OFF = booleanPreferencesKey("lock_on_screen_off")
        private val KEY_SCREENSHOT_PROTECTION = booleanPreferencesKey("screenshot_protection")
        private val KEY_INTRUDER_SELFIE_ENABLED = booleanPreferencesKey("intruder_selfie_enabled")
        private val KEY_INTRUDER_TRIGGER_COUNT = intPreferencesKey("intruder_trigger_count")
        private val KEY_TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
        private val KEY_GRID_COLUMNS = intPreferencesKey("grid_columns")
        private val KEY_SORT_ORDER = stringPreferencesKey("sort_order")
        private val KEY_BIOMETRIC_ON_LAUNCH = booleanPreferencesKey("biometric_on_launch")

        // Rate limiting state
        private val KEY_FAILED_ATTEMPTS = intPreferencesKey("failed_attempts")
        private val KEY_LOCKOUT_UNTIL_TIMESTAMP = longPreferencesKey("lockout_until_timestamp")
        private val KEY_LAST_DISMISSED_INTRUDER_TIMESTAMP = longPreferencesKey("last_dismissed_intruder_timestamp")
    }

    // Auto-lock delay in ms: 0L = immediate on background, 30_000L = 30s, 60_000L = 1m, 300_000L = 5m
    val autoLockTimeoutMs: Flow<Long> = context.dataStore.data.map { it[KEY_AUTO_LOCK_TIMEOUT_MS] ?: 0L }
    val lockOnScreenOff: Flow<Boolean> = context.dataStore.data.map { it[KEY_LOCK_ON_SCREEN_OFF] ?: true }

    // Screenshot protection: Default TRUE (FLAG_SECURE active)
    val screenshotProtection: Flow<Boolean> = context.dataStore.data.map { it[KEY_SCREENSHOT_PROTECTION] ?: true }

    // Intruder selfie
    val intruderSelfieEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_INTRUDER_SELFIE_ENABLED] ?: false }
    val intruderTriggerCount: Flow<Int> = context.dataStore.data.map { it[KEY_INTRUDER_TRIGGER_COUNT] ?: 3 }

    // Trash retention days: 30 default, 7, or 0 for never auto-delete
    val trashRetentionDays: Flow<Int> = context.dataStore.data.map { it[KEY_TRASH_RETENTION_DAYS] ?: 30 }

    // Appearance: default 3 columns (ideal for S23 Ultra)
    val gridColumns: Flow<Int> = context.dataStore.data.map { it[KEY_GRID_COLUMNS] ?: 3 }

    // Sort order: "DATE_TAKEN_DESC", "DATE_TAKEN_ASC", "IMPORTED_DESC", "NAME_ASC"
    val sortOrder: Flow<String> = context.dataStore.data.map { it[KEY_SORT_ORDER] ?: "DATE_TAKEN_DESC" }

    // Prompt biometric immediately on lock screen
    val biometricOnLaunch: Flow<Boolean> = context.dataStore.data.map { it[KEY_BIOMETRIC_ON_LAUNCH] ?: true }

    // Rate limiting
    val failedAttempts: Flow<Int> = context.dataStore.data.map { it[KEY_FAILED_ATTEMPTS] ?: 0 }
    val lockoutUntilTimestamp: Flow<Long> = context.dataStore.data.map { it[KEY_LOCKOUT_UNTIL_TIMESTAMP] ?: 0L }
    val lastDismissedIntruderTimestamp: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_DISMISSED_INTRUDER_TIMESTAMP] ?: 0L }

    suspend fun setAutoLockTimeoutMs(timeoutMs: Long) {
        require(timeoutMs in setOf(0L, 30_000L, 60_000L, 300_000L)) { "Unsupported auto-lock timeout" }
        context.dataStore.edit { it[KEY_AUTO_LOCK_TIMEOUT_MS] = timeoutMs }
    }

    suspend fun setLockOnScreenOff(enabled: Boolean) {
        context.dataStore.edit { it[KEY_LOCK_ON_SCREEN_OFF] = enabled }
    }

    suspend fun setScreenshotProtection(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SCREENSHOT_PROTECTION] = enabled }
    }

    suspend fun setIntruderSelfieEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INTRUDER_SELFIE_ENABLED] = enabled }
    }

    suspend fun setIntruderTriggerCount(count: Int) {
        require(count in 1..10) { "Intruder threshold must be between 1 and 10" }
        context.dataStore.edit { it[KEY_INTRUDER_TRIGGER_COUNT] = count }
    }

    suspend fun setTrashRetentionDays(days: Int) {
        require(days in setOf(0, 7, 30, 90)) { "Unsupported trash retention" }
        context.dataStore.edit { it[KEY_TRASH_RETENTION_DAYS] = days }
    }

    suspend fun setGridColumns(columns: Int) {
        require(columns in 2..5) { "Grid columns must be between 2 and 5" }
        context.dataStore.edit { it[KEY_GRID_COLUMNS] = columns }
    }

    suspend fun setSortOrder(order: String) {
        require(order in setOf("DATE_TAKEN_DESC", "DATE_TAKEN_ASC", "IMPORTED_DESC", "IMPORTED_ASC", "SIZE_DESC", "SIZE_ASC")) {
            "Unsupported gallery sort"
        }
        context.dataStore.edit { it[KEY_SORT_ORDER] = order }
    }

    suspend fun setBiometricOnLaunch(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BIOMETRIC_ON_LAUNCH] = enabled }
    }

    suspend fun recordFailedAttempt(lockoutUntil: Long) {
        context.dataStore.edit {
            val current = it[KEY_FAILED_ATTEMPTS] ?: 0
            it[KEY_FAILED_ATTEMPTS] = current + 1
            it[KEY_LOCKOUT_UNTIL_TIMESTAMP] = lockoutUntil
        }
    }

    suspend fun resetFailedAttempts() {
        context.dataStore.edit {
            it[KEY_FAILED_ATTEMPTS] = 0
            it[KEY_LOCKOUT_UNTIL_TIMESTAMP] = 0L
        }
    }

    suspend fun setLastDismissedIntruderTimestamp(timestamp: Long) {
        context.dataStore.edit {
            it[KEY_LAST_DISMISSED_INTRUDER_TIMESTAMP] = timestamp
        }
    }
}
