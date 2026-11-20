package `fun`.kirari.hanako.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HISTORY_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

/** 一条作答记录。 */
data class HistoryEntry(
    val id: String,
    val createdAt: Long,
    val route: SolveRoute,
    val answer: String,
    val letters: String,
    val transcript: String? = null,
    val ocrText: String? = null,
    val visionModel: String = "",
    val textModel: String = "",
    val searchQuery: String? = null
) {
    val timeLabel: String
        get() = HISTORY_TIME_FORMATTER.format(
            Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault())
        )

    /** 列表里的一行摘要。 */
    val summary: String
        get() {
            val head = transcript?.takeIf { it.isNotBlank() }
                ?: ocrText?.takeIf { it.isNotBlank() }
                ?: answer
            return head.replace(Regex("\\s+"), " ").trim().take(60)
        }
}

/**
 * 历史记录，落在一个 JSON 文件里（`~/.hanako-pc/history.json`）。
 *
 * 没有用 kotlinx 的序列化插件生成 serializer：整个应用只有这一处需要持久化结构化数据，
 * 手写读写反而更省事，也避免为了一个 @Serializable 给 pc/app 再加一个编译插件。
 * 文件损坏时按「历史为空」处理，不能让一条坏记录把应用卡死在启动。
 */
object PcHistory {

    private val json = Json { prettyPrint = true }

    fun file(): File = File(System.getProperty("user.home"), ".hanako-pc/history.json")

    fun load(): List<HistoryEntry> = runCatching {
        val f = file()
        if (!f.exists()) return emptyList()
        val root = json.parseToJsonElement(f.readText()).jsonArray
        root.mapNotNull { element ->
            runCatching { element.jsonObject.toEntry() }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /** 新记录放最前面，并裁到上限。 */
    fun append(entry: HistoryEntry, limit: Int): List<HistoryEntry> {
        val updated = (listOf(entry) + load()).take(limit.coerceIn(10, 2000))
        write(updated)
        return updated
    }

    fun delete(id: String): List<HistoryEntry> {
        val updated = load().filterNot { it.id == id }
        write(updated)
        return updated
    }

    fun clear(): List<HistoryEntry> {
        runCatching { file().delete() }
        return emptyList()
    }

    private fun write(entries: List<HistoryEntry>) {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            val array = buildJsonArray { entries.forEach { add(it.toJson()) } }
            f.writeText(json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), array))
        }
    }

    private fun HistoryEntry.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("createdAt", createdAt)
        put("route", route.name)
        put("answer", answer)
        put("letters", letters)
        transcript?.let { put("transcript", it) }
        ocrText?.let { put("ocrText", it) }
        put("visionModel", visionModel)
        put("textModel", textModel)
        searchQuery?.let { put("searchQuery", it) }
    }

    private fun JsonObject.toEntry(): HistoryEntry {
        fun str(name: String): String? =
            this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        return HistoryEntry(
            id = str("id") ?: error("缺少 id"),
            createdAt = this["createdAt"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
                ?: 0L,
            route = str("route")?.let { runCatching { SolveRoute.valueOf(it) }.getOrNull() }
                ?: SolveRoute.MULTIMODAL_DIRECT,
            answer = str("answer").orEmpty(),
            letters = str("letters").orEmpty(),
            transcript = str("transcript"),
            ocrText = str("ocrText"),
            visionModel = str("visionModel").orEmpty(),
            textModel = str("textModel").orEmpty(),
            searchQuery = str("searchQuery")
        )
    }
}
