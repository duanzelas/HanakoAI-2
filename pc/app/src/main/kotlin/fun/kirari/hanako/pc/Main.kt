package `fun`.kirari.hanako.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.Color
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
    // 无界面自检：验证依赖与设备在运行时可用
    // gradlew :app:run --args="--smoke"
    if (args.contains("--smoke")) {
        println("app      = ${PcInfo.APP_NAME} ${PcInfo.VERSION}")
        println("repo     = ${PcInfo.REPO_URL}")
        println("jvm      = ${System.getProperty("java.version")}")
        println("providers= ${ProviderKind.entries.size}")
        println("presets  = ${ProviderPreset.entries.joinToString { it.label }}")
        println("routes   = ${SolveRoute.entries.joinToString { it.name }}")
        println("sources  = ${RecordOptions().label} / ${RecordOptions(system = false, mic = true).label}")
        println(
            "keys     = " + HotkeyAction.entries.joinToString { "${it.label}:${PcSettings().hotkeyOf(it).label}" }
        )
        println("search   = ${SearchKind.entries.joinToString { it.label }}")
        println("parser   = '答案：ABD' -> ${AnswerParser.lettersOf("答案：ABD")}")
        println("parser   = '选 B'     -> ${AnswerParser.lettersOf("选 B")}")
        println("parser   = '今天天气' -> '${AnswerParser.lettersOf("今天天气不错")}'")

        val recorder = PcRecorder()
        val micStart = recorder.start(RecordOptions(system = false, mic = true))
        val micOk = micStart is RecordStart.Ok
        if (micOk) recorder.cancel()
        println("mic      = ${if (micOk) "可用" else "不可用（无输入设备或被占用）"}")
        val devices = PcRecorder.systemDevices()
        println("system   = ${devices.joinToString().ifBlank { "未找到回环设备（需在声音设置里启用「立体声混音」）" }}")
        val sysStart = if (devices.isEmpty()) {
            RecordStart.NoSystemDevice
        } else {
            recorder.start(RecordOptions(system = true))
        }
        val sysOk = sysStart is RecordStart.Ok
        if (sysOk) recorder.cancel()
        println("sysRecord= ${if (sysOk) "可用" else "不可用：$sysStart"}")

        // 真录一秒看数据流：只开系统、只开麦、两路混音各来一次
        fun recordFor(options: RecordOptions): String {
            val started = recorder.start(options)
            if (started !is RecordStart.Ok) return "启动失败：$started"
            Thread.sleep(1_500)
            val elapsed = recorder.elapsedMillis()
            val clip = recorder.stop() ?: return "无数据"
            return "wall=${elapsed}ms audio=${clip.durationMillis}ms peak=${clip.peakAmplitude} " +
                "[${started.sources.joinToString("+")}]"
        }
        println("recSystem= ${recordFor(RecordOptions(system = sysOk, mic = false))}")
        println("recMic   = ${recordFor(RecordOptions(system = false, mic = micOk))}")
        println("recMix   = ${recordFor(RecordOptions(system = sysOk, mic = micOk))}")

        val hotkeys = HotkeyController({ PcSettings() }, {}, {}, {})
        println("hotkeys  = ${hotkeys.install()}")
        hotkeys.uninstall()

        val tray = TrayController({}, {}, {}, {}, {}, {})
        println("tray     = ${tray.install()}")
        tray.update("ABD", "答案 ABD")
        tray.setRecording(true)
        tray.setRecording(false)
        tray.uninstall()
        return
    }

    application {
        val scope = rememberCoroutineScope()
        val state = remember {
            AppState(scope).also { if (args.contains("--settings")) it.showSettings = true }
        }

        val tray = remember {
            TrayController(
                onCapture = { state.captureMultimodal() },
                onSelect = { state.startSelect() },
                onClipboard = { state.captureClipboard() },
                onOpenSettings = { state.showMain = true },
                onExit = { exitApplication() },
                onRecord = { state.toggleRecording() }
            )
        }
        val hotkeys = remember {
            HotkeyController(
                // 每次按键都读当前设置，改完键位立即生效，不用重启
                settingsProvider = { state.settings },
                onCapture = { state.captureMultimodal() },
                onSelect = { state.startSelect() },
                onRecord = { state.toggleRecording() }
            )
        }

        DisposableEffect(Unit) {
            tray.install()
            state.tray = tray
            state.hotkeys = hotkeys
            state.hotkeyAvailable = hotkeys.install()
            onDispose {
                hotkeys.uninstall()
                state.hotkeys = null
                tray.uninstall()
            }
        }

        MainWindow(state) { exitApplication() }
        SettingsWindow(state)
        BubbleWindow(state)
    }
}

