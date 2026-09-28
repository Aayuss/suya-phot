package com.suyaphot.app.feature.photos

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.model.MediaItem
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.EmptyState
import com.suyaphot.app.ui.components.MediaFilter
import com.suyaphot.app.ui.components.MediaTile
import com.suyaphot.app.ui.components.SegmentedFilterChips
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.SuyaIconButton
import com.suyaphot.app.ui.components.SuyaSearchField
import com.suyaphot.app.ui.components.SuyaTopBar
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PhotosScreen(
    container: AppContainer,
    onMediaClick: (itemId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val session = container.sessionManager.sessionState.collectAsState().value
    val vaultId = (session as? VaultSession.Unlocked)?.vaultId ?: ""

    var selectedFilter by remember { mutableStateOf(MediaFilter.ALL) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val selectedMediaIds = remember { mutableStateListOf<String>() }
    val isInSelectionMode by remember { derivedStateOf { selectedMediaIds.isNotEmpty() } }

    var isImporting by remember { mutableStateOf(false) }
    var importProgressText by remember { mutableStateOf("") }

    // Dialog states
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }

    // Multi-picker launcher
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isImporting = true
            scope.launch {
                container.importCoordinator.importBatch(
                    uris = uris,
                    folderId = null,
                    onItemComplete = { current, total, _ ->
                        importProgressText = "Importing $current of $total items..."
                    }
                )
                isImporting = false
                importProgressText = ""
            }
        }
    }

    // Media stream query depending on active filter
    val mediaFlow = remember(vaultId, selectedFilter) {
        when (selectedFilter) {
            MediaFilter.ALL -> container.database.mediaItemDao().getAllActive(vaultId)
            MediaFilter.PHOTOS -> container.database.mediaItemDao().getPhotosOnly(vaultId)
            MediaFilter.VIDEOS -> container.database.mediaItemDao().getVideosOnly(vaultId)
            MediaFilter.FAVORITES -> container.database.mediaItemDao().getFavorites(vaultId)
        }
    }
    val rawEntities by mediaFlow.collectAsState(initial = emptyList())

    val items = remember(rawEntities) {
        rawEntities.map { entity ->
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuyaIconButton(
                            icon = Icons.Default.SelectAll,
                            contentDescription = "Select all",
                            onClick = {
                                selectedMediaIds.clear()
                                selectedMediaIds.addAll(items.map { it.id })
                            },
                            size = 38
                        )
                    }
                }
            } else {
                SuyaTopBar(
                    title = "Suya Phot",
                    actions = {
                        SuyaIconButton(
                            icon = Icons.Default.Search,
                            contentDescription = "Search",
                            onClick = { isSearchVisible = !isSearchVisible },
                            active = isSearchVisible
                        )
                        SuyaIconButton(
                            icon = Icons.Default.Add,
                            contentDescription = "Import media",
                            onClick = {
                                pickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }
                        )
                    }
                )
            }

            // Search Bar (collapsible)
            AnimatedVisibility(
                visible = isSearchVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    SuyaSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "Search filenames, folders..."
                    )
                }
            }

            // Filter Chips
            SegmentedFilterChips(
                selectedFilter = selectedFilter,
                onFilterSelected = { selectedFilter = it },
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
            )

            // Media Grid or Empty State
            if (items.isEmpty() && !isImporting) {
                EmptyState(
                    icon = Icons.Default.PhotoLibrary,
                    title = "No media in vault",
                    subtitle = "Tap '+' to import private photos or videos from your gallery.",
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
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(
                        items = items,
                        key = { it.id }
                    ) { item ->
                        val isSelected = selectedMediaIds.contains(item.id)
                        MediaTile(
                            item = item,
                            isSelected = isSelected,
                            isInSelectionMode = isInSelectionMode,
                            onClick = {
                                if (isInSelectionMode) {
                                    if (isSelected) selectedMediaIds.remove(item.id)
                                    else selectedMediaIds.add(item.id)
                                } else {
                                    onMediaClick(item.id)
                                }
                            },
                            onLongClick = {
                                if (!selectedMediaIds.contains(item.id)) {
                                    selectedMediaIds.add(item.id)
                                }
                            },
                            thumbLoader = { itemId ->
                                val thumbFile = container.vaultFileStore.getThumbFile(vaultId, itemId)
                                val subkey = (container.sessionManager.sessionState.value as? VaultSession.Unlocked)?.thumbSubkey
                                if (subkey != null) {
                                    container.thumbnailGenerator.decryptThumbnail(thumbFile, subkey)
                                } else null
                            }
                        )
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
                        icon = Icons.Default.Restore,
                        contentDescription = "Restore to Gallery",
                        onClick = { showRestoreConfirmDialog = true }
                    )
                    SuyaIconButton(
                        icon = Icons.Outlined.Delete,
                        contentDescription = "Move to Trash",
                        onClick = { showDeleteConfirmDialog = true }
                    )
                }
            }
        }

        // Import loading overlay
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
                        text = importProgressText.ifEmpty { "Encrypting media into vault..." },
                        fontFamily = SoraFontFamily,
                        fontSize = 14.sp,
                        color = SuyaColors.White
                    )
                }
            }
        }
    }

    // Move to Trash confirmation
    if (showDeleteConfirmDialog) {
        SuyaDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
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
                    val ids = selectedMediaIds.toList()
                    selectedMediaIds.clear()
                    showDeleteConfirmDialog = false
                    withContext(Dispatchers.IO) {
                        container.database.mediaItemDao().softDelete(ids, System.currentTimeMillis())
                    }
                }
            }
        )
    }

    // Restore to Gallery confirmation
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
                    val ids = selectedMediaIds.toList()
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
