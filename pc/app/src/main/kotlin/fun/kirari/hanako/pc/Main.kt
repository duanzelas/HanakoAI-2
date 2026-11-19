package `fun`.kirari.hanako.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import `fun`.kirari.llm.core.ProviderKind
import kotlinx.coroutines.delay

fun main(args: Array<String>) {
    // 无界面自检：验证依赖在运行时加载正常，便于命令行冒烟测试
    // gradlew :app:run --args="--smoke"
    if (args.contains("--smoke")) {
        println("app      = ${PcInfo.APP_NAME} ${PcInfo.VERSION}")
        println("repo     = ${PcInfo.REPO_URL}")
        println("jvm      = ${System.getProperty("java.version")}")
        println("providers= ${ProviderKind.entries.size}")
        println("parser   = '答案：ABD' -> ${AnswerParser.lettersOf("答案：ABD")}")
        println("parser   = '选 B'     -> ${AnswerParser.lettersOf("选 B")}")
        println("parser   = '今天天气' -> '${AnswerParser.lettersOf("今天天气不错")}'")

        val hotkeys = HotkeyController({}, {})
        println("hotkeys  = ${hotkeys.install()}")
        hotkeys.uninstall()

        val tray = TrayController({}, {}, {}, {}, {})
        println("tray     = ${tray.install()}")
        tray.update("ABD", "答案 ABD")
        tray.uninstall()
        return
    }

    application {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope) }

        val tray = remember {
            TrayController(
                onCapture = { state.captureNow() },
                onSelect = { state.startSelect() },
                onClipboard = { state.captureClipboard() },
                onOpenSettings = { state.showMain = true },
                onExit = { exitApplication() }
            )
        }
        val hotkeys = remember {
            HotkeyController(
                onCapture = { state.captureNow() },
                onSelect = { state.startSelect() }
            )
        }

        DisposableEffect(Unit) {
            tray.install()
            state.tray = tray
            state.hotkeyAvailable = hotkeys.install()
            onDispose {
                hotkeys.uninstall()
                tray.uninstall()
            }
        }

        MainWindow(state) { exitApplication() }
        BubbleWindow(state)
    }
}

@Composable
private fun MainWindow(state: AppState, onExit: () -> Unit) {
    Window(
        onCloseRequest = {
            // 有托盘就缩到托盘常驻，没有托盘则直接退出，避免后台死进程
            if (state.tray?.isAvailable == true) state.showMain = false else onExit()
        },
        visible = state.showMain,
        title = "${PcInfo.APP_NAME} ${PcInfo.VERSION}（预览）",
        state = rememberWindowState(width = 1000.dp, height = 700.dp)
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    ConfigPane(state)
                    VerticalLine()
                    ResultPane(state)
                }
            }
        }
    }
}

