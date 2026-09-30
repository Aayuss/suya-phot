package com.suyaphot.app.app

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.StrictMode
import androidx.lifecycle.ProcessLifecycleOwner
import com.suyaphot.app.BuildConfig
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultStorageMutationGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import androidx.core.content.ContextCompat

class SuyaApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var screenStateReceiver: ScreenStateReceiver

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build()
            )
        }
        container = AppContainer(this)

        // Lifecycle observer for auto-lock and session timeouts
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.sessionManager)
        screenStateReceiver = ScreenStateReceiver(container.sessionManager::onScreenTurnedOff)
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Clean up any stale partial files and temporary share files from prior abnormal terminations
        applicationScope.launch {
            try {
                VaultStorageMutationGate.withExclusiveMutation {
                    container.vaultFileStore.clearEphemeralPlaintextCaches()
                    container.vaultFileStore.clearStaleBackupRestoreStaging()
                    val vaults = container.database.vaultDao().getAllVaults()
                    val activeVaultIds = vaults.map { it.id }.toSet()
                    container.vaultFileStore.clearOrphanVaultDirs(activeVaultIds)
                }
            } catch (e: Exception) {
                SafeLog.w("SuyaApp", "Initial cleanup error", e)
            }
        }
    }

}
