package com.example.ui.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.model.VpnStatus
import com.example.ui.components.ShieldCoreVisual
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.IceCyan
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Short, efficient cinematic first-launch intro (~1.6s).
 * Respects both in-app Reduce Motion and system animator duration preferences.
 */
@Composable
fun OpeningAnimationScreen(
    reduceMotion: Boolean,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val systemScale = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }
    val effectiveReduceMotion = reduceMotion || systemScale == 0f

    val shieldAlpha = remember { Animatable(if (effectiveReduceMotion) 1f else 0f) }
    val shieldScale = remember { Animatable(if (effectiveReduceMotion) 1f else 0.84f) }
    val floatOffset = remember { Animatable(if (effectiveReduceMotion) 0f else 18f) }
    val titleAlpha = remember { Animatable(if (effectiveReduceMotion) 1f else 0f) }
    val overallAlpha = remember { Animatable(1f) }

    val particles = remember {
        listOf(
            Triple(0.18f, 0.25f, 3.2f),
            Triple(0.82f, 0.22f, 2.6f),
            Triple(0.28f, 0.72f, 2.2f),
            Triple(0.76f, 0.68f, 3.4f),
            Triple(0.50f, 0.16f, 2.0f),
            Triple(0.14f, 0.52f, 2.5f),
            Triple(0.88f, 0.46f, 2.1f)
        )
    }

    LaunchedEffect(effectiveReduceMotion) {
        if (effectiveReduceMotion) {
            delay(550)
            onFinished()
            return@LaunchedEffect
        }

        launch {
            shieldAlpha.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        }
        launch {
            shieldScale.animateTo(1f, tween(680, easing = FastOutSlowInEasing))
        }
        launch {
            floatOffset.animateTo(-4f, tween(950, easing = FastOutSlowInEasing))
        }
        delay(320)
        launch {
            titleAlpha.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        }
        delay(1050)
        overallAlpha.animateTo(0f, tween(280))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianCanvas)
            .alpha(overallAlpha.value)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onFinished
            )
            .testTag("opening_animation_screen")
    ) {
        // Ambient lighting + subtle particles
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ElectricViolet.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.42f),
                    radius = size.width * 0.72f
                ),
                radius = size.width * 0.72f,
                center = Offset(size.width * 0.5f, size.height * 0.42f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(IceCyan.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.48f),
                    radius = size.width * 0.52f
                ),
                radius = size.width * 0.52f,
                center = Offset(size.width * 0.5f, size.height * 0.48f)
            )

            if (!effectiveReduceMotion) {
                particles.forEachIndexed { idx, (px, py, radius) ->
                    val color = if (idx % 2 == 0) ElectricViolet else IceCyan
                    drawCircle(
                        color = color.copy(alpha = 0.42f * shieldAlpha.value),
                        radius = radius.dp.toPx(),
                        center = Offset(
                            size.width * px,
                            size.height * py + floatOffset.value
                        )
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(188.dp)
                    .graphicsLayer {
                        alpha = shieldAlpha.value
                        scaleX = shieldScale.value
                        scaleY = shieldScale.value
                        translationY = floatOffset.value
                    },
                contentAlignment = Alignment.Center
            ) {
                ShieldCoreVisual(
                    status = VpnStatus.CONNECTING,
                    reduceMotion = effectiveReduceMotion,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(titleAlpha.value)
            ) {
                Text(
                    text = stringResource(R.string.brand_full_title),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        letterSpacing = 1.6.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "DNS PROTECTION • RESOLVER BENCHMARKING",
                    style = MaterialTheme.typography.labelMedium,
                    color = IceCyan.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center
                )
            }
        }

        TextButton(
            onClick = onFinished,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
                .testTag("skip_intro_button")
        ) {
            Text(
                text = "Tap to continue",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary.copy(alpha = 0.7f)
            )
        }
    }
}
