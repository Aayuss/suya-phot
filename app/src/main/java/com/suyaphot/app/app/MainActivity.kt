package com.suyaphot.app.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.folders.ViewerAccessScope
import com.suyaphot.app.domain.gallery.ViewerCollection
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
                val allowScreenshots = try {
                    val spClass = Class.forName("android.os.SystemProperties")
                    val getBoolean = spClass.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                    getBoolean.invoke(null, "debug.suya.allow_screenshots", false) as Boolean
                } catch (_: Throwable) { false }

                if (screenshotProtection && !allowScreenshots) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            SuyaTheme {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    MainAppHost(container = container)
                }
            }
        }
    }

    var onHardwareKeyEventListener: ((android.view.KeyEvent) -> Boolean)? = null

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (onHardwareKeyEventListener?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
fun MainAppHost(container: AppContainer) {
    var hasVaultConfigured by remember { mutableStateOf<Boolean?>(null) }
    val sessionState by container.sessionManager.sessionState.collectAsState()
    val hideSensitiveUi by container.sessionManager.hideSensitiveUi.collectAsState()

    var activeTab by rememberSaveable { mutableStateOf(SuyaNavTab.PHOTOS) }
    var pendingHiddenEntryRequest by remember { mutableStateOf<Long?>(null) }
    var activeViewerItemId by remember { mutableStateOf<String?>(null) }
    var activeViewerScope by remember { mutableStateOf<ViewerAccessScope?>(null) }
    var activeViewerCollection by remember { mutableStateOf<ViewerCollection?>(null) }
    val accessRevision by container.folderAccessManager.revision.collectAsState()
    var showTrashScreen by rememberSaveable { mutableStateOf(false) }
    var showPrivateTrashScreen by rememberSaveable { mutableStateOf(false) }
    var showIntruderLogsScreen by rememberSaveable { mutableStateOf(false) }

    // Reconcile active jobs whenever vault is unlocked
    LaunchedEffect(sessionState) {
        val s = sessionState
        if (s is VaultSession.Unlocked) {
            withContext(Dispatchers.IO) {
                container.pendingShareManager.processPendingShares(s)
                container.importRecoveryManager.reconcileActiveJobs(s)
                container.restoreRecoveryManager.reconcile(s)
                container.trashCoordinator.reconcilePending(s.vaultId)
            }
        } else {
            pendingHiddenEntryRequest = null
            container.vaultSearchIndex.clear()
            container.encryptedThumbnailRepository.clear()
            withContext(Dispatchers.IO) {
                container.vaultFileStore.clearEphemeralPlaintextCaches()
            }
        }
    }

    // Check if initial vault setup exists
    LaunchedEffect(Unit) {
        hasVaultConfigured = withContext(Dispatchers.IO) {
            val vaults = container.database.vaultDao().getAllVaults()
            vaults.isNotEmpty()
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
    if (sessionState is VaultSession.Locked) {
        LockScreen(
            container = container,
            onUnlocked = {
                // Session unlocked, automatically advances
            }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Vault is unlocked: display media viewer or navigation tab
        val viewerAllowed = run {
            accessRevision
            activeViewerScope?.let(container.folderAccessManager::isScopeValid) ?: true
        }
        LaunchedEffect(accessRevision, viewerAllowed) {
            if (!viewerAllowed) { activeViewerItemId = null; activeViewerScope = null }
        }
        BackHandler(activeViewerItemId != null || showTrashScreen || showPrivateTrashScreen || showIntruderLogsScreen) {
            when {
                activeViewerItemId != null -> { activeViewerItemId = null; activeViewerScope = null }
                showTrashScreen -> showTrashScreen = false
                showPrivateTrashScreen -> showPrivateTrashScreen = false
                showIntruderLogsScreen -> showIntruderLogsScreen = false
            }
        }
        if (activeViewerItemId != null && !viewerAllowed) {
            Box(modifier = Modifier.fillMaxSize().background(SuyaColors.Background))
        } else if (activeViewerItemId != null) {
            MediaViewerScreen(
                itemId = activeViewerItemId!!,
                collection = activeViewerCollection ?: ViewerCollection.Gallery(com.suyaphot.app.domain.gallery.GalleryFilter.ALL, "DATE_TAKEN_DESC"),
                container = container,
                onBack = { activeViewerItemId = null; activeViewerScope = null }
            )
        } else if (showTrashScreen) {
            TrashScreen(
                container = container,
                onBack = { showTrashScreen = false }
            )
        } else if (showPrivateTrashScreen) {
            TrashScreen(
                container = container,
                onBack = { showPrivateTrashScreen = false },
                privateMode = true
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
                        onTabSelected = {
                            if (activeTab == SuyaNavTab.FOLDERS && it != SuyaNavTab.FOLDERS) {
                                container.folderAccessManager.clear()
                            }
                            activeTab = it
                        },
                        onTabLongPressed = { tab ->
                            if (tab == SuyaNavTab.FOLDERS) {
                                activeTab = SuyaNavTab.FOLDERS
                                pendingHiddenEntryRequest = System.currentTimeMillis()
                            }
                        }
                    )
                },
                containerColor = SuyaColors.Background
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    AnimatedContent(targetState = activeTab, label = "tab_content") { tab ->
                        when (tab) {
                            SuyaNavTab.PHOTOS -> {
                                PhotosScreen(
                                    container = container,
                                    onMediaClick = { itemId, collection -> activeViewerScope = null; activeViewerCollection = collection; activeViewerItemId = itemId }
                                )
                            }
                            SuyaNavTab.FOLDERS -> {
                                FoldersScreen(
                                    container = container,
                                    hiddenEntryRequest = pendingHiddenEntryRequest,
                                    onHiddenEntryConsumed = { pendingHiddenEntryRequest = null },
                                    onMediaClick = { itemId, viewerScope, collection -> activeViewerScope = viewerScope; activeViewerCollection = collection; activeViewerItemId = itemId },
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
                                    onOpenTrash = { showTrashScreen = true },
                                    onOpenPrivateTrash = { showPrivateTrashScreen = true }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (hideSensitiveUi) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().background(SuyaColors.Background)
            ) {
                Image(
                    painter = painterResource(com.suyaphot.app.R.drawable.ic_suya_logo),
                    contentDescription = "Suya Phot privacy cover",
                    modifier = Modifier.size(72.dp)
                )
            }
        }
    }
}
