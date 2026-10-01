package com.suyaphot.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.launch

/**
 * Micro-animated Favorite star toggle for media viewing.
 *
 * Micro-interaction specification:
 * - Fluid background circle transition from translucent dark (`SuyaColors.Fill07`) to Suya orange (`SuyaColors.Accent`)
 * - Pop keyframe scale: 1.00 at 0ms -> 0.86 at 90ms -> 1.16 at 170ms -> settles to 1.00 at 280ms
 * - Subtle rotational tilt: 0° -> -6° at 90ms -> +3° at 170ms -> settles to 0° at 280ms
 * - Seamless morph/crossfade between outline star (`StarBorder`) and filled star (`Star`)
 * - Smooth, subtle fade back on unfavorite
 */
@Composable
fun FavoriteViewerButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scaleAnim = remember { Animatable(1f) }
    val rotationAnim = remember { Animatable(0f) }

    LaunchedEffect(isFavorite) {
        if (isFavorite) {
            launch {
                // Keyframe scale: 1.00 -> 0.86 (90ms) -> 1.16 (80ms) -> 1.00 (110ms)
                scaleAnim.animateTo(0.86f, tween(90, easing = FastOutSlowInEasing))
                scaleAnim.animateTo(1.16f, tween(80, easing = FastOutSlowInEasing))
                scaleAnim.animateTo(1.00f, tween(110, easing = FastOutSlowInEasing))
            }
            launch {
                // Keyframe rotation: 0° -> -6° (90ms) -> +3° (80ms) -> 0° (110ms)
                rotationAnim.animateTo(-6f, tween(90, easing = FastOutSlowInEasing))
                rotationAnim.animateTo(3f, tween(80, easing = FastOutSlowInEasing))
                rotationAnim.animateTo(0f, tween(110, easing = FastOutSlowInEasing))
            }
        } else {
            launch {
                scaleAnim.animateTo(1.0f, tween(180, easing = FastOutSlowInEasing))
            }
            launch {
                rotationAnim.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
            }
        }
    }

    val backgroundColor by animateColorAsState(
        targetValue = if (isFavorite) SuyaColors.Accent else SuyaColors.Fill07,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "favoriteBgColor"
    )

    val filledAlpha by animateFloatAsState(
        targetValue = if (isFavorite) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "filledAlpha"
    )

    val borderAlpha by animateFloatAsState(
        targetValue = if (isFavorite) 0f else 1f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "borderAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                rotationZ = rotationAnim.value
            }
        ) {
            if (borderAlpha > 0f) {
                Icon(
                    imageVector = Icons.Default.StarBorder,
                    contentDescription = "Favorite",
                    tint = SuyaColors.White.copy(alpha = borderAlpha),
                    modifier = Modifier.size(20.dp)
                )
            }
            if (filledAlpha > 0f) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Favorite",
                    tint = Color.White.copy(alpha = filledAlpha),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
