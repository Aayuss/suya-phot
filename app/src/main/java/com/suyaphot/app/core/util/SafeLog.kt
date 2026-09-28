package com.suyaphot.app.core.util

import android.util.Log
import com.suyaphot.app.BuildConfig

/**
 * Safe logging utility that prevents accidental leakage of sensitive data
 * and strips verbose logs in release builds.
 */
object SafeLog {
    private const val TAG_PREFIX = "SuyaPhot:"

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("$TAG_PREFIX$tag", sanitize(message))
        }
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.i("$TAG_PREFIX$tag", sanitize(message))
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.w("$TAG_PREFIX$tag", sanitize(message), throwable)
            } else {
                Log.w("$TAG_PREFIX$tag", sanitize(message))
            }
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.e("$TAG_PREFIX$tag", sanitize(message), throwable)
            } else {
                Log.e("$TAG_PREFIX$tag", sanitize(message))
            }
        }
    }

    private fun sanitize(input: String): String {
        // Redact potential PIN patterns, keys or hashes from logs
        return input.replace(Regex("\\b\\d{4,12}\\b"), "[REDACTED_NUM]")
    }
}
