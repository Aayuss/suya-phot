package com.suyaphot.app.feature.intruder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.database.entity.IntruderEventEntity
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.EmptyState
import com.suyaphot.app.ui.components.PatternLockPad
import com.suyaphot.app.ui.components.PinDots
import com.suyaphot.app.ui.components.SecurePinPad
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaIconButton
import com.suyaphot.app.ui.components.SuyaTopBar
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun IntruderLogsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val session by container.sessionManager.sessionState.collectAsState()
    val isSecondary = (session as? VaultSession.Unlocked)?.kind == VaultKind.SECONDARY
    val realVaultId = (session as? VaultSession.Unlocked)?.vaultId ?: ""

    // Secondary vault mode must never see real intruder logs
    if (isSecondary) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(SuyaColors.Background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SuyaTopBar(
                    title = "Security Log",
                    navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavigationClick = onBack
                )
                EmptyState(
                    icon = Icons.Default.CameraAlt,
                    title = "No Logs Available",
                    subtitle = "No security events are recorded for this session.",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        return
    }

    // Intruder Logs require re-authentication (PIN/Pattern or Biometric) to view
    var isUnlocked by rememberSaveable { mutableStateOf(false) }

    if (!isUnlocked) {
        IntruderLogsGate(
            container = container,
            vaultId = realVaultId,
            onAuthenticated = {
                isUnlocked = true
            },
            onBack = onBack,
            modifier = modifier
        )
        return
    }

    val scope = rememberCoroutineScope()
    val events by container.database.intruderEventDao().getEventsForVault(realVaultId).collectAsState(initial = emptyList())
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy  h:mm a", Locale.getDefault()) }

    var selectedViewerData by remember { mutableStateOf<Triple<IntruderEventEntity, Bitmap, String>?>(null) }

    // Once successfully unlocked and viewed, dismiss home-screen intruder alert banner
    LaunchedEffect(Unit) {
        container.preferences.setLastDismissedIntruderTimestamp(System.currentTimeMillis())
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
                title = "Intruder Attempts",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = onBack,
                actions = {
                    if (events.isNotEmpty()) {
                        SuyaButton(
                            text = "Clear All",
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    for (event in events) {
                                        event.encryptedImageRelativePath?.let {
                                            container.vaultFileStore.getSecurityFile(realVaultId, event.id).delete()
                                        }
                                    }
                                    container.database.intruderEventDao().deleteAllForVault(realVaultId)
                                }
                            },
                            variant = ButtonVariant.Ghost
                        )
                    }
                }
            )

            if (events.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.CameraAlt,
                    title = "No Intruder Events",
                    subtitle = "Failed unlock attempts exceeding your trigger threshold will appear here with a front-camera selfie.",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(events, key = { it.id }) { event ->
                        val photoFile = event.encryptedImageRelativePath?.let {
                            container.vaultFileStore.getSecurityFile(realVaultId, event.id)
                        }
                        var bitmap by remember(event.id) { mutableStateOf<Bitmap?>(null) }
                        var detailText by remember(event.id) { mutableStateOf("Failed PIN attempt") }

                        LaunchedEffect(event.id, event.encryptedImageRelativePath, event.encryptedDetails) {
                            val loaded = withContext(Dispatchers.IO) {
                                val key = container.intruderKeyProvider.getOrCreateKey()
                                val aad = "suya-phot:intruder:v1:${event.id}".toByteArray(Charsets.UTF_8)
                                val loadedBitmap = if (photoFile?.exists() == true) {
                                    runCatching {
                                        val encrypted = photoFile.readBytes()
                                        val decrypted = try {
                                            Aead.decryptWithPrependedNonce(key, encrypted, aad)
                                        } finally {
                                            encrypted.fill(0)
                                        }
                                        try {
                                            BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)
                                        } finally {
                                            decrypted.fill(0)
                                        }
                                    }.getOrNull()
                                } else {
                                    null
                                }
                                val loadedDetails = event.encryptedDetails?.let { encryptedDetails ->
                                    runCatching {
                                        val decrypted = Aead.decryptWithPrependedNonce(key, encryptedDetails, aad)
                                        try {
                                            String(decrypted, Charsets.UTF_8)
                                        } finally {
                                            decrypted.fill(0)
                                        }
                                    }.getOrNull()
                                } ?: "Failed PIN attempt"
                                loadedBitmap to loadedDetails
                            }
                            bitmap = loaded.first
                            detailText = loaded.second
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = SuyaColors.Fill06,
                            border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = bitmap != null) {
                                    val bmp = bitmap
                                    if (bmp != null) {
                                        selectedViewerData = Triple(event, bmp, detailText)
                                    }
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(14.dp)
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = checkNotNull(bitmap).asImageBitmap(),
                                        contentDescription = "Intruder photo",
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = SuyaColors.Fill07,
                                        modifier = Modifier.size(56.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = SuyaColors.TextMuted)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = detailText,
                                        fontFamily = SoraFontFamily,
                                        fontSize = 14.sp,
                                        color = SuyaColors.White
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = dateFormat.format(Date(event.createdAt)),
                                        fontFamily = SoraFontFamily,
                                        fontSize = 11.sp,
                                        color = SuyaColors.TextMuted
                                    )
                                }

                                SuyaIconButton(
                                    icon = Icons.Outlined.Delete,
                                    contentDescription = "Delete log",
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            photoFile?.delete()
                                            container.database.intruderEventDao().deleteForVault(event.id, realVaultId)
                                        }
                                    },
                                    size = 36
                                )
                            }
                        }
                    }
                }
            }
        }

        // Full Screen Photo Viewer Dialog
        selectedViewerData?.let { (viewerEvent, viewerBitmap, viewerDetail) ->
            IntruderPhotoViewerDialog(
                event = viewerEvent,
                bitmap = viewerBitmap,
                detailText = viewerDetail,
                onClose = { selectedViewerData = null },
                onDelete = {
                    scope.launch(Dispatchers.IO) {
                        val photoFile = viewerEvent.encryptedImageRelativePath?.let {
                            container.vaultFileStore.getSecurityFile(realVaultId, viewerEvent.id)
                        }
                        photoFile?.delete()
                        container.database.intruderEventDao().deleteForVault(viewerEvent.id, realVaultId)
                    }
                    selectedViewerData = null
                }
            )
        }
    }
}

