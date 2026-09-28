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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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

    val retentionDays by container.preferences.trashRetentionDays.collectAsState(initial = 30)

    // Auto-purge uses the configured retention and drains all expired batches safely.
    LaunchedEffect(vaultId) {
        if (vaultId.isNotEmpty()) {
            container.trashCoordinator.purgeExpired(vaultId)
        }
    }

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

    val selectedIds = remember { mutableStateMapOf<String, Unit>() }
    val gridCols by container.preferences.gridColumns.collectAsState(initial = 3)
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

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
                                val thumbFile = container.vaultFileStore.getThumbFile(vaultId, itemId)
                                val subkey = (container.sessionManager.sessionState.value as? VaultSession.Unlocked)?.thumbSubkey
                                if (subkey != null) {
                                    container.thumbnailGenerator.decryptThumbnail(thumbFile, subkey, itemId)
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
