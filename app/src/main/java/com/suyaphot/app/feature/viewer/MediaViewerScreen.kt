package com.suyaphot.app.feature.viewer

import android.content.Intent
import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import com.suyaphot.app.domain.gallery.ViewerCollection
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

private class ViewerInsufficientSpace : IllegalStateException()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    itemId: String,
    collection: ViewerCollection,
    container: AppContainer,
    onBack: () -> Unit
) {
    val sessionState by container.sessionManager.sessionState.collectAsState()
    val vaultId = (sessionState as? VaultSession.Unlocked)?.vaultId
    var ids by remember(itemId, collection) { mutableStateOf(listOf(itemId)) }
    val pager = rememberPagerState { ids.size }
    var zoomed by remember { mutableStateOf(false) }
    var isFetchingNext by remember { mutableStateOf(false) }
    var isFetchingPrev by remember { mutableStateOf(false) }
    var reachedStart by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }

    LaunchedEffect(vaultId, itemId, collection) {
        val id = vaultId ?: return@LaunchedEffect
        val window = withContext(Dispatchers.IO) {
            container.galleryRepository.viewerWindow(id, collection, itemId, windowSize = 80)
        }
        ids = if (window.ids.isNotEmpty()) window.ids else listOf(itemId)
        pager.scrollToPage(window.currentIndex.coerceIn(0, (ids.size - 1).coerceAtLeast(0)))
        reachedStart = window.absoluteStart == 0
        reachedEnd = (window.absoluteStart + window.ids.size) >= window.totalCount
    }

    LaunchedEffect(pager.currentPage, ids.size) {
        val id = vaultId ?: return@LaunchedEffect
        val currentIdx = pager.currentPage

        // Near end of window: fetch next batch
        if (currentIdx >= ids.size - 10 && !isFetchingNext && !reachedEnd && ids.isNotEmpty()) {
            isFetchingNext = true
            val lastId = ids.last()
            val nextBatch = withContext(Dispatchers.IO) {
                container.galleryRepository.fetchNextViewerBatch(id, collection, lastId, limit = 40)
            }
            if (nextBatch.isNotEmpty()) {
                val newUnique = nextBatch.filter { it !in ids }
                if (newUnique.isNotEmpty()) {
                    ids = ids + newUnique
                } else {
                    reachedEnd = true
                }
            } else {
                reachedEnd = true
            }
            isFetchingNext = false
        }

        // Near start of window: fetch previous batch
        if (currentIdx <= 10 && !isFetchingPrev && !reachedStart && ids.isNotEmpty()) {
            isFetchingPrev = true
            val firstId = ids.first()
            val prevBatch = withContext(Dispatchers.IO) {
                container.galleryRepository.fetchPreviousViewerBatch(id, collection, firstId, limit = 40)
            }
            if (prevBatch.isNotEmpty()) {
                val newUnique = prevBatch.filter { it !in ids }
                if (newUnique.isNotEmpty()) {
                    val currentItemId = ids.getOrNull(currentIdx)
                    ids = newUnique + ids
                    val newIndex = if (currentItemId != null) ids.indexOf(currentItemId) else currentIdx + newUnique.size
                    pager.scrollToPage(newIndex.coerceIn(0, (ids.size - 1).coerceAtLeast(0)))
                } else {
                    reachedStart = true
                }
            } else {
                reachedStart = true
            }
            isFetchingPrev = false
        }
    }

    LaunchedEffect(pager.settledPage) { zoomed = false }

    HorizontalPager(
        state = pager,
        key = { ids[it] },
        userScrollEnabled = !zoomed,
        modifier = Modifier.fillMaxSize()
    ) { index ->
        val pageId = ids[index]
        if (index == pager.settledPage) {
            MediaViewerPage(pageId, container, onBack, onZoomChanged = { zoomed = it })
        } else {
            ViewerPreviewPage(pageId, container)
        }
    }
}

