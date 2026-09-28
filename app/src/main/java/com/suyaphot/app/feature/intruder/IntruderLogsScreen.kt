package com.suyaphot.app.feature.intruder

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.EmptyState
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaIconButton
import com.suyaphot.app.ui.components.SuyaTopBar
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun IntruderLogsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val events by container.database.intruderEventDao().getAllEvents().collectAsState(initial = emptyList())
    val dateFormat = SimpleDateFormat("MMM dd, yyyy  h:mm a", Locale.getDefault())

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
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
                                    container.database.intruderEventDao().deleteAll()
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
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = SuyaColors.Fill06,
                            border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(14.dp)
                            ) {
                                // Decrypt intruder photo if available
                                val photoFile = event.encryptedImageRelativePath?.let {
                                    container.vaultFileStore.getSecurityFile("global_security", event.id)
                                }

                                if (photoFile != null && photoFile.exists()) {
                                    val dummyKey = "suya-phot-intruder-safety-key-256".toByteArray(Charsets.UTF_8).copyOf(32)
                                    val bitmap = try {
                                        val dec = Aead.decryptWithPrependedNonce(dummyKey, photoFile.readBytes(), event.id.toByteArray(Charsets.UTF_8))
                                        BitmapFactory.decodeByteArray(dec, 0, dec.size)
                                    } catch (e: Exception) { null }

                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
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
                                        text = event.failureType,
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
                                            container.database.intruderEventDao().delete(event.id)
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
    }
}
