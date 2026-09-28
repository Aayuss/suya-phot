package com.suyaphot.app.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.ProcessLifecycleOwner
import com.suyaphot.app.R
import com.suyaphot.app.core.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SuyaApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Lifecycle observer for auto-lock and session timeouts
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.sessionManager)

        createNotificationChannel()

        // Clean up any stale partial files and temporary share files from prior abnormal terminations
        CoroutineScope(Dispatchers.IO).launch {
            try {
                container.vaultFileStore.clearShareCache()
            } catch (e: Exception) {
                SafeLog.w("SuyaApp", "Initial cleanup error", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_VAULT_OPS,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_VAULT_OPS = "suya_phot_vault_ops"
    }
}
