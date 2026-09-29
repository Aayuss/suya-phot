package com.suyaphot.app.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.crypto.PepperProvider
import com.suyaphot.app.core.crypto.SensitiveKeyHandle
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.domain.auth.PinAuthenticator
import com.suyaphot.app.domain.auth.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class CredentialValidationTest {
    @Test fun nonDigitPinCannotBeStoredDuringCredentialChange() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val pepper = object : PepperProvider {
            override fun hmacSha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
        }
        val keys = KeyManager(context, pepper)
        val master = ByteArray(32) { 7 }
        val session = SessionManager(SecurityPreferences(context), CoroutineScope(Dispatchers.Unconfined))
        try {
            val envelope = keys.createPinEnvelope(master, "123456".toCharArray(), 50_000)
            db.vaultDao().insert(VaultEntity("vault", 0, 1, 1, envelope.serialize()))
            session.unlock("vault", VaultKind.REAL, SensitiveKeyHandle(master.copyOf()),
                ByteArray(32), ByteArray(32), ByteArray(32))
            val auth = PinAuthenticator(db.vaultDao(), keys, VaultCrypto(), session, SecurityPreferences(context))
            assertFalse(auth.changeCurrentCredential("123456".toCharArray(), 0, "ABCDEF".toCharArray(), 0))
            assertFalse(auth.changeCurrentCredential("123456".toCharArray(), 0, "123A56".toCharArray(), 0))
            assertEquals(0, db.vaultDao().getVault("vault")!!.credentialTypeCode)
            assertTrue(auth.changeCurrentCredential("123456".toCharArray(), 0, "654321".toCharArray(), 0))
            val updated = KeyManager.PinEnvelope.deserialize(db.vaultDao().getVault("vault")!!.pinEnvelope)
            assertTrue(keys.unwrapPinEnvelope(updated, "654321".toCharArray())!!.contentEquals(master))
            assertFalse(PatternCredential.isCanonical("P:0268".toCharArray()))
            assertTrue(PatternCredential.isCanonical(PatternCredential.canonicalChars(intArrayOf(0, 1, 4, 5))))
        } finally { db.close() }
    }
}
