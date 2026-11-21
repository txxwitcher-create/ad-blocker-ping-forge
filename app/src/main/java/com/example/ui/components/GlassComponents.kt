package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.VpnStatus
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.Emerald
import com.example.ui.theme.IceCyan
import com.example.ui.theme.LocalForgeExtras
import com.example.ui.theme.WarmAmber
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ForgeAtmosphereBackground(
    accentColor: Color = ElectricViolet,
    secondaryColor: Color = IceCyan,
    content: @Composable () -> Unit
) {
    val extras = LocalForgeExtras.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(extras.canvasBg)
    ) {
        // Subtle atmospheric backdrop art blended gently into obsidian canvas
        Image(
            painter = painterResource(id = R.drawable.img_forge_atmosphere),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (extras.isHighContrast) 0.10f else 0.18f)
        )

        // Controlled dual-point ambient radial lighting
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accentColor.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(size.width * 0.2f, size.height * 0.12f),
                    radius = size.width * 0.85f
                ),
                radius = size.width * 0.85f,
                center = Offset(size.width * 0.2f, size.height * 0.12f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(secondaryColor.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.78f),
                    radius = size.width * 0.75f
                ),
                radius = size.width * 0.75f,
                center = Offset(size.width * 0.85f, size.height * 0.78f)
            )
        }

        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accentBorder: Color? = null,
    cornerRadius: Dp = 20.dp,
    contentPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val extras = LocalForgeExtras.current
    val shape = RoundedCornerShape(cornerRadius)
    val borderBrush = if (accentBorder != null) {
        Brush.linearGradient(
            colors = listOf(
                accentBorder.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.08f),
                accentBorder.copy(alpha = 0.25f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.14f),
                extras.glassBorder,
                Color.White.copy(alpha = 0.05f)
            )
        )
    }

    val containerBrush = Brush.verticalGradient(
        colors = listOf(
            extras.glassElevated.copy(alpha = 0.88f),
            extras.glassSurface.copy(alpha = 0.78f)
        )
    )

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, borderBrush),
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(containerBrush)
                    .padding(contentPadding),
                content = content
            )
        }
    } else {
        Surface(
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, borderBrush),
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(containerBrush)
                    .padding(contentPadding),
                content = content
            )
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 3D-inspired interactive Cyber Shield Visual rendered natively on Compose Canvas
 * with beveled gradients, specular sweep, and orbital state ring.
 */
@Composable
fun ShieldCoreVisual(
    status: VpnStatus,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryAccent = when (status) {
        VpnStatus.CONNECTED -> Emerald
        VpnStatus.CONNECTING -> IceCyan
        VpnStatus.ERROR -> AlertCrimson
        VpnStatus.DISCONNECTED -> ElectricViolet
    }
    val secondaryAccent = when (status) {
        VpnStatus.CONNECTED -> IceCyan
        VpnStatus.CONNECTING -> ElectricViolet
        VpnStatus.ERROR -> WarmAmber
        VpnStatus.DISCONNECTED -> IceCyan.copy(alpha = 0.65f)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shield_motion")
    val pulseFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (status == VpnStatus.CONNECTING) 1100 else 2800,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (status == VpnStatus.CONNECTING) 2200 else 9000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit"
    )

    val effectivePulse = if (reduceMotion) 0.5f else pulseFraction
    val effectiveAngle = if (reduceMotion) 45f else orbitAngle

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseRadius = size.minDimension * 0.44f

        // Outer ambient halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryAccent.copy(alpha = 0.24f + effectivePulse * 0.14f),
                    secondaryAccent.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = baseRadius * 1.25f
            ),
            radius = baseRadius * 1.25f,
            center = Offset(cx, cy)
        )

        // Orbital ring
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color.Transparent,
                    primaryAccent.copy(alpha = 0.7f),
                    secondaryAccent.copy(alpha = 0.85f),
                    Color.Transparent
                ),
                center = Offset(cx, cy)
            ),
            radius = baseRadius * (0.94f + effectivePulse * 0.03f),
            center = Offset(cx, cy),
            style = Stroke(width = 2.5.dp.toPx())
        )

        // Orbiting telemetry node
        val rad = Math.toRadians(effectiveAngle.toDouble())
        val nodeX = cx + (baseRadius * 0.94f * cos(rad)).toFloat()
        val nodeY = cy + (baseRadius * 0.94f * sin(rad)).toFloat()
        drawCircle(
            color = secondaryAccent,
            radius = 4.dp.toPx(),
            center = Offset(nodeX, nodeY)
        )

        // 3D-inspired Shield Silhouette
        val sw = baseRadius * 1.08f
        val sh = baseRadius * 1.24f
        val shieldPath = Path().apply {
            moveTo(cx, cy - sh * 0.54f)
            lineTo(cx + sw * 0.52f, cy - sh * 0.34f)
            cubicTo(
                cx + sw * 0.54f, cy + sh * 0.08f,
                cx + sw * 0.32f, cy + sh * 0.38f,
                cx, cy + sh * 0.54f
            )
            cubicTo(
                cx - sw * 0.32f, cy + sh * 0.38f,
                cx - sw * 0.54f, cy + sh * 0.08f,
                cx - sw * 0.52f, cy - sh * 0.34f
            )
            close()
        }

        // Shield body depth fill
        drawPath(
            path = shieldPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF1F273C),
                    Color(0xFF0E1320),
                    Color(0xFF171E30)
                ),
                start = Offset(cx - sw * 0.4f, cy - sh * 0.5f),
                end = Offset(cx + sw * 0.4f, cy + sh * 0.5f)
            )
        )

        // Right-half facet lighting for 3D bevel illusion
        val rightFacet = Path().apply {
            moveTo(cx, cy - sh * 0.54f)
            lineTo(cx + sw * 0.52f, cy - sh * 0.34f)
            cubicTo(
                cx + sw * 0.54f, cy + sh * 0.08f,
                cx + sw * 0.32f, cy + sh * 0.38f,
                cx, cy + sh * 0.54f
            )
            close()
        }
        drawPath(
            path = rightFacet,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.09f),
                    Color.Transparent
                )
            )
        )

        // Glowing shield rim
        drawPath(
            path = shieldPath,
            brush = Brush.linearGradient(
                colors = listOf(primaryAccent, secondaryAccent, primaryAccent.copy(alpha = 0.55f)),
                start = Offset(cx - sw * 0.5f, cy - sh * 0.5f),
                end = Offset(cx + sw * 0.5f, cy + sh * 0.5f)
            ),
            style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round)
        )

        // Inner emblem: Pulse Bolt / Lock Core
        val boltPath = Path().apply {
            moveTo(cx + sw * 0.05f, cy - sh * 0.22f)
            lineTo(cx - sw * 0.14f, cy + sh * 0.02f)
            lineTo(cx - sw * 0.01f, cy + sh * 0.02f)
            lineTo(cx - sw * 0.06f, cy + sh * 0.24f)
            lineTo(cx + sw * 0.15f, cy - sh * 0.03f)
            lineTo(cx + sw * 0.01f, cy - sh * 0.03f)
            close()
        }
        drawPath(
            path = boltPath,
            brush = Brush.linearGradient(
                colors = listOf(secondaryAccent, primaryAccent)
            )
        )
        drawPath(
            path = boltPath,
            color = Color.White.copy(alpha = 0.45f),
            style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