@Composable
private fun ViewerPreviewPage(
    itemId: String,
    container: AppContainer
) {
    val sessionState by container.sessionManager.sessionState.collectAsState()
    val vaultId = (sessionState as? VaultSession.Unlocked)?.vaultId
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isVideo by remember { mutableStateOf(false) }
    var isAllowed by remember { mutableStateOf(true) }

    LaunchedEffect(itemId, vaultId) {
        val id = vaultId ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val entity = container.database.mediaItemDao().getItemForVault(itemId, id)
            if (entity == null || entity.deletedAt != null ||
                (entity.concealed && (entity.folderId == null || !container.folderAccessManager.canOpen(id, entity.folderId)))) {
                withContext(Dispatchers.Main) {
                    isAllowed = false
                    previewBitmap = null
                }
                return@withContext
            }

            isVideo = entity.mediaTypeCode == MediaType.VIDEO.code
            val lease = container.sessionManager.acquireOperationKeyLease() ?: return@withContext
            try {
                val previewFile = container.vaultFileStore.getPreviewFile(id, itemId)
                val bmp = if (previewFile.exists()) {
                    container.thumbnailGenerator.decryptImagePreview(previewFile, lease.thumbSubkey, itemId)
                } else null

                val finalBmp = bmp ?: container.thumbnailGenerator.decryptThumbnail(
                    container.vaultFileStore.getThumbFile(id, itemId),
                    lease.thumbSubkey,
                    itemId
                )
                withContext(Dispatchers.Main) {
                    previewBitmap = finalBmp
                    isAllowed = true
                }
            } finally {
                lease.close()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (!isAllowed) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Protected item",
                tint = SuyaColors.TextMuted,
                modifier = Modifier.size(48.dp)
            )
        } else if (previewBitmap != null) {
            Image(
                bitmap = previewBitmap!!.asImageBitmap(),
                contentDescription = "Preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            if (isVideo) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Video",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaViewerPage(
    itemId: String,
    container: AppContainer,
    onBack: () -> Unit,
    onZoomChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionState by container.sessionManager.sessionState.collectAsState()
    val session = sessionState as? VaultSession.Unlocked

    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        container.sessionManager.endSystemActivity()
    }

    var mediaEntity by remember { mutableStateOf<MediaItemEntity?>(null) }
    var metadata by remember { mutableStateOf<PrivateMediaMetadata?>(null) }
    var fullBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadingVideo by remember { mutableStateOf(false) }

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
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var deepZoomFile by remember { mutableStateOf<File?>(null) }
    var deepZoomTile by remember { mutableStateOf<RegionTile?>(null) }
    var deepZoomError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scale) { onZoomChanged(scale > 1f) }

    // Original plaintext exists only while deep zoom is active. It is authenticated before any region decode.
    LaunchedEffect(itemId, session?.vaultId, scale > 2f) {
        if (scale <= 2f || session == null || mediaEntity?.mediaTypeCode != MediaType.IMAGE.code) {
            deepZoomTile = null
            deepZoomFile?.delete()
            deepZoomFile = null
            return@LaunchedEffect
        }
        if (deepZoomFile != null) return@LaunchedEffect
        deepZoomError = null
        val file = runCatching { withContext(Dispatchers.IO) {
            val lease = container.sessionManager.acquireOperationKeyLease() ?: return@withContext null
            var temp: File? = null
            try {
                val entity = container.database.mediaItemDao().getItemForVault(itemId, lease.vaultId) ?: return@withContext null
                if (entity.deletedAt != null || (entity.concealed &&
                    (entity.folderId == null || !container.folderAccessManager.canOpen(lease.vaultId, entity.folderId)))) return@withContext null
                val candidate = container.vaultFileStore.createViewerTempFile(itemId, metadata?.originalFileExtension ?: "img")
                temp = candidate
                val reserve = maxOf(64L * 1024 * 1024, entity.plaintextSize / 10)
                if ((candidate.parentFile?.usableSpace ?: 0L) < entity.plaintextSize + reserve) throw ViewerInsufficientSpace()
                val verified = container.vaultCrypto.decryptVerifiedToFile(
                    container.vaultFileStore.getMediaFile(lease.vaultId, itemId), lease.mediaSubkey, itemId, candidate
                )
                check(verified.plaintextSize == entity.plaintextSize)
                check(verified.sha256.toHex().equals(entity.sha256Hex, true))
                currentCoroutineContext().ensureActive()
                check(container.sessionManager.currentVaultId == lease.vaultId)
                check(!entity.concealed || (entity.folderId != null &&
                    container.folderAccessManager.canOpen(lease.vaultId, entity.folderId)))
                temp = null
                candidate
            } finally {
                temp?.delete()
                lease.close()
            }
        } }.getOrElse {
            if (it is CancellationException) throw it
            deepZoomError = if (it is ViewerInsufficientSpace) "Not enough private storage for deep zoom."
                else "Original detail could not be verified."
            null
        }
        deepZoomFile = file
    }
    LaunchedEffect(deepZoomFile, scale, offset, viewport, metadata?.orientation) {
        val file = deepZoomFile ?: return@LaunchedEffect
        if (scale <= 2f || viewport == IntSize.Zero) return@LaunchedEffect
        delay(80)
        val tile = withContext(Dispatchers.IO) {
            decodeRegionTile(file, viewport, scale, offset, metadata?.orientation ?: 1)
        }
        deepZoomTile = tile
    }
    DisposableEffect(deepZoomTile) {
        val tile = deepZoomTile
        onDispose { tile?.bitmap?.recycle() }
    }

    // Load media data and decrypt on demand
    LaunchedEffect(itemId, session?.vaultId) {
        isLoading = true
        loadingVideo = false
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
            scope.launch { loadingVideo = entity.mediaTypeCode == MediaType.VIDEO.code }
            if (entity.mediaTypeCode == MediaType.IMAGE.code) {
                val previewFile = container.vaultFileStore.getPreviewFile(lease.vaultId, itemId)
                val existingPreview = container.thumbnailGenerator.decryptImagePreview(previewFile, lease.thumbSubkey, itemId)
                if (existingPreview != null) {
                    if (container.sessionManager.currentVaultId != lease.vaultId ||
                        (entity.concealed && (entity.folderId == null ||
                            !container.folderAccessManager.canOpen(lease.vaultId, entity.folderId)))) {
                        existingPreview.recycle()
                        return@withContext ViewerLoad()
                    }
                    return@withContext ViewerLoad(entity, decodedMetadata, bitmap = existingPreview)
                }
                val extension = decodedMetadata?.originalFileExtension ?: "img"
                val verified = container.vaultFileStore.createViewerTempFile(itemId, extension)
                try {
                    val reserve = maxOf(64L * 1024 * 1024, entity.plaintextSize / 10)
                    if ((verified.parentFile?.usableSpace ?: 0L) < entity.plaintextSize + reserve) throw ViewerInsufficientSpace()
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
                    if (previewFile.exists()) previewFile.delete()
                    val generated = container.thumbnailGenerator.generateAndEncryptImagePreview(
                        Uri.fromFile(verified), itemId, lease.thumbSubkey, previewFile,
                        decodedMetadata?.orientation ?: 1
                    )
                    if (generated) {
                        container.database.mediaItemDao().setPreviewPathForVault(lease.vaultId, itemId, previewFile.name)
                    }
                    val previewBitmap = if (generated) {
                        container.thumbnailGenerator.decryptImagePreview(previewFile, lease.thumbSubkey, itemId)
                    } else null
                    if (previewBitmap != null) return@withContext ViewerLoad(entity, decodedMetadata, bitmap = previewBitmap)
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
                    val reserve = maxOf(64L * 1024 * 1024, entity.plaintextSize / 10)
                    if ((temp.parentFile?.usableSpace ?: 0L) < entity.plaintextSize + reserve) throw ViewerInsufficientSpace()
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
            if (it is CancellationException) throw it
            loadError = if (it is ViewerInsufficientSpace) "Not enough private storage space to prepare this media."
                else "Could not open verified media. The vault copy remains unchanged."
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
    DisposableEffect(itemId) {
        onDispose {
            shareJob?.cancel()
            exoPlayer?.release()
            exoPlayer = null
            tempPlaybackFile?.delete()
            deepZoomFile?.delete()
            fullBitmap?.recycle()
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
                    Text("${if (loadingVideo) "Preparing video" else "Preparing preview"}… ${(progress.first * 100 / progress.second).coerceIn(0, 100)}%", color = SuyaColors.White)
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
                            .onSizeChanged { viewport = it }
                            .pointerInput(scale > 1f) {
                                if (scale > 1f) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 5f)
                                        if (scale <= 1f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            val maxOffsetX = (size.width * (scale - 1)) / 2
                                            val maxOffsetY = (size.height * (scale - 1)) / 2
                                            offset = Offset(
                                                x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                                y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                            )
                                        }
                                    }
                                } else {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        do {
                                            val event = awaitPointerEvent()
                                            val canceled = event.changes.any { it.isConsumed }
                                            if (!canceled && event.changes.size > 1) {
                                                val zoomChange = event.calculateZoom()
                                                if (zoomChange != 1f) {
                                                    scale = (scale * zoomChange).coerceIn(1f, 5f)
                                                    event.changes.forEach {
                                                        if (it.positionChanged()) it.consume()
                                                    }
                                                }
                                            }
                                        } while (!canceled && event.changes.any { it.pressed })
                                    }
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
                        val tile = deepZoomTile
                        if (scale > 2f && tile != null && !tile.bitmap.isRecycled) {
                            val density = LocalDensity.current
                            Image(
                                bitmap = tile.bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .offset { IntOffset(tile.left, tile.top) }
                                    .size(with(density) { tile.width.toDp() }, with(density) { tile.height.toDp() })
                            )
                        }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
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

                                // Optimistic UI update so the star reacts on the exact tap instead of
                                // waiting for the Room write + dispatcher round trip. Roll back on failure.
                                mediaEntity = current.copy(favorite = newFav)
                                scope.launch {
                                    val activeVaultId = session?.vaultId ?: run {
                                        mediaEntity = current
                                        return@launch
                                    }
                                    val updated = withContext(Dispatchers.IO) {
                                        container.database.mediaItemDao().updateFavoriteForVault(
                                            activeVaultId,
                                            current.id,
                                            newFav,
                                            System.currentTimeMillis()
                                        )
                                    }
                                    if (updated != 1) {
                                        mediaEntity = current
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
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
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
                                        container.sessionManager.beginSystemActivity()
                                        shareLauncher.launch(Intent.createChooser(send, "Share media"))
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
        deepZoomError?.let { error ->
            Text(error, color = SuyaColors.Negative, modifier = Modifier.align(Alignment.Center))
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

private data class RegionTile(val bitmap: Bitmap, val left: Int, val top: Int, val width: Int, val height: Int)

/** Decode only the visible original-image rectangle, with a bounded output bitmap. */
@Suppress("DEPRECATION")
private fun decodeRegionTile(file: File, viewport: IntSize, scale: Float, pan: Offset, orientation: Int): RegionTile? {
    val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false) ?: return null
    try {
        val rawW = decoder.width
        val rawH = decoder.height
        if (rawW <= 0 || rawH <= 0) return null
        val rotated = orientation in 5..8
        val width = if (rotated) rawH else rawW
        val height = if (rotated) rawW else rawH
        val fit = min(viewport.width.toFloat() / width, viewport.height.toFloat() / height)
        if (fit <= 0f) return null
        val pixelsPerImagePixel = fit * scale
        val left = (width / 2f + (-viewport.width / 2f - pan.x) / pixelsPerImagePixel).coerceIn(0f, width.toFloat())
        val right = (width / 2f + (viewport.width / 2f - pan.x) / pixelsPerImagePixel).coerceIn(0f, width.toFloat())
        val top = (height / 2f + (-viewport.height / 2f - pan.y) / pixelsPerImagePixel).coerceIn(0f, height.toFloat())
        val bottom = (height / 2f + (viewport.height / 2f - pan.y) / pixelsPerImagePixel).coerceIn(0f, height.toFloat())
        if (right - left < 1f || bottom - top < 1f) return null
        fun toRaw(x: Float, y: Float): Pair<Float, Float> =
            com.suyaphot.app.core.media.DeepZoomOrientationMapper.toRaw(x, y, rawW.toFloat(), rawH.toFloat(), orientation)
        val corners = listOf(toRaw(left, top), toRaw(right, top), toRaw(left, bottom), toRaw(right, bottom))
        val rect = Rect(
            floor(corners.minOf { it.first }).toInt().coerceIn(0, rawW - 1),
            floor(corners.minOf { it.second }).toInt().coerceIn(0, rawH - 1),
            ceil(corners.maxOf { it.first }).toInt().coerceIn(1, rawW),
            ceil(corners.maxOf { it.second }).toInt().coerceIn(1, rawH)
        )
        if (rect.width() <= 0 || rect.height() <= 0) return null
        var sample = 1
        while (maxOf(rect.width(), rect.height()) / sample > 2048) sample *= 2
        val source = decoder.decodeRegion(rect, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return null
        val bitmap = applyExifOrientation(source, orientation)
        return RegionTile(
            bitmap,
            (viewport.width / 2f + (left - width / 2f) * pixelsPerImagePixel + pan.x).toInt(),
            (viewport.height / 2f + (top - height / 2f) * pixelsPerImagePixel + pan.y).toInt(),
            ((right - left) * pixelsPerImagePixel).toInt().coerceAtLeast(1),
            ((bottom - top) * pixelsPerImagePixel).toInt().coerceAtLeast(1)
        )
    } finally {
        decoder.recycle()
    }
}

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
