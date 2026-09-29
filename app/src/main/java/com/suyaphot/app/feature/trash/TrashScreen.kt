package com.suyaphot.app.feature.trash

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.model.MediaItem
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.domain.folders.FolderAccessRequirement
import com.suyaphot.app.feature.folders.FolderRecoveryResetDialog
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.EmptyState
import com.suyaphot.app.ui.components.MediaTile
import com.suyaphot.app.ui.components.PatternLockPad
import com.suyaphot.app.ui.components.SuyaTextField
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.SuyaIconButton
import com.suyaphot.app.ui.components.SuyaTopBar
import com.suyaphot.app.ui.theme.SuyaColors
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TrashScreen(
    container: AppContainer,
    onBack: () -> Unit,
    privateMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val session = container.sessionManager.sessionState.collectAsState().value
    val vaultId = (session as? VaultSession.Unlocked)?.vaultId ?: ""
    val accessRevision by container.folderAccessManager.revision.collectAsState()
    if (privateMode && !container.folderAccessManager.hasHiddenGrant(vaultId)) {
        PrivateTrashGate(container, vaultId, onBack)
        return
    }

    val retentionDays by container.preferences.trashRetentionDays.collectAsState(initial = 30)

    // Auto-purge uses the configured retention and drains all expired batches safely.
    LaunchedEffect(vaultId) {
        if (vaultId.isNotEmpty()) {
            container.trashCoordinator.purgeExpired(vaultId)
        }
    }

    val trashFlow = remember(vaultId, privateMode, accessRevision) {
        container.galleryRepository.pagedTrash(vaultId, privateMode)
    }
    val pagedEntities = trashFlow.collectAsLazyPagingItems()
    var missingLockIds by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(pagedEntities.itemCount, accessRevision, privateMode) {
        if (!privateMode) { missingLockIds = emptyList(); return@LaunchedEffect }
        val missing = LinkedHashSet<String>()
        for (folderId in container.database.mediaItemDao().getPrivateTrashFolderIds(vaultId)) {
            if (container.database.folderDao().getFolderForVault(folderId, vaultId) != null) {
                val locks = container.folderAccessManager.missingLockIds(vaultId, folderId)
                    ?: continue
                if (locks.isNotEmpty()) { missing += locks.first(); continue }
            }
        }
        missingLockIds = missing.toList()
    }

    val selectedIds = remember { mutableStateMapOf<String, Unit>() }
    val gridCols by container.preferences.gridColumns.collectAsState(initial = 3)
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var pendingLockId by remember { mutableStateOf<String?>(null) }
    var pendingLockType by remember { mutableIntStateOf(0) }
    var lockInput by remember { mutableStateOf("") }
    var lockError by remember { mutableStateOf<String?>(null) }
    var lockErrorTrigger by remember { mutableIntStateOf(0) }
    var pendingResetFolderId by remember { mutableStateOf<String?>(null) }
    var pendingResetFolderName by remember { mutableStateOf("Protected folder") }
    var pendingResetType by remember { mutableIntStateOf(0) }

    fun triggerNextUnlock() {
        scope.launch {
            val folderIds = container.database.mediaItemDao().getPrivateTrashFolderIds(vaultId)
            for (folderId in folderIds) {
                when (val req = container.folderAccessManager.nextRequirement(vaultId, folderId)) {
                    is FolderAccessRequirement.RecoveryReset -> {
                        val folder = container.database.folderDao().getFolderForVault(req.folderId, vaultId)
                        val unlocked = session as? VaultSession.Unlocked
                        val folderName = if (folder != null && unlocked != null) {
                            try {
                                val bytes = Aead.decryptWithPrependedNonce(
                                    unlocked.metaSubkey,
                                    folder.encryptedName,
                                    folder.id.toByteArray(Charsets.UTF_8)
                                )
                                try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0.toByte()) }
                            } catch (_: Exception) {
                                "Protected folder"
                            }
                        } else "Protected folder"

                        pendingResetFolderId = req.folderId
                        pendingResetFolderName = folderName
                        pendingResetType = req.credentialTypeCode
                        return@launch
                    }
                    is FolderAccessRequirement.Credential -> {
                        pendingLockId = req.lockId
                        pendingLockType = req.credentialTypeCode
                        lockInput = ""
                        lockError = null
                        return@launch
                    }
                    else -> { /* continue */ }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SuyaTopBar(
                title = if (privateMode) "Private Trash" else "Vault Trash",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = onBack,
                actions = {
                    if (pagedEntities.itemCount > 0) {
                        SuyaButton(
                            text = "Empty",
                            onClick = { showEmptyTrashDialog = true },
                            variant = ButtonVariant.Ghost
                        )
                    }
                }
            )

            if (privateMode && missingLockIds.isNotEmpty()) {
                SuyaButton(
                    text = "Unlock protected deleted items",
                    onClick = { triggerNextUnlock() },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                )
            }

            if (pagedEntities.itemCount == 0 && pagedEntities.loadState.refresh is LoadState.NotLoading) {
                EmptyState(
                    icon = Icons.Default.Delete,
                    title = "Trash is empty",
                    subtitle = if (retentionDays == 0) {
                        "Items remain here until you delete them permanently."
                    } else {
                        "Items are automatically purged after $retentionDays days."
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridCols.coerceIn(2, 5)),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(2.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(count = pagedEntities.itemCount,
                        key = { index -> pagedEntities.peek(index)?.id ?: "placeholder_$index" }) { index ->
                        val entity = pagedEntities[index] ?: return@items
                        val item = MediaItem(
                            id = entity.id, vaultId = entity.vaultId, folderId = entity.folderId,
                            type = MediaType.fromCode(entity.mediaTypeCode),
                            plaintextSize = entity.plaintextSize, cipherSize = entity.cipherSize,
                            sha256Hex = entity.sha256Hex, importedAt = entity.importedAt,
                            updatedAt = entity.updatedAt, favorite = entity.favorite,
                            deletedAt = entity.deletedAt, previousFolderId = entity.previousFolderId
                        )
                        val isSelected = selectedIds.containsKey(item.id)
                        MediaTile(
                            item = item,
                            isSelected = isSelected,
                            isInSelectionMode = selectedIds.isNotEmpty(),
                            onClick = {
                                if (isSelected) selectedIds.remove(item.id)
                                else selectedIds[item.id] = Unit
                            },
                            onLongClick = {
                                if (!selectedIds.containsKey(item.id)) selectedIds[item.id] = Unit
                            },
                            thumbLoader = { itemId ->
                                container.encryptedThumbnailRepository.load(vaultId, itemId, item.updatedAt)
                            }
                        )
                    }
                }

                if (selectedIds.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SuyaColors.Surface)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SuyaButton(
                            text = "Restore (${selectedIds.size})",
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val toRestore = selectedIds.keys.toList()
                                    container.trashCoordinator.restore(vaultId, toRestore)
                                    withContext(Dispatchers.Main) { selectedIds.clear() }
                                }
                            },
                            variant = ButtonVariant.Secondary
                        )
                        SuyaButton(
                            text = "Delete Forever",
                            onClick = { showDeleteSelectedDialog = true },
                            variant = ButtonVariant.Primary
                        )
                    }
                }
            }
        }
    }

    if (pendingLockId != null) {
        SuyaDialog(
            onDismissRequest = { pendingLockId = null; lockInput = "" },
            title = "Unlock protected folder",
            confirmText = if (pendingLockType == 0) "Unlock" else null,
            onConfirm = if (pendingLockType == 0) ({
                scope.launch {
                    val success = container.folderLockManager.unlock(pendingLockId!!, lockInput.toCharArray(), 0)
                    lockInput = ""
                    if (success) {
                        pendingLockId = null
                        triggerNextUnlock()
                    } else {
                        lockError = "Incorrect folder PIN"
                        lockErrorTrigger++
                    }
                }
            }) else null,
            content = {
                Column {
                    if (pendingLockType == 0) {
                        SuyaTextField(lockInput, onValueChange = { lockInput = it.take(12) }, label = "Folder PIN", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    } else {
                        PatternLockPad(
                            onPatternComplete = { raw ->
                                val chars = runCatching { PatternCredential.canonicalChars(raw) }.getOrNull()
                                if (chars == null) { lockError = "Connect at least four dots"; lockErrorTrigger++ }
                                else scope.launch {
                                    val success = container.folderLockManager.unlock(pendingLockId!!, chars, 1)
                                    if (success) {
                                        pendingLockId = null
                                        triggerNextUnlock()
                                    } else {
                                        lockError = "Incorrect folder pattern"
                                        lockErrorTrigger++
                                    }
                                }
                            },
                            errorTrigger = lockErrorTrigger,
                            enabled = true
                        )
                    }
                    lockError?.let { Text(it, color = SuyaColors.Negative, fontSize = 12.sp) }
                }
            }
        )
    }

    if (pendingResetFolderId != null) {
        FolderRecoveryResetDialog(
            container = container,
            folderId = pendingResetFolderId!!,
            folderName = pendingResetFolderName,
            initialTypeCode = pendingResetType,
            onDismissRequest = { pendingResetFolderId = null },
            onResetSuccess = {
                pendingResetFolderId = null
                triggerNextUnlock()
            }
        )
    }

    if (showDeleteSelectedDialog) {
        val count = selectedIds.size
        SuyaDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = "Delete $count item${if (count == 1) "" else "s"} forever?",
            content = {
                Text("This cannot be undone. Encrypted media will be removed from private storage.", color = SuyaColors.TextMuted)
            },
            confirmText = "Delete Forever",
            onConfirm = {
                val ids = selectedIds.keys.toList()
                showDeleteSelectedDialog = false
                scope.launch {
                    container.trashCoordinator.permanentDelete(vaultId, ids)
                    selectedIds.clear()
                }
            }
        )
    }

    if (showEmptyTrashDialog) {
        SuyaDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            title = "Empty Trash?",
            content = {
                Text(
                    text = "All items in Trash will be permanently erased from private storage.",
                    color = SuyaColors.TextMuted
                )
            },
            confirmText = "Empty Trash",
            onConfirm = {
                scope.launch {
                    showEmptyTrashDialog = false
                    val ids = container.database.mediaItemDao().getAllTrashIds(vaultId, privateMode)
                    container.trashCoordinator.permanentDelete(vaultId, ids)
                }
            }
        )
    }
}

