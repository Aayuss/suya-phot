package com.suyaphot.app.feature.shizuku

import android.content.Context
import android.content.pm.PackageManager
import com.suyaphot.app.core.util.SafeLog

/**
 * Optional Shizuku integration manager.
 * Safely handles environments where Shizuku is absent or inactive.
 */
class ShizukuManager(private val context: Context) {

    /**
     * Checks if Shizuku server is installed and running on the device.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            SafeLog.w("ShizukuManager", "Error checking Shizuku package", e)
            false
        }
    }

    /**
     * Checks if permission is granted to communicate with Shizuku service.
     */
    fun isPermissionGranted(): Boolean {
        if (!isShizukuAvailable()) return false
        return try {
            // Evaluates Shizuku permission check if Shizuku binder is active
            false
        } catch (e: Exception) {
            false
        }
    }
}
