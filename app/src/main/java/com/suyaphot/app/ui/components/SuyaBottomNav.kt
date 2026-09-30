package com.suyaphot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * Compact expanding bottom navigation.
 *
 * Only the selected destination shows its text label. A single orange pill physically slides
 * between destinations; the UI does not cross-fade independent selection backgrounds.
 */
@Composable
fun SuyaBottomNav(
    selectedTab: SuyaNavTab,
    onTabSelected: (SuyaNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = SuyaNavTab.entries
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)

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
                .padding(vertical = 6.dp)
        ) {
            val collapsedWidth = 50.dp
            val expandedWidth = 124.dp
            val gap = 6.dp
            val totalWidth = expandedWidth + collapsedWidth * (tabs.size - 1) + gap * (tabs.size - 1)
            val startX = maxOf(0.dp, (maxWidth - totalWidth) / 2)

            fun targetX(index: Int) =
                startX +
                    (collapsedWidth + gap) * index +
                    if (selectedIndex < index) expandedWidth - collapsedWidth else 0.dp

            val indicatorX = animateDpAsState(
                targetValue = targetX(selectedIndex),
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "bottom_nav_indicator_x"
            )

            Box(
                modifier = Modifier
                    .offset(x = indicatorX.value)
                    .width(expandedWidth)
                    .height(50.dp)
                    .background(SuyaColors.Accent, RoundedCornerShape(25.dp))
            )

            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val itemX = animateDpAsState(
                    targetValue = targetX(index),
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    label = "bottom_nav_item_x_${tab.name}"
                )
                val itemWidth = animateDpAsState(
                    targetValue = if (selected) expandedWidth else collapsedWidth,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    label = "bottom_nav_item_width_${tab.name}"
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset(x = itemX.value)
                        .width(itemWidth.value)
                        .height(50.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (!selected) onTabSelected(tab)
                            }
                        )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            tint = if (selected) SuyaColors.White else SuyaColors.TextMuted,
                            modifier = Modifier.size(22.dp)
                        )
                        AnimatedVisibility(
                            visible = selected,
                            enter = expandHorizontally(
                                animationSpec = tween(180, easing = FastOutSlowInEasing)
                            ) + fadeIn(tween(140)),
                            exit = shrinkHorizontally(
                                animationSpec = tween(160, easing = FastOutSlowInEasing)
                            ) + fadeOut(tween(100))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = tab.title,
                                    fontFamily = SoraFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    color = Color.White,
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
