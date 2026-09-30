package com.suyaphot.app.feature.photos

import android.graphics.Bitmap
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.LaunchedEffect
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
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.model.MediaItem
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.domain.importmedia.ImportResult
import com.suyaphot.app.domain.restore.RestoreResult
import com.suyaphot.app.domain.gallery.GalleryFilter
import com.suyaphot.app.domain.gallery.ViewerCollection
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.database.entity.VaultJobEntity
import com.suyaphot.app.core.model.JobType
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.SourceDisposition
import com.suyaphot.app.domain.importmedia.ImportJobPayload
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey

@Composable
fun PhotosScreen(
    container: AppContainer,
    onMediaClick: (itemId: String, collection: ViewerCollection) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val session = container.sessionManager.sessionState.collectAsState().value
    val vaultId = (session as? VaultSession.Unlocked)?.vaultId ?: ""

    var selectedFilter by remember { mutableStateOf(MediaFilter.ALL) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val selectedMediaIds = remember { mutableStateMapOf<String, Unit>() }
    var allMatchingIds by remember { mutableStateOf<Set<String>?>(null) }
    val isInSelectionMode by remember { derivedStateOf { selectedMediaIds.isNotEmpty() || allMatchingIds != null } }
    val gridCols by container.preferences.gridColumns.collectAsState(initial = 3)
    val sortOrder by container.preferences.sortOrder.collectAsState(initial = "DATE_TAKEN_DESC")
    val trashRetentionDays by container.preferences.trashRetentionDays.collectAsState(initial = 30)
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun selectedIds(): List<String> = allMatchingIds?.toList() ?: selectedMediaIds.keys.toList()
    fun clearSelection() { selectedMediaIds.clear(); allMatchingIds = null }

    var isImporting by remember { mutableStateOf(false) }
    var importProgressText by remember { mutableStateOf("") }

    // Dialog states
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }

    var pendingImportUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingMoveConsentMode by remember { mutableStateOf<SourceDeletionCoordinator.DeleteConsentMode?>(null) }
    var pendingMoveConsentItems by remember { mutableStateOf<List<ImportResult.Success>>(emptyList()) }
    var pendingMoveAlreadyDeleted by remember { mutableStateOf(0) }

    fun moveSummaryText(deleted: Int, retained: Int, failed: Int): String = when {
        retained == 0 && failed == 0 -> "$deleted moved into Suya Phot. Public originals removed."
        failed > 0 -> "$deleted moved. $retained original(s) remain because Android could not delete them."
        else -> "$deleted moved. $retained original(s) kept in Gallery."
    }

    val moveConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        container.sessionManager.endSystemActivity()
        val mode = pendingMoveConsentMode
        val pending = pendingMoveConsentItems
        val alreadyDeleted = pendingMoveAlreadyDeleted
        pendingMoveConsentMode = null
        pendingMoveConsentItems = emptyList()
        pendingMoveAlreadyDeleted = 0
        if (mode == null || pending.isEmpty()) return@rememberLauncherForActivityResult

        scope.launch {
            val summary = container.vaultMoveFinalizer.completeConsent(
                pending = pending,
                mode = mode,
                granted = result.resultCode == android.app.Activity.RESULT_OK
            )
            statusMessage = moveSummaryText(
                deleted = alreadyDeleted + summary.deletedCount,
                retained = summary.retainedCount,
                failed = summary.failedCount
            )
        }
    }

    fun startImport(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            isImporting = true
            scope.launch {
                // "Add to vault" is MOVE semantics: first create + authenticate the encrypted
                // private copy, then remove the public source only after that commit succeeds.
                val results = container.importCoordinator.importBatch(
                    uris = uris,
                    folderId = null,
                    mode = ImportMode.MOVE,
                    onItemComplete = { current, total, _ ->
                        scope.launch { importProgressText = "Securing $current of $total items..." }
                    }
                )
                isImporting = false
                importProgressText = ""

                val successes = results.filterIsInstance<ImportResult.Success>()
                val failedImports = results.count { it is ImportResult.Failure }
                when (val finalized = container.vaultMoveFinalizer.begin(successes)) {
                    is com.suyaphot.app.domain.importmedia.VaultMoveFinalizer.Result.Completed -> {
                        val s = finalized.summary
                        statusMessage = if (failedImports > 0) {
                            moveSummaryText(s.deletedCount, s.retainedCount, s.failedCount) +
                                " $failedImports import(s) failed; their originals were untouched."
                        } else {
                            moveSummaryText(s.deletedCount, s.retainedCount, s.failedCount)
                        }
                    }
                    is com.suyaphot.app.domain.importmedia.VaultMoveFinalizer.Result.RequiresConsent -> {
                        pendingMoveConsentMode = finalized.mode
                        pendingMoveConsentItems = finalized.pending
                        pendingMoveAlreadyDeleted = finalized.alreadyDeletedCount
                        statusMessage = "Encrypted copies are safe. Confirm Android's delete prompt to finish moving."
                        container.sessionManager.beginSystemActivity()
                        moveConsentLauncher.launch(
                            IntentSenderRequest.Builder(finalized.intentSender).build()
                        )
                    }
                }
            }
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        container.sessionManager.endSystemActivity()
        val uris = pendingImportUris
        pendingImportUris = emptyList()
        if (!granted) statusMessage = "Location permission denied; import continues, but GPS/original bytes may be redacted."
        startImport(uris)
    }
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        container.sessionManager.endSystemActivity()
        if (uris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingImportUris = uris
            container.sessionManager.beginSystemActivity()
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else startImport(uris)
    }

    val attentionJobs by remember(vaultId) {
        if (vaultId.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList())
        else container.database.vaultJobDao().observeJobsWithSourceDispositions(
            vaultId = vaultId,
            typeCode = JobType.IMPORT.code,
            dispositionCodes = listOf(
                SourceDisposition.RETAINED_AFTER_INTERRUPTION.code,
                SourceDisposition.DELETE_FAILED.code
            )
        )
    }.collectAsState(initial = emptyList())

    var pendingAttentionConsentMode by remember { mutableStateOf<SourceDeletionCoordinator.DeleteConsentMode?>(null) }
    var pendingAttentionUris by remember { mutableStateOf<List<Pair<VaultJobEntity, Uri>>>(emptyList()) }

    val attentionConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        container.sessionManager.endSystemActivity()
        val mode = pendingAttentionConsentMode
        val current = pendingAttentionUris
        pendingAttentionConsentMode = null
        pendingAttentionUris = emptyList()
        val unlocked = session as? VaultSession.Unlocked ?: return@rememberLauncherForActivityResult
        val vaultId = unlocked.vaultId
        if (result.resultCode == android.app.Activity.RESULT_OK && mode != null) {
            scope.launch(Dispatchers.IO) {
                val verified = container.sourceDeletionCoordinator.completeConsent(current.map { it.second }, mode)
                val now = System.currentTimeMillis()
                for ((job, uri) in current) {
                    if (uri in verified.deletedUris) {
                        container.database.vaultJobDao().updateTerminalImportState(
                            id = job.id,
                            vaultId = vaultId,
                            stateCode = JobState.COMPLETED.code,
                            sourceDispositionCode = SourceDisposition.DELETED.code,
                            errorCode = null,
                            now = now
                        )
                    } else {
                        container.database.vaultJobDao().updateTerminalImportState(
                            id = job.id,
                            vaultId = vaultId,
                            stateCode = JobState.COMPLETED.code,
                            sourceDispositionCode = SourceDisposition.DELETE_FAILED.code,
                            errorCode = "SOURCE_DELETE_FAILED_VAULT_SAFE",
                            now = now
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    statusMessage = if (verified.retainedUris.isNotEmpty()) {
                        "Some originals could not be deleted by Android; encrypted copies are safe."
                    } else {
                        "Originals deleted. Move complete."
                    }
                }
            }
        } else {
            scope.launch(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                for ((job, _) in current) {
                    container.database.vaultJobDao().updateTerminalImportState(
                        id = job.id,
                        vaultId = vaultId,
                        stateCode = JobState.COMPLETED.code,
                        sourceDispositionCode = SourceDisposition.RETAINED_BY_USER.code,
                        errorCode = null,
                        now = now
                    )
                }
                withContext(Dispatchers.Main) {
                    statusMessage = "Kept originals in Gallery."
                }
            }
        }
    }

    val galleryFilter = when (selectedFilter) {
        MediaFilter.ALL -> GalleryFilter.ALL
        MediaFilter.PHOTOS -> GalleryFilter.PHOTOS
        MediaFilter.VIDEOS -> GalleryFilter.VIDEOS
        MediaFilter.FAVORITES -> GalleryFilter.FAVORITES
    }
    val pagingFlow = remember(vaultId, galleryFilter, sortOrder) {
        container.galleryRepository.paged(vaultId, galleryFilter, sortOrder)
    }
    val pagedEntities = pagingFlow.collectAsLazyPagingItems()
    var searchEntities by remember { mutableStateOf<List<MediaItemEntity>>(emptyList()) }
    var searchIndexRevision by remember { mutableStateOf(0L) }

    LaunchedEffect(vaultId, session) {
        val unlocked = session as? VaultSession.Unlocked ?: return@LaunchedEffect
        val dao = container.database.mediaItemDao()
        dao.observeMediaChanges(vaultId).collectLatest {
            delay(250)
            container.vaultSearchIndex.rebuild(vaultId, unlocked.metaSubkey.copyOf(), dao)
            clearSelection()
            searchIndexRevision++
        }
    }
    LaunchedEffect(searchQuery, galleryFilter, sortOrder, searchIndexRevision, vaultId) {
        if (searchQuery.isBlank()) {
            searchEntities = emptyList()
        } else {
            delay(250)
            searchEntities = container.vaultSearchIndex.search(searchQuery, galleryFilter, sortOrder, vaultId, container.database.mediaItemDao())
        }
    }
    val isSearching = searchQuery.isNotBlank()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
            .imePadding()
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
                            onClick = { clearSelection() },
                            size = 38
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "${allMatchingIds?.size ?: selectedMediaIds.size} selected",
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
                                scope.launch {
                                    val ids = if (isSearching) searchEntities.map { it.id }
                                    else container.database.mediaItemDao().getAllVisibleIdsForFilter(vaultId, galleryFilter.ordinal)
                                    selectedMediaIds.clear()
                                    allMatchingIds = ids.toSet()
                                }
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
                            contentDescription = "Move media to Suya Phot",
                            onClick = {
                                container.sessionManager.beginSystemActivity()
                                pickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }
                        )
                    }
                )
            }

            statusMessage?.let { message ->
                Text(
                    text = message,
                    fontFamily = SoraFontFamily,
                    fontSize = 12.sp,
                    color = SuyaColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                )
            }

            // Attention Banner for Interrupted / Failed Source Deletions
            if (attentionJobs.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SuyaColors.Surface,
                    border = BorderStroke(1.dp, SuyaColors.Line),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                        .testTag("move_attention_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        val count = attentionJobs.size
                        Text(
                            text = if (count == 1) "1 move needs attention" else "$count moves need attention",
                            fontFamily = SoraFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = SuyaColors.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "The encrypted copy is safe in Suya Phot, but the original still remains in Gallery.",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.TextMuted,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SuyaButton(
                                text = "Keep Original",
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val unlocked = session as? VaultSession.Unlocked ?: return@launch
                                        val now = System.currentTimeMillis()
                                        for (job in attentionJobs) {
                                            container.database.vaultJobDao().updateTerminalImportState(
                                                id = job.id,
                                                vaultId = unlocked.vaultId,
                                                stateCode = JobState.COMPLETED.code,
                                                sourceDispositionCode = SourceDisposition.RETAINED_BY_USER.code,
                                                errorCode = null,
                                                now = now
                                            )
                                        }
                                        withContext(Dispatchers.Main) {
                                            statusMessage = "Kept originals in Gallery."
                                        }
                                    }
                                },
                                variant = ButtonVariant.Ghost
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SuyaButton(
                                text = "Finish Moving",
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val unlocked = session as? VaultSession.Unlocked ?: return@launch
                                        val jobsWithUris = mutableListOf<Pair<VaultJobEntity, Uri>>()
                                        for (job in attentionJobs) {
                                            try {
                                                val decrypted = Aead.decryptWithPrependedNonce(
                                                    keyBytes = unlocked.metaSubkey,
                                                    payload = job.encryptedPayload,
                                                    aad = "job:${job.id}:v1".toByteArray(Charsets.UTF_8)
                                                )
                                                val payload = try {
                                                    ImportJobPayload.deserialize(decrypted)
                                                } finally {
                                                    decrypted.fill(0)
                                                }
                                                jobsWithUris.add(Pair(job, Uri.parse(payload.sourceUri)))
                                            } catch (_: Exception) {
                                                container.database.vaultJobDao().updateTerminalImportState(
                                                    id = job.id,
                                                    vaultId = unlocked.vaultId,
                                                    stateCode = JobState.COMPLETED.code,
                                                    sourceDispositionCode = SourceDisposition.DELETE_FAILED.code,
                                                    errorCode = "SOURCE_DELETE_FAILED_VAULT_SAFE",
                                                    now = System.currentTimeMillis()
                                                )
                                            }
                                        }
                                        if (jobsWithUris.isEmpty()) return@launch

                                        when (val outcome = container.sourceDeletionCoordinator.deleteSources(jobsWithUris.map { it.second })) {
                                            is SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                                                val now = System.currentTimeMillis()
                                                for ((job, uri) in jobsWithUris) {
                                                    if (uri in outcome.deletedUris) {
                                                        container.database.vaultJobDao().updateTerminalImportState(
                                                            id = job.id,
                                                            vaultId = unlocked.vaultId,
                                                            stateCode = JobState.COMPLETED.code,
                                                            sourceDispositionCode = SourceDisposition.DELETED.code,
                                                            errorCode = null,
                                                            now = now
                                                        )
                                                    }
                                                }
                                                withContext(Dispatchers.Main) {
                                                    statusMessage = "Originals deleted. Move complete."
                                                }
                                            }
                                            is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                                                val now = System.currentTimeMillis()
                                                for ((job, uri) in jobsWithUris) {
                                                    if (uri in outcome.deletedUris) {
                                                        container.database.vaultJobDao().updateTerminalImportState(
                                                            id = job.id,
                                                            vaultId = unlocked.vaultId,
                                                            stateCode = JobState.COMPLETED.code,
                                                            sourceDispositionCode = SourceDisposition.DELETED.code,
                                                            errorCode = null,
                                                            now = now
                                                        )
                                                    }
                                                }
                                                val remaining = jobsWithUris.filter { it.second in outcome.uris }
                                                pendingAttentionUris = remaining
                                                pendingAttentionConsentMode = outcome.mode
                                                container.sessionManager.beginSystemActivity()
                                                attentionConsentLauncher.launch(
                                                    IntentSenderRequest.Builder(outcome.intentSender).build()
                                                )
                                            }
                                            is SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                                                val now = System.currentTimeMillis()
                                                for ((job, uri) in jobsWithUris) {
                                                    if (uri in outcome.deletedUris) {
                                                        container.database.vaultJobDao().updateTerminalImportState(
                                                            id = job.id,
                                                            vaultId = unlocked.vaultId,
                                                            stateCode = JobState.COMPLETED.code,
                                                            sourceDispositionCode = SourceDisposition.DELETED.code,
                                                            errorCode = null,
                                                            now = now
                                                        )
                                                    } else {
                                                        container.database.vaultJobDao().updateTerminalImportState(
                                                            id = job.id,
                                                            vaultId = unlocked.vaultId,
                                                            stateCode = JobState.COMPLETED.code,
                                                            sourceDispositionCode = SourceDisposition.DELETE_FAILED.code,
                                                            errorCode = "SOURCE_DELETE_FAILED_VAULT_SAFE",
                                                            now = now
                                                        )
                                                    }
                                                }
                                                withContext(Dispatchers.Main) {
                                                    statusMessage = "Android no longer allows automatic deletion. Vault copy remains safe."
                                                }
                                            }
                                        }
                                    }
                                },
                                variant = ButtonVariant.Primary
                            )
                        }
                    }
                }
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
                        placeholder = "Search filenames, media..."
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
            if (((isSearching && searchEntities.isEmpty()) || (!isSearching && pagedEntities.itemCount == 0)) && !isImporting) {
                EmptyState(
                    icon = Icons.Default.PhotoLibrary,
                    title = if (searchQuery.isNotBlank()) "No search results" else "No media in vault",
                    subtitle = if (searchQuery.isNotBlank()) "Try a different search term." else "Tap '+' to move photos or videos into your encrypted vault.",
                    actionText = if (searchQuery.isBlank()) "Move Photos & Videos" else null,
                    onActionClick = {
                        container.sessionManager.beginSystemActivity()
                        pickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridCols.coerceIn(2, 5)),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).testTag("photos_grid")
                ) {
                    if (isSearching) {
                        items(searchEntities, key = { it.id }) { entity ->
                            val item = entity.toMediaItem()
                            val isSelected = allMatchingIds?.contains(item.id) ?: selectedMediaIds.containsKey(item.id)
                            MediaTile(
                                item = item,
                                isSelected = isSelected,
                                isInSelectionMode = isInSelectionMode,
                                onClick = {
                                    if (isInSelectionMode) {
                                        if (allMatchingIds != null) allMatchingIds = if (isSelected) allMatchingIds!! - item.id else allMatchingIds!! + item.id
                                        else if (isSelected) selectedMediaIds.remove(item.id) else selectedMediaIds[item.id] = Unit
                                    } else onMediaClick(item.id, ViewerCollection.Gallery(galleryFilter, sortOrder, searchEntities.map { it.id }))
                                },
                                onLongClick = { if (allMatchingIds == null) selectedMediaIds[item.id] = Unit },
                                thumbLoader = { itemId ->
                                    container.encryptedThumbnailRepository.load(vaultId, itemId, item.updatedAt)
                                }
                            )
                        }
                    } else items(
                        count = pagedEntities.itemCount,
                        key = pagedEntities.itemKey { it.id }
                    ) { index ->
                        val entity = pagedEntities[index] ?: return@items
                        val item = entity.toMediaItem()
                        val isSelected = allMatchingIds?.contains(item.id) ?: selectedMediaIds.containsKey(item.id)
                        MediaTile(
                            item = item,
                            isSelected = isSelected,
                            isInSelectionMode = isInSelectionMode,
                            onClick = {
                                if (isInSelectionMode) {
                                    if (allMatchingIds != null) allMatchingIds = if (isSelected) allMatchingIds!! - item.id else allMatchingIds!! + item.id
                                    else if (isSelected) selectedMediaIds.remove(item.id)
                                    else selectedMediaIds[item.id] = Unit
                                } else {
                                    onMediaClick(item.id, ViewerCollection.Gallery(galleryFilter, sortOrder))
                                }
                            },
                            onLongClick = {
                                if (allMatchingIds == null && !selectedMediaIds.containsKey(item.id)) {
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
                    text = if (trashRetentionDays == 0) "Items remain in Trash until you delete them permanently."
                    else "Items in Trash are retained for $trashRetentionDays days before permanent deletion.",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.TextMuted
                )
            },
            confirmText = "Move to Trash",
            onConfirm = {
                scope.launch {
                    val ids = selectedIds()
                    clearSelection()
                    showDeleteConfirmDialog = false
                    val moved = withContext(Dispatchers.IO) {
                        container.database.mediaItemDao().softDeleteForVault(vaultId, ids, System.currentTimeMillis())
                    }
                    statusMessage = "$moved moved to Trash${if (moved != ids.size) ", ${ids.size - moved} not moved" else ""}"
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
                    val ids = selectedIds()
                    showRestoreConfirmDialog = false
                    val results = withContext(Dispatchers.IO) {
                        ids.map { id -> id to container.restoreCoordinator.restoreItem(id, move = true) }
                    }
                    val failed = results.filter { it.second is RestoreResult.Failure }.map { it.first }
                    val pending = results.count { it.second is RestoreResult.SuccessWithCleanupPending }
                    val completed = results.count { it.second is RestoreResult.Success }
                    clearSelection()
                    failed.forEach { selectedMediaIds[it] = Unit }
                    statusMessage = "$completed moved to Gallery, $pending cleanup pending, ${failed.size} failed"
                }
            }
        )
    }
}

private fun MediaItemEntity.toMediaItem() = MediaItem(
    id = id,
    vaultId = vaultId,
    folderId = folderId,
    type = MediaType.fromCode(mediaTypeCode),
    plaintextSize = plaintextSize,
    cipherSize = cipherSize,
    sha256Hex = sha256Hex,
    importedAt = importedAt,
    updatedAt = updatedAt,
    favorite = favorite,
    deletedAt = deletedAt,
    previousFolderId = previousFolderId
)
