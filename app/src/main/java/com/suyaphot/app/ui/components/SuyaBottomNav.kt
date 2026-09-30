package com.suyaphot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
    modifier: Modifier = Modifier,
    onTabLongPressed: (SuyaNavTab) -> Unit = {}
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
            val selectedWidth = (maxWidth * 0.46f)
                .coerceAtLeast(92.dp)
                .coerceAtMost(112.dp)
                .coerceAtMost(maxWidth)
            val compactWidth = ((maxWidth - selectedWidth) / (SuyaNavTab.entries.size - 1))
                .coerceAtLeast(34.dp)
            val selectedIndex = SuyaNavTab.entries.indexOf(selectedTab).coerceAtLeast(0)
            val indicatorX by animateDpAsState(
                targetValue = compactWidth * selectedIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "bottom_nav_indicator_x"
            )

            // One persistent selection pill physically slides between destinations.
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(selectedWidth)
                    .size(height = 50.dp, width = selectedWidth)
                    .background(SuyaColors.Accent, RoundedCornerShape(25.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SuyaNavTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    val interaction = remember(tab) { MutableInteractionSource() }
                    val itemWidth by animateDpAsState(
                        targetValue = if (selected) selectedWidth else compactWidth,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "bottom_nav_item_width"
                    )

                    Row(
                        modifier = Modifier
                            .width(itemWidth)
                            .size(height = 50.dp, width = itemWidth)
                            .combinedClickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = { onTabSelected(tab) },
                                onLongClick = { onTabLongPressed(tab) }
                            ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
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
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            ),
                            exit = shrinkHorizontally(
                                shrinkTowards = Alignment.Start,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(7.dp))
                                Text(
                                    text = tab.title,
                                    color = SuyaColors.White,
                                    fontFamily = SoraFontFamily,
                                    fontSize = 12.sp,
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