// ---------------------------------------------------------------- 主窗口

@Composable
private fun MainWindow(state: AppState, onExit: () -> Unit) {
    Window(
        onCloseRequest = {
            // 有托盘就缩到托盘常驻，没有托盘则直接退出，避免后台死进程
            if (state.tray?.isAvailable == true) state.showMain = false else onExit()
        },
        visible = state.showMain,
        title = "${PcInfo.APP_NAME} ${PcInfo.VERSION}（预览）",
        state = rememberWindowState(width = 1020.dp, height = 720.dp)
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopBar(state)
                    Row(modifier = Modifier.fillMaxSize()) {
                        OperationPane(state)
                        VerticalLine()
                        ResultPane(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: AppState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "HanakoAI-EX",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "v${PcInfo.VERSION}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            state.status,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.letters.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                state.letters,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.weight(1f))
        RecordButton(state)
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { state.showSettings = true }) { Text("设置") }
    }
    SectionDivider()
}

/** 顶栏的录音开关：录音中是红底白字并带计时，一眼能确认状态。 */
@Composable
private fun RecordButton(state: AppState) {
    Button(
        onClick = { state.toggleRecording() },
        enabled = !state.busy,
        colors = if (state.recording) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors()
        }
    ) {
        Text(
            if (state.recording) {
                "■ 结束 " + formatSeconds(state.recordSeconds)
            } else {
                "● 录音"
            }
        )
    }
}

// ---------------------------------------------------------------- 操作区

