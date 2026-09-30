package com.suyaphot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.scale
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
    val selectedIndex = SuyaNavTab.entries.indexOf(selectedTab).coerceAtLeast(0)
    val selectedWidth = 112.dp
    val idleWidth = 52.dp
    val gap = 8.dp
    val totalWidth = selectedWidth + (idleWidth * 3) + (gap * 3)

    val indicatorX by animateDpAsState(
        targetValue = (idleWidth + gap) * selectedIndex,
        animationSpec = spring(
            dampingRatio = 0.84f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bottom_nav_indicator_x"
    )

    Surface(
        shape = RoundedCornerShape(30.dp),
        color = SuyaColors.Fill07,
        modifier = modifier
            .navigationBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.width(totalWidth)) {
                Box(
                    modifier = Modifier
                        .offset(x = indicatorX)
                        .width(selectedWidth)
                        .height(50.dp)
                        .background(
                            color = SuyaColors.Accent,
                            shape = RoundedCornerShape(25.dp)
                        )
                )

                Row(
                    modifier = Modifier.width(totalWidth),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuyaNavTab.entries.forEach { tab ->
                        val isSelected = tab == selectedTab
                        val itemWidth by animateDpAsState(
                            targetValue = if (isSelected) selectedWidth else idleWidth,
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "bottom_nav_item_width"
                        )
                        val iconScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.02f else 1f,
                            animationSpec = spring(
                                dampingRatio = 0.82f,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "bottom_nav_icon_scale"
                        )
                        val iconTint by animateColorAsState(
                            targetValue = if (isSelected) SuyaColors.White else SuyaColors.TextMuted,
                            label = "bottom_nav_icon_tint"
                        )

                        Row(
                            modifier = Modifier
                                .width(itemWidth)
                                .height(50.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onTabSelected(tab) }
                                )
                                .padding(horizontal = if (isSelected) 14.dp else 0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = iconTint,
                                modifier = Modifier
                                    .width(22.dp)
                                    .height(22.dp)
                                    .scale(iconScale)
                            )

                            AnimatedVisibility(
                                visible = isSelected,
                                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = tab.title,
                                        style = SuyaStyles.ButtonText,
                                        color = SuyaColors.White,
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
