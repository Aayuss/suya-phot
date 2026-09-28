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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.model.MediaItem
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.EmptyState
import com.suyaphot.app.ui.components.MediaTile
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.SuyaIconButton
import com.suyaphot.app.ui.components.SuyaTopBar
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TrashScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val session = container.sessionManager.sessionState.collectAsState().value
    val vaultId = (session as? VaultSession.Unlocked)?.vaultId ?: ""

    val trashFlow = remember(vaultId) {
        container.database.mediaItemDao().getTrashItems(vaultId)
    }
    val rawEntities by trashFlow.collectAsState(initial = emptyList())

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

    val selectedIds = remember { mutableStateListOf<String>() }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SuyaTopBar(
                title = "Vault Trash",
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

            if (items.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Delete,
                    title = "Trash is empty",
                    subtitle = "Items moved to Trash are automatically purged after 30 days.",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(2.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(items, key = { it.id }) { item ->
                        val isSelected = selectedIds.contains(item.id)
                        MediaTile(
                            item = item,
                            isSelected = isSelected,
                            isInSelectionMode = selectedIds.isNotEmpty(),
                            onClick = {
                                if (isSelected) selectedIds.remove(item.id)
                                else selectedIds.add(item.id)
                            },
                            onLongClick = {
                                if (!selectedIds.contains(item.id)) selectedIds.add(item.id)
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
                                    val toRestore = selectedIds.toList()
                                    selectedIds.clear()
                                    container.database.mediaItemDao().restoreFromTrash(toRestore, System.currentTimeMillis())
                                }
                            },
                            variant = ButtonVariant.Secondary
                        )
                        SuyaButton(
                            text = "Delete Forever",
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val toDelete = selectedIds.toList()
                                    selectedIds.clear()
                                    for (id in toDelete) {
                                        container.vaultFileStore.getMediaFile(vaultId, id).delete()
                                        container.vaultFileStore.getThumbFile(vaultId, id).delete()
                                    }
                                    container.database.mediaItemDao().deleteBatch(toDelete)
                                }
                            },
                            variant = ButtonVariant.Primary
                        )
                    }
                }
            }
        }
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
                scope.launch(Dispatchers.IO) {
                    showEmptyTrashDialog = false
                    for (item in items) {
                        container.vaultFileStore.getMediaFile(vaultId, item.id).delete()
                        container.vaultFileStore.getThumbFile(vaultId, item.id).delete()
                    }
                    container.database.mediaItemDao().deleteBatch(items.map { it.id })
                }
            }
        )
    }
}
