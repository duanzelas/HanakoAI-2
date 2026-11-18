package `fun`.kirari.hanako.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import `fun`.kirari.llm.core.LlmClient
import `fun`.kirari.llm.core.LlmEvent
import `fun`.kirari.llm.core.ProviderConfig
import `fun`.kirari.llm.core.ProviderKind
import `fun`.kirari.llm.core.StreamRequest
import kotlinx.coroutines.launch
import java.io.File
import java.util.Properties

/**
 * Hanako PC 的最小可用骨架：
 * 只做「配置模型 → 输入问题 → 流式看回答」这一条链路，
 * 用来验证 kirari-llm-core 能在桌面端原样复用。
 */
fun main(args: Array<String>) {
    // 无界面自检：验证 JVM + Compose 依赖在运行时真的加载得起来，
    // 便于 CI 或命令行冒烟测试（gradle :app:run --args="--smoke"）。
    if (args.contains("--smoke")) {
        println("Hanako PC smoke ok: jvm=${System.getProperty("java.version")}, " +
            "providers=${ProviderKind.entries.size}")
        return
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Hanako PC 0.0.20（预览）") {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App() {
    val saved = remember { PcSettings.load() }
    var kind by remember { mutableStateOf(saved.kind) }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var systemPrompt by remember { mutableStateOf(saved.systemPrompt) }

    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val client = remember { LlmClient() }

    // 配置变更即落盘，省掉一个"保存"按钮
    LaunchedEffect(kind, baseUrl, apiKey, model, systemPrompt) {
        PcSettings(kind, baseUrl, apiKey, model, systemPrompt).save()
    }

    fun send() {
        if (busy || question.isBlank() || model.isBlank()) return
        val prompt = question
        busy = true
        answer = ""
        error = null
        scope.launch {
            try {
                val request = StreamRequest(
                    provider = ProviderConfig(kind = kind, baseUrl = baseUrl.trim(), apiKey = apiKey.trim()),
                    model = model.trim(),
                    systemPrompt = systemPrompt,
                    userPrompt = prompt,
                    firstDeltaTimeoutMillis = 60_000,
                    trustAllHttpsCertificates = false
                )
                client.stream(request).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> answer += event.text
                        is LlmEvent.ToolCall -> answer += "\n[tool] ${event.name} ${event.arguments}\n"
                        LlmEvent.Done -> Unit
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                busy = false
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        ConfigPane(
            kind = kind,
            onKindChange = { kind = it },
            baseUrl = baseUrl,
            onBaseUrlChange = { baseUrl = it },
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            model = model,
            onModelChange = { model = it },
            systemPrompt = systemPrompt,
            onSystemPromptChange = { systemPrompt = it }
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant)
        )

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = if (busy) "正在请求 ${kindLabel(kind)} / $model …" else "就绪",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = when {
                            answer.isNotEmpty() -> answer
                            error != null -> "请求失败：$error"
                            else -> "在下方输入问题，回车或点「发送」。"
                        },
                        fontFamily = FontFamily.Default,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("问题") },
                    maxLines = 3
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { send() }, enabled = !busy) {
                    Text(if (busy) "…" else "发送")
                }
            }
        }
    }
}

@Composable
private fun ConfigPane(
    kind: ProviderKind,
    onKindChange: (ProviderKind) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(340.dp)
            .fillMaxHeight()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("模型配置", style = MaterialTheme.typography.titleMedium)
        Text(
            "配置保存在 ~/.hanako-pc/config.properties，明文存储，仅本机使用。",
            style = MaterialTheme.typography.bodySmall
        )

        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(kindLabel(kind))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ProviderKind.entries.forEach { k ->
                    DropdownMenuItem(
                        text = { Text(kindLabel(k)) },
                        onClick = {
                            onKindChange(k)
                            expanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            singleLine = true,
            placeholder = { Text("https://api.openai.com/v1") }
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            singleLine = true
        )
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型") },
            singleLine = true,
            placeholder = { Text("gpt-4o-mini") }
        )
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = onSystemPromptChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("System Prompt（可选）") },
            minLines = 3,
            maxLines = 6
        )
    }
}

private fun kindLabel(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENAI_COMPATIBLE -> "OpenAI 兼容"
    ProviderKind.OPENAI_RESPONSES -> "OpenAI Responses"
    ProviderKind.ANTHROPIC -> "Anthropic"
    ProviderKind.GOOGLE -> "Google Gemini"
    ProviderKind.KIRARI_NETWORK -> "The Kirari Network"
}

/** 极简配置持久化，避免为此引入 DataStore。 */
private data class PcSettings(
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val systemPrompt: String = ""
) {
    fun save() {
        runCatching {
            val file = configFile()
            file.parentFile?.mkdirs()
            val props = Properties()
            props["kind"] = kind.name
            props["baseUrl"] = baseUrl
            props["apiKey"] = apiKey
            props["model"] = model
            props["systemPrompt"] = systemPrompt
            file.outputStream().use { props.store(it, "Hanako PC") }
        }
    }

    companion object {
        fun load(): PcSettings = runCatching {
            val file = configFile()
            if (!file.exists()) return PcSettings()
            val props = Properties()
            file.inputStream().use { props.load(it) }
            PcSettings(
                kind = props.getProperty("kind")
                    ?.let { runCatching { ProviderKind.valueOf(it) }.getOrNull() }
                    ?: ProviderKind.OPENAI_COMPATIBLE,
                baseUrl = props.getProperty("baseUrl") ?: "https://api.openai.com/v1",
                apiKey = props.getProperty("apiKey").orEmpty(),
                model = props.getProperty("model") ?: "gpt-4o-mini",
                systemPrompt = props.getProperty("systemPrompt").orEmpty()
            )
        }.getOrDefault(PcSettings())

        private fun configFile(): File =
            File(System.getProperty("user.home"), ".hanako-pc/config.properties")
    }
}
