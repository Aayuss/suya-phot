package com.suyaphot.app.feature.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.model.MediaItem
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.auth.PatternCredential
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var accessTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(privateMode) {
        while (privateMode) {
            delay(1_000)
            accessTick++
        }
    }
    accessTick
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

    val trashFlow = remember(vaultId, privateMode) {
        if (privateMode) container.database.mediaItemDao().getPrivateTrashItems(vaultId)
        else container.database.mediaItemDao().getTrashItems(vaultId)
    }
    val rawEntities by trashFlow.collectAsState(initial = emptyList())
    var authorizedEntities by remember { mutableStateOf(rawEntities) }
    var missingLockIds by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(rawEntities, accessRevision, privateMode) {
        if (!privateMode) { authorizedEntities = rawEntities; missingLockIds = emptyList(); return@LaunchedEffect }
        val allowed = ArrayList<com.suyaphot.app.core.database.entity.MediaItemEntity>()
        val missing = LinkedHashSet<String>()
        for (entity in rawEntities) {
            val folderId = entity.previousFolderId
            if (folderId != null && container.database.folderDao().getFolderForVault(folderId, vaultId) != null) {
                val locks = container.folderAccessManager.missingLockIds(vaultId, folderId)
                if (locks.isNotEmpty()) { missing += locks.first(); continue }
            }
            allowed += entity
        }
        authorizedEntities = allowed
        missingLockIds = missing.toList()
    }

    val items = remember(authorizedEntities) {
        authorizedEntities.map { entity ->
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

    val selectedIds = remember { mutableStateMapOf<String, Unit>() }
    val gridCols by container.preferences.gridColumns.collectAsState(initial = 3)
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var pendingLockId by remember { mutableStateOf<String?>(null) }
    var pendingLockType by remember { mutableIntStateOf(0) }
    var lockInput by remember { mutableStateOf("") }
    var lockError by remember { mutableStateOf<String?>(null) }
    var lockErrorTrigger by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SuyaTopBar(
                title = if (privateMode) "Private Trash" else "Vault Trash",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = onBack,
                actions = {
                    if (items.isNotEmpty()) {
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
                    onClick = {
                        scope.launch {
                            val id = missingLockIds.first()
                            pendingLockType = container.database.folderLockDao().getForVault(vaultId, id)?.credentialTypeCode ?: 0
                            pendingLockId = id
                            lockInput = ""
                            lockError = null
                        }
                    },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                )
            }

            if (items.isEmpty()) {
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
                    items(items, key = { it.id }) { item ->
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
                    if (success) pendingLockId = null else { lockError = "Incorrect folder PIN"; lockErrorTrigger++ }
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
                                    if (success) pendingLockId = null else { lockError = "Incorrect folder pattern"; lockErrorTrigger++ }
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
                    container.trashCoordinator.permanentDelete(vaultId, items.map { it.id })
                }
            }
        )
    }
}

@Composable
private fun PrivateTrashGate(container: AppContainer, vaultId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var credentialTypeCode by remember(vaultId) { mutableIntStateOf(-1) }
    var pinInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(vaultId) {
        credentialTypeCode = container.database.vaultDao().getVault(vaultId)?.credentialTypeCode ?: -1
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
        error?.let { Text(it, color = SuyaColors.Negative, fontSize = 12.sp) }
    }
}
