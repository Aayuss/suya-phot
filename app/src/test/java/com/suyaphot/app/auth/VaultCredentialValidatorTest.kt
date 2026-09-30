package com.suyaphot.app.auth

import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.domain.auth.VaultCredentialValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultCredentialValidatorTest {

    @Test
    fun testPinValidation() {
        assertTrue(VaultCredentialValidator.isValid("123456".toCharArray(), VaultCredentialValidator.TYPE_PIN))
        assertTrue(VaultCredentialValidator.isValid("000000".toCharArray(), VaultCredentialValidator.TYPE_PIN))
        assertTrue(VaultCredentialValidator.isValid("999999".toCharArray(), VaultCredentialValidator.TYPE_PIN))

        // Non-digit
        assertFalse(VaultCredentialValidator.isValid("ABCDEF".toCharArray(), VaultCredentialValidator.TYPE_PIN))
        assertFalse(VaultCredentialValidator.isValid("12345A".toCharArray(), VaultCredentialValidator.TYPE_PIN))
        assertFalse(VaultCredentialValidator.isValid("12 456".toCharArray(), VaultCredentialValidator.TYPE_PIN))

        // Wrong length
        assertFalse(VaultCredentialValidator.isValid("12345".toCharArray(), VaultCredentialValidator.TYPE_PIN))
        assertFalse(VaultCredentialValidator.isValid("1234567".toCharArray(), VaultCredentialValidator.TYPE_PIN))
        assertFalse(VaultCredentialValidator.isValid("".toCharArray(), VaultCredentialValidator.TYPE_PIN))
    }

    @Test
    fun testPatternValidation() {
        val validPattern = PatternCredential.canonicalChars(intArrayOf(0, 1, 2, 5, 8))
        assertTrue(VaultCredentialValidator.isValid(validPattern, VaultCredentialValidator.TYPE_PATTERN))

        // Malformed / Non-canonical patterns
        assertFalse(VaultCredentialValidator.isValid("P:012".toCharArray(), VaultCredentialValidator.TYPE_PATTERN))
        assertFalse(VaultCredentialValidator.isValid("P:0123456789".toCharArray(), VaultCredentialValidator.TYPE_PATTERN))
        assertFalse(VaultCredentialValidator.isValid("X:01258".toCharArray(), VaultCredentialValidator.TYPE_PATTERN))
        assertFalse(VaultCredentialValidator.isValid("1234".toCharArray(), VaultCredentialValidator.TYPE_PATTERN))
        assertFalse(VaultCredentialValidator.isValid("".toCharArray(), VaultCredentialValidator.TYPE_PATTERN))
    }

    @Test
    fun testUnsupportedCredentialType() {
        assertFalse(VaultCredentialValidator.isValid("123456".toCharArray(), 99))
        assertFalse(VaultCredentialValidator.isValid("123456".toCharArray(), -1))
        assertFalse(VaultCredentialValidator.isValid("123456".toCharArray(), 2))
    }
}
