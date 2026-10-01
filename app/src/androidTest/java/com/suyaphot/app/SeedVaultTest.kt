package com.suyaphot.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suyaphot.app.app.SuyaApp
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.LockReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SeedVaultTest {

    @Test
    fun seedVaultWithPin123456() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SuyaApp>()
        val container = app.container

        val existing = container.database.vaultDao().getVaultByKind(VaultKind.REAL.code)
        if (existing == null) {
            val masterKey = container.keyManager.generateMasterKey()
            val pinChars = "123456".toCharArray()
            val pinEnvelope = container.keyManager.createPinEnvelope(masterKey, pinChars)
            val recoveryCode = container.keyManager.generateRecoverySecret()
            val recoveryEnvelope = container.keyManager.createRecoveryEnvelope(
                masterKey,
                container.keyManager.normalizeRecoverySecret(recoveryCode)
            )
            val newVaultId = UUID.randomUUID().toString()
            val vault = VaultEntity(
                id = newVaultId,
                kindCode = VaultKind.REAL.code,
                createdAt = System.currentTimeMillis(),
                schemaVersion = 5,
                pinEnvelope = pinEnvelope.serialize(),
                recoveryEnvelope = recoveryEnvelope.serialize(),
                biometricEnvelope = null,
                biometricIv = null,
                credentialTypeCode = 0
            )
            container.database.vaultDao().insert(vault)
        }
        container.sessionManager.lock(LockReason.Explicit)
        assertTrue("Vault must exist", container.database.vaultDao().getAllVaults().isNotEmpty())
    }
}
