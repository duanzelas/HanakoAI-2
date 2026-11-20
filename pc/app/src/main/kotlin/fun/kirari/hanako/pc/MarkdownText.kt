package `fun`.kirari.hanako.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 轻量 Markdown 渲染。
 *
 * 没有引第三方渲染库：Compose Desktop 的 markdown 库版本和 Compose 1.7.3 绑得很紧，
 * 为一个只用来显示答案的组件引入依赖、还要跟版本对齐，不如自己处理模型真正会输出的那几种：
 * 标题、项目符号、代码块、粗体、行内代码。
 *
 * 公式（`$...$`、`\\(...\\)`）**原样显示**：桌面端做 LaTeX 排版需要 JLaTeXMath 这类 Swing 组件，
 * 嵌进 Compose 之后滚动、选中、缩放都会出问题。保留原文至少不会把公式渲染成乱码。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text, baseColor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                    color = baseColor
                )

                is MdBlock.Bullet -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("•", color = baseColor)
                    Text(text = inline(block.text, baseColor), color = baseColor)
                }

                is MdBlock.Code -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = baseColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(codeBackground, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )

                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, baseColor),
                    color = baseColor
                )
            }
        }
    }
}

/** 结果区用：可选中复制。 */
@Composable
fun SelectableMarkdown(text: String, modifier: Modifier = Modifier) {
    SelectionContainer(modifier = modifier) {
        MarkdownText(text)
    }
}

// ---------------------------------------------------------------- 解析

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
}

private fun parseMarkdown(source: String): List<MdBlock> {
    if (source.isBlank()) return emptyList()
    val blocks = ArrayList<MdBlock>()
    val code = StringBuilder()
    var inCode = false

    source.lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks += MdBlock.Code(code.toString().trimEnd())
                code.clear()
                inCode = false
            } else {
                inCode = true
            }
            return@forEach
        }
        if (inCode) {
            code.appendLine(line)
            return@forEach
        }
        if (line.isBlank()) return@forEach

        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("### ") -> blocks += MdBlock.Heading(3, trimmed.removePrefix("### "))
            trimmed.startsWith("## ") -> blocks += MdBlock.Heading(2, trimmed.removePrefix("## "))
            trimmed.startsWith("# ") -> blocks += MdBlock.Heading(1, trimmed.removePrefix("# "))
            trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                blocks += MdBlock.Bullet(trimmed.drop(2))

            // 有序列表保留序号，模型给「1. 2. 3.」时不要丢掉层级
            Regex("^\\d+[.)]\\s+").find(trimmed) != null -> blocks += MdBlock.Bullet(trimmed)
            trimmed.startsWith("[") && trimmed.contains("copy:") -> Unit
            else -> blocks += MdBlock.Paragraph(trimmed)
        }
    }
    if (inCode && code.isNotEmpty()) blocks += MdBlock.Code(code.toString().trimEnd())
    return blocks
}

/**
 * 行内格式：**粗体**、`代码`、*斜体*、$公式$。
 *
 * 手写扫描而不是正则替换，是为了避免 `**a** 和 **b**` 这种一行多组时错配。
 */
private fun inline(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val buffer = StringBuilder()
    fun flush() {
        if (buffer.isNotEmpty()) {
            append(buffer.toString())
            buffer.clear()
        }
    }
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    buffer.append("**"); i += 2
                }
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = baseColor.copy(alpha = 0.08f)
                        )
                    ) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    buffer.append('`'); i++
                }
            }

            // 行内公式原样保留，只改配色，避免和正文混在一起看不清边界
            text[i] == '$' -> {
                val end = text.indexOf('$', i + 1)
                if (end > i + 1) {
                    flush()
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontStyle = FontStyle.Italic,
                            color = baseColor.copy(alpha = 0.85f)
                        )
                    ) { append(text.substring(i, end + 1)) }
                    i = end + 1
                } else {
                    buffer.append('$'); i++
                }
            }

            else -> {
                buffer.append(text[i]); i++
            }
        }
    }
    flush()
}
