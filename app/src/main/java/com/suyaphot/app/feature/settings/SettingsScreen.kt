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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.suyaphot.app.BuildConfig
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.model.VaultKind
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
    onOpenPrivateTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val session = container.sessionManager.sessionState.collectAsState().value
    val vaultId = (session as? VaultSession.Unlocked)?.vaultId ?: ""

    var storageBytes by remember { mutableLongStateOf(0L) }
    val sortOrder by container.preferences.sortOrder.collectAsState(initial = "DATE_TAKEN_DESC")
    val retentionDays by container.preferences.trashRetentionDays.collectAsState(initial = 30)
    val autoLockMs by container.preferences.autoLockTimeoutMs.collectAsState(initial = 0L)
    val lockOnScreenOff by container.preferences.lockOnScreenOff.collectAsState(initial = true)
    val biometricEnabled by container.preferences.biometricOnLaunch.collectAsState(initial = true)
    val realVault by container.database.vaultDao()
        .observeVaultByKind(VaultKind.REAL.code)
        .collectAsState(initial = null)
    val biometricEnrolled = realVault?.biometricEnvelope != null && realVault?.biometricIv != null
    var showSortDialog by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showBackupRestore by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vaultId) {
        storageBytes = withContext(Dispatchers.IO) {
            container.vaultFileStore.getVaultStorageBytes(vaultId)
        }
    }

    if (showBackupRestore) {
        BackupRestoreScreen(
            container = container,
            mode = BackupRestoreMode.UNLOCKED_VAULT,
            onBack = { showBackupRestore = false }
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
            .imePadding()
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
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Export an encrypted .suyavault archive anytime to back up or migrate your media across devices.",
                            fontFamily = SoraFontFamily,
                            fontSize = 12.sp,
                            color = SuyaColors.TextMuted
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        SuyaButton(
                            text = "Clean Temporary Cache",
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    container.vaultFileStore.clearEphemeralPlaintextCaches()
                                    val refreshed = container.vaultFileStore.getVaultStorageBytes(vaultId)
                                    withContext(Dispatchers.Main) { storageBytes = refreshed }
                                }
                            },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Backup & Restore Action (Supported only on Real Vault)
                val isRealVault = (session as? com.suyaphot.app.domain.auth.VaultSession.Unlocked)?.kind == com.suyaphot.app.core.model.VaultKind.REAL
                if (isRealVault) {
                    SettingRowItem(
                        title = "Backup & Restore",
                        subtitle = "Export or restore portable encrypted .suyavault archive",
                        icon = Icons.Default.CloudUpload,
                        onClick = { showBackupRestore = true }
                    )
                }

                // Trash Action
                SettingRowItem(
                    title = "Vault Trash",
                    subtitle = "View and restore deleted media",
                    icon = Icons.Default.Delete,
                    onClick = onOpenTrash
                )
                SettingRowItem(
                    title = "Private Trash",
                    subtitle = "Deleted media from hidden or locked folders; requires re-authentication",
                    icon = Icons.Default.Delete,
                    onClick = onOpenPrivateTrash
                )

                SettingRowItem(
                    title = "Gallery Sort",
                    subtitle = sortOrder.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                    icon = Icons.AutoMirrored.Filled.Sort,
                    onClick = { showSortDialog = true }
                )

                SettingRowItem(
                    title = "Trash Retention",
                    subtitle = if (retentionDays == 0) "Never auto-delete" else "$retentionDays days",
                    icon = Icons.Default.CleaningServices,
                    onClick = { showRetentionDialog = true }
                )

                SettingRowItem(
                    title = "Auto-lock",
                    subtitle = when (autoLockMs) { 0L -> "Immediately"; 30_000L -> "30 seconds"; 60_000L -> "1 minute"; else -> "5 minutes" },
                    icon = Icons.Default.Lock,
                    onClick = { showAutoLockDialog = true }
                )

                SettingToggleRow(
                    title = "Lock on screen off",
                    subtitle = "Immediately lock when the display turns off",
                    checked = lockOnScreenOff,
                    onCheckedChange = { scope.launch { container.preferences.setLockOnScreenOff(it) } }
                )

                if (biometricEnrolled) {
                    SettingToggleRow(
                        title = "Biometric unlock",
                        subtitle = "Allow fingerprint or any strong biometric Android can use for vault unlock",
                        checked = biometricEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { container.preferences.setBiometricOnLaunch(enabled) }
                        }
                    )
                }

                if (showSortDialog) {
                    ChoiceDialog(
                        title = "Gallery Sort",
                        choices = listOf(
                            "DATE_TAKEN_DESC" to "Date taken (newest)",
                            "DATE_TAKEN_ASC" to "Date taken (oldest)",
                            "IMPORTED_DESC" to "Imported (newest)",
                            "IMPORTED_ASC" to "Imported (oldest)",
                            "SIZE_DESC" to "Size (largest)",
                            "SIZE_ASC" to "Size (smallest)"
                        ),
                        selected = sortOrder,
                        onDismiss = { showSortDialog = false },
                        onSelect = { scope.launch { container.preferences.setSortOrder(it) }; showSortDialog = false }
                    )
                }
                if (showRetentionDialog) {
                    ChoiceDialog(
                        title = "Trash Retention",
                        choices = listOf("0" to "Never", "7" to "7 days", "30" to "30 days", "90" to "90 days"),
                        selected = retentionDays.toString(),
                        onDismiss = { showRetentionDialog = false },
                        onSelect = { scope.launch { container.preferences.setTrashRetentionDays(it.toInt()) }; showRetentionDialog = false }
                    )
                }
                if (showAutoLockDialog) {
                    ChoiceDialog(
                        title = "Auto-lock",
                        choices = listOf("0" to "Immediately", "30000" to "30 seconds", "60000" to "1 minute", "300000" to "5 minutes"),
                        selected = autoLockMs.toString(),
                        onDismiss = { showAutoLockDialog = false },
                        onSelect = { scope.launch { container.preferences.setAutoLockTimeoutMs(it.toLong()) }; showAutoLockDialog = false }
                    )
                }

                // Grid Columns Setting
                val gridColumns by container.preferences.gridColumns.collectAsState(initial = 3)
                var showGridColumnsDialog by remember { mutableStateOf(false) }

                SettingRowItem(
                    title = "Gallery Grid Columns",
                    subtitle = "$gridColumns columns (current)",
                    icon = Icons.Default.GridView,
                    onClick = { showGridColumnsDialog = true }
                )

                if (showGridColumnsDialog) {
                    com.suyaphot.app.ui.components.SuyaDialog(
                        onDismissRequest = { showGridColumnsDialog = false },
                        title = "Grid Density",
                        content = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(2, 3, 4, 5).forEach { cols ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                scope.launch {
                                                    container.preferences.setGridColumns(cols)
                                                    showGridColumnsDialog = false
                                                }
                                            }
                                            .padding(vertical = 8.dp)
                                    ) {
                                        androidx.compose.material3.RadioButton(
                                            selected = gridColumns == cols,
                                            onClick = {
                                                scope.launch {
                                                    container.preferences.setGridColumns(cols)
                                                    showGridColumnsDialog = false
                                                }
                                            },
                                            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = SuyaColors.Accent)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "$cols columns",
                                            fontFamily = SoraFontFamily,
                                            fontSize = 14.sp,
                                            color = SuyaColors.White
                                        )
                                    }
                                }
                            }
                        },
                        confirmText = "Close",
                        onConfirm = { showGridColumnsDialog = false }
                    )
                }

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
                        Text(text = "Version ${BuildConfig.VERSION_NAME}", fontFamily = SoraFontFamily, fontSize = 12.sp, color = SuyaColors.TextMuted)
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
private fun ChoiceDialog(
    title: String,
    choices: List<Pair<String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    com.suyaphot.app.ui.components.SuyaDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column {
                choices.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 6.dp)
                    ) {
                        androidx.compose.material3.RadioButton(selected = selected == value, onClick = { onSelect(value) })
                        Text(label, color = SuyaColors.White, fontFamily = SoraFontFamily)
                    }
                }
            }
        },
        confirmText = "Close",
        onConfirm = onDismiss
    )
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(shape = RoundedCornerShape(20.dp), color = SuyaColors.Fill06, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontFamily = SoraFontFamily, color = SuyaColors.White, fontSize = 15.sp)
                Text(subtitle, fontFamily = SoraFontFamily, color = SuyaColors.TextMuted, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
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
