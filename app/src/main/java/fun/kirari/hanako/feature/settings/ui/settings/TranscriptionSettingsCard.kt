package `fun`.kirari.hanako.feature.settings.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import `fun`.kirari.hanako.core.audio.AudioPermissionActivity
import `fun`.kirari.hanako.core.data.TranscriptionSettings
import `fun`.kirari.hanako.core.data.isDeepSeekAsr
import `fun`.kirari.hanako.core.ui.components.DraftOutlinedTextField

/**
 * 语音转写设置。
 *
 * 默认就是 DeepSeek 官方 ASR：`https://api.deepseek.com` + `/v1/tools/asr`。
 * 这个接口的语种是自动识别的，没有 language 入参，所以界面上不提供语种下拉，
 * 只说明「按英文理解」，避免给出改不动实际行为的假开关。
 */
@Composable
internal fun TranscriptionSettingsCard(
    settings: TranscriptionSettings,
    onChange: (TranscriptionSettings) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionGranted by remember { mutableStateOf(AudioPermissionActivity.hasPermission(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = AudioPermissionActivity.hasPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SwitchSettingRow(
            title = "启用语音转写",
            subtitle = "「语音+画面」模式用它把录音转成文字，再结合截图作答。",
            checked = settings.enabled,
            onCheckedChange = { onChange(settings.copy(enabled = it)) }
        )

        DraftOutlinedTextField(
            fieldKey = "transcription_base_url",
            value = settings.baseUrl,
            onCommit = { onChange(settings.copy(baseUrl = it.trim())) },
            modifier = Modifier.fillMaxWidth(),
            label = "服务地址",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        DraftOutlinedTextField(
            fieldKey = "transcription_path",
            value = settings.path,
            onCommit = { onChange(settings.copy(path = it.trim().ifBlank { "/v1/tools/asr" })) },
            modifier = Modifier.fillMaxWidth(),
            label = "接口路径",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        DraftOutlinedTextField(
            fieldKey = "transcription_api_key",
            value = settings.apiKey,
            onCommit = { onChange(settings.copy(apiKey = it.trim())) },
            modifier = Modifier.fillMaxWidth(),
            label = "API Key（留空则复用 DeepSeek 提供方的 Key）",
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = when {
                        settings.isDeepSeekAsr -> "识别语种：英文（DeepSeek ASR 自动识别，无 language 参数）"
                        else -> "识别语种：${settings.language}（自定义端点会作为 language 参数发送）"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "内录（系统声音）需要屏幕录制会话；微信语音等禁止被内录的应用，" +
                        "请用悬浮球菜单里的「麦克风」独立录音。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (permissionGranted) "录音权限：已授予" else "录音权限：未授予",
                style = MaterialTheme.typography.bodySmall,
                color = if (permissionGranted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            TextButton(
                onClick = {
                    runCatching { context.startActivity(AudioPermissionActivity.intent(context)) }
                }
            ) {
                Text(if (permissionGranted) "重新授权" else "授予权限")
            }
        }
    }
}
