package `fun`.kirari.hanako.pc

/**
 * 从模型回答里抽选项字母，用于托盘图标和悬浮小球上显示 ABCD。
 * 宁可返回空串，也不要把正常文字里的 A 误判成选项。
 */
object AnswerParser {

    private val patterns = listOf(
        // 答案：B / 答案: ABD / 答案是 C
        Regex("""答\s*案\s*[是为]?\s*[:：]\s*([A-Ha-h]{1,6})"""),
        Regex("""答\s*案\s*[是为]\s*([A-Ha-h]{1,6})"""),
        // 正确选项：AB / 应选 C / 选择 D
        Regex("""(?:正确选项|应选|选择|选)\s*[:：]?\s*([A-Ha-h]{1,6})"""),
        // 整行只有一个字母组合
        Regex("""(?m)^\s*([A-Ha-h]{1,6})\s*$""")
    )

    fun lettersOf(answer: String): String {
        val text = answer.take(400)
        for (pattern in patterns) {
            val candidate = pattern.find(text)?.groupValues?.getOrNull(1) ?: continue
            val normalized = normalize(candidate)
            if (normalized.isNotEmpty()) return normalized
        }
        return ""
    }

    /** 只保留 A-H、去重、按字母序，最多 4 个（多选题常见上限）。 */
    private fun normalize(raw: String): String {
        val letters = raw.uppercase().filter { it in 'A'..'H' }.toSortedSet()
        if (letters.isEmpty()) return ""
        return letters.take(4).joinToString("")
    }
}
