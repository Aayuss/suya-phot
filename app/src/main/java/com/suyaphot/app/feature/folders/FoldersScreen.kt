package com.suyaphot.app.feature.folders

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.model.Folder
import com.suyaphot.app.core.model.MediaItem
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.folders.FolderDeletePolicy
import com.suyaphot.app.domain.folders.FolderManager
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.EmptyState
import com.suyaphot.app.ui.components.FolderTile
import com.suyaphot.app.ui.components.MediaTile
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.SuyaIconButton
import com.suyaphot.app.ui.components.SuyaTextField
import com.suyaphot.app.ui.components.SuyaTopBar
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FoldersScreen(
    container: AppContainer,
    onMediaClick: (itemId: String) -> Unit = {},
    onFolderOpened: (folderId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val session = container.sessionManager.sessionState.collectAsState().value
    val vaultId = (session as? VaultSession.Unlocked)?.vaultId ?: ""

    var currentParentId by remember { mutableStateOf<String?>(null) }
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

    var showMoveFolderDialog by remember { mutableStateOf(false) }
    var targetParentFolderId by remember { mutableStateOf<String?>(null) }
    var allFoldersInVault by remember { mutableStateOf<List<Folder>>(emptyList()) }

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
                    onItemComplete = { current, total, _ ->
                        importProgressText = "Importing $current of $total items..."
                    }
                )
                isImporting = false
                importProgressText = ""
            }
        }
    }

    // Subfolders flow for current parent
    val foldersFlow = remember(currentParentId) {
        container.folderManager.getSubFoldersFlow(currentParentId)
    }
    val folders by foldersFlow.collectAsState(initial = emptyList())

    // Media items flow for current parent folder
    val mediaFlow = remember(vaultId, currentParentId) {
        container.database.mediaItemDao().getByFolder(vaultId, currentParentId)
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

    val currentTitle = if (currentParentId == null) {
        "Folders"
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
                    navigationIcon = if (currentParentId != null) Icons.AutoMirrored.Filled.ArrowBack else null,
                    onNavigationClick = if (currentParentId != null) {
                        {
                            val parentIdx = breadcrumbs.indexOfLast { it.first == currentParentId } - 1
                            currentParentId = if (parentIdx >= 0) breadcrumbs[parentIdx].first else null
                        }
                    } else null,
                    actions = {
                        SuyaIconButton(
                            icon = Icons.Default.Add,
                            contentDescription = "Import media here",
                            onClick = {
                                pickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }
                        )
                        SuyaIconButton(
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
                                currentParentId = crumb.first
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
                    actionText = "Import Photos & Videos",
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
                                onClick = { currentParentId = folder.id },
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
                                        onMediaClick(item.id)
                                    }
                                },
                                onLongClick = {
                                    if (!selectedMediaIds.containsKey(item.id)) {
                                        selectedMediaIds[item.id] = Unit
                                    }
                                },
                                thumbLoader = { itemId ->
                                    val thumbFile = container.vaultFileStore.getThumbFile(vaultId, itemId)
                                    val subkey = (container.sessionManager.sessionState.value as? VaultSession.Unlocked)?.thumbSubkey
                                    if (subkey != null) {
                                        container.thumbnailGenerator.decryptThumbnail(thumbFile, subkey, itemId)
                                    } else null
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
                        container.database.mediaItemDao().softDelete(ids, System.currentTimeMillis())
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