@Composable
private fun PrivateTrashGate(container: AppContainer, vaultId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var credentialTypeCode by remember(vaultId) { mutableIntStateOf(-1) }
    var biometricIv by remember(vaultId) { mutableStateOf<ByteArray?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(vaultId) {
        val vault = container.database.vaultDao().getVault(vaultId)
        credentialTypeCode = vault?.credentialTypeCode ?: -1
        biometricIv = vault?.biometricIv?.takeIf { vault.biometricEnvelope != null }
    }
    fun submit(chars: CharArray) {
        scope.launch {
            val valid = container.pinAuthenticator.verifyCurrentCredential(chars, credentialTypeCode)
            chars.fill('\u0000')
            pinInput = ""
            if (valid) container.folderAccessManager.grantHidden(vaultId)
            else { error = "Incorrect vault credential"; errorTrigger++ }
        }
    }
    fun submitBiometric() {
        val iv = biometricIv ?: return
        val activity = context as? FragmentActivity ?: return
        try {
            val cipher = container.keyManager.createBiometricDecryptCipher(vaultId, iv)
            val prompt = BiometricPrompt(
                activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authorized = result.cryptoObject?.cipher ?: return
                        scope.launch {
                            if (container.pinAuthenticator.verifyCurrentBiometric(authorized)) {
                                container.folderAccessManager.grantHidden(vaultId)
                            } else error = "Fingerprint unavailable; use your vault credential"
                        }
                    }
                }
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Open Private Trash")
                    .setNegativeButtonText("Use vault credential")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build(),
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (_: Exception) { error = "Fingerprint unavailable; use your vault credential" }
    }
    Column(modifier = Modifier.fillMaxSize().background(SuyaColors.Background).padding(18.dp)) {
        SuyaTopBar("Private Trash", navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = onBack)
        Text("Re-authenticate to view deleted protected media.", color = SuyaColors.TextMuted, fontSize = 13.sp)
        if (credentialTypeCode == 0) {
            SuyaTextField(pinInput, onValueChange = { pinInput = it.take(6) }, label = "Vault PIN", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            SuyaButton("Unlock", onClick = { submit(pinInput.toCharArray()) })
        } else if (credentialTypeCode == 1) {
            PatternLockPad(
                onPatternComplete = { raw ->
                    val chars = runCatching { PatternCredential.canonicalChars(raw) }.getOrNull()
                    if (chars == null) { error = "Connect at least four dots"; errorTrigger++ }
                    else submit(chars)
                },
                errorTrigger = errorTrigger,
                enabled = true
            )
        }
        if (biometricIv != null) {
            SuyaButton("Use vault fingerprint", onClick = ::submitBiometric, variant = ButtonVariant.Secondary)
        }
        error?.let { Text(it, color = SuyaColors.Negative, fontSize = 12.sp) }
    }
}
