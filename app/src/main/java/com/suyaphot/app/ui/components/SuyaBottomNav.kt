package com.suyaphot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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

@Composable
fun SuyaBottomNav(
    selectedTab: SuyaNavTab,
    onTabSelected: (SuyaNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = SuyaNavTab.entries
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
    val iconWidth = 50.dp
    val selectedWidth = 116.dp
    val gap = 8.dp

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
            val totalWidth = selectedWidth + iconWidth * (tabs.size - 1) + gap * (tabs.size - 1)
            val baseX = ((maxWidth - totalWidth) / 2f).coerceAtLeast(0.dp)
            val motionSpec = spring<androidx.compose.ui.unit.Dp>(
                dampingRatio = 0.82f,
                stiffness = Spring.StiffnessMediumLow
            )

            fun targetX(index: Int): androidx.compose.ui.unit.Dp =
                baseX + when {
                    index <= selectedIndex -> (iconWidth + gap) * index
                    else -> (iconWidth + gap) * (index - 1) + selectedWidth + gap
                }

            val pillX by animateDpAsState(
                targetValue = baseX + (iconWidth + gap) * selectedIndex,
                animationSpec = motionSpec,
                label = "bottom_nav_pill_x"
            )

            Box(
                modifier = Modifier
                    .offset(x = pillX)
                    .width(selectedWidth)
                    .height(50.dp)
                    .background(SuyaColors.Accent, RoundedCornerShape(25.dp))
            )

            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val x by animateDpAsState(
                    targetValue = targetX(index),
                    animationSpec = motionSpec,
                    label = "bottom_nav_item_\${tab.name}"
                )
                val targetWidth = if (selected) selectedWidth else iconWidth
                val interactionSource = remember(tab) { MutableInteractionSource() }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset(x = x)
                        .width(targetWidth)
                        .height(50.dp)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { if (!selected) onTabSelected(tab) }
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
                                expandFrom = Alignment.Start,
                                animationSpec = spring(
                                    dampingRatio = 0.86f,
                                    stiffness = Spring.StiffnessMedium
                                )
                            ) + fadeIn(),
                            exit = shrinkHorizontally(
                                shrinkTowards = Alignment.Start,
                                animationSpec = spring(
                                    dampingRatio = 0.9f,
                                    stiffness = Spring.StiffnessMedium
                                )
                            ) + fadeOut()
                        ) {
                            Text(
                                text = tab.title,
                                color = SuyaColors.White,
                                fontFamily = SoraFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
