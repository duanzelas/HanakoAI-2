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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        println("presets  = ${ProviderPreset.entries.joinToString { it.label }}")
        println("routes   = ${SolveRoute.entries.joinToString { it.name }}")
        println("search   = ${SearchKind.entries.joinToString { it.label }}")
        println("parser   = '答案：ABD' -> ${AnswerParser.lettersOf("答案：ABD")}")
        println("parser   = '选 B'     -> ${AnswerParser.lettersOf("选 B")}")
        println("parser   = '今天天气' -> '${AnswerParser.lettersOf("今天天气不错")}'")
        println("markdown = ${runCatching { "ok" }.getOrDefault("n/a")}")
        val recorder = PcRecorder()
        println("mic      = ${if (recorder.start()) { recorder.cancel(); "可用" } else "不可用（无输入设备或被占用）"}")

        val hotkeys = HotkeyController({}, {}, {})
        println("hotkeys  = ${hotkeys.install()}")
        hotkeys.uninstall()

        val tray = TrayController({}, {}, {}, {}, {}, {})
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
                onExit = { exitApplication() },
                onRecord = { state.toggleRecording() }
            )
        }
        val hotkeys = remember {
            HotkeyController(
                onCapture = { state.captureNow() },
                onSelect = { state.startSelect() },
                onRecord = { state.toggleRecording() }
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
        state = rememberWindowState(width = 1060.dp, height = 740.dp)
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
        title = "HanakoAI-EX",
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

// ---------------------------------------------------------------- 左侧配置

@Composable
private fun ConfigPane(state: AppState) {
    val settings = state.settings
    var kindExpanded by remember { mutableStateOf(false) }
    var engineExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(420.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("模型配置", style = MaterialTheme.typography.titleMedium)

        // 预设芯片：点一下把 Base URL 和模型名一起填好
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProviderPreset.entries.forEach { preset ->
                FilterChip(
                    selected = settings.baseUrl.trim() == preset.baseUrl &&
                        settings.visionModel == preset.visionModel,
                    onClick = { state.applyPreset(preset) },
                    label = { Text(preset.label, fontSize = 12.sp) }
                )
            }
        }

        Box {
            OutlinedButton(onClick = { kindExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(kindLabel(settings.kind))
            }
            DropdownMenu(expanded = kindExpanded, onDismissRequest = { kindExpanded = false }) {
                ProviderKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kindLabel(kind)) },
                        onClick = {
                            state.updateSettings { it.copy(kind = kind) }
                            kindExpanded = false
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SolveRoute.entries.forEach { route ->
                FilterChip(
                    selected = settings.route == route,
                    onClick = { state.updateSettings { it.copy(route = route) } },
                    label = {
                        Text(
                            text = when (route) {
                                SolveRoute.MULTIMODAL_DIRECT -> "多模态"
                                SolveRoute.OCR_THEN_LLM -> "OCR→LLM"
                                SolveRoute.AUDIO_AND_SCREEN -> "语音+画面"
                            },
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }

        // 录音作答：与模式无关，任何模式下都能录一段话再配当前画面
        Button(
            onClick = { state.toggleRecording() },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
            colors = if (state.recording) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            Text(
                if (state.recording) {
                    "■ 结束并作答  " + formatSeconds(state.recordSeconds)
                } else {
                    "● 录音作答（Ctrl+Alt+V）"
                }
            )
        }
        if (state.recording) {
            Text(
                text = "上限 ${settings.maxRecordSeconds} 秒，到时会自动结束；录音期间请让题目声音继续播放",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
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
            Button(onClick = { state.captureNow() }, enabled = !state.busy && !state.recording) {
                Text("立即作答")
            }
            OutlinedButton(onClick = { state.captureClipboard() }, enabled = !state.busy && !state.recording) {
                Text("读剪贴板")
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("快捷键", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Ctrl+Alt+S 按固定区域截图作答\n" +
                "Ctrl+Alt+R 重新框选区域\n" +
                "Ctrl+Alt+V 开始/结束录音作答",
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
                append("\n历史：~/.hanako-pc/history.json")
            },
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(4.dp))
        SectionDivider()
        Text("语音转写", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = settings.transcriptionBaseUrl,
            onValueChange = { value -> state.updateSettings { it.copy(transcriptionBaseUrl = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("转写地址") },
            supportingText = { Text("默认 DeepSeek 官方 ASR（/tools/asr）", fontSize = 11.sp) },
            singleLine = true
        )
        OutlinedTextField(
            value = settings.transcriptionApiKey,
            onValueChange = { value -> state.updateSettings { it.copy(transcriptionApiKey = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("转写 API Key（留空复用主 Key）") },
            singleLine = true
        )

        Spacer(Modifier.height(4.dp))
        SectionDivider()
        Text("联网搜索", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.searchEnabled,
                onCheckedChange = { enabled -> state.updateSettings { it.copy(searchEnabled = enabled) } }
            )
            Spacer(Modifier.width(8.dp))
            Text("作答前先搜一下题目", style = MaterialTheme.typography.bodySmall)
        }
        if (settings.searchEnabled) {
            Box {
                OutlinedButton(onClick = { engineExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(settings.searchKind.label)
                }
                DropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                    SearchKind.entries.forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.label) },
                            onClick = {
                                state.updateSettings { it.copy(searchKind = kind) }
                                engineExpanded = false
                            }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = settings.searchApiKey,
                onValueChange = { value -> state.updateSettings { it.copy(searchApiKey = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索 API Key") },
                singleLine = true
            )
        }

        Spacer(Modifier.height(4.dp))
        SectionDivider()
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

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

// ---------------------------------------------------------------- 右侧结果

private enum class RightTab { RESULT, HISTORY }

@Composable
private fun ResultPane(state: AppState) {
    var tab by remember { mutableStateOf(RightTab.RESULT) }

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
            Spacer(Modifier.weight(1f))
            TabButton("结果", tab == RightTab.RESULT) { tab = RightTab.RESULT }
            TabButton("历史 ${state.history.size}", tab == RightTab.HISTORY) { tab = RightTab.HISTORY }
        }

        // 版本切换：同一张图重新生成多次时，可以往回翻
        if (state.versions.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { state.showVersion(state.versionIndex - 1) },
                    enabled = state.versionIndex > 0
                ) { Text("<") }
                Text(
                    "第 ${state.versionIndex + 1}/${state.versions.size} 个答案",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { state.showVersion(state.versionIndex + 1) },
                    enabled = state.versionIndex < state.versions.lastIndex
                ) { Text(">") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { state.regenerate() }, enabled = !state.busy && !state.recording) {
                    Text("重新生成")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when (tab) {
            RightTab.RESULT -> ResultBody(state)
            RightTab.HISTORY -> HistoryBody(state) { tab = RightTab.RESULT }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ResultBody(state: AppState) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        when {
            state.answer.isNotBlank() -> SelectableMarkdown(state.answer)
            state.busy -> Text("处理中…", fontSize = 14.sp)
            else -> Text(
                "Ctrl+Alt+S 按固定区域截图作答，Ctrl+Alt+V 录音后作答。\n" +
                    "答案会同时出现在托盘图标和右下角浮窗上。\n" +
                    "历史记录会存到 ~/.hanako-pc/history.json。",
                fontSize = 14.sp
            )
        }
    }

    val transcript = state.transcript
    if (!transcript.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text("语音转写（${transcript.length} 字）", style = MaterialTheme.typography.labelMedium)
        SelectionContainerText(transcript, maxLines = 6, background = MaterialTheme.colorScheme.surfaceContainerHighest)
    }

    val ocr = state.ocrText
    if (!ocr.isNullOrBlank()) {
        Spacer(Modifier.height(6.dp))
        Text("OCR 原文（${ocr.length} 字）", style = MaterialTheme.typography.labelMedium)
        SelectionContainerText(ocr, maxLines = 6, background = MaterialTheme.colorScheme.surfaceContainerHighest)
    }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { state.copyAnswer() }, enabled = state.answer.isNotBlank()) {
            Text("复制答案")
        }
        OutlinedButton(onClick = { state.regenerate() }, enabled = !state.busy && state.answer.isNotBlank()) {
            Text("重新生成")
        }
        OutlinedButton(onClick = { state.bubbleVisible = !state.bubbleVisible }) {
            Text(if (state.bubbleVisible) "隐藏浮窗" else "显示浮窗")
        }
        if (state.selectedHistory != null) {
            OutlinedButton(onClick = { state.closeHistory() }) { Text("退出历史") }
        }
    }
}

/**
 * 转写 / OCR 原文：默认折到 [maxLines] 行，点一下展开全文。
 *
 * 不在这里做内部滚动——外层结果区已经是一个滚动容器，嵌套滚动在桌面端会互相抢滚轮。
 */
@Composable
private fun SelectionContainerText(
    text: String,
    maxLines: Int,
    background: androidx.compose.ui.graphics.Color
) {
    var expanded by remember { mutableStateOf(false) }
    SelectionContainer {
        Text(
            text = text,
            fontSize = 12.sp,
            maxLines = if (expanded) Int.MAX_VALUE else maxLines,
            modifier = Modifier
                .fillMaxWidth()
                .background(background, RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(10.dp)
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HistoryBody(
    state: AppState,
    onBack: () -> Unit
) {
    if (state.history.isEmpty()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("还没有历史记录。作答一次就会出现在这里。", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("共 ${state.history.size} 条", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { state.clearHistory() }) { Text("清空") }
    }
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(state.history, key = { it.id }) { entry ->
            HistoryRow(
                entry = entry,
                onOpen = {
                    state.openHistory(entry)
                    onBack()
                },
                onDelete = { state.deleteHistory(entry) }
            )
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.timeLabel, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                Text(entry.route.label, style = MaterialTheme.typography.labelSmall)
                if (entry.letters.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        entry.letters,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { onDelete() }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(entry.summary.ifBlank { "（空）" }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

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

private fun formatSeconds(total: Int): String {
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

private fun kindLabel(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENAI_COMPATIBLE -> "OpenAI 兼容"
    ProviderKind.OPENAI_RESPONSES -> "OpenAI Responses"
    ProviderKind.ANTHROPIC -> "Anthropic"
    ProviderKind.GOOGLE -> "Google Gemini"
    ProviderKind.KIRARI_NETWORK -> "The Kirari Network"
}
