package `fun`.kirari.hanako.feature.settings.ui.automation

import `fun`.kirari.hanako.feature.settings.ui.settings.SliderSettingRow

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `fun`.kirari.hanako.core.data.BubbleAppearanceSettings
import `fun`.kirari.hanako.core.data.MAX_BUBBLE_DIAMETER_DP
import `fun`.kirari.hanako.core.data.MAX_BUBBLE_LETTER_TEXT_SIZE_DP
import `fun`.kirari.hanako.core.data.MAX_COUNTDOWN_TEXT_SIZE_DP
import `fun`.kirari.hanako.core.data.MAX_SPINNER_DIAMETER_DP
import `fun`.kirari.hanako.core.data.MIN_BUBBLE_DIAMETER_DP
import `fun`.kirari.hanako.core.data.MIN_BUBBLE_LETTER_TEXT_SIZE_DP
import `fun`.kirari.hanako.core.data.MIN_COUNTDOWN_TEXT_SIZE_DP
import `fun`.kirari.hanako.core.data.MIN_SPINNER_DIAMETER_DP
import kotlinx.coroutines.delay

@Composable
internal fun BubbleAppearanceResetButton(
    onReset: () -> Unit
) {
    OutlinedButton(onClick = onReset) {
        Text("重置")
    }
}

/** 悬浮窗外观面板里当前正在编辑哪一颗球。 */
private enum class AppearanceSlot(val label: String, val role: String) {
    PRIMARY("悬浮窗 1", "纯视觉"),
    SECONDARY("悬浮窗 2", "语音+画面")
}

/**
 * 双悬浮窗外观面板。
 *
 * 两颗球各自独立的一份 [BubbleAppearanceSettings]，这里只做切换，实际编辑交给
 * [BubbleAppearanceSettingsCard]，避免把同一套滑块写两遍。
 */
