package com.suyaphot.app.feature.folders

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.model.Folder
import com.suyaphot.app.core.model.MediaItem
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.domain.folders.FolderDeletePolicy
import com.suyaphot.app.domain.folders.FolderManager
import com.suyaphot.app.domain.folders.ViewerAccessScope
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.EmptyState
import com.suyaphot.app.ui.components.FolderTile
import com.suyaphot.app.ui.components.MediaTile
import com.suyaphot.app.ui.components.PatternLockPad
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.SuyaIconButton
import com.suyaphot.app.ui.components.SuyaTextField
import com.suyaphot.app.ui.components.SuyaTopBar
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf

@Composable
fun FoldersScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onMediaClick: (itemId: String, scope: ViewerAccessScope?) -> Unit = { _, _ -> },
    onFolderOpened: (folderId: String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.sessionManager.sessionState.collectAsState().value
    val vaultId = (session as? VaultSession.Unlocked)?.vaultId ?: ""

    var currentParentId by remember { mutableStateOf<String?>(null) }
    var hiddenMode by remember { mutableStateOf(false) }
    val accessRevision by container.folderAccessManager.revision.collectAsState()
    var pendingFolderId by remember { mutableStateOf<String?>(null) }
    var pendingLockId by remember { mutableStateOf<String?>(null) }
    var pendingLockBioIv by remember { mutableStateOf<ByteArray?>(null) }
    var showHiddenAuth by remember { mutableStateOf(false) }
    var hiddenBioIv by remember { mutableStateOf<ByteArray?>(null) }
    var gateInput by remember { mutableStateOf("") }
    var gateError by remember { mutableStateOf<String?>(null) }
    var gateErrorTrigger by remember { mutableIntStateOf(0) }
    var gateTypeCode by remember { mutableIntStateOf(0) }
    var breadcrumbs by remember { mutableStateOf<List<Pair<String?, String>>>(emptyList()) }

    // Dialog states
    var showCreateDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var createFolderError by remember { mutableStateOf<String?>(null) }

    // Folder Context action sheet state
    var selectedFolderForAction by remember { mutableStateOf<Folder?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameFolderName by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    var showDeleteFolderDialog by remember { mutableStateOf(false) }
    var deleteFolderPolicy by remember { mutableStateOf(FolderDeletePolicy.MOVE_CONTENTS_TO_PARENT) }
    var showHideConfirmDialog by remember { mutableStateOf(false) }
    var showCreateLockDialog by remember { mutableStateOf(false) }
    var lockTypeCode by remember { mutableIntStateOf(0) }
    var newLockInput by remember { mutableStateOf("") }
    var confirmLockInput by remember { mutableStateOf("") }
    var firstLockPattern by remember { mutableStateOf<IntArray?>(null) }
    var lockError by remember { mutableStateOf<String?>(null) }
    var lockErrorTrigger by remember { mutableIntStateOf(0) }
    var showEnrollBiometricDialog by remember { mutableStateOf(false) }
    var enrollLockId by remember { mutableStateOf<String?>(null) }
    var enrollTypeCode by remember { mutableIntStateOf(0) }
    var enrollInput by remember { mutableStateOf("") }
    var enrollError by remember { mutableStateOf<String?>(null) }
    var enrollErrorTrigger by remember { mutableIntStateOf(0) }

    var showMoveFolderDialog by remember { mutableStateOf(false) }
    var targetParentFolderId by remember { mutableStateOf<String?>(null) }
    var allFoldersInVault by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var folderActionStatus by remember { mutableStateOf<String?>(null) }

    // Media Multi-selection in current folder
    val selectedMediaIds = remember { mutableStateMapOf<String, Unit>() }
    val isInSelectionMode by remember { derivedStateOf { selectedMediaIds.isNotEmpty() } }
    val gridCols by container.preferences.gridColumns.collectAsState(initial = 3)

    var showMoveMediaDialog by remember { mutableStateOf(false) }
    var moveMediaTargetFolderId by remember { mutableStateOf<String?>(null) }
    var showTrashConfirmDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }

    // Import states
    var isImporting by remember { mutableStateOf(false) }
    var importProgressText by remember { mutableStateOf("") }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isImporting = true
            scope.launch {
                container.importCoordinator.importBatch(
                    uris = uris,
                    folderId = currentParentId,
                    mode = ImportMode.COPY,
                    onItemComplete = { current, total, _ ->
                        scope.launch { importProgressText = "Importing $current of $total items..." }
                    }
                )
                isImporting = false
                importProgressText = ""
            }
        }
    }

    // Subfolders flow for current parent
    val foldersFlow = remember(currentParentId, hiddenMode, vaultId) {
        container.folderManager.getSubFoldersFlow(currentParentId, hiddenMode)
    }
    val folders by foldersFlow.collectAsState(initial = emptyList())

    // Media items flow for current parent folder
    val mediaFlow = remember(vaultId, currentParentId, hiddenMode) {
        if (hiddenMode && currentParentId == null) flowOf(emptyList())
        else container.database.mediaItemDao().getByFolder(vaultId, currentParentId)
    }
    val rawMediaEntities by mediaFlow.collectAsState(initial = emptyList())
    val mediaItems = remember(rawMediaEntities) {
        rawMediaEntities.map { entity ->
            MediaItem(
                id = entity.id,
                vaultId = entity.vaultId,
                folderId = entity.folderId,
                type = MediaType.fromCode(entity.mediaTypeCode),
                plaintextSize = entity.plaintextSize,
                cipherSize = entity.cipherSize,
                sha256Hex = entity.sha256Hex,
                importedAt = entity.importedAt,
                updatedAt = entity.updatedAt,
                favorite = entity.favorite,
                deletedAt = entity.deletedAt,
                previousFolderId = entity.previousFolderId
            )
        }
    }

    LaunchedEffect(currentParentId) {
        selectedMediaIds.clear()
        breadcrumbs = container.folderManager.getBreadcrumbs(currentParentId)
        if (currentParentId != null) {
            onFolderOpened(currentParentId!!)
        }
    }

    LaunchedEffect(accessRevision, currentParentId, hiddenMode, vaultId) {
        if (hiddenMode && !container.folderAccessManager.hasHiddenGrant(vaultId)) {
            container.folderAccessManager.clear()
            hiddenMode = false
            currentParentId = null
        } else if (currentParentId != null && !container.folderAccessManager.canOpen(vaultId, currentParentId!!)) {
            container.folderAccessManager.retainLocksForFolder(vaultId, null)
            currentParentId = null
        }
    }

    fun attemptOpenFolder(folderId: String) {
        pendingFolderId = folderId
        scope.launch {
            val entity = container.database.folderDao().getFolderForVault(folderId, vaultId) ?: return@launch
            if (entity.effectiveHidden && !container.folderAccessManager.hasHiddenGrant(vaultId)) {
                val vault = container.database.vaultDao().getVault(vaultId)
                gateTypeCode = vault?.credentialTypeCode ?: 0
                hiddenBioIv = vault?.biometricIv?.takeIf { vault.biometricEnvelope != null }
                gateInput = ""
                gateError = null
                showHiddenAuth = true
                return@launch
            }
            val missing = container.folderAccessManager.missingLockIds(vaultId, folderId)
            if (missing.isNotEmpty()) {
                pendingLockId = missing.first()
                val lock = container.database.folderLockDao().getForVault(vaultId, missing.first())
                gateTypeCode = lock?.credentialTypeCode ?: 0
                pendingLockBioIv = lock?.biometricIv?.takeIf { lock.biometricEnvelope != null }
                gateInput = ""
                gateError = null
                return@launch
            }
            container.folderAccessManager.retainLocksForFolder(vaultId, folderId)
            currentParentId = folderId
            pendingFolderId = null
        }
    }

    fun submitGate(credential: CharArray) {
        scope.launch {
            val success = if (showHiddenAuth) {
                container.pinAuthenticator.verifyCurrentCredential(credential, gateTypeCode)
            } else {
                val lockId = pendingLockId ?: return@launch
                container.folderLockManager.unlock(lockId, credential, gateTypeCode)
            }
            credential.fill('\u0000')
            gateInput = ""
            if (!success) {
                gateError = "Incorrect credential"
                gateErrorTrigger++
                return@launch
            }
            gateError = null
            if (showHiddenAuth) {
                container.folderAccessManager.grantHidden(vaultId)
                hiddenMode = true
                showHiddenAuth = false
            } else {
                pendingLockId = null
            }
            pendingFolderId?.let { attemptOpenFolder(it) }
        }
    }

    fun launchFolderBiometric() {
        val lockId = pendingLockId ?: return
        val iv = pendingLockBioIv ?: return
        val activity = context as? FragmentActivity ?: return
        try {
            val cipher = container.keyManager.createBiometricDecryptCipher("folder_$lockId", iv)
            val prompt = BiometricPrompt(
                activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authCipher = result.cryptoObject?.cipher ?: return
                        scope.launch {
                            if (container.folderLockManager.unlockWithBiometric(lockId, authCipher)) {
                                pendingLockId = null
                                pendingLockBioIv = null
                                pendingFolderId?.let { attemptOpenFolder(it) }
                            } else gateError = "Fingerprint unavailable; use the folder credential"
                        }
                    }
                }
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock protected folder")
                    .setNegativeButtonText("Use folder credential")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build(),
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (_: Exception) { gateError = "Fingerprint unavailable; use the folder credential" }
    }

    fun launchHiddenBiometric() {
        val iv = hiddenBioIv ?: return
        val activity = context as? FragmentActivity ?: return
        try {
            val cipher = container.keyManager.createBiometricDecryptCipher(vaultId, iv)
            val prompt = BiometricPrompt(
                activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authorizedCipher = result.cryptoObject?.cipher ?: return
                        scope.launch {
                            if (container.pinAuthenticator.verifyCurrentBiometric(authorizedCipher)) {
                                container.folderAccessManager.grantHidden(vaultId)
                                showHiddenAuth = false
                                hiddenMode = true
                                pendingFolderId?.let { attemptOpenFolder(it) }
                            } else gateError = "Fingerprint unavailable; use your vault credential"
                        }
                    }
                }
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Open Hidden folders")
                    .setNegativeButtonText("Use vault credential")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build(),
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (_: Exception) { gateError = "Fingerprint unavailable; use your vault credential" }
    }

    fun enrollFolderBiometric(credential: CharArray) {
        val lockId = enrollLockId ?: return
        val activity = context as? FragmentActivity ?: return
        scope.launch {
            val token = container.folderLockManager.tokenForBiometricEnrollment(lockId, credential, enrollTypeCode)
            if (token == null) { enrollError = "Incorrect folder credential"; enrollErrorTrigger++; return@launch }
            try {
                val cipher = container.keyManager.createBiometricEncryptCipher("folder_$lockId")
                val prompt = BiometricPrompt(
                    activity, ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val authCipher = result.cryptoObject?.cipher
                            try {
                                if (authCipher != null) {
                                    val envelope = authCipher.doFinal(token)
                                    scope.launch {
                                        if (container.folderLockManager.saveBiometricEnvelope(lockId, envelope, authCipher.iv)) {
                                            showEnrollBiometricDialog = false
                                            selectedFolderForAction = null
                                            folderActionStatus = "Fingerprint enabled for folder"
                                        } else enrollError = "Could not save fingerprint setting"
                                    }
                                }
                            } catch (_: Exception) { enrollError = "Fingerprint enrollment failed" }
                            finally { token.fill(0) }
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { token.fill(0) }
                    }
                )
                prompt.authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Enable folder fingerprint")
                        .setNegativeButtonText("Cancel")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .build(),
                    BiometricPrompt.CryptoObject(cipher)
                )
            } catch (_: Exception) { token.fill(0); enrollError = "Fingerprint unavailable" }
        }
    }

    LaunchedEffect(hiddenMode, currentParentId, vaultId) {
        while (hiddenMode || currentParentId != null) {
            delay(1_000)
            if (hiddenMode && !container.folderAccessManager.hasHiddenGrant(vaultId)) {
                container.folderAccessManager.clear()
                hiddenMode = false
                currentParentId = null
                break
            }
            currentParentId?.let {
                if (!container.folderAccessManager.canOpen(vaultId, it)) {
                    container.folderAccessManager.retainLocksForFolder(vaultId, null)
                    currentParentId = null
                }
            }
        }
    }

    val currentTitle = if (currentParentId == null) {
        if (hiddenMode) "Hidden folders" else "Folders"
    } else {
        breadcrumbs.lastOrNull { it.first == currentParentId }?.second ?: "Folder"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            if (isInSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SuyaIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Clear selection",
                            onClick = { selectedMediaIds.clear() },
                            size = 38
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "${selectedMediaIds.size} selected",
                            fontFamily = SoraFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 18.sp,
                            color = SuyaColors.White
                        )
                    }
                    SuyaIconButton(
                        icon = Icons.Default.SelectAll,
                        contentDescription = "Select all",
                        onClick = {
                            selectedMediaIds.clear()
                            mediaItems.forEach { selectedMediaIds[it.id] = Unit }
                        },
                        size = 38
                    )
                }
            } else {
                SuyaTopBar(
                    title = currentTitle,
                    navigationIcon = if (currentParentId != null || hiddenMode) Icons.AutoMirrored.Filled.ArrowBack else null,
                    onNavigationClick = if (currentParentId != null || hiddenMode) {
                        {
                            if (currentParentId == null) {
                                hiddenMode = false
                                container.folderAccessManager.clear()
                            } else {
                                val parentIdx = breadcrumbs.indexOfLast { it.first == currentParentId } - 1
                                val parent = if (parentIdx >= 0) breadcrumbs[parentIdx].first else null
                                scope.launch {
                                    container.folderAccessManager.retainLocksForFolder(vaultId, parent)
                                    currentParentId = parent
                                }
                            }
                        }
                    } else null,
                    actions = {
                        if (currentParentId == null && !hiddenMode) {
                            SuyaIconButton(
                                icon = Icons.Default.VisibilityOff,
                                contentDescription = "Hidden folders",
                                onClick = {
                                    scope.launch {
                                        val vault = container.database.vaultDao().getVault(vaultId)
                                        gateTypeCode = vault?.credentialTypeCode ?: 0
                                        hiddenBioIv = vault?.biometricIv?.takeIf { vault.biometricEnvelope != null }
                                        gateInput = ""
                                        gateError = null
                                        showHiddenAuth = true
                                    }
                                }
                            )
                        }
                        if (!hiddenMode || currentParentId != null) SuyaIconButton(
                            icon = Icons.Default.Add,
                            contentDescription = "Import media here",
                            onClick = {
                                pickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }
                        )
                        if (!hiddenMode || currentParentId != null) SuyaIconButton(
                            icon = Icons.Default.CreateNewFolder,
                            contentDescription = "New subfolder",
                            onClick = {
                                createFolderError = null
                                newFolderName = ""
                                showCreateDialog = true
                            }
                        )
                    }
                )
            }

            // Breadcrumb trail
            folderActionStatus?.let {
                Text(it, color = SuyaColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp))
            }
            if (breadcrumbs.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(breadcrumbs) { crumb ->
                        val isLast = crumb.first == currentParentId
                        Text(
                            text = crumb.second,
                            fontFamily = SoraFontFamily,
                            fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
                            fontSize = 13.sp,
                            color = if (isLast) SuyaColors.White else SuyaColors.Accent,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    container.folderAccessManager.retainLocksForFolder(vaultId, crumb.first)
                                    currentParentId = crumb.first
                                }
                            }
                        )
                        if (!isLast) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = SuyaColors.TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Main Content: Folders and Media in Folder
            if (folders.isEmpty() && mediaItems.isEmpty() && !isImporting) {
                EmptyState(
                    icon = Icons.Default.Folder,
                    title = if (currentParentId == null) "No folders created" else "This folder is empty",
                    subtitle = if (currentParentId == null) "Create organized, nested folders for your private media." else "Import media or create subfolders inside.",
                    actionText = if (hiddenMode && currentParentId == null) null else "Import Photos & Videos",
                    onActionClick = {
                        pickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridCols.coerceIn(2, 5)),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Child Folders section
                    if (folders.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "FOLDERS (${folders.size})",
                                fontFamily = SoraFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                color = SuyaColors.TextFaint,
                                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
                            )
                        }
                        items(
                            items = folders,
                            key = { "f_" + it.id },
                            span = { GridItemSpan(maxLineSpan) }
                        ) { folder ->
                            FolderTile(
                                folder = folder,
                                onClick = { attemptOpenFolder(folder.id) },
                                onLongClick = {
                                    selectedFolderForAction = folder
                                }
                            )
                        }
                    }

                    // Media items section
                    if (mediaItems.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "MEDIA (${mediaItems.size})",
                                fontFamily = SoraFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                color = SuyaColors.TextFaint,
                                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
                            )
                        }
                        items(
                            items = mediaItems,
                            key = { "m_" + it.id }
                        ) { item ->
                            val isSelected = selectedMediaIds.containsKey(item.id)
                            MediaTile(
                                item = item,
                                isSelected = isSelected,
                                isInSelectionMode = isInSelectionMode,
                                onClick = {
                                    if (isInSelectionMode) {
                                        if (isSelected) selectedMediaIds.remove(item.id)
                                        else selectedMediaIds[item.id] = Unit
                                    } else {
                                        val folderId = currentParentId
                                        if (folderId == null) onMediaClick(item.id, null)
                                        else scope.launch {
                                            val viewerScope = container.folderAccessManager.scopeForFolder(vaultId, folderId)
                                            if (viewerScope != null) onMediaClick(item.id, viewerScope)
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (!selectedMediaIds.containsKey(item.id)) {
                                        selectedMediaIds[item.id] = Unit
                                    }
                                },
                                thumbLoader = { itemId ->
                                    container.encryptedThumbnailRepository.load(vaultId, itemId, item.updatedAt)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Selection Action Bottom Sheet / Bar
        if (isInSelectionMode) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = SuyaColors.Surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuyaIconButton(
                        icon = Icons.AutoMirrored.Filled.DriveFileMove,
                        contentDescription = "Move to folder",
                        onClick = { showMoveMediaDialog = true }
                    )
                    SuyaIconButton(
                        icon = Icons.Default.Restore,
                        contentDescription = "Restore to Gallery",
                        onClick = { showRestoreConfirmDialog = true }
                    )
                    SuyaIconButton(
                        icon = Icons.Outlined.Delete,
                        contentDescription = "Move to Trash",
                        onClick = { showTrashConfirmDialog = true }
                    )
                }
            }
        }

        // Import loading indicator
        if (isImporting) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SuyaColors.Surface.copy(alpha = 0.95f),
                border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    CircularProgressIndicator(
                        color = SuyaColors.Accent,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = importProgressText.ifEmpty { "Encrypting media into folder..." },
                        fontFamily = SoraFontFamily,
                        fontSize = 14.sp,
                        color = SuyaColors.White
                    )
                }
            }
        }
    }

    if (showHiddenAuth || pendingLockId != null) {
        SuyaDialog(
            onDismissRequest = {
                showHiddenAuth = false
                pendingLockId = null
                pendingFolderId = null
                gateInput = ""
            },
            title = if (showHiddenAuth) "Open Hidden folders" else "Unlock folder",
            confirmText = if (gateTypeCode == 0) "Unlock" else null,
            onConfirm = if (gateTypeCode == 0) ({ submitGate(gateInput.toCharArray()) }) else null,
            content = {
                Column {
                    if (gateTypeCode == 1) {
                        PatternLockPad(
                            onPatternComplete = { raw ->
                                val chars = runCatching { PatternCredential.canonicalChars(raw) }.getOrNull()
                                if (chars == null) { gateError = "Connect at least four dots"; gateErrorTrigger++ }
                                else submitGate(chars)
                            },
                            errorTrigger = gateErrorTrigger,
                            enabled = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        SuyaTextField(
                            value = gateInput,
                            onValueChange = { gateInput = it.take(12); gateError = null },
                            label = "PIN",
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                        )
                    }
                    if (pendingLockId != null && pendingLockBioIv != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        SuyaButton(
                            text = "Use fingerprint",
                            onClick = { launchFolderBiometric() },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (showHiddenAuth && hiddenBioIv != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        SuyaButton(
                            text = "Use fingerprint",
                            onClick = { launchHiddenBiometric() },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    gateError?.let { Text(it, color = SuyaColors.Negative, fontSize = 12.sp) }
                }
            }
        )
    }

    // Create Folder Dialog
    if (showCreateDialog) {
        SuyaDialog(
            onDismissRequest = {
                showCreateDialog = false
                newFolderName = ""
                createFolderError = null
            },
            title = if (currentParentId == null) "New Folder" else "New Subfolder",
            content = {
                Column {
                    SuyaTextField(
                        value = newFolderName,
                        onValueChange = {
                            newFolderName = it
                            createFolderError = null
                        },
                        placeholder = "Folder name",
                        label = "Name"
                    )
                    if (createFolderError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = createFolderError!!,
                            fontFamily = SoraFontFamily,
                            fontSize = 12.sp,
                            color = SuyaColors.Negative
                        )
                    }
                }
            },
            confirmText = "Create",
            onConfirm = {
                scope.launch {
                    try {
                        container.folderManager.createFolder(
                            name = newFolderName,
                            parentId = currentParentId
                        )
                        newFolderName = ""
                        showCreateDialog = false
                        createFolderError = null
                    } catch (e: Exception) {
                        createFolderError = e.message ?: "Failed to create folder"
                    }
                }
            }
        )
    }

    // Folder Actions Dialog / Menu (on folder long press)
    if (selectedFolderForAction != null) {
        val targetFolder = selectedFolderForAction!!
        SuyaDialog(
            onDismissRequest = { selectedFolderForAction = null },
            title = targetFolder.name,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuyaButton(
                        text = "Rename Folder",
                        leadingIcon = Icons.Default.Edit,
                        onClick = {
                            renameFolderName = targetFolder.name
                            renameError = null
                            showRenameDialog = true
                        },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SuyaButton(
                        text = "Move Folder",
                        leadingIcon = Icons.AutoMirrored.Filled.DriveFileMove,
                        onClick = {
                            scope.launch {
                                allFoldersInVault = container.folderManager.getMoveDestinations(hiddenMode)
                                targetParentFolderId = targetFolder.parentId
                                showMoveFolderDialog = true
                            }
                        },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SuyaButton(
                        text = if (targetFolder.directHidden) "Unhide Folder" else "Hide Folder",
                        leadingIcon = Icons.Default.VisibilityOff,
                        onClick = { showHideConfirmDialog = true },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SuyaButton(
                        text = if (targetFolder.lockId == null) "Lock Folder" else "Remove Folder Lock",
                        leadingIcon = Icons.Default.Lock,
                        onClick = {
                            if (targetFolder.lockId == null) {
                                lockTypeCode = 0
                                newLockInput = ""
                                confirmLockInput = ""
                                firstLockPattern = null
                                lockError = null
                                showCreateLockDialog = true
                            } else {
                                scope.launch {
                                    if (!container.folderAccessManager.hasLockGrant(vaultId, targetFolder.lockId)) {
                                        attemptOpenFolder(targetFolder.id)
                                    } else {
                                        container.folderLockManager.remove(targetFolder.id)
                                        selectedFolderForAction = null
                                    }
                                }
                            }
                        },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (targetFolder.lockId != null && container.folderAccessManager.hasLockGrant(vaultId, targetFolder.lockId)) {
                        SuyaButton(
                            text = "Allow fingerprint for folder",
                            onClick = {
                                scope.launch {
                                    val lock = container.database.folderLockDao().getForVault(vaultId, targetFolder.lockId)
                                    if (lock != null) {
                                        enrollLockId = lock.id
                                        enrollTypeCode = lock.credentialTypeCode
                                        enrollInput = ""
                                        enrollError = null
                                        showEnrollBiometricDialog = true
                                    }
                                }
                            },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    SuyaButton(
                        text = "Delete Folder",
                        leadingIcon = Icons.Outlined.Delete,
                        onClick = {
                            showDeleteFolderDialog = true
                        },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmText = "Done",
            onConfirm = { selectedFolderForAction = null }
        )
    }

    if (showMoveFolderDialog && selectedFolderForAction != null) {
        val moving = selectedFolderForAction!!
        val byId = allFoldersInVault.associateBy { it.id }
        fun validTarget(targetId: String): Boolean {
            var cursor: String? = targetId
            val seen = HashSet<String>()
            while (cursor != null) {
                if (!seen.add(cursor) || cursor == moving.id) return false
                cursor = byId[cursor]?.parentId
            }
            return true
        }
        SuyaDialog(
            onDismissRequest = { showMoveFolderDialog = false },
            title = "Move ${moving.name}",
            confirmText = "Move",
            onConfirm = {
                scope.launch {
                    val moved = container.folderManager.moveFolder(moving.id, targetParentFolderId)
                    folderActionStatus = if (moved) "Folder moved" else "Folder could not be moved"
                    if (moved) { showMoveFolderDialog = false; selectedFolderForAction = null }
                }
            },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(300.dp).verticalScroll(rememberScrollState())) {
                    SuyaButton("Root", onClick = { targetParentFolderId = null }, variant = if (targetParentFolderId == null) ButtonVariant.Primary else ButtonVariant.Secondary)
                    allFoldersInVault.filter { validTarget(it.id) }.forEach { folder ->
                        SuyaButton(
                            text = folder.name,
                            onClick = { targetParentFolderId = folder.id },
                            variant = if (targetParentFolderId == folder.id) ButtonVariant.Primary else ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        )
    }

    if (showHideConfirmDialog && selectedFolderForAction != null) {
        val target = selectedFolderForAction!!
        SuyaDialog(
            onDismissRequest = { showHideConfirmDialog = false },
            title = if (target.directHidden) "Unhide ${target.name}?" else "Hide ${target.name}?",
            confirmText = if (target.directHidden) "Unhide" else "Hide",
            onConfirm = {
                scope.launch {
                    container.folderPrivacyCoordinator.setHidden(vaultId, target.id, !target.directHidden)
                    showHideConfirmDialog = false
                    selectedFolderForAction = null
                }
            },
            content = {
                Text(
                    if (target.directHidden) "It will remain hidden if its parent is still hidden."
                    else "This folder, its subfolders and media will disappear from normal Photos, Search, Favorites and Folders. Open Hidden folders to access it.",
                    color = SuyaColors.TextMuted, fontSize = 13.sp
                )
            }
        )
    }

    if (showCreateLockDialog && selectedFolderForAction != null) {
        val target = selectedFolderForAction!!
        SuyaDialog(
            onDismissRequest = { showCreateLockDialog = false; newLockInput = ""; confirmLockInput = ""; firstLockPattern = null },
            title = "Lock ${target.name}",
            confirmText = if (lockTypeCode == 0) "Create lock" else null,
            onConfirm = if (lockTypeCode == 0) ({
                if (newLockInput.length !in 4..12 || !newLockInput.all(Char::isDigit)) {
                    lockError = "Use 4–12 digits (6 or more recommended)"
                } else if (newLockInput != confirmLockInput) {
                    lockError = "PINs do not match"
                } else {
                    scope.launch {
                        val created = container.folderLockManager.create(target.id, newLockInput.toCharArray(), 0)
                        if (created) {
                            showCreateLockDialog = false
                            selectedFolderForAction = null
                            newLockInput = ""
                            confirmLockInput = ""
                        } else lockError = "Could not create folder lock"
                    }
                }
            }) else null,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuyaButton("PIN", onClick = { lockTypeCode = 0; firstLockPattern = null }, variant = if (lockTypeCode == 0) ButtonVariant.Primary else ButtonVariant.Secondary)
                        SuyaButton("Pattern", onClick = { lockTypeCode = 1; newLockInput = ""; confirmLockInput = "" }, variant = if (lockTypeCode == 1) ButtonVariant.Primary else ButtonVariant.Secondary)
                    }
                    if (lockTypeCode == 0) {
                        SuyaTextField(newLockInput, onValueChange = { newLockInput = it.take(12) }, label = "New PIN", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        SuyaTextField(confirmLockInput, onValueChange = { confirmLockInput = it.take(12) }, label = "Confirm PIN", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    } else {
                        Text(if (firstLockPattern == null) "Draw a pattern" else "Draw it again to confirm", color = SuyaColors.TextMuted, fontSize = 13.sp)
                        PatternLockPad(
                            onPatternComplete = { raw ->
                                val normalized = runCatching { PatternCredential.normalize(raw) }.getOrNull()
                                if (normalized == null) { lockError = "Connect at least four dots"; lockErrorTrigger++ }
                                else if (firstLockPattern == null) { firstLockPattern = normalized; lockError = null }
                                else if (!normalized.contentEquals(firstLockPattern)) { lockError = "Patterns do not match"; lockErrorTrigger++; firstLockPattern = null }
                                else {
                                    scope.launch {
                                        val created = container.folderLockManager.create(target.id, PatternCredential.canonicalChars(normalized), 1)
                                        if (created) { showCreateLockDialog = false; selectedFolderForAction = null; firstLockPattern = null }
                                        else lockError = "Could not create folder lock"
                                    }
                                }
                            },
                            errorTrigger = lockErrorTrigger,
                            enabled = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    lockError?.let { Text(it, color = SuyaColors.Negative, fontSize = 12.sp) }
                    Text("Folder locks guard access inside an unlocked vault; they are not separate encryption keys for media.", color = SuyaColors.TextMuted, fontSize = 11.sp)
                }
            }
        )
    }

    if (showEnrollBiometricDialog && enrollLockId != null) {
        SuyaDialog(
            onDismissRequest = { showEnrollBiometricDialog = false; enrollInput = "" },
            title = "Enable folder fingerprint",
            confirmText = if (enrollTypeCode == 0) "Continue" else null,
            onConfirm = if (enrollTypeCode == 0) ({ enrollFolderBiometric(enrollInput.toCharArray()); enrollInput = "" }) else null,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Verify this folder's credential before enrolling fingerprint.", color = SuyaColors.TextMuted, fontSize = 12.sp)
                    if (enrollTypeCode == 0) {
                        SuyaTextField(enrollInput, onValueChange = { enrollInput = it.take(12) }, label = "Folder PIN", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    } else {
                        PatternLockPad(
                            onPatternComplete = { raw ->
                                val chars = runCatching { PatternCredential.canonicalChars(raw) }.getOrNull()
                                if (chars == null) { enrollError = "Connect at least four dots"; enrollErrorTrigger++ }
                                else enrollFolderBiometric(chars)
                            },
                            errorTrigger = enrollErrorTrigger,
                            enabled = true
                        )
                    }
                    enrollError?.let { Text(it, color = SuyaColors.Negative, fontSize = 12.sp) }
                }
            }
        )
    }

    // Rename Folder Dialog
    if (showRenameDialog && selectedFolderForAction != null) {
        val targetFolder = selectedFolderForAction!!
        SuyaDialog(
            onDismissRequest = {
                showRenameDialog = false
                renameError = null
            },
            title = "Rename Folder",
            content = {
                Column {
                    SuyaTextField(
                        value = renameFolderName,
                        onValueChange = {
                            renameFolderName = it
                            renameError = null
                        },
                        placeholder = "New folder name",
                        label = "Folder Name"
                    )
                    if (renameError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = renameError!!,
                            fontFamily = SoraFontFamily,
                            fontSize = 12.sp,
                            color = SuyaColors.Negative
                        )
                    }
                }
            },
            confirmText = "Save",
            onConfirm = {
                scope.launch {
                    try {
                        container.folderManager.renameFolder(targetFolder.id, renameFolderName)
                        showRenameDialog = false
                        selectedFolderForAction = null
                    } catch (e: Exception) {
                        renameError = e.message ?: "Failed to rename folder"
                    }
                }
            }
        )
    }

    // Delete Folder Confirmation Dialog (with policy choice)
    if (showDeleteFolderDialog && selectedFolderForAction != null) {
        val targetFolder = selectedFolderForAction!!
        SuyaDialog(
            onDismissRequest = { showDeleteFolderDialog = false },
            title = "Delete '${targetFolder.name}'?",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Choose what happens to the files and subfolders inside:",
                        fontFamily = SoraFontFamily,
                        fontSize = 13.sp,
                        color = SuyaColors.TextMuted
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteFolderPolicy = FolderDeletePolicy.MOVE_CONTENTS_TO_PARENT }
                    ) {
                        RadioButton(
                            selected = deleteFolderPolicy == FolderDeletePolicy.MOVE_CONTENTS_TO_PARENT,
                            onClick = { deleteFolderPolicy = FolderDeletePolicy.MOVE_CONTENTS_TO_PARENT },
                            colors = RadioButtonDefaults.colors(selectedColor = SuyaColors.Accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Move contents to parent folder",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.White
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteFolderPolicy = FolderDeletePolicy.DELETE_CONTENTS_TO_TRASH }
                    ) {
                        RadioButton(
                            selected = deleteFolderPolicy == FolderDeletePolicy.DELETE_CONTENTS_TO_TRASH,
                            onClick = { deleteFolderPolicy = FolderDeletePolicy.DELETE_CONTENTS_TO_TRASH },
                            colors = RadioButtonDefaults.colors(selectedColor = SuyaColors.Accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Move all contents to Vault Trash",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.White
                        )
                    }
                }
            },
            confirmText = "Delete",
            onConfirm = {
                scope.launch {
                    container.folderManager.deleteFolder(targetFolder.id, deleteFolderPolicy)
                    showDeleteFolderDialog = false
                    selectedFolderForAction = null
                }
            }
        )
    }

    // Move Media Items to Folder Dialog
    if (showMoveMediaDialog) {
        SuyaDialog(
            onDismissRequest = { showMoveMediaDialog = false },
            title = "Move ${selectedMediaIds.size} items",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Select destination folder:",
                        fontFamily = SoraFontFamily,
                        fontSize = 13.sp,
                        color = SuyaColors.TextMuted
                    )

                    // Root option
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { moveMediaTargetFolderId = null }
                    ) {
                        RadioButton(
                            selected = moveMediaTargetFolderId == null,
                            onClick = { moveMediaTargetFolderId = null },
                            colors = RadioButtonDefaults.colors(selectedColor = SuyaColors.Accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Root (All Photos)",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.White
                        )
                    }

                    // Available folders
                    folders.forEach { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { moveMediaTargetFolderId = f.id }
                        ) {
                            RadioButton(
                                selected = moveMediaTargetFolderId == f.id,
                                onClick = { moveMediaTargetFolderId = f.id },
                                colors = RadioButtonDefaults.colors(selectedColor = SuyaColors.Accent)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = f.name,
                                fontFamily = SoraFontFamily,
                                fontSize = 13.sp,
                                color = SuyaColors.White
                            )
                        }
                    }
                }
            },
            confirmText = "Move",
            onConfirm = {
                scope.launch {
                    val ids = selectedMediaIds.keys.toList()
                    selectedMediaIds.clear()
                    showMoveMediaDialog = false
                    container.folderManager.moveMediaToFolder(ids, moveMediaTargetFolderId)
                }
            }
        )
    }

    // Move to Trash Confirmation
    if (showTrashConfirmDialog) {
        SuyaDialog(
            onDismissRequest = { showTrashConfirmDialog = false },
            title = "Move to Vault Trash?",
            content = {
                Text(
                    text = "Items in Trash are retained for 30 days before permanent deletion.",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.TextMuted
                )
            },
            confirmText = "Move to Trash",
            onConfirm = {
                scope.launch {
                    val ids = selectedMediaIds.keys.toList()
                    selectedMediaIds.clear()
                    showTrashConfirmDialog = false
                    withContext(Dispatchers.IO) {
                        container.database.mediaItemDao().softDeleteForVault(
                            vaultId,
                            ids,
                            System.currentTimeMillis()
                        )
                    }
                }
            }
        )
    }

    // Restore Confirmation
    if (showRestoreConfirmDialog) {
        SuyaDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = "Restore to Public Gallery?",
            content = {
                Text(
                    text = "These items will be decrypted and returned to your public Samsung Gallery.",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.TextMuted
                )
            },
            confirmText = "Restore",
            onConfirm = {
                scope.launch {
                    val ids = selectedMediaIds.keys.toList()
                    selectedMediaIds.clear()
                    showRestoreConfirmDialog = false
                    withContext(Dispatchers.IO) {
                        for (id in ids) {
                            container.restoreCoordinator.restoreItem(id, move = true)
                        }
                    }
                }
            }
        )
    }
}
