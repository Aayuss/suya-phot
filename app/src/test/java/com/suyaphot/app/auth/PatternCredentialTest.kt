package com.suyaphot.app.auth

import com.suyaphot.app.domain.auth.PatternCredential
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PatternCredentialTest {
    @Test fun insertsSkippedMidpoint() {
        assertArrayEquals(intArrayOf(0, 1, 2, 5, 8), PatternCredential.normalize(intArrayOf(0, 2, 5, 8)))
    }

    @Test fun ignoresRepeatedNodes() {
        assertArrayEquals(intArrayOf(0, 1, 2, 5), PatternCredential.normalize(intArrayOf(0, 1, 1, 2, 5)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortPattern() { PatternCredential.normalize(intArrayOf(0, 1, 2)) }

    @Test fun canonicalizationIsStable() {
        assertArrayEquals("P:01258".toCharArray(), PatternCredential.canonicalChars(intArrayOf(0, 2, 5, 8)))
    }
}