@Composable
internal fun MultiBubbleAppearancePanel(
    primary: BubbleAppearanceSettings,
    secondary: BubbleAppearanceSettings,
    multiBubbleEnabled: Boolean,
    onPrimaryChange: (BubbleAppearanceSettings) -> Unit,
    onSecondaryChange: (BubbleAppearanceSettings) -> Unit
) {
    var slot by remember { mutableStateOf(AppearanceSlot.PRIMARY) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppearanceSlot.entries.forEach { entry ->
                SlotChip(
                    modifier = Modifier.weight(1f),
                    slot = entry,
                    selected = slot == entry,
                    onClick = { slot = entry }
                )
            }
        }
        if (slot == AppearanceSlot.SECONDARY && !multiBubbleEnabled) {
            Text(
                text = "「多悬浮窗」关闭时不会显示悬浮窗 2，这里的设置会在开启后生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val settings = if (slot == AppearanceSlot.PRIMARY) primary else secondary
        // key(slot)：切换球时重建编辑区，别让上一颗球还在 180ms 防抖里的草稿写到另一颗球上
        key(slot) {
            BubbleAppearanceSettingsCard(
                settings = settings,
                onChange = if (slot == AppearanceSlot.PRIMARY) onPrimaryChange else onSecondaryChange
            )
        }
    }
}

@Composable
private fun SlotChip(
    modifier: Modifier = Modifier,
    slot: AppearanceSlot,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = slot.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = slot.role,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
internal fun BubbleAppearanceSettingsCard(
    settings: BubbleAppearanceSettings,
    onChange: (BubbleAppearanceSettings) -> Unit
) {
    var draftSettings by remember(settings) { mutableStateOf(settings) }

    LaunchedEffect(draftSettings) {
        if (draftSettings != settings) {
            delay(180)
            onChange(draftSettings)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BubblePreview(settings = draftSettings)
        SliderSettingRow(
            title = "悬浮球直径",
            value = draftSettings.bubbleDiameterDp,
            range = MIN_BUBBLE_DIAMETER_DP..MAX_BUBBLE_DIAMETER_DP,
            valueText = draftSettings.bubbleDiameterDp.toInt().toString(),
            onValueChange = { draftSettings = draftSettings.copy(bubbleDiameterDp = it) },
            onValueChangeFinished = { onChange(draftSettings) }
        )
        SliderSettingRow(
            title = "加载动画直径",
            value = draftSettings.spinnerDiameterDp,
            range = MIN_SPINNER_DIAMETER_DP..MAX_SPINNER_DIAMETER_DP,
            valueText = draftSettings.spinnerDiameterDp.toInt().toString(),
            onValueChange = { draftSettings = draftSettings.copy(spinnerDiameterDp = it) },
            onValueChangeFinished = { onChange(draftSettings) }
        )
        SliderSettingRow(
            title = "字母大小",
            value = draftSettings.letterTextSizeDp,
            range = MIN_BUBBLE_LETTER_TEXT_SIZE_DP..MAX_BUBBLE_LETTER_TEXT_SIZE_DP,
            valueText = draftSettings.letterTextSizeDp.toInt().toString(),
            onValueChange = { draftSettings = draftSettings.copy(letterTextSizeDp = it) },
            onValueChangeFinished = { onChange(draftSettings) }
        )
        SliderSettingRow(
            title = "倒计时字号",
            value = draftSettings.countdownTextSizeDp,
            range = MIN_COUNTDOWN_TEXT_SIZE_DP..MAX_COUNTDOWN_TEXT_SIZE_DP,
            valueText = draftSettings.countdownTextSizeDp.toInt().toString(),
            onValueChange = { draftSettings = draftSettings.copy(countdownTextSizeDp = it) },
            onValueChangeFinished = { onChange(draftSettings) }
        )
        SliderSettingRow(
            title = "字母透明度",
            value = draftSettings.letterOpacity,
            range = 0f..100f,
            valueText = draftSettings.letterOpacity.toInt().toString(),
            onValueChange = { draftSettings = draftSettings.copy(letterOpacity = it) },
            onValueChangeFinished = { onChange(draftSettings) }
        )
        SliderSettingRow(
            title = "悬浮球透明度",
            value = draftSettings.overallOpacity,
            range = 0f..100f,
            valueText = draftSettings.overallOpacity.toInt().toString(),
            onValueChange = { draftSettings = draftSettings.copy(overallOpacity = it) },
            onValueChangeFinished = { onChange(draftSettings) }
        )
        ColorSection(
            settings = draftSettings,
            onChange = {
                draftSettings = it
                onChange(it)
            }
        )
    }
}

/**
 * 配色区。
 *
 * 默认关闭自定义配色，此时球体颜色跟着状态走（错误红、内录蓝、麦克风红…）。
 * 打开后才用下面三个颜色统一覆盖，代价是不再区分状态。
 */
@Composable
private fun ColorSection(
    settings: BubbleAppearanceSettings,
    onChange: (BubbleAppearanceSettings) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "自定义配色",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (settings.customColorsEnabled) {
                        "球体、图标与字母都按下面三个颜色显示。"
                    } else {
                        "关闭时跟随状态变色（答题蓝、错误红、麦克风红…）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.customColorsEnabled,
                onCheckedChange = { onChange(settings.copy(customColorsEnabled = it)) }
            )
        }
        if (settings.customColorsEnabled) {
            ColorSwatchRow(
                title = "球体颜色",
                selected = settings.backgroundColor,
                presets = BACKGROUND_PRESETS
            ) { onChange(settings.copy(backgroundColor = it)) }
            ColorSwatchRow(
                title = "字体颜色",
                selected = settings.contentColor,
                presets = CONTENT_PRESETS
            ) { onChange(settings.copy(contentColor = it)) }
            ColorSwatchRow(
                title = "描边颜色",
                selected = settings.borderColor,
                presets = BORDER_PRESETS
            ) { onChange(settings.copy(borderColor = it)) }
        }
    }
}

@Composable
private fun ColorSwatchRow(
    title: String,
    selected: Int,
    presets: List<Int>,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets) { color ->
                val isSelected = color == selected
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color(color))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = RoundedCornerShape(percent = 50)
                        )
                        .clickable { onSelect(color) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = contrastOn(color),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 选中的勾要压在色块上看得到，深色底配白勾、浅色底配黑勾。 */
private fun contrastOn(color: Int): Color {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (luminance > 0.6) Color.Black else Color.White
}

private val BACKGROUND_PRESETS = listOf(
    0xFFD0BCFF.toInt(),
    0xFFEADDFF.toInt(),
    0xFFFFD8E4.toInt(),
    0xFFFFDAD6.toInt(),
    0xFFD3E3FD.toInt(),
    0xFFB5EAD7.toInt(),
    0xFFFFE0B2.toInt(),
    0xFFFFFFFF.toInt(),
    0xFF263238.toInt(),
    0xFF000000.toInt()
)

private val CONTENT_PRESETS = listOf(
    0xFF381E72.toInt(),
    0xFF4F378B.toInt(),
    0xFF0B57D0.toInt(),
    0xFF8C1D18.toInt(),
    0xFFBA1A1A.toInt(),
    0xFF1B5E20.toInt(),
    0xFFFFFFFF.toInt(),
    0xFF000000.toInt()
)

private val BORDER_PRESETS = listOf(
    0xFFFFFFFF.toInt(),
    0xFF000000.toInt(),
    0xFF6750A4.toInt(),
    0xFF0B57D0.toInt(),
    0xFFBA1A1A.toInt(),
    0x00FFFFFF
)

@Composable
private fun BubblePreview(settings: BubbleAppearanceSettings) {
    var previewState by remember { mutableStateOf(BubblePreviewState.Idle) }
    val bubbleDiameter = settings.bubbleDiameterDp.dp
    val spinnerDiameter = settings.spinnerDiameterDp.dp
    val overallAlpha = (settings.overallOpacity / 100f).coerceIn(0f, 1f)
    val letterAlpha = (settings.letterOpacity / 100f).coerceIn(0f, 1f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "预览",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BubblePreviewCanvas(
                    modifier = Modifier.weight(1f),
                    settings = settings,
                    previewState = previewState,
                    bubbleDiameter = bubbleDiameter,
                    spinnerDiameter = spinnerDiameter,
                    overallAlpha = overallAlpha,
                    letterAlpha = letterAlpha
                )
                BubblePreviewStateSelector(
                    selectedState = previewState,
                    onSelectState = { previewState = it }
                )
            }
        }
    }
}

