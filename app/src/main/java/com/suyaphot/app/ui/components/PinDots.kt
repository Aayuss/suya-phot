package com.suyaphot.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.suyaphot.app.ui.theme.SuyaColors

@Composable
fun PinDots(
    pinLength: Int,
    enteredCount: Int,
    modifier: Modifier = Modifier,
    shakeTrigger: Int = 0
) {
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            val shakeValues = listOf(-16f, 16f, -12f, 12f, -8f, 8f, -4f, 4f, 0f)
            for (value in shakeValues) {
                shakeOffset.animateTo(
                    targetValue = value,
                    animationSpec = spring(stiffness = Spring.StiffnessHigh)
                )
            }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.offset { IntOffset(shakeOffset.value.toInt(), 0) }
    ) {
        for (i in 0 until pinLength) {
            val isFilled = i < enteredCount
            val dotSize by animateDpAsState(
                targetValue = if (isFilled) 14.dp else 12.dp,
                label = "dot_size"
            )

            Box(
                modifier = Modifier
                    .size(dotSize)
                    .background(
                        color = if (isFilled) SuyaColors.White else Color.Transparent,
                        shape = CircleShape
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (isFilled) SuyaColors.White else SuyaColors.StrokeMid,
                        shape = CircleShape
                    )
            )
        }
    }
}
