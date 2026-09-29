package com.suyaphot.app.feature.viewer

import android.content.Intent
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.media.applyExifOrientation
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.restore.RestoreResult
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.SuyaIconButton
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    itemId: String,
    container: AppContainer,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionState by container.sessionManager.sessionState.collectAsState()
    val session = sessionState as? VaultSession.Unlocked

    var mediaEntity by remember { mutableStateOf<MediaItemEntity?>(null) }
    var metadata by remember { mutableStateOf<PrivateMediaMetadata?>(null) }
    var fullBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var isChromeVisible by remember { mutableStateOf(true) }
    var showDetailsSheet by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    val trashRetentionDays by container.preferences.trashRetentionDays.collectAsState(initial = 30)
    var shareJob by remember { mutableStateOf<Job?>(null) }
    var shareProgress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }

    // Video temporary playback file
    var tempPlaybackFile by remember { mutableStateOf<File?>(null) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    // Zoom & pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Load media data and decrypt on demand
    LaunchedEffect(itemId, session?.vaultId) {
        isLoading = true
        loadError = null
        val load = runCatching { withContext(Dispatchers.IO) {
            val lease = container.sessionManager.acquireOperationKeyLease() ?: return@withContext ViewerLoad()
            try {
            val entity = container.database.mediaItemDao().getItemForVault(itemId, lease.vaultId)
                ?: return@withContext ViewerLoad()
            if (entity.deletedAt != null || (entity.concealed &&
                    (entity.folderId == null || !container.folderAccessManager.canOpen(lease.vaultId, entity.folderId)))) {
                return@withContext ViewerLoad()
            }
            val decodedMetadata = runCatching {
                val bytes = Aead.decryptWithPrependedNonce(
                    lease.metaSubkey,
                    entity.encryptedMetadata,
                    itemId.toByteArray(Charsets.UTF_8)
                )
                try {
                    PrivateMediaMetadata.deserialize(bytes)
                } finally {
                    bytes.fill(0)
                }
            }.getOrNull()

            val vaultFile = container.vaultFileStore.getMediaFile(lease.vaultId, itemId)
            if (entity.mediaTypeCode == MediaType.IMAGE.code) {
                val extension = decodedMetadata?.originalFileExtension ?: "img"
                val verified = container.vaultFileStore.createViewerTempFile(itemId, extension)
                try {
                    check((verified.parentFile?.usableSpace ?: 0L) >= entity.plaintextSize + 16L * 1024 * 1024)
                    val coroutine = currentCoroutineContext()
                    val verification = container.vaultCrypto.decryptVerifiedToFile(
                        vaultFile, lease.mediaSubkey, itemId, verified
                    ) { current, total ->
                        coroutine.ensureActive()
                        scope.launch { loadProgress = current to total }
                    }
                    check(verification.plaintextSize == entity.plaintextSize)
                    check(verification.sha256.toHex().equals(entity.sha256Hex, ignoreCase = true))
                    coroutine.ensureActive()
                    check(container.sessionManager.currentVaultId == lease.vaultId)
                    check(!entity.concealed || (entity.folderId != null &&
                        container.folderAccessManager.canOpen(lease.vaultId, entity.folderId)))
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(verified.absolutePath, bounds)
                    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                    val sample = if (maxDim > 2560) (maxDim / 2560).coerceAtLeast(1) else 1
                    val decoded = BitmapFactory.decodeFile(
                        verified.absolutePath,
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                    )
                    val bitmap = decoded?.let {
                        applyExifOrientation(it, decodedMetadata?.orientation ?: 1)
                    }
                    ViewerLoad(entity, decodedMetadata, bitmap = bitmap)
                } finally {
                    verified.delete()
                }
            } else {
                val temp = container.vaultFileStore.createPlaybackTempFile(
                    decodedMetadata?.originalFileExtension ?: "mp4"
                )
                try {
                    check((temp.parentFile?.usableSpace ?: 0L) >= entity.plaintextSize + 16L * 1024 * 1024)
                    val coroutine = currentCoroutineContext()
                    val verification = container.vaultCrypto.decryptVerifiedToFile(
                        vaultFile, lease.mediaSubkey, itemId, temp
                    ) { current, total ->
                        coroutine.ensureActive()
                        scope.launch { loadProgress = current to total }
                    }
                    check(verification.plaintextSize == entity.plaintextSize)
                    check(verification.sha256.toHex().equals(entity.sha256Hex, ignoreCase = true))
                    coroutine.ensureActive()
                    check(container.sessionManager.currentVaultId == lease.vaultId)
                    check(!entity.concealed || (entity.folderId != null &&
                        container.folderAccessManager.canOpen(lease.vaultId, entity.folderId)))
                    ViewerLoad(entity, decodedMetadata, playbackFile = temp)
                } catch (t: Throwable) {
                    temp.delete()
                    throw t
                }
            }
            } finally { lease.close() }
        } }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            loadError = "Could not open a verified preview. The vault copy remains unchanged."
            ViewerLoad()
        }
        mediaEntity = load.entity
        metadata = load.metadata
        fullBitmap = load.bitmap
        tempPlaybackFile = load.playbackFile
        loadProgress = null
        isLoading = false
    }

    // Release temporary playback file and ExoPlayer upon exit or lock
    DisposableEffect(Unit) {
        onDispose {
            shareJob?.cancel()
            exoPlayer?.release()
            exoPlayer = null
            tempPlaybackFile?.delete()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isLoading) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = SuyaColors.Accent)
                val progress = loadProgress
                if (progress != null && progress.second > 0) {
                    Text("Verifying ${(progress.first * 100 / progress.second).coerceIn(0, 100)}%", color = SuyaColors.White)
                }
                Text("Cancel", color = SuyaColors.Accent, modifier = Modifier.clickable(onClick = onBack))
            }
        } else {
            loadError?.let { Text(it, color = SuyaColors.Negative, modifier = Modifier.align(Alignment.Center)) }
            val entity = mediaEntity
            if (entity != null) {
                if (entity.mediaTypeCode == MediaType.IMAGE.code && fullBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    val maxOffsetX = (size.width * (scale - 1)) / 2
                                    val maxOffsetY = (size.height * (scale - 1)) / 2
                                    offset = Offset(
                                        x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                        y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                    )
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { isChromeVisible = !isChromeVisible },
                                    onDoubleTap = {
                                        if (scale > 1f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2.5f
                                        }
                                    }
                                )
                            }
                    ) {
                        Image(
                            bitmap = fullBitmap!!.asImageBitmap(),
                            contentDescription = metadata?.originalDisplayName ?: "Photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                        )
                    }
                } else if (entity.mediaTypeCode == MediaType.VIDEO.code && tempPlaybackFile != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { isChromeVisible = !isChromeVisible }
                            )
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    val player = ExoPlayer.Builder(ctx).build().also {
                                        exoPlayer = it
                                        val mediaItem = ExoMediaItem.fromUri(android.net.Uri.fromFile(tempPlaybackFile))
                                        it.setMediaItem(mediaItem)
                                        it.prepare()
                                        it.playWhenReady = true
                                    }
                                    this.player = player
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // Top Chrome Bar
        AnimatedVisibility(
            visible = isChromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuyaIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack
                    )

                    Text(
                        text = metadata?.originalDisplayName ?: "Media",
                        fontFamily = SoraFontFamily,
                        fontSize = 15.sp,
                        color = SuyaColors.White,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuyaIconButton(
                            icon = if (mediaEntity?.favorite == true) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Favorite",
                            active = mediaEntity?.favorite == true,
                            onClick = {
                                val current = mediaEntity ?: return@SuyaIconButton
                                val newFav = !current.favorite
                                scope.launch {
                                    val activeVaultId = session?.vaultId ?: return@launch
                                    val updated = withContext(Dispatchers.IO) {
                                        container.database.mediaItemDao().updateFavoriteForVault(
                                            activeVaultId,
                                            current.id,
                                            newFav,
                                            System.currentTimeMillis()
                                        )
                                    }
                                    if (updated == 1) {
                                        mediaEntity = current.copy(favorite = newFav)
                                    }
                                }
                            }
                        )
                        SuyaIconButton(
                            icon = Icons.Default.Info,
                            contentDescription = "Details",
                            onClick = { showDetailsSheet = true }
                        )
                    }
                }
            }
        }

        // Bottom Chrome Bar
        AnimatedVisibility(
            visible = isChromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuyaIconButton(
                        icon = Icons.Default.Restore,
                        contentDescription = "Move to Gallery",
                        onClick = { showRestoreDialog = true }
                    )
                    SuyaIconButton(
                        icon = Icons.Default.Share,
                        contentDescription = "Share securely",
                        onClick = {
                            if (shareJob != null) return@SuyaIconButton
                            shareError = null
                            shareProgress = 0L to (mediaEntity?.plaintextSize ?: 0L)
                            shareJob = scope.launch {
                                try {
                                    val prepared = container.shareCoordinator.prepare(itemId) { current, total ->
                                        scope.launch { shareProgress = current to total }
                                    }
                                    if (prepared == null) {
                                        shareError = "Could not prepare a verified share copy"
                                    } else {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = prepared.mimeType
                                            putExtra(Intent.EXTRA_STREAM, prepared.uri)
                                            clipData = ClipData.newUri(context.contentResolver, "Suya Phot media", prepared.uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(send, "Share media"))
                                    }
                                } catch (_: Exception) {
                                    shareError = "Sharing was cancelled or unavailable"
                                } finally {
                                    shareJob = null
                                    shareProgress = null
                                }
                            }
                        }
                    )
                    SuyaIconButton(
                        icon = Icons.Outlined.Delete,
                        contentDescription = "Trash",
                        onClick = { showTrashDialog = true }
                    )
                }
            }
        }
        if (shareJob != null) {
            Surface(
                color = SuyaColors.Surface.copy(alpha = 0.95f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                    CircularProgressIndicator(color = SuyaColors.Accent)
                    val progress = shareProgress
                    Text(
                        if (progress != null && progress.second > 0) "Preparing share ${(progress.first * 100 / progress.second).coerceIn(0, 100)}%"
                        else "Preparing verified share copy...",
                        color = SuyaColors.White,
                        fontSize = 13.sp
                    )
                    Text("Cancel", color = SuyaColors.Accent, modifier = Modifier.clickable { shareJob?.cancel() })
                }
            }
        }
        shareError?.let { error ->
            Text(error, color = SuyaColors.Negative, modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp))
        }
        restoreError?.let { error ->
            Text(error, color = SuyaColors.Negative, modifier = Modifier.align(Alignment.TopCenter).padding(top = 120.dp))
        }
    }

    // Details Bottom Sheet
    if (showDetailsSheet && metadata != null) {
        val meta = metadata!!
        val clipboard = LocalClipboard.current
        val dateFormat = SimpleDateFormat("MMM dd, yyyy  h:mm a", Locale.getDefault())

        ModalBottomSheet(
            onDismissRequest = { showDetailsSheet = false },
            containerColor = SuyaColors.Surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Details",
                    fontFamily = SoraFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    color = SuyaColors.White
                )

                DetailRow("Filename", meta.originalDisplayName)
                DetailRow("Type", meta.originalMimeType)
                meta.dateTakenMs?.let {
                    DetailRow("Date Taken", dateFormat.format(Date(it)))
                }
                meta.originalRelativePath?.let {
                    DetailRow("Original Path", it)
                }
                if (meta.width != null && meta.height != null) {
                    DetailRow("Dimensions", "${meta.width} × ${meta.height}")
                }
                mediaEntity?.plaintextSize?.let {
                    DetailRow("Size", formatFileSize(it))
                }
                mediaEntity?.sha256Hex?.let { hash ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SHA-256", fontFamily = SoraFontFamily, fontSize = 12.sp, color = SuyaColors.TextMuted)
                            Text(hash.take(16) + "...", fontFamily = SoraFontFamily, fontSize = 13.sp, color = SuyaColors.White)
                        }
                        Text(
                            text = "Copy",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.Accent,
                            modifier = Modifier.clickable {
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("SHA-256", hash))) }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Restore dialog
    if (showRestoreDialog) {
        SuyaDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = "Move to Original Gallery?",
            content = {
                Text(
                    text = if (mediaEntity?.concealed == true)
                        "This protected item will be decrypted and moved to the public Gallery, where other gallery apps can see it."
                    else "This item will be decrypted, verified, and placed back in your public Gallery.",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.TextMuted
                )
            },
            confirmText = "Move to Gallery",
            onConfirm = {
                scope.launch {
                    showRestoreDialog = false
                    when (container.restoreCoordinator.restoreItem(itemId, move = true)) {
                        is RestoreResult.Success,
                        is RestoreResult.SuccessWithCleanupPending -> onBack()
                        is RestoreResult.Failure -> restoreError = "Could not move this item. The private copy remains in Suya Phot."
                    }
                }
            }
        )
    }

    // Trash dialog
    if (showTrashDialog) {
        SuyaDialog(
            onDismissRequest = { showTrashDialog = false },
            title = "Move to Vault Trash?",
            content = {
                Text(
                    text = if (trashRetentionDays == 0) "This item will stay in Trash until you delete it permanently."
                    else "This item will stay in Trash for $trashRetentionDays days before permanent deletion.",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.TextMuted
                )
            },
            confirmText = "Move to Trash",
            onConfirm = {
                showTrashDialog = false
                scope.launch {
                    val activeVaultId = session?.vaultId ?: return@launch
                    withContext(Dispatchers.IO) {
                        container.database.mediaItemDao().softDeleteForVault(
                            activeVaultId,
                            listOf(itemId),
                            System.currentTimeMillis()
                        )
                    }
                    onBack()
                }
            }
        )
    }
}

private data class ViewerLoad(
    val entity: MediaItemEntity? = null,
    val metadata: PrivateMediaMetadata? = null,
    val bitmap: Bitmap? = null,
    val playbackFile: File? = null
)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontFamily = SoraFontFamily,
            fontSize = 11.sp,
            color = SuyaColors.TextMuted
        )
        Text(
            text = value,
            fontFamily = SoraFontFamily,
            fontSize = 14.sp,
            color = SuyaColors.White
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
