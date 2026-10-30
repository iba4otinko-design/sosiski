package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.FileCategory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AnimatedOceanShoreHeader(
    isDark: Boolean,
    surfEnabled: Boolean,
    musicEnabled: Boolean,
    isCloudConfigured: Boolean,
    showHiddenFiles: Boolean = false,
    onOpenDrawer: () -> Unit,
    onToggleSurf: () -> Unit,
    onToggleMusic: () -> Unit,
    onToggleShowHiddenFiles: () -> Unit = {},
    onOpenSpaceFromMenu: () -> Unit,
    onOpenSettingsFromMenu: () -> Unit,
    onShareBobroPlyazhApk: () -> Unit = {},
    onQuickToggleTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    var moreMenuExpanded by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "ocean_waves")
    val wavePhase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase_1"
    )
    val wavePhase2 by infiniteTransition.animateFloat(
        initialValue = (2f * PI).toFloat(),
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase_2"
    )

    val deepWater = if (isDark) Color(0xFF0A2A38) else Color(0xFF0B8291)
    val shallowWater = if (isDark) Color(0xFF154E5E) else Color(0xFF38B6C4)
    val foamColor = if (isDark) Color(0xFF7CE8F5).copy(alpha = 0.42f) else Color.White.copy(alpha = 0.65f)
    val sandShoreColor = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(deepWater, shallowWater, sandShoreColor)
                )
            )
    ) {
        val backWavePath = remember { Path() }
        val frontWavePath = remember { Path() }
        val crestPath = remember { Path() }

        Canvas(
            modifier = Modifier.matchParentSize()
        ) {
            val w = size.width
            val h = size.height

            drawCircle(
                color = Color.White.copy(alpha = if (isDark) 0.08f else 0.16f),
                radius = h * 0.42f,
                center = Offset(w * 0.84f, h * 0.24f)
            )

            backWavePath.reset()
            backWavePath.moveTo(0f, h)
            var x = 0f
            val backStep = 16f
            while (x <= w + backStep) {
                val y = h * 0.68f + sin(x / w * 2.5f * PI + wavePhase2).toFloat() * 11f
                backWavePath.lineTo(x, y)
                x += backStep
            }
            backWavePath.lineTo(w, h)
            backWavePath.close()
            drawPath(path = backWavePath, color = foamColor.copy(alpha = 0.28f))

            frontWavePath.reset()
            frontWavePath.moveTo(0f, h)
            x = 0f
            val frontStep = 14f
            while (x <= w + frontStep) {
                val y = h * 0.78f +
                    sin(x / w * 3.2f * PI + wavePhase1).toFloat() * 9f +
                    cos(x / w * 1.6f * PI - wavePhase2).toFloat() * 5f
                frontWavePath.lineTo(x, y)
                x += frontStep
            }
            frontWavePath.lineTo(w, h)
            frontWavePath.close()
            drawPath(path = frontWavePath, color = sandShoreColor)

            crestPath.reset()
            x = 0f
            var first = true
            while (x <= w + frontStep) {
                val y = h * 0.78f +
                    sin(x / w * 3.2f * PI + wavePhase1).toFloat() * 9f +
                    cos(x / w * 1.6f * PI - wavePhase2).toFloat() * 5f - 3f
                if (first) {
                    crestPath.moveTo(x, y)
                    first = false
                } else {
                    crestPath.lineTo(x, y)
                }
                x += frontStep
            }
            drawPath(
                path = crestPath,
                color = foamColor,
                style = Stroke(width = 5f, cap = StrokeCap.Round)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .testTag("header_drawer_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Menu,
                        contentDescription = stringResource(R.string.cd_open_drawer),
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = Color.White.copy(alpha = 0.20f),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("cloud_status_pill")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isCloudConfigured) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(
                                        if (isCloudConfigured) R.string.label_cloud_synced else R.string.label_offline_ready
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.app_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Three-dots (...) overflow button replacing the old theme button
                Box {
                    IconButton(
                        onClick = { moreMenuExpanded = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .testTag("header_more_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreHoriz,
                            contentDescription = stringResource(R.string.cd_more_menu),
                            tint = Color.White
                        )
                    }

                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_item_space)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.DataUsage, contentDescription = null)
                            },
                            onClick = {
                                moreMenuExpanded = false
                                onOpenSpaceFromMenu()
                            },
                            modifier = Modifier.testTag("more_menu_space_item")
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_item_settings)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Settings, contentDescription = null)
                            },
                            onClick = {
                                moreMenuExpanded = false
                                onOpenSettingsFromMenu()
                            },
                            modifier = Modifier.testTag("more_menu_settings_item")
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_share_bobroplyazh_apk)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Share, contentDescription = null)
                            },
                            onClick = {
                                moreMenuExpanded = false
                                onShareBobroPlyazhApk()
                            },
                            modifier = Modifier.testTag("more_menu_share_self_apk_item")
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (showHiddenFiles) {
                                            R.string.menu_hide_hidden_files
                                        } else {
                                            R.string.menu_show_hidden_files
                                        }
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (showHiddenFiles) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                moreMenuExpanded = false
                                onToggleShowHiddenFiles()
                            },
                            modifier = Modifier.testTag("more_menu_hidden_files_item")
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (isDark) R.string.menu_item_theme_light else R.string.menu_item_theme_dark
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                moreMenuExpanded = false
                                onQuickToggleTheme()
                            },
                            modifier = Modifier.testTag("more_menu_theme_item")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = surfEnabled,
                    onClick = onToggleSurf,
                    label = { Text(stringResource(R.string.label_surf_short)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Waves,
                            contentDescription = stringResource(R.string.cd_surf_toggle),
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.White.copy(alpha = 0.18f),
                        labelColor = Color.White,
                        iconColor = Color.White,
                        selectedContainerColor = Color.White,
                        selectedLabelColor = Color(0xFF075963),
                        selectedLeadingIconColor = Color(0xFF075963)
                    ),
                    border = null,
                    modifier = Modifier.testTag("quick_surf_chip")
                )

                FilterChip(
                    selected = musicEnabled,
                    onClick = onToggleMusic,
                    label = { Text(stringResource(R.string.label_music_short)) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (musicEnabled) Icons.Outlined.MusicNote else Icons.Outlined.MusicOff,
                            contentDescription = stringResource(R.string.cd_music_toggle),
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.White.copy(alpha = 0.18f),
                        labelColor = Color.White,
                        iconColor = Color.White,
                        selectedContainerColor = Color.White,
                        selectedLabelColor = Color(0xFF075963),
                        selectedLeadingIconColor = Color(0xFF075963)
                    ),
                    border = null,
                    modifier = Modifier.testTag("quick_music_chip")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private val SAND_GRAIN_OFFSETS = arrayOf(
    Offset(0.20f, 0.22f),
    Offset(0.78f, 0.25f),
    Offset(0.18f, 0.72f),
    Offset(0.80f, 0.74f),
    Offset(0.50f, 0.82f),
    Offset(0.32f, 0.16f)
)

private val SHELL_RAY_ANGLES = floatArrayOf(-0.42f, -0.15f, 0.15f, 0.42f)

/**
 * Custom sculpted sand icon ("пісочна іконка") rendered via Canvas for each file category.
 */
@Composable
fun SandyFileIcon(
    category: FileCategory,
    isStarred: Boolean = false,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminanceApprox() < 0.35f
    val sandBase = if (isDark) Color(0xFF3E342A) else Color(0xFFEBD7B2)
    val sandHighlight = if (isDark) Color(0xFF594B3C) else Color(0xFFF9EED8)
    val sandShadow = if (isDark) Color(0xFF241E18) else Color(0xFFC9AE82)

    val accentColor = when (category) {
        FileCategory.FOLDER -> if (isDark) Color(0xFFF0B87A) else Color(0xFF9C5D2D)
        FileCategory.IMAGES -> if (isDark) Color(0xFFFFB09C) else Color(0xFFD16047)
        FileCategory.DOCUMENTS -> if (isDark) Color(0xFF7CDCE8) else Color(0xFF0D7D8C)
        FileCategory.AUDIO -> if (isDark) Color(0xFFFFCF70) else Color(0xFFC27D1A)
        FileCategory.VIDEO -> if (isDark) Color(0xFFE899DC) else Color(0xFF9B3E8E)
        FileCategory.ARCHIVES_ANDROID -> if (isDark) Color(0xFF81E6B8) else Color(0xFF1F8A5B)
        FileCategory.OTHER -> if (isDark) Color(0xFFA8C4D4) else Color(0xFF4F6B7A)
    }

    val glyphPath = remember { Path() }

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        drawRoundRect(
            color = sandShadow,
            topLeft = Offset(w * 0.06f, h * 0.10f),
            size = Size(w * 0.88f, h * 0.86f),
            cornerRadius = CornerRadius(w * 0.32f, w * 0.32f)
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(sandHighlight, sandBase),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            ),
            topLeft = Offset(w * 0.06f, h * 0.06f),
            size = Size(w * 0.88f, h * 0.84f),
            cornerRadius = CornerRadius(w * 0.32f, w * 0.32f)
        )

        for (pt in SAND_GRAIN_OFFSETS) {
            drawCircle(
                color = sandShadow.copy(alpha = 0.55f),
                radius = w * 0.018f,
                center = Offset(w * pt.x, h * pt.y)
            )
        }

        drawSculptedCategoryGlyph(category, accentColor, sandHighlight, glyphPath)

        if (isStarred) {
            drawCircle(
                color = Color(0xFFF5B400),
                radius = w * 0.12f,
                center = Offset(w * 0.84f, h * 0.18f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.75f),
                radius = w * 0.045f,
                center = Offset(w * 0.82f, h * 0.16f)
            )
        }
    }
}