@Composable
private fun OperationPane(state: AppState) {
    val settings = state.settings
    val systemDevices = remember { PcRecorder.systemDevices() }
    val options = settings.recordOptions()

    Column(
        modifier = Modifier
            .width(310.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("操作", style = MaterialTheme.typography.titleSmall)

        Button(
            onClick = { state.captureNow() },
            enabled = !state.busy && !state.recording,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (settings.route == SolveRoute.AUDIO_AND_SCREEN) "按固定区域录音作答"
                else "按固定区域截图作答"
            )
        }
        OutlinedButton(
            onClick = { state.startSelect() },
            enabled = !state.busy && !state.recording,
            modifier = Modifier.fillMaxWidth()
        ) { Text("重新框选区域") }
        OutlinedButton(
            onClick = { state.captureClipboard() },
            enabled = !state.busy && !state.recording,
            modifier = Modifier.fillMaxWidth()
        ) { Text("从剪贴板读图作答") }

        Spacer(Modifier.height(6.dp))
        SectionDivider()
        Text("当前配置", style = MaterialTheme.typography.titleSmall)
        InfoRow("处理方式", settings.route.label)
        InfoRow("模型", settings.visionModel.ifBlank { "未配置" })
        InfoRow("录音音源", options.label)
        if (!options.any) {
            Text(
                text = "两个音源都关着：到 设置 → 语音与录音 打开系统内录或麦克风",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else if (options.system && systemDevices.isEmpty()) {
            Text(
                text = "未找到「立体声混音」设备：到 声音设置 → 录制 → 右键「显示禁用的设备」→ 启用它，" +
                    "或只开麦克风",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else if (options.system) {
            Text(
                text = "可用设备：" + systemDevices.joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(6.dp))
        SectionDivider()
        Text("截图区域", style = MaterialTheme.typography.titleSmall)
        val region = settings.region
        Text(
            text = if (region == null) {
                "未框选。按 Ctrl+Alt+R 或点「重新框选区域」。"
            } else {
                "${region.width} × ${region.height} @ (${region.x}, ${region.y})\n位置固定，之后每次截图都取这里"
            },
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(6.dp))
        SectionDivider()
        Text("快捷键", style = MaterialTheme.typography.titleSmall)
        HotkeyLine(settings.hotkeyCapture.label, "多模态识屏（截一张）")
        HotkeyLine(settings.hotkeyRecord.label, "录音识屏（开始/结束）")
        HotkeyLine(settings.hotkeySelect.label, "重新框选区域")
        Text(
            text = "键位在「设置 → 快捷键」里改",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = buildString {
                append("托盘：")
                append(if (state.tray?.isAvailable == true) "已启用" else "当前系统不支持")
                append("\n全局热键：")
                append(if (state.hotkeyAvailable) "已注册" else "注册失败（可能被杀毒软件占用）")
                append("\n配置：~/.hanako-pc/config.properties")
                append("\n历史：~/.hanako-pc/history.json")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 左栏的键位行：键名固定宽度，说明跟在后面，换行只影响说明那一列。 */
@Composable
private fun HotkeyLine(key: String, action: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = action,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

// ---------------------------------------------------------------- 设置窗口（二级）

@Composable
private fun SettingsWindow(state: AppState) {
    Window(
        onCloseRequest = {
            state.cancelHotkeyCapture()
            state.showSettings = false
        },
        visible = state.showSettings,
        title = "设置 · ${PcInfo.APP_NAME}",
        state = rememberWindowState(width = 560.dp, height = 780.dp)
    ) {
        // 设置窗和主窗都默认居中，直接弹出来会被主窗整个盖住（看起来像「点了没反应」），
        // 每次显示时主动提到最前。
        LaunchedEffect(state.showSettings) {
            if (state.showSettings) {
                runCatching {
                    window.toFront()
                    window.requestFocus()
                }
            }
        }
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("设置", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            state.cancelHotkeyCapture()
                            state.showSettings = false
                        }) { Text("关闭") }
                    }
                    ModelSection(state)
                    SectionDivider()
                    RouteSection(state)
                    SectionDivider()
                    HotkeySection(state)
                    SectionDivider()
                    VoiceSection(state)
                    SectionDivider()
                    SearchSection(state)
                    SectionDivider()
                    PromptSection(state)
                    SectionDivider()
                    AboutBlock()
                }
            }
        }
    }
}

@Composable
private fun ModelSection(state: AppState) {
    val settings = state.settings
    var kindExpanded by remember { mutableStateOf(false) }

    Text("模型配置", style = MaterialTheme.typography.titleSmall)
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
}

@Composable
private fun RouteSection(state: AppState) {
    val settings = state.settings
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
}

@Composable
private fun HotkeySection(state: AppState) {
    val settings = state.settings

    Text("快捷键", style = MaterialTheme.typography.titleSmall)
    HotkeyRow(state, HotkeyAction.CAPTURE, settings.hotkeyCapture)
    HotkeyRow(state, HotkeyAction.RECORD, settings.hotkeyRecord)
    HotkeyRow(state, HotkeyAction.SELECT, settings.hotkeySelect)

    Text(
        text = "点「更改」后直接按下新的键或组合键（可带 Ctrl / Alt / Shift / Win），Esc 取消。" +
            "建议带修饰键：单键在打字时会误触。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!state.hotkeyAvailable) {
        Text(
            text = "全局快捷键注册失败：可能被杀毒软件拦截，请允许本程序监听键盘后重启。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun HotkeyRow(state: AppState, action: HotkeyAction, spec: HotkeySpec) {
    val capturing = state.capturingAction == action
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(action.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = action.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (capturing) "请按键…" else spec.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (capturing) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        Spacer(Modifier.width(8.dp))
        if (capturing) {
            OutlinedButton(onClick = { state.cancelHotkeyCapture() }) { Text("取消") }
        } else {
            OutlinedButton(
                onClick = { state.beginHotkeyCapture(action) },
                enabled = state.hotkeyAvailable
            ) { Text("更改") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { state.resetHotkey(action) }) {
                Text("默认", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun VoiceSection(state: AppState) {
    val settings = state.settings
    val systemDevices = remember { PcRecorder.systemDevices() }

    Text("语音与录音", style = MaterialTheme.typography.titleSmall)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = settings.systemAudioEnabled,
            onCheckedChange = { enabled ->
                state.updateSettings { it.copy(systemAudioEnabled = enabled) }
            }
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text("系统内录", style = MaterialTheme.typography.bodyMedium)
            Text(
                "默认开启。走声卡的「立体声混音」回环设备，录视频/网课里的声音。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = settings.micEnabled,
            onCheckedChange = { enabled ->
                state.updateSettings { it.copy(micEnabled = enabled) }
            }
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text("麦克风", style = MaterialTheme.typography.bodyMedium)
            Text(
                "默认关闭。打开后与系统声音混音，适合自己也要说话的场景。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    val options = settings.recordOptions()
    Text(
        text = when {
            !options.any ->
                "两个音源都关着，按录音键会直接报错。至少打开一个。"

            options.system && options.mic && systemDevices.isEmpty() ->
                "没有找到「立体声混音」：到 声音设置 → 录制 → 右键「显示禁用的设备」→ 启用它。" +
                    "现在录音只会用麦克风。部分机器需要装虚拟声卡（如 VB-Cable）才能内录。"

            options.system && systemDevices.isEmpty() ->
                "没有找到「立体声混音」：到 声音设置 → 录制 → 右键「显示禁用的设备」→ 启用「立体声混音」。" +
                    "部分机器需要装虚拟声卡（如 VB-Cable）才能内录。"

            options.system ->
                "本次录音：" + options.label + "；设备：" + systemDevices.joinToString()

            else -> "本次录音：麦克风（不含系统声音）"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (options.system && systemDevices.isEmpty()) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
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
    OutlinedTextField(
        value = settings.maxRecordSeconds.toString(),
        onValueChange = { value ->
            val seconds = value.filter { it.isDigit() }.take(3).toIntOrNull()
            if (seconds != null) state.updateSettings { it.copy(maxRecordSeconds = seconds) }
        },
        modifier = Modifier.width(180.dp),
        label = { Text("录音上限（秒）") },
        singleLine = true
    )
}

@Composable
private fun SearchSection(state: AppState) {
    val settings = state.settings
    var engineExpanded by remember { mutableStateOf(false) }

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
}

@Composable
private fun PromptSection(state: AppState) {
    val settings = state.settings
    Text("System Prompt", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = settings.systemPrompt,
        onValueChange = { value -> state.updateSettings { it.copy(systemPrompt = value) } },
        modifier = Modifier.fillMaxWidth(),
        minLines = 4,
        maxLines = 8
    )
}

// ---------------------------------------------------------------- 浮窗与结果

@Composable
private fun BubbleWindow(state: AppState) {
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

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

private enum class RightTab { RESULT, HISTORY }

@Composable
private fun ResultPane(state: AppState) {
    var tab by remember { mutableStateOf(RightTab.RESULT) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabButton("结果", tab == RightTab.RESULT) { tab = RightTab.RESULT }
            TabButton("历史 ${state.history.size}", tab == RightTab.HISTORY) { tab = RightTab.HISTORY }
            Spacer(Modifier.weight(1f))
            if (state.versions.size > 1) {
                TextButton(
                    onClick = { state.showVersion(state.versionIndex - 1) },
                    enabled = state.versionIndex > 0
                ) { Text("<") }
                Text(
                    "${state.versionIndex + 1}/${state.versions.size}",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { state.showVersion(state.versionIndex + 1) },
                    enabled = state.versionIndex < state.versions.lastIndex
                ) { Text(">") }
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
private fun ColumnScope.ResultBody(state: AppState) {
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
                "${state.settings.hotkeyCapture.label} 截一张图作答，" +
                    "${state.settings.hotkeyRecord.label} 录音后作答。\n" +
                    "答案会同时出现在托盘图标和右下角浮窗上。\n" +
                    "模型、音源、快捷键、联网搜索等都在右上角「设置」里。",
                fontSize = 14.sp
            )
        }
    }

    val transcript = state.transcript
    if (!transcript.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text("语音转写（${transcript.length} 字）", style = MaterialTheme.typography.labelMedium)
        FoldableText(transcript, maxLines = 6, background = MaterialTheme.colorScheme.surfaceContainerHighest)
    }

    val ocr = state.ocrText
    if (!ocr.isNullOrBlank()) {
        Spacer(Modifier.height(6.dp))
        Text("OCR 原文（${ocr.length} 字）", style = MaterialTheme.typography.labelMedium)
        FoldableText(ocr, maxLines = 6, background = MaterialTheme.colorScheme.surfaceContainerHighest)
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
 * 转写 / OCR 原文：默认折到 [maxLines] 行，点一下展开。
 *
 * 不在这里做内部滚动——外层结果区已是滚动容器，嵌套滚动在桌面端会互相抢滚轮。
 */
@Composable
private fun FoldableText(text: String, maxLines: Int, background: Color) {
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
private fun ColumnScope.HistoryBody(state: AppState, onBack: () -> Unit) {
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
            Text(
                entry.summary.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
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
