package com.suyaphot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors

enum class SuyaNavTab(val title: String, val icon: ImageVector) {
    PHOTOS("Photos", Icons.Default.PhotoLibrary),
    FOLDERS("Folders", Icons.Default.Folder),
    SECURITY("Security", Icons.Default.Security),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun SuyaBottomNav(
    selectedTab: SuyaNavTab,
    onTabSelected: (SuyaNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = SuyaColors.Fill07,
        modifier = modifier
            .navigationBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .padding(6.dp)
        ) {
            val tabs = SuyaNavTab.entries
            val cellWidth = maxWidth / tabs.size
            val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
            val indicatorX by animateDpAsState(
                targetValue = cellWidth * selectedIndex,
                animationSpec = tween(durationMillis = 260),
                label = "bottom_nav_indicator_x"
            )

            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(cellWidth)
                    .height(50.dp)
                    .background(
                        color = SuyaColors.Accent,
                        shape = RoundedCornerShape(25.dp)
                    )
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                tabs.forEach { tab ->
                    val isSelected = tab == selectedTab
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(cellWidth)
                            .height(50.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onTabSelected(tab) }
                            )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) SuyaColors.White else SuyaColors.TextMuted,
                                modifier = Modifier.size(22.dp)
                            )

                            AnimatedVisibility(
                                visible = isSelected,
                                enter = fadeIn(tween(160, delayMillis = 70)) +
                                    expandHorizontally(tween(210), expandFrom = Alignment.Start),
                                exit = fadeOut(tween(90)) +
                                    shrinkHorizontally(tween(130), shrinkTowards = Alignment.Start)
                            ) {
                                Text(
                                    text = tab.title,
                                    fontFamily = SoraFontFamily,
                                    fontSize = 12.sp,
                                    color = SuyaColors.White,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
