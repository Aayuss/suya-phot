package com.suyaphot.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.suyaphot.app.core.model.Folder
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors

@Composable
fun SuyaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    onTitleLongClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (navigationIcon != null && onNavigationClick != null) {
                SuyaIconButton(
                    icon = navigationIcon,
                    contentDescription = "Back",
                    onClick = onNavigationClick,
                    size = 38
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = title,
                fontFamily = SoraFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 20.sp,
                color = SuyaColors.White,
                modifier = if (onTitleLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onTitleLongClick
                    )
                } else {
                    Modifier
                }
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions()
        }
    }
}

@Composable
fun SuyaSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search..."
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SuyaColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = SuyaColors.TextMuted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontFamily = SoraFontFamily,
                        fontSize = 14.sp,
                        color = SuyaColors.TextMuted
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    cursorBrush = SolidColor(SuyaColors.Accent),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = SoraFontFamily,
                        fontSize = 14.sp,
                        color = SuyaColors.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun SuyaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                fontFamily = SoraFontFamily,
                fontSize = 12.sp,
                color = SuyaColors.TextMuted,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SuyaColors.Surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.padding(horizontal = 14.dp)
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        fontFamily = SoraFontFamily,
                        fontSize = 14.sp,
                        color = SuyaColors.TextMuted
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    cursorBrush = SolidColor(SuyaColors.Accent),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = SoraFontFamily,
                        fontSize = 14.sp,
                        color = SuyaColors.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun FolderTile(
    folder: Folder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        label = "folder_scale"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SuyaColors.Fill06,
        border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SuyaColors.Fill07,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (folder.lockId != null) Icons.Default.Lock else Icons.Default.Folder,
                        contentDescription = if (folder.lockId != null) "Locked folder" else "Folder",
                        tint = SuyaColors.Accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    fontFamily = SoraFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = SuyaColors.White
                )
                Text(
                    text = if (folder.lockId != null || folder.effectiveProtected) "Locked" else "${folder.itemCount} items",
                    fontFamily = SoraFontFamily,
                    fontSize = 12.sp,
                    color = SuyaColors.TextMuted
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = SuyaColors.TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SuyaColors.Fill07,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SuyaColors.TextMuted,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = title,
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            color = SuyaColors.White
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            fontFamily = SoraFontFamily,
            fontSize = 13.sp,
            color = SuyaColors.TextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionText != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(18.dp))
            SuyaButton(
                text = actionText,
                onClick = onActionClick,
                variant = ButtonVariant.Primary
            )
        }
    }
}

@Composable
fun SuyaDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String?,
    onConfirm: (() -> Unit)?,
    dismissText: String = "Cancel",
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SuyaColors.Surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    fontFamily = SoraFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    color = SuyaColors.White
                )
                Spacer(modifier = Modifier.height(14.dp))
                content()
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    SuyaButton(
                        text = dismissText,
                        onClick = onDismissRequest,
                        variant = ButtonVariant.Ghost
                    )
                    if (confirmText != null && onConfirm != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        SuyaButton(
                            text = confirmText,
                            onClick = onConfirm,
                            variant = ButtonVariant.Primary
                        )
                    }
                }
            }
        }
    }
}