private fun DrawScope.drawSculptedCategoryGlyph(
    category: FileCategory,
    accent: Color,
    innerLight: Color,
    reusablePath: Path
) {
    val w = size.width
    val h = size.height
    when (category) {
        FileCategory.FOLDER -> {
            drawRoundRect(
                color = accent,
                topLeft = Offset(w * 0.24f, h * 0.42f),
                size = Size(w * 0.52f, h * 0.32f),
                cornerRadius = CornerRadius(w * 0.08f, w * 0.08f)
            )
            for (i in 0..2) {
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(w * (0.25f + i * 0.18f), h * 0.32f),
                    size = Size(w * 0.13f, h * 0.14f),
                    cornerRadius = CornerRadius(w * 0.03f, w * 0.03f)
                )
            }
            drawRoundRect(
                color = innerLight,
                topLeft = Offset(w * 0.43f, h * 0.54f),
                size = Size(w * 0.14f, h * 0.20f),
                cornerRadius = CornerRadius(w * 0.07f, w * 0.07f)
            )
            drawLine(
                color = accent,
                start = Offset(w * 0.50f, h * 0.32f),
                end = Offset(w * 0.50f, h * 0.19f),
                strokeWidth = w * 0.035f,
                cap = StrokeCap.Round
            )
            reusablePath.reset()
            reusablePath.moveTo(w * 0.50f, h * 0.19f)
            reusablePath.lineTo(w * 0.66f, h * 0.24f)
            reusablePath.lineTo(w * 0.50f, h * 0.28f)
            reusablePath.close()
            drawPath(reusablePath, color = Color(0xFF159AA8))
        }

        FileCategory.IMAGES -> {
            reusablePath.reset()
            reusablePath.moveTo(w * 0.50f, h * 0.72f)
            reusablePath.cubicTo(w * 0.22f, h * 0.64f, w * 0.20f, h * 0.30f, w * 0.50f, h * 0.26f)
            reusablePath.cubicTo(w * 0.80f, h * 0.30f, w * 0.78f, h * 0.64f, w * 0.50f, h * 0.72f)
            reusablePath.close()
            drawPath(reusablePath, color = accent)
            for (angle in SHELL_RAY_ANGLES) {
                drawLine(
                    color = innerLight.copy(alpha = 0.75f),
                    start = Offset(w * 0.50f, h * 0.68f),
                    end = Offset(w * (0.50f + angle * 0.5f), h * 0.35f),
                    strokeWidth = w * 0.03f,
                    cap = StrokeCap.Round
                )
            }
        }

        FileCategory.DOCUMENTS -> {
            drawRoundRect(
                color = accent,
                topLeft = Offset(w * 0.30f, h * 0.26f),
                size = Size(w * 0.40f, h * 0.46f),
                cornerRadius = CornerRadius(w * 0.08f, w * 0.08f)
            )
            for (row in 0..2) {
                val y = h * (0.37f + row * 0.11f)
                drawLine(
                    color = innerLight,
                    start = Offset(w * 0.37f, y),
                    end = Offset(w * 0.63f, y),
                    strokeWidth = w * 0.04f,
                    cap = StrokeCap.Round
                )
            }
        }

        FileCategory.AUDIO -> {
            reusablePath.reset()
            val cx = w * 0.50f
            val cy = h * 0.50f
            val outerR = w * 0.27f
            val innerR = w * 0.12f
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) outerR else innerR
                val rad = (i * 36.0 - 90.0) * PI / 180.0
                val px = cx + (r * cos(rad)).toFloat()
                val py = cy + (r * sin(rad)).toFloat()
                if (i == 0) reusablePath.moveTo(px, py) else reusablePath.lineTo(px, py)
            }
            reusablePath.close()
            drawPath(reusablePath, color = accent)
            drawCircle(color = innerLight, radius = w * 0.045f, center = Offset(cx, cy))
        }

        FileCategory.VIDEO -> {
            drawRoundRect(
                color = accent,
                topLeft = Offset(w * 0.25f, h * 0.30f),
                size = Size(w * 0.50f, h * 0.38f),
                cornerRadius = CornerRadius(w * 0.10f, w * 0.10f)
            )
            reusablePath.reset()
            reusablePath.moveTo(w * 0.44f, h * 0.39f)
            reusablePath.lineTo(w * 0.60f, h * 0.49f)
            reusablePath.lineTo(w * 0.44f, h * 0.59f)
            reusablePath.close()
            drawPath(reusablePath, color = innerLight)
        }

        FileCategory.ARCHIVES_ANDROID -> {
            drawRoundRect(
                color = accent,
                topLeft = Offset(w * 0.33f, h * 0.36f),
                size = Size(w * 0.34f, h * 0.38f),
                cornerRadius = CornerRadius(w * 0.10f, w * 0.10f)
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(w * 0.42f, h * 0.24f),
                size = Size(w * 0.16f, h * 0.14f),
                cornerRadius = CornerRadius(w * 0.04f, w * 0.04f)
            )
            drawLine(
                color = innerLight,
                start = Offset(w * 0.42f, h * 0.54f),
                end = Offset(w * 0.58f, h * 0.54f),
                strokeWidth = w * 0.045f,
                cap = StrokeCap.Round
            )
        }

        FileCategory.OTHER -> {
            drawCircle(
                color = accent,
                radius = w * 0.22f,
                center = Offset(w * 0.50f, h * 0.49f)
            )
            drawCircle(
                color = innerLight.copy(alpha = 0.6f),
                radius = w * 0.07f,
                center = Offset(w * 0.43f, h * 0.42f)
            )
        }
    }
}

