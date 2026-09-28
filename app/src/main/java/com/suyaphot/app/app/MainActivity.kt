package com.suyaphot.app.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.feature.folders.FoldersScreen
import com.suyaphot.app.feature.intruder.IntruderLogsScreen
import com.suyaphot.app.feature.lock.LockScreen
import com.suyaphot.app.feature.onboarding.SetupScreen
import com.suyaphot.app.feature.photos.PhotosScreen
import com.suyaphot.app.feature.security.SecurityScreen
import com.suyaphot.app.feature.settings.SettingsScreen
import com.suyaphot.app.feature.trash.TrashScreen
import com.suyaphot.app.feature.viewer.MediaViewerScreen
import com.suyaphot.app.ui.components.SuyaBottomNav
import com.suyaphot.app.ui.components.SuyaNavTab
import com.suyaphot.app.ui.theme.SuyaColors
import com.suyaphot.app.ui.theme.SuyaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as SuyaApp
        val container = app.container

        lifecycleScope.launch(Dispatchers.IO) {
            container.vaultFileStore.clearShareCache()
            container.vaultFileStore.clearPlaybackCache()
        }

        setContent {
            val screenshotProtection by container.preferences.screenshotProtection.collectAsState(initial = true)
            LaunchedEffect(screenshotProtection) {
                if (screenshotProtection) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            SuyaTheme {
                MainAppHost(container = container)
            }
        }
    }
}

@Composable
fun MainAppHost(container: AppContainer) {
    var hasVaultConfigured by remember { mutableStateOf<Boolean?>(null) }
    val sessionState by container.sessionManager.sessionState.collectAsState()
    val hideSensitiveUi by container.sessionManager.hideSensitiveUi.collectAsState()

    var activeTab by remember { mutableStateOf(SuyaNavTab.PHOTOS) }
    var activeViewerItemId by remember { mutableStateOf<String?>(null) }
    var showTrashScreen by remember { mutableStateOf(false) }
    var showIntruderLogsScreen by remember { mutableStateOf(false) }

    // Reconcile active jobs whenever vault is unlocked
    LaunchedEffect(sessionState) {
        val s = sessionState
        if (s is VaultSession.Unlocked) {
            container.importRecoveryManager.reconcileActiveJobs(s)
        }
    }

    // Check if initial vault setup exists
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val vaults = container.database.vaultDao().getAllVaults()
            hasVaultConfigured = vaults.isNotEmpty()
        }
    }

    if (hasVaultConfigured == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SuyaColors.Background)
        )
        return
    }

    if (!hasVaultConfigured!!) {
        SetupScreen(
            container = container,
            onSetupComplete = {
                hasVaultConfigured = true
            }
        )
        return
    }

    // Vault is configured: check lock state
    if (sessionState is VaultSession.Locked || hideSensitiveUi) {
        LockScreen(
            container = container,
            onUnlocked = {
                // Session unlocked, automatically advances
            }
        )
        return
    }

    // Vault is unlocked: display media viewer or navigation tab
    if (activeViewerItemId != null) {
        MediaViewerScreen(
            itemId = activeViewerItemId!!,
            container = container,
            onBack = { activeViewerItemId = null }
        )
    } else if (showTrashScreen) {
        TrashScreen(
            container = container,
            onBack = { showTrashScreen = false }
        )
    } else if (showIntruderLogsScreen) {
        IntruderLogsScreen(
            container = container,
            onBack = { showIntruderLogsScreen = false }
        )
    } else {
        Scaffold(
            bottomBar = {
                SuyaBottomNav(
                    selectedTab = activeTab,
                    onTabSelected = { activeTab = it }
                )
            },
            containerColor = SuyaColors.Background
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding())
            ) {
                AnimatedContent(targetState = activeTab, label = "tab_content") { tab ->
                    when (tab) {
                        SuyaNavTab.PHOTOS -> {
                            PhotosScreen(
                                container = container,
                                onMediaClick = { itemId -> activeViewerItemId = itemId }
                            )
                        }
                        SuyaNavTab.FOLDERS -> {
                            FoldersScreen(
                                container = container,
                                onMediaClick = { itemId -> activeViewerItemId = itemId },
                                onFolderOpened = { /* folder traversal handled internally */ }
                            )
                        }
                        SuyaNavTab.SECURITY -> {
                            SecurityScreen(
                                container = container,
                                onViewIntruderLogs = { showIntruderLogsScreen = true }
                            )
                        }
                        SuyaNavTab.SETTINGS -> {
                            SettingsScreen(
                                container = container,
                                onOpenTrash = { showTrashScreen = true }
                            )
                        }
                    }
                }
            }
        }
    }
}