@Composable
private fun IntruderLogsGate(
    container: AppContainer,
    vaultId: String,
    onAuthenticated: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var credentialTypeCode by remember(vaultId) { mutableIntStateOf(0) }
    var biometricIv by remember(vaultId) { mutableStateOf<ByteArray?>(null) }
    var hasBiometric by remember(vaultId) { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var shakeTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(vaultId) {
        val vault = withContext(Dispatchers.IO) {
            container.database.vaultDao().getVault(vaultId)
        }
        credentialTypeCode = vault?.credentialTypeCode ?: 0
        biometricIv = vault?.biometricIv?.takeIf { vault.biometricEnvelope != null }
        hasBiometric = biometricIv != null
    }

    fun submitPin(pin: String) {
        if (pin.length != 6) return
        scope.launch {
            val chars = pin.toCharArray()
            val valid = container.pinAuthenticator.verifyCurrentCredential(chars, 0)
            chars.fill('\u0000')
            if (valid) {
                onAuthenticated()
            } else {
                enteredPin = ""
                errorMessage = "Incorrect PIN"
                shakeTrigger++
            }
        }
    }

    fun submitPattern(pattern: IntArray) {
        val chars = runCatching { PatternCredential.canonicalChars(pattern) }.getOrNull()
        if (chars == null) {
            errorMessage = "Connect at least 4 dots"
            shakeTrigger++
            return
        }
        scope.launch {
            val valid = container.pinAuthenticator.verifyCurrentCredential(chars, 1)
            chars.fill('\u0000')
            if (valid) {
                onAuthenticated()
            } else {
                errorMessage = "Incorrect pattern"
                shakeTrigger++
            }
        }
    }

    fun launchBiometricPrompt() {
        val iv = biometricIv ?: return
        val activity = context as? FragmentActivity ?: return
        try {
            val cipher = container.keyManager.createBiometricDecryptCipher(vaultId, iv)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Intruder Logs")
                .setSubtitle("Use your biometric to view intruder logs")
                .setNegativeButtonText(if (credentialTypeCode == 1) "Use Pattern" else "Use PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()

            val biometricPrompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authCipher = result.cryptoObject?.cipher ?: return
                        scope.launch {
                            val valid = container.pinAuthenticator.verifyCurrentBiometric(authCipher)
                            if (valid) {
                                onAuthenticated()
                            } else {
                                errorMessage = "Biometric authentication failed"
                            }
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            errorMessage = errString.toString()
                        }
                    }

                    override fun onAuthenticationFailed() {
                        errorMessage = "Biometric not recognized"
                    }
                }
            )
            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) {
            errorMessage = "Biometric unlock unavailable"
        }
    }

    // Auto-prompt biometric on entry if enrolled and enabled
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPromptedBiometric by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                hasPromptedBiometric = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(hasBiometric) {
        if (hasBiometric && !hasPromptedBiometric) {
            val autoPrompt = container.preferences.biometricOnLaunch.first()
            if (autoPrompt) {
                delay(200L)
                val act = context as? FragmentActivity
                if (act != null && act.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !hasPromptedBiometric) {
                    hasPromptedBiometric = true
                    launchBiometricPrompt()
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
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SuyaTopBar(
                title = "Intruder Logs",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = onBack
            )

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                shape = CircleShape,
                color = SuyaColors.Fill07,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = SuyaColors.Accent,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Authentication Required",
                fontFamily = SoraFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = SuyaColors.White
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (credentialTypeCode == 1) "Draw your vault pattern to view intruder logs"
                else "Enter your vault PIN to view intruder logs",
                fontFamily = SoraFontFamily,
                fontSize = 13.sp,
                color = SuyaColors.TextMuted
            )

            Spacer(modifier = Modifier.height(16.dp))

            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.Negative,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            if (credentialTypeCode == 0) {
                PinDots(
                    pinLength = 6,
                    enteredCount = enteredPin.length,
                    shakeTrigger = shakeTrigger
                )

                Spacer(modifier = Modifier.weight(1f))

                SecurePinPad(
                    onDigitClick = { digit ->
                        if (enteredPin.length < 6) {
                            val newPin = enteredPin + digit
                            enteredPin = newPin
                            if (newPin.length == 6) {
                                submitPin(newPin)
                            }
                        }
                    },
                    onBackspaceClick = {
                        if (enteredPin.isNotEmpty()) {
                            enteredPin = enteredPin.dropLast(1)
                        }
                    },
                    showBiometric = hasBiometric,
                    onBiometricClick = {
                        launchBiometricPrompt()
                    }
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))

                PatternLockPad(
                    onPatternComplete = ::submitPattern,
                    errorTrigger = shakeTrigger,
                    modifier = Modifier.fillMaxWidth(0.85f)
                )

                if (hasBiometric) {
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { launchBiometricPrompt() }) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Use Biometric",
                            tint = SuyaColors.Accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Use Biometric",
                            fontFamily = SoraFontFamily,
                            fontSize = 14.sp,
                            color = SuyaColors.Accent
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IntruderPhotoViewerDialog(
    event: IntruderEventEntity,
    bitmap: Bitmap,
    detailText: String,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy  h:mm a", Locale.getDefault()) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isChromeVisible by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // Pinch-to-zoom / Pan Image
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
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
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Intruder photo full screen",
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

            // Top Bar Overlay
            AnimatedVisibility(
                visible = isChromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            SuyaIconButton(
                                icon = Icons.Default.Close,
                                contentDescription = "Close viewer",
                                onClick = onClose,
                                size = 40
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = detailText,
                                    fontFamily = SoraFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = SuyaColors.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = dateFormat.format(Date(event.createdAt)),
                                    fontFamily = SoraFontFamily,
                                    fontSize = 12.sp,
                                    color = SuyaColors.TextMuted
                                )
                            }
                        }

                        SuyaIconButton(
                            icon = Icons.Outlined.Delete,
                            contentDescription = "Delete intruder log",
                            onClick = onDelete,
                            size = 40
                        )
                    }
                }
            }
        }
    }
}
