package com.suyaphot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
            val slotWidth = maxWidth / tabs.size
            val indicatorX by animateDpAsState(
                targetValue = slotWidth * selectedIndex,
                animationSpec = spring(
                    dampingRatio = 0.86f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "nav_indicator_x"
            )

            // One continuous pill that physically travels between tabs.
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(slotWidth)
                    .fillMaxHeight()
                    .background(
                        color = SuyaColors.Accent,
                        shape = RoundedCornerShape(24.dp)
                    )
            )

            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    val selected = tab == selectedTab
                    val tint by animateColorAsState(
                        targetValue = if (selected) SuyaColors.White else SuyaColors.TextMuted,
                        animationSpec = tween(160),
                        label = "nav_icon_tint"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(slotWidth)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { if (!selected) onTabSelected(tab) }
                            )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = tint,
                                modifier = Modifier.size(22.dp)
                            )
                            AnimatedVisibility(
                                visible = selected,
                                enter = expandHorizontally(
                                    animationSpec = tween(180),
                                    expandFrom = Alignment.Start
                                ) + fadeIn(tween(130)),
                                exit = shrinkHorizontally(
                                    animationSpec = tween(150),
                                    shrinkTowards = Alignment.Start
                                ) + fadeOut(tween(90))
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(7.dp))
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
