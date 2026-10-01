package com.suyaphot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Compact native-style bottom navigation.
 *
 * - Selected tab expands horizontally to icon + label.
 * - Unselected tabs remain icon-only.
 * - A single orange pill physically slides between final tab positions.
 * - Long-press is deliberately preserved for the hidden-folders gesture.
 */
@Composable
fun SuyaBottomNav(
    selectedTab: SuyaNavTab,
    onTabSelected: (SuyaNavTab) -> Unit,
    modifier: Modifier = Modifier,
    onTabLongPressed: (SuyaNavTab) -> Unit = {}
) {
    val selectedWidth = 112.dp
    val iconOnlyWidth = 50.dp
    val gap = 6.dp
    val selectedIndex = SuyaNavTab.entries.indexOf(selectedTab).coerceAtLeast(0)

    val indicatorTargetX = (iconOnlyWidth + gap) * selectedIndex
    val indicatorX by animateDpAsState(
        targetValue = indicatorTargetX,
        animationSpec = spring(dampingRatio = 0.84f, stiffness = 520f),
        label = "bottom_nav_indicator_x"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .navigationBarsPadding()
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = SuyaColors.Fill07,
            modifier = Modifier.wrapContentWidth()
        ) {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .width(selectedWidth + iconOnlyWidth * 3 + gap * 3)
                    .height(50.dp)
            ) {
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
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuyaNavTab.entries.forEach { tab ->
                        val selected = tab == selectedTab
                        val itemWidth by animateDpAsState(
                            targetValue = if (selected) selectedWidth else iconOnlyWidth,
                            animationSpec = spring(dampingRatio = 0.86f, stiffness = 620f),
                            label = "bottom_nav_item_width"
                        )

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .width(itemWidth)
                                .height(50.dp)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        if (!selected) onTabSelected(tab)
                                    },
                                    onLongClick = { onTabLongPressed(tab) }
                                )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                    tint = if (selected) SuyaColors.White else SuyaColors.TextMuted,
                                    modifier = Modifier.size(22.dp)
                                )

                                AnimatedVisibility(
                                    visible = selected,
                                    enter = expandHorizontally(
                                        expandFrom = Alignment.Start,
                                        animationSpec = spring(dampingRatio = 0.86f, stiffness = 650f)
                                    ),
                                    exit = shrinkHorizontally(
                                        shrinkTowards = Alignment.Start,
                                        animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f)
                                    )
                                ) {
                                    Text(
                                        text = tab.title,
                                        fontFamily = SoraFontFamily,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SuyaColors.White,
                                        modifier = Modifier.padding(start = 7.dp)
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
