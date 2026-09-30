package com.suyaphot.app.domain.auth

object VaultCredentialValidator {
    const val TYPE_PIN = 0
    const val TYPE_PATTERN = 1

    fun isValid(
        chars: CharArray,
        typeCode: Int
    ): Boolean =
        when (typeCode) {
            TYPE_PIN ->
                chars.size == 6 &&
                chars.all(Char::isDigit)

            TYPE_PATTERN ->
                PatternCredential.isCanonical(chars)

            else ->
                false
        }
}
