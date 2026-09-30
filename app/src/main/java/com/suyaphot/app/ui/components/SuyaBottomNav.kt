package com.suyaphot.app.ui.components

import androidx.compose.animation.animateColorAsState
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
                .height(50.dp)
        ) {
            val tabs = SuyaNavTab.entries
            val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
            val slotWidth = maxWidth / tabs.size
            val pillWidth = 100.dp
            val maxOffset = (maxWidth - pillWidth).coerceAtLeast(0.dp)
            val rawTarget = slotWidth * selectedIndex + (slotWidth - pillWidth) / 2
            val targetOffset = rawTarget.coerceIn(0.dp, maxOffset)

            val pillOffset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "nav_pill_offset"
            )

            // Icon-only fixed slots. The selected slot is visually replaced by the
            // shared orange pill below, so the selection feels like one object sliding.
            Row(modifier = Modifier.fillMaxWidth()) {
                tabs.forEach { tab ->
                    val selected = tab == selectedTab
                    val iconTint by animateColorAsState(
                        targetValue = if (selected) Color.Transparent else SuyaColors.TextMuted,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "nav_icon_tint"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(slotWidth)
                            .height(50.dp)
                            .clickable(
                                interactionSource = remember(tab) { MutableInteractionSource() },
                                indication = null,
                                onClick = { onTabSelected(tab) }
                            )
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(25.dp),
                color = SuyaColors.Accent,
                modifier = Modifier
                    .offset(x = pillOffset)
                    .width(pillWidth)
                    .height(50.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = selectedTab.icon,
                        contentDescription = selectedTab.title,
                        tint = SuyaColors.White,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(
                        text = selectedTab.title,
                        color = SuyaColors.White,
                        fontFamily = SoraFontFamily,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 7.dp)
                    )
                }
            }
        }
    }
}
