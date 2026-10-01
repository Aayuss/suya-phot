package com.suyaphot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
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
    modifier: Modifier = Modifier,
    onTabLongPressed: (SuyaNavTab) -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = SuyaColors.Fill07,
        modifier = modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(62.dp)) {
            val edge = 6.dp
            val usable = maxWidth - edge * 2
            val segment = usable / SuyaNavTab.entries.size
            val pillWidth = minOf(112.dp, segment + 24.dp)
            val index = SuyaNavTab.entries.indexOf(selectedTab).coerceAtLeast(0)
            val targetX = edge + segment * index + (segment - pillWidth) / 2
            val indicatorX = animateDpAsState(
                targetValue = targetX,
                animationSpec = spring(dampingRatio = 0.84f, stiffness = 520f),
                label = "bottom_nav_indicator_x"
            ).value

            Box(
                Modifier.offset(x = indicatorX).align(Alignment.CenterStart)
                    .width(pillWidth).height(50.dp)
                    .background(SuyaColors.Accent, RoundedCornerShape(25.dp))
            )

            Row(Modifier.fillMaxSize().padding(horizontal = edge), verticalAlignment = Alignment.CenterVertically) {
                SuyaNavTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f).fillMaxHeight().combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTabSelected(tab) },
                            onLongClick = { onTabLongPressed(tab) }
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(tab.icon, tab.title, tint = if (selected) SuyaColors.White else SuyaColors.TextMuted, modifier = Modifier.size(22.dp))
                            AnimatedVisibility(
                                visible = selected,
                                enter = expandHorizontally(expandFrom = Alignment.Start, animationSpec = spring(dampingRatio = 0.86f, stiffness = 650f)),
                                exit = shrinkHorizontally(shrinkTowards = Alignment.Start, animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f))
                            ) {
                                Text(tab.title, fontFamily = SoraFontFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SuyaColors.White, modifier = Modifier.padding(start = 7.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
