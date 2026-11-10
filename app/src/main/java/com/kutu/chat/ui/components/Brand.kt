package com.kutu.chat.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kutu.chat.domain.Providers
import com.kutu.chat.ui.theme.KutuBrand

/** Kutu logosu: geçişli yuvarlak kare içinde ışıltı. */
@Composable
fun KutuLogo(size: Dp = 56.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(KutuBrand.gradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/** Sağlayıcıyı ayırt eden küçük renkli nokta. */
@Composable
fun ProviderDot(provider: String, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    val color = Providers.byId(provider)?.color?.let { Color(it) } ?: MaterialTheme.colorScheme.outline
    Box(modifier = modifier.size(size).clip(CircleShape).background(color))
}

/** "Yazıyor" göstergesi: sırayla zıplayan üç nokta. */
@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val offset by transition.animateFloat(
                initialValue = 0f, targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1100
                        0f at index * 140 using FastOutSlowInEasing
                        -6f at index * 140 + 220 using FastOutSlowInEasing
                        0f at index * 140 + 440
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dot$index",
            )
            Box(
                Modifier.size(8.dp).graphicsLayer { translationY = offset * density }.clip(CircleShape).background(color.copy(alpha = 0.85f)),
            )
        }
    }
}
