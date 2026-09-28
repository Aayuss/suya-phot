package com.suyaphot.app.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.R
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.domain.auth.LockReason
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaTopBar
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val session = container.sessionManager.sessionState.collectAsState().value
    val vaultId = (session as? VaultSession.Unlocked)?.vaultId ?: ""

    var storageBytes by remember { mutableLongStateOf(0L) }

    LaunchedEffect(vaultId) {
        withContext(Dispatchers.IO) {
            storageBytes = container.vaultFileStore.getVaultStorageBytes(vaultId)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SuyaTopBar(title = "Settings")

            Column(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Storage Card
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SuyaColors.Fill06,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Storage, contentDescription = null, tint = SuyaColors.Accent)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = "Vault Storage", fontFamily = SoraFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = SuyaColors.White)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = formatStorageSize(storageBytes),
                            fontFamily = SoraFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 24.sp,
                            color = SuyaColors.White
                        )
                        Text(text = "Encrypted local data & thumbnails", fontFamily = SoraFontFamily, fontSize = 12.sp, color = SuyaColors.TextMuted)
                        Spacer(modifier = Modifier.height(14.dp))
                        SuyaButton(
                            text = "Clean Temporary Cache",
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    container.vaultFileStore.clearShareCache()
                                    storageBytes = container.vaultFileStore.getVaultStorageBytes(vaultId)
                                }
                            },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Trash Action
                SettingRowItem(
                    title = "Vault Trash",
                    subtitle = "View and restore deleted media",
                    icon = Icons.Default.Delete,
                    onClick = onOpenTrash
                )

                // Lock Vault Now
                SettingRowItem(
                    title = "Lock Vault Now",
                    subtitle = "Clears all decrypted keys from memory",
                    icon = Icons.Default.Lock,
                    onClick = {
                        container.sessionManager.lock(LockReason.Explicit)
                    }
                )

                // About Card
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SuyaColors.Fill06,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_suya_logo),
                            contentDescription = "Suya Phot",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = "Suya Phot", fontFamily = SoraFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = SuyaColors.White)
                        Text(text = "Version 1.0.0 (Release)", fontFamily = SoraFontFamily, fontSize = 12.sp, color = SuyaColors.TextMuted)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Local-first privacy vault. Zero telemetry, no cloud upload, no tracking, battery efficient.",
                            fontFamily = SoraFontFamily,
                            fontSize = 12.sp,
                            color = SuyaColors.TextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun SettingRowItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SuyaColors.Fill06,
        border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SuyaColors.Fill07,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, tint = SuyaColors.White, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontFamily = SoraFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = SuyaColors.White)
                Text(text = subtitle, fontFamily = SoraFontFamily, fontSize = 12.sp, color = SuyaColors.TextMuted)
            }
            Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = SuyaColors.TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