@Composable
fun TideWashOverlay(
    trigger: Int,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            progress.snapTo(0.01f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 950, easing = FastOutSlowInEasing)
            )
            progress.snapTo(0f)
        }
    }

    val p = progress.value
    if (p > 0f) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val sweepY = if (p <= 0.5f) {
                size.height * (p * 2f)
            } else {
                size.height * ((1f - p) * 2f)
            }
            val wavePath = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                var x = size.width
                while (x >= 0f) {
                    val y = sweepY + sin(x / size.width * 4f * PI + p * 8f).toFloat() * 18f
                    lineTo(x, y)
                    x -= 20f
                }
                close()
            }
            drawPath(
                path = wavePath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1298A8).copy(alpha = 0.38f),
                        Color(0xFF7BE8F2).copy(alpha = 0.52f)
                    )
                )
            )
        }
    }
}

private fun Color.luminanceApprox(): Float {
    return 0.299f * red + 0.587f * green + 0.114f * blue
}

@Composable
fun AllFilesAccessBannerCard(
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("all_files_access_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderSpecial,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.all_files_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.all_files_banner_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
                )
            }
            Button(
                onClick = onGrantClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("grant_all_files_access_btn")
            ) {
                Text(text = stringResource(R.string.all_files_grant_btn))
            }
        }
    }
}

