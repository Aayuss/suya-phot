package com.suyaphot.app.feature.folders

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.ui.components.EmptyState
import com.suyaphot.app.ui.components.FolderTile
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.SuyaIconButton
import com.suyaphot.app.ui.components.SuyaTextField
import com.suyaphot.app.ui.components.SuyaTopBar
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.launch

@Composable
fun FoldersScreen(
    container: AppContainer,
    onFolderOpened: (folderId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var currentParentId by remember { mutableStateOf<String?>(null) }
    var breadcrumbs by remember { mutableStateOf<List<Pair<String?, String>>>(emptyList()) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val foldersFlow = remember(currentParentId) {
        container.folderManager.getSubFoldersFlow(currentParentId)
    }
    val folders by foldersFlow.collectAsState(initial = emptyList())

    LaunchedEffect(currentParentId) {
        breadcrumbs = container.folderManager.getBreadcrumbs(currentParentId)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SuyaTopBar(
                title = "Folders",
                actions = {
                    SuyaIconButton(
                        icon = Icons.Default.CreateNewFolder,
                        contentDescription = "New Folder",
                        onClick = { showCreateDialog = true }
                    )
                }
            )

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

            if (folders.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Folder,
                    title = "No folders created",
                    subtitle = "Organize your private media into arbitrarily nested folders.",
                    actionText = "Create First Folder",
                    onActionClick = { showCreateDialog = true },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(folders, key = { it.id }) { folder ->
                        FolderTile(
                            folder = folder,
                            onClick = {
                                currentParentId = folder.id
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        SuyaDialog(
            onDismissRequest = {
                showCreateDialog = false
                newFolderName = ""
            },
            title = "New Folder",
            content = {
                SuyaTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = "Folder name",
                    label = "Name"
                )
            },
            confirmText = "Create",
            onConfirm = {
                if (newFolderName.isNotBlank()) {
                    scope.launch {
                        container.folderManager.createFolder(
                            name = newFolderName.trim(),
                            parentId = currentParentId
                        )
                        newFolderName = ""
                        showCreateDialog = false
                    }
                }
            }
        )
    }
}
