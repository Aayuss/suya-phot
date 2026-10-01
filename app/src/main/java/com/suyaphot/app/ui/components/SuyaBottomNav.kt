package com.suyaphot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.suyaphot.app.ui.theme.SuyaColors
import com.suyaphot.app.ui.theme.SuyaStyles

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
                .padding(6.dp)
        ) {
            val tabs = SuyaNavTab.entries
            val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
            val gap = 4.dp
            val collapsedWidth = 52.dp
            val selectedWidth = (
                maxWidth -
                    collapsedWidth * (tabs.size - 1) -
                    gap * (tabs.size - 1)
                ).coerceAtLeast(104.dp)

            val animatedWidths = tabs.map { tab ->
                animateDpAsState(
                    targetValue = if (tab == selectedTab) selectedWidth else collapsedWidth,
                    animationSpec = tween(durationMillis = 240),
                    label = "bottom_nav_width_${tab.name}"
                ).value
            }

            val selectionX = animatedWidths
                .take(selectedIndex)
                .fold(0.dp) { total, width -> total + width + gap }

            Box(
                modifier = Modifier
                    .offset(x = selectionX)
                    .width(animatedWidths[selectedIndex])
                    .height(50.dp)
                    .background(
                        color = SuyaColors.Accent,
                        shape = RoundedCornerShape(25.dp)
                    )
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = tab == selectedTab
                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) SuyaColors.White else SuyaColors.TextMuted,
                        animationSpec = tween(durationMillis = 180),
                        label = "bottom_nav_tint_${tab.name}"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(animatedWidths[index])
                            .height(50.dp)
                            .clickable(
                                interactionSource = remember(tab) { MutableInteractionSource() },
                                indication = null,
                                onClick = { if (!isSelected) onTabSelected(tab) }
                            )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = iconTint,
                                modifier = Modifier.size(22.dp)
                            )

                            AnimatedVisibility(
                                visible = isSelected,
                                enter = fadeIn(tween(150)) + expandHorizontally(tween(220)),
                                exit = fadeOut(tween(100)) + shrinkHorizontally(tween(180))
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = tab.title,
                                        color = SuyaColors.White,
                                        style = SuyaStyles.ButtonText,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
