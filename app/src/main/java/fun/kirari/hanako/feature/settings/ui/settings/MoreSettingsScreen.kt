package `fun`.kirari.hanako.feature.settings.ui.settings

import `fun`.kirari.hanako.feature.settings.ui.automation.AutoModeSettingsCard
import `fun`.kirari.hanako.feature.settings.ui.automation.BubbleAppearanceResetButton
import `fun`.kirari.hanako.feature.settings.ui.automation.BubbleAppearanceSettingsCard
import `fun`.kirari.hanako.feature.settings.ui.automation.BubbleMenuSettingsCard

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import `fun`.kirari.hanako.BuildConfig
import `fun`.kirari.hanako.core.data.AutomationSettings
import `fun`.kirari.hanako.core.data.BubbleAppearanceSettings
import `fun`.kirari.hanako.core.data.KirariSettings
import `fun`.kirari.hanako.core.data.ScreenCaptureMethod
import `fun`.kirari.hanako.feature.home.presentation.RegisterScrollToTopHandler
import `fun`.kirari.hanako.feature.overlay.window.OverlayWindowHost
import kotlinx.coroutines.launch

@Composable
fun MoreSettingsScreen(
    scrollRoute: String,
    automationSettings: AutomationSettings,
    selectedMethod: ScreenCaptureMethod,
    trustAllHttpsCertificates: Boolean,
    kirariSettings: KirariSettings,
    hasKirariClientId: Boolean,
    hasNotificationPermission: Boolean,
    onToggleCompletionNotification: (Boolean) -> Unit,
    onOpenNotificationPermission: () -> Unit,
    onToggleStaticMode: (Boolean) -> Unit,
    onNavigateStaticVibrationSettings: () -> Unit,
    onUpdateAutomationSettings: (AutomationSettings) -> Unit,
    onUpdateTimeoutSeconds: (Int) -> Unit,
    onSelectMethod: (ScreenCaptureMethod) -> Unit,
    onToggleTrustAllHttpsCertificates: (Boolean) -> Unit,
    onUpdateKirariServerUrl: (String) -> Unit,
    onLoginKirari: () -> Unit,
    onLogoutKirari: () -> Unit
) {
    var timeoutInput by remember(automationSettings.autoModeTimeoutSeconds) {
        mutableStateOf(automationSettings.autoModeTimeoutSeconds.toString())
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    RegisterScrollToTopHandler(route = scrollRoute) {
        coroutineScope.launch {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MoreSettingCard(
                icon = Icons.Default.Adjust,
                title = "悬浮球外观",
                subtitle = "调整悬浮球的尺寸与透明度。",
                trailing = {
                    BubbleAppearanceResetButton(
                        onReset = {
                            onUpdateAutomationSettings(
                                automationSettings.copy(bubbleAppearance = BubbleAppearanceSettings())
                            )
                        }
                    )
                }
            ) {
                BubbleAppearanceSettingsCard(
                    settings = automationSettings.bubbleAppearance,
                    onChange = { bubbleAppearance ->
                        onUpdateAutomationSettings(automationSettings.copy(bubbleAppearance = bubbleAppearance))
                    }
                )
            }
        }
        item {
            MoreSettingCard(
                icon = Icons.Default.DonutLarge,
                title = "扇形菜单设置",
                subtitle = "配置悬浮球双击展开的快捷菜单。"
            ) {
                BubbleMenuSettingsCard(
                    appearanceSettings = automationSettings.bubbleAppearance,
                    enabled = automationSettings.bubbleMenuEnabled,
                    onEnabledChange = { enabled ->
                        onUpdateAutomationSettings(automationSettings.copy(bubbleMenuEnabled = enabled))
                    }
                )
            }
        }
        item {
            AccessibilityOverlayCard()
        }
        item {
            MoreSettingCard(
                icon = Icons.Default.SmartToy,
                title = "自动模式",
                subtitle = "主页长按启动进入自动模式。"
            ) {
                AutoModeSettingsCard(
                    automationSettings = automationSettings,
                    timeoutInput = timeoutInput,
                    hasNotificationPermission = hasNotificationPermission,
                    onTimeoutInputChange = { timeoutInput = it },
                    onToggleCompletionNotification = onToggleCompletionNotification,
                    onOpenNotificationPermission = onOpenNotificationPermission,
                    onToggleStaticMode = onToggleStaticMode,
                    onNavigateStaticVibrationSettings = onNavigateStaticVibrationSettings,
                    onUpdateTimeoutSeconds = onUpdateTimeoutSeconds
                )
            }
        }
        item {
            MoreSettingCard(
                icon = Icons.Default.Security,
                title = "网络兼容",
                subtitle = "HTTP 与自签 HTTPS 测试接口。"
            ) {
                TrustAllHttpsSwitch(
                    trustAllHttpsCertificates = trustAllHttpsCertificates,
                    onToggleTrustAllHttpsCertificates = onToggleTrustAllHttpsCertificates
                )
            }
        }
        item {
            MoreSettingCard(
                icon = Icons.Default.PhoneAndroid,
                title = "屏幕录制方式",
                subtitle = "管理当前激活的截图实现。"
            ) {
                ScreenCaptureMethodList(
                    selectedMethod = selectedMethod,
                    onSelectMethod = onSelectMethod
                )
            }
        }
        item {
            if (BuildConfig.SHOW_KIRARI_ENTRY) {
                MoreSettingCard(
                    icon = Icons.Default.Cloud,
                    title = "The Kirari Network",
                    subtitle = "标准 OIDC 登录与 Kirari LLM 网关。"
                ) {
                    KirariSettingsCard(
                        kirariSettings = kirariSettings,
                        hasKirariClientId = hasKirariClientId,
                        onServerUrlCommit = onUpdateKirariServerUrl,
                        onLogin = onLoginKirari,
                        onLogout = onLogoutKirari
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

/**
 * 无障碍悬浮层开关的入口。
 *
 * 微信收款码、银行、支付密码等页面会调用 setHideOverlayWindows(true)，
 * WMS 只会强制隐藏 TYPE_APPLICATION_OVERLAY 这类系统警报窗口，
 * TYPE_ACCESSIBILITY_OVERLAY 不在名单内，因此开启无障碍服务后悬浮球可以常驻。
 */
@Composable
private fun AccessibilityOverlayCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(OverlayWindowHost.usingAccessibilityOverlay) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = OverlayWindowHost.usingAccessibilityOverlay
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MoreSettingCard(
        icon = Icons.Default.Accessibility,
        title = "无障碍悬浮层",
        subtitle = if (enabled) {
            "已启用，悬浮球可在收款码等页面保持可见。"
        } else {
            "未启用，这些页面会把悬浮球隐藏掉。"
        },
        trailing = {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = if (enabled) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
            ) {
                Text(
                    text = if (enabled) "已开启" else "去开启",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Text(
                text = "部分敏感页面（微信收款码、银行、支付密码等）会请求系统隐藏普通悬浮窗，" +
                    "悬浮球会随之消失。开启「Hanako 悬浮层增强」后改用无障碍悬浮层，不受该限制；" +
                    "开启后需要重启悬浮球才会生效。本服务不读取、不监听任何界面内容。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
