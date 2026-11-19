package `fun`.kirari.hanako.feature.home.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.core.model.ProcessingRoute
import `fun`.kirari.hanako.core.data.ScreenCaptureMethod

@Composable
fun HeroSection(
    overlayEnabled: Boolean,
    hasOverlayPermission: Boolean,
    captureMethod: ScreenCaptureMethod,
    staticModeEnabled: Boolean,
    route: ProcessingRoute,
    onSelectRoute: (ProcessingRoute) -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onStartAutoMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            HeroHeader(overlayEnabled = overlayEnabled)
            ProcessingRouteSelector(route = route, onSelectRoute = onSelectRoute)
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))
            HeroActions(
                overlayEnabled = overlayEnabled,
                hasOverlayPermission = hasOverlayPermission,
                captureMethod = captureMethod,
                staticModeEnabled = staticModeEnabled,
                onOpenOverlayPermission = onOpenOverlayPermission,
                onToggleOverlay = onToggleOverlay,
                onStartAutoMode = onStartAutoMode
            )
        }
    }
}

@Composable
private fun HeroHeader(overlayEnabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f),
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Column {
                Text(
                    "HanakoAI-EX",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "屏幕智能助理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
            }
        }

        val statusContainerColor = if (overlayEnabled) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f)
        }
        val statusDotColor = if (overlayEnabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        }
        Surface(
            shape = CircleShape,
            color = statusContainerColor,
            border = if (overlayEnabled) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            } else {
                null
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Text(
                    if (overlayEnabled) "运行中" else "已停止",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun ProcessingRouteSelector(
    route: ProcessingRoute,
    onSelectRoute: (ProcessingRoute) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "工作模式",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ProcessingRoute.entries.forEach { item ->
                val isSelected = route == item
                val (title, subtitle, icon) = when (item) {
                    ProcessingRoute.OCR_THEN_LLM -> Triple("OCR 模式", "提取文字后分析", Icons.Default.Language)
                    ProcessingRoute.MULTIMODAL_DIRECT -> Triple("多模态视觉", "直接分析全屏幕", Icons.Default.AutoAwesome)
                    ProcessingRoute.AUDIO_AND_SCREEN -> Triple("语音+画面", "录音结合截图", Icons.Default.Mic)
                }
                ModeSelectorItem(
                    modifier = Modifier.weight(1f),
                    title = title,
                    subtitle = subtitle,
                    icon = icon,
                    isSelected = isSelected,
                    onClick = { onSelectRoute(item) }
                )
            }
        }
    }
}

@Composable
private fun HeroActions(
    overlayEnabled: Boolean,
    hasOverlayPermission: Boolean,
    captureMethod: ScreenCaptureMethod,
    staticModeEnabled: Boolean,
    onOpenOverlayPermission: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onStartAutoMode: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onOpenOverlayPermission,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text("权限设置", fontWeight = FontWeight.Medium)
            }

            val buttonColor = if (overlayEnabled) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            }
            val buttonTextColor = if (overlayEnabled) {
                MaterialTheme.colorScheme.onError
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        enabled = hasOverlayPermission,
                        onClick = {
                            if (overlayEnabled) {
                                onToggleOverlay(false)
                            } else if (staticModeEnabled) {
                                onStartAutoMode()
                            } else {
                                onToggleOverlay(true)
                            }
                        },
                        onLongClick = {
                            if (!overlayEnabled && hasOverlayPermission && !staticModeEnabled) {
                                onStartAutoMode()
                            }
                        }
                    ),
                shape = RoundedCornerShape(16.dp),
                color = buttonColor,
                contentColor = buttonTextColor
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (overlayEnabled) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (overlayEnabled) "关闭" else "启动", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (hasOverlayPermission && !overlayEnabled) {
            StartGestureHint()
        }

        HeroHint(
            hasOverlayPermission = hasOverlayPermission,
            captureMethod = captureMethod,
            overlayEnabled = overlayEnabled,
            staticModeEnabled = staticModeEnabled
        )
    }
}

/**
 * 启动手势提示。
 *
 * 同一个按钮两种行为：短按进普通模式（作答会弹面板），长按进自动模式（不弹面板，
 * 答案直接落在悬浮球上或复制到剪贴板）。这两个行为差别很大，光靠一行小字说明不够，
 * 所以在按钮正下方单独给一条提示。
 */
@Composable
private fun StartGestureHint() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            GestureHintItem(action = "短按", result = "弹窗作答")
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            )
            GestureHintItem(action = "长按", result = "自动模式 · 不弹窗")
        }
    }
}

@Composable
private fun GestureHintItem(action: String, result: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = action,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = result,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun HeroHint(
    hasOverlayPermission: Boolean,
    captureMethod: ScreenCaptureMethod,
    overlayEnabled: Boolean,
    staticModeEnabled: Boolean
) {
    if (!hasOverlayPermission) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "核心提示：请先开启悬浮窗权限以激活功能",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        return
    }

    val hint = when {
        captureMethod == ScreenCaptureMethod.MEDIA_PROJECTION &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !overlayEnabled -> {
            if (staticModeEnabled) {
                "提示：静态模式已开启，点击启动直接进入自动模式（不弹窗）；悬浮球双击展开快捷菜单；Android 14+ 首次会弹出截屏授权。"
            } else {
                "提示：短按启动进入普通模式（作答弹窗），长按启动进入自动模式（不弹窗）；悬浮球双击展开快捷菜单；Android 14+ 首次会弹出截屏授权。"
            }
        }
        captureMethod == ScreenCaptureMethod.SHIZUKU_ADB && !overlayEnabled ->
            "提示：当前使用 Shizuku 路线。启动时会先申请 Shizuku 授权，后续截图走 shell screencap；悬浮球双击展开快捷菜单。"
        !overlayEnabled -> {
            if (staticModeEnabled) {
                "静态模式已开启，点击启动直接进入自动模式（不弹窗）；悬浮球双击展开快捷菜单。"
            } else {
                "短按启动进入普通模式（作答弹窗），长按启动进入自动模式（不弹窗）；悬浮球双击展开快捷菜单。"
            }
        }
        else -> null
    }

    hint?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun ModeSelectorItem(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f)
        },
        label = "containerColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        label = "contentColor"
    )
    val borderDp by animateDpAsState(
        targetValue = if (isSelected) 0.dp else 1.dp,
        label = "borderDp"
    )

    Surface(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = if (borderDp > 0.dp) {
            BorderStroke(borderDp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))
        } else {
            null
        }
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            Column(
                modifier = Modifier.align(Alignment.TopStart),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = if (isSelected) 1f else 0.8f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.65f),
                    maxLines = 1
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.TopEnd)
                )
            }
        }
    }
}