@Composable
private fun BubblePreviewCanvas(
    modifier: Modifier,
    settings: BubbleAppearanceSettings,
    previewState: BubblePreviewState,
    bubbleDiameter: Dp,
    spinnerDiameter: Dp,
    overallAlpha: Float,
    letterAlpha: Float
) {
    val bubbleColor = if (settings.customColorsEnabled) {
        Color(settings.backgroundColor)
    } else {
        Color(0xFFEADDFF)
    }
    val contentColor = if (settings.customColorsEnabled) {
        Color(settings.contentColor)
    } else {
        Color(0xFF4F378B)
    }
    val borderColor = if (settings.customColorsEnabled) {
        Color(settings.borderColor)
    } else {
        Color.White
    }
    Box(
        modifier = modifier
            .height(156.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        if (previewState == BubblePreviewState.Loading) {
            SpinnerRing(
                size = spinnerDiameter,
                color = contentColor,
                alpha = overallAlpha
            )
        }
        if (previewState != BubblePreviewState.Countdown) {
            BubbleCircle(
                size = bubbleDiameter,
                alpha = overallAlpha,
                shape = RoundedCornerShape(percent = 50),
                color = bubbleColor,
                borderColor = borderColor
            ) {
                if (previewState == BubblePreviewState.Idle || previewState == BubblePreviewState.Loading) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size((settings.bubbleDiameterDp * 0.45f).dp)
                    )
                }
            }
        }
        if (previewState == BubblePreviewState.Letters) {
            Text(
                text = "AB",
                modifier = Modifier.alpha(letterAlpha),
                color = contentColor,
                fontSize = settings.letterTextSizeDp.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = settings.letterTextSizeDp.sp
            )
        }
        if (previewState == BubblePreviewState.Countdown) {
            // 倒计时用胶囊 + 独立字号，和真实录音状态一致
            Surface(
                modifier = Modifier.alpha(overallAlpha),
                shape = RoundedCornerShape(percent = 50),
                color = bubbleColor,
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
            ) {
                Text(
                    text = "1:23",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = contentColor,
                    fontSize = settings.countdownTextSizeDp.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BubblePreviewStateSelector(
    selectedState: BubblePreviewState,
    onSelectState: (BubblePreviewState) -> Unit
) {
    Surface(
        modifier = Modifier
            .width(112.dp)
            .height(156.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "状态切换",
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            BubblePreviewState.entries.forEach { state ->
                BubblePreviewStateItem(
                    state = state,
                    selected = selectedState == state,
                    onClick = { onSelectState(state) }
                )
            }
        }
    }
}

@Composable
private fun BubblePreviewStateItem(
    state: BubblePreviewState,
    selected: Boolean,
    onClick: () -> Unit
) {
    val itemShape = RoundedCornerShape(10.dp)
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            .clip(itemShape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                )
        )
        Text(
            text = state.label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1
        )
    }
}

private enum class BubblePreviewState(val label: String) {
    Idle("闲置"),
    Loading("加载"),
    Letters("字母"),
    Countdown("倒计时")
}

@Composable
private fun SpinnerRing(
    size: Dp,
    color: Color,
    alpha: Float
) {
    val transition = rememberInfiniteTransition(label = "bubble_preview_spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bubble_preview_spinner_rotation"
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation - 90f }
            .alpha(alpha),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.fillMaxSize(),
            progress = { 0.72f },
            color = color,
            strokeWidth = 3.dp,
            trackColor = Color.Transparent
        )
    }
}

@Composable
private fun BubbleCircle(
    size: Dp,
    alpha: Float,
    shape: Shape,
    color: Color,
    borderColor: Color,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .size(size)
            .alpha(alpha),
        shape = shape,
        color = color,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}
