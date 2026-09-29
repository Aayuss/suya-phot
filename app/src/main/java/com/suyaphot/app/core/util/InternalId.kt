package com.suyaphot.app.core.util

/**
 * Validates untrusted or internal IDs ensuring they only contain safe alphanumeric,
 * underscore, or dash characters with a bounded length of 1 to 128 characters.
 * Prevents directory traversal, control characters, and unbounded allocation.
 */
object InternalId {
    private val PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")

    fun requireValid(value: String, label: String): String {
        require(PATTERN.matches(value)) {
            "Invalid $label: contains illegal characters or exceeds length limits"
        }
        return value
    }

    fun isValid(value: String): Boolean = PATTERN.matches(value)
}