@Composable
private fun BubbleWindow(state: AppState) {
    // 托盘旁边的答案浮窗：只显示字母，点一下把完整答案复制走
    LaunchedEffect(state.bubbleVisible, state.answer) {
        if (state.bubbleVisible) {
            delay(15_000)
            state.bubbleVisible = false
        }
    }

    Window(
        onCloseRequest = { state.bubbleVisible = false },
        visible = state.bubbleVisible,
        title = "Hanako",
        state = rememberWindowState(
            position = WindowPosition(Alignment.BottomEnd),
            size = DpSize(240.dp, 116.dp)
        ),
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true
    ) {
        MaterialTheme {
            Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable {
                            state.copyAnswer()
                            state.bubbleVisible = false
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = state.letters.ifBlank { "✓" },
                        fontSize = if (state.letters.length > 2) 32.sp else 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "点击复制答案",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalLine() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun ConfigPane(state: AppState) {
    val settings = state.settings
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(400.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("模型配置", style = MaterialTheme.typography.titleMedium)

        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(kindLabel(settings.kind))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ProviderKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kindLabel(kind)) },
                        onClick = {
                            state.updateSettings { it.copy(kind = kind) }
                            expanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = settings.baseUrl,
            onValueChange = { value -> state.updateSettings { it.copy(baseUrl = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            singleLine = true
        )
        OutlinedTextField(
            value = settings.apiKey,
            onValueChange = { value -> state.updateSettings { it.copy(apiKey = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = settings.visionModel,
                onValueChange = { value -> state.updateSettings { it.copy(visionModel = value) } },
                modifier = Modifier.weight(1f),
                label = { Text("视觉模型") },
                singleLine = true
            )
            OutlinedTextField(
                value = settings.textModel,
                onValueChange = { value -> state.updateSettings { it.copy(textModel = value) } },
                modifier = Modifier.weight(1f),
                label = { Text("文本模型") },
                singleLine = true
            )
        }

        Text("处理方式", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { state.updateSettings { it.copy(route = SolveRoute.MULTIMODAL_DIRECT) } },
                enabled = settings.route != SolveRoute.MULTIMODAL_DIRECT
            ) { Text("多模态直答") }
            Button(
                onClick = { state.updateSettings { it.copy(route = SolveRoute.OCR_THEN_LLM) } },
                enabled = settings.route != SolveRoute.OCR_THEN_LLM
            ) { Text("先 OCR 再作答") }
        }

        Text("截图区域", style = MaterialTheme.typography.titleSmall)
        val region = settings.region
        Text(
            text = when {
                region == null -> "未框选。按 Ctrl+Alt+R 或点下面的按钮框选一次。"
                else -> "${region.width} × ${region.height} @ (${region.x}, ${region.y})" +
                    " —— 位置固定，之后每次截图都取这里"
            },
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { state.startSelect() }) { Text("重新框选") }
            Button(onClick = { state.captureNow() }, enabled = !state.busy) { Text("立即作答") }
            OutlinedButton(onClick = { state.captureClipboard() }, enabled = !state.busy) {
                Text("读剪贴板")
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("快捷键", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Ctrl+Alt+S 按固定区域截图作答\nCtrl+Alt+R 重新框选区域",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append("托盘：")
                append(
                    if (state.tray?.isAvailable == true) "已启用，图标会显示答案字母"
                    else "当前系统不支持托盘"
                )
                append("\n全局热键：")
                append(if (state.hotkeyAvailable) "已注册" else "注册失败（可能被杀毒软件占用）")
                append("\n配置：~/.hanako-pc/config.properties")
            },
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = settings.systemPrompt,
            onValueChange = { value -> state.updateSettings { it.copy(systemPrompt = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("System Prompt") },
            minLines = 3,
            maxLines = 6
        )

        Spacer(Modifier.height(8.dp))
        AboutBlock()
    }
}

/**
 * 版本与仓库入口。
 *
 * 桌面端没有「设置 → 更多」那一层，所以直接放配置面板底部：
 * 用户报问题时能一眼看到版本号，也能点开仓库对照更新日志。
 */
@Composable
private fun AboutBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("关于", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "${PcInfo.APP_NAME} ${PcInfo.VERSION}（预览）",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = PcInfo.REPO_LABEL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { PcInfo.openRepo() }
        )
        Text(
            text = "点击地址可在浏览器中打开源码与更新日志",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ResultPane(state: AppState) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.status, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(12.dp))
            if (state.letters.isNotBlank()) {
                Text(
                    state.letters,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
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
                        state.answer.isNotBlank() -> state.answer
                        state.busy -> "处理中…"
                        else -> "用 Ctrl+Alt+S 按固定区域截图，或点左侧「立即作答」「读剪贴板」。\n" +
                            "答案会同时出现在托盘图标和右下角浮窗上。"
                    },
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { state.copyAnswer() }, enabled = state.answer.isNotBlank()) {
                Text("复制答案")
            }
            OutlinedButton(onClick = { state.bubbleVisible = !state.bubbleVisible }) {
                Text(if (state.bubbleVisible) "隐藏浮窗" else "显示浮窗")
            }
            val ocr = state.ocrText
            if (!ocr.isNullOrBlank()) {
                Text(
                    "OCR 已识别 ${ocr.length} 字",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
    }
}

private fun kindLabel(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENAI_COMPATIBLE -> "OpenAI 兼容"
    ProviderKind.OPENAI_RESPONSES -> "OpenAI Responses"
    ProviderKind.ANTHROPIC -> "Anthropic"
    ProviderKind.GOOGLE -> "Google Gemini"
    ProviderKind.KIRARI_NETWORK -> "The Kirari Network"
}
