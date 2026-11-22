package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
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

        // Controlled dual-point ambient radial lighting cached per size & accent colors
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val primaryCenter = Offset(size.width * 0.2f, size.height * 0.12f)
                    val primaryRadius = (size.width * 0.85f).coerceAtLeast(1f)
                    val primaryBrush = Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = 0.18f), Color.Transparent),
                        center = primaryCenter,
                        radius = primaryRadius
                    )

                    val secondaryCenter = Offset(size.width * 0.85f, size.height * 0.78f)
                    val secondaryRadius = (size.width * 0.75f).coerceAtLeast(1f)
                    val secondaryBrush = Brush.radialGradient(
                        colors = listOf(secondaryColor.copy(alpha = 0.12f), Color.Transparent),
                        center = secondaryCenter,
                        radius = secondaryRadius
                    )

                    onDrawBehind {
                        drawCircle(
                            brush = primaryBrush,
                            radius = primaryRadius,
                            center = primaryCenter
                        )
                        drawCircle(
                            brush = secondaryBrush,
                            radius = secondaryRadius,
                            center = secondaryCenter
                        )
                    }
                }
        )

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
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val borderStroke = remember(accentBorder, extras.glassBorder) {
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
        BorderStroke(1.dp, borderBrush)
    }

    val containerBrush = remember(extras.glassElevated, extras.glassSurface) {
        Brush.verticalGradient(
            colors = listOf(
                extras.glassElevated.copy(alpha = 0.88f),
                extras.glassSurface.copy(alpha = 0.78f)
            )
        )
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = Color.Transparent,
            border = borderStroke,
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
            border = borderStroke,
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
    val bgColor = remember(color) { color.copy(alpha = 0.14f) }
    val borderColor = remember(color) { color.copy(alpha = 0.35f) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, borderColor, CircleShape)
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
 *
 * Animates only on VPN status transitions (zero idle animations or continuous redraws)
 * and caches vector paths and gradient shaders via drawWithCache for stable 60 FPS.
 */
@Composable
fun ShieldCoreVisual(
    status: VpnStatus,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val targetPrimaryAccent = when (status) {
        VpnStatus.CONNECTED -> Emerald
        VpnStatus.CONNECTING -> IceCyan
        VpnStatus.ERROR -> AlertCrimson
        VpnStatus.DISCONNECTED -> ElectricViolet
    }
    val targetSecondaryAccent = when (status) {
        VpnStatus.CONNECTED -> IceCyan
        VpnStatus.CONNECTING -> ElectricViolet
        VpnStatus.ERROR -> WarmAmber
        VpnStatus.DISCONNECTED -> IceCyan.copy(alpha = 0.65f)
    }
    val targetHaloFraction = when (status) {
        VpnStatus.CONNECTED -> 0.85f
        VpnStatus.CONNECTING -> 0.70f
        VpnStatus.ERROR -> 0.65f
        VpnStatus.DISCONNECTED -> 0.50f
    }
    val targetNodeAngle = when (status) {
        VpnStatus.CONNECTED -> 315f
        VpnStatus.CONNECTING -> 180f
        VpnStatus.ERROR -> 135f
        VpnStatus.DISCONNECTED -> 45f
    }

    val colorSpec = remember(reduceMotion) {
        if (reduceMotion) snap() else tween<Color>(durationMillis = 280, easing = FastOutSlowInEasing)
    }
    val floatSpec = remember(reduceMotion) {
        if (reduceMotion) snap() else tween<Float>(durationMillis = 320, easing = FastOutSlowInEasing)
    }

    val primaryAccentState = animateColorAsState(
        targetValue = targetPrimaryAccent,
        animationSpec = colorSpec,
        label = "shield_primary_accent"
    )
    val secondaryAccentState = animateColorAsState(
        targetValue = targetSecondaryAccent,
        animationSpec = colorSpec,
        label = "shield_secondary_accent"
    )
    val haloFractionState = animateFloatAsState(
        targetValue = targetHaloFraction,
        animationSpec = floatSpec,
        label = "shield_halo_fraction"
    )
    val nodeAngleState = animateFloatAsState(
        targetValue = targetNodeAngle,
        animationSpec = floatSpec,
        label = "shield_node_angle"
    )

    Spacer(
        modifier = modifier.drawWithCache {
            val primaryAccent = primaryAccentState.value
            val secondaryAccent = secondaryAccentState.value
            val haloFraction = haloFractionState.value
            val nodeAngle = nodeAngleState.value

            val cx = size.width / 2f
            val cy = size.height / 2f
            val center = Offset(cx, cy)
            val baseRadius = (size.minDimension * 0.44f).coerceAtLeast(1f)
            val haloRadius = baseRadius * 1.25f
            val ringRadius = baseRadius * (0.94f + haloFraction * 0.03f)

            val haloBrush = Brush.radialGradient(
                colors = listOf(
                    primaryAccent.copy(alpha = 0.24f + haloFraction * 0.14f),
                    secondaryAccent.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = center,
                radius = haloRadius
            )

            val orbitBrush = Brush.sweepGradient(
                colors = listOf(
                    Color.Transparent,
                    primaryAccent.copy(alpha = 0.7f),
                    secondaryAccent.copy(alpha = 0.85f),
                    Color.Transparent
                ),
                center = center
            )
            val orbitStroke = Stroke(width = 2.5.dp.toPx())

            val rad = Math.toRadians(nodeAngle.toDouble())
            val nodeCenter = Offset(
                x = cx + (baseRadius * 0.94f * cos(rad)).toFloat(),
                y = cy + (baseRadius * 0.94f * sin(rad)).toFloat()
            )
            val nodeRadius = 4.dp.toPx()

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

            val bodyBrush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF1F273C),
                    Color(0xFF0E1320),
                    Color(0xFF171E30)
                ),
                start = Offset(cx - sw * 0.4f, cy - sh * 0.5f),
                end = Offset(cx + sw * 0.4f, cy + sh * 0.5f)
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
            val facetBrush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.09f),
                    Color.Transparent
                )
            )

            val rimBrush = Brush.linearGradient(
                colors = listOf(primaryAccent, secondaryAccent, primaryAccent.copy(alpha = 0.55f)),
                start = Offset(cx - sw * 0.5f, cy - sh * 0.5f),
                end = Offset(cx + sw * 0.5f, cy + sh * 0.5f)
            )
            val rimStroke = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round)

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
            val boltBrush = Brush.linearGradient(
                colors = listOf(secondaryAccent, primaryAccent)
            )
            val boltHighlightColor = Color.White.copy(alpha = 0.45f)
            val boltStroke = Stroke(
                width = 1.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )

            onDrawBehind {
                // Outer ambient halo
                drawCircle(
                    brush = haloBrush,
                    radius = haloRadius,
                    center = center
                )

                // Orbital ring
                drawCircle(
                    brush = orbitBrush,
                    radius = ringRadius,
                    center = center,
                    style = orbitStroke
                )

                // Telemetry node on ring
                drawCircle(
                    color = secondaryAccent,
                    radius = nodeRadius,
                    center = nodeCenter
                )

                // Shield body depth fill
                drawPath(path = shieldPath, brush = bodyBrush)

                // Right-half bevel highlight
                drawPath(path = rightFacet, brush = facetBrush)

                // Glowing shield rim
                drawPath(path = shieldPath, brush = rimBrush, style = rimStroke)

                // Inner pulse bolt emblem
                drawPath(path = boltPath, brush = boltBrush)
                drawPath(path = boltPath, color = boltHighlightColor, style = boltStroke)
            }
        }
    )
}
