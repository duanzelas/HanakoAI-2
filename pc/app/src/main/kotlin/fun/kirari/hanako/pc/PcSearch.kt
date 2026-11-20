package `fun`.kirari.hanako.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 一条搜索结果。 */
data class SearchHit(val title: String, val snippet: String, val url: String)

/**
 * 联网搜索。
 *
 * 三家接口形态完全不同，这里各写一个最小的请求/解析，只取「标题 + 摘要 + 链接」：
 * 模型要的是参考资料，不需要分页、相关度分数这些元数据。
 *
 * 没有引入 Android 端那套 `SearchEngineAdapter`——它依赖 app 模块里的配置模型，
 * 而 PC 端只需要三个字段，复制 40 行比造一层抽象便宜。
 */
class PcSearchClient(private val client: OkHttpClient = defaultClient()) {

    suspend fun search(settings: PcSettings, query: String): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val key = settings.searchApiKey.trim()
        if (key.isBlank()) return emptyList()
        val count = settings.searchResultCount.coerceIn(1, 10)
        return when (settings.searchKind) {
            SearchKind.TAVILY -> tavily(key, q, count)
            SearchKind.BRAVE -> brave(key, q, count)
            SearchKind.SERPER -> serper(key, q, count)
        }
    }

    private fun tavily(key: String, query: String, count: Int): List<SearchHit> {
        val payload = buildJsonObject {
            put("api_key", key)
            put("query", query)
            put("max_results", count)
            put("search_depth", "basic")
        }
        val body = post("https://api.tavily.com/search", payload, mapOf())
        return body["results"].asObjects().mapNotNull { obj ->
            hit(
                title = obj["title"]?.jsonPrimitive?.contentOrNull,
                snippet = obj["content"]?.jsonPrimitive?.contentOrNull,
                url = obj["url"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    private fun brave(key: String, query: String, count: Int): List<SearchHit> {
        val url = "https://api.search.brave.com/res/v1/web/search?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + "&count=" + count
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", key)
            .get()
            .build()
        val body = execute(request)
        return body?.get("web")?.jsonObject?.get("results").asObjects().mapNotNull { obj ->
            hit(
                title = obj["title"]?.jsonPrimitive?.contentOrNull,
                snippet = obj["description"]?.jsonPrimitive?.contentOrNull,
                url = obj["url"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    private fun serper(key: String, query: String, count: Int): List<SearchHit> {
        val payload = buildJsonObject {
            put("q", query)
            put("num", count)
        }
        val body = post("https://google.serper.dev/search", payload, mapOf("X-API-KEY" to key))
        return body["organic"].asObjects().mapNotNull { obj ->
            hit(
                title = obj["title"]?.jsonPrimitive?.contentOrNull,
                snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull,
                url = obj["link"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    private fun post(url: String, payload: JsonObject, headers: Map<String, String>): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return execute(request) ?: JsonObject(emptyMap())
    }

    private fun execute(request: Request): JsonObject? {
        return runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("搜索接口 ${response.code}：${text.take(160)}")
                }
                json.parseToJsonElement(text).jsonObject
            }
        }.getOrNull()
    }

    private fun hit(title: String?, snippet: String?, url: String?): SearchHit? {
        if (title.isNullOrBlank() && snippet.isNullOrBlank()) return null
        return SearchHit(
            title = title?.trim().orEmpty(),
            snippet = snippet?.trim().orEmpty(),
            url = url?.trim().orEmpty()
        )
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        /** 把搜索结果拼成给模型看的参考资料块。 */
        fun renderHits(hits: List<SearchHit>): String {
            if (hits.isEmpty()) return ""
            return buildString {
                appendLine("以下是联网检索到的参考资料，可能不完整或有噪音，请自行判断是否采信：")
                hits.forEachIndexed { index, hit ->
                    append(index + 1).append(". ")
                    if (hit.title.isNotBlank()) append(hit.title).append("：")
                    appendLine(hit.snippet.ifBlank { "（无摘要）" })
                    if (hit.url.isNotBlank()) appendLine("   " + hit.url)
                }
            }.trim()
        }
    }
}

/**
 * 安全取数组：接口返回结构变化（少字段、类型不对）时返回空表，
 * 不要把整次作答炸掉——搜索只是辅助，失败也要能继续答题。
 */
private fun kotlinx.serialization.json.JsonElement?.asObjects(): List<JsonObject> =
    this?.let { element ->
        runCatching { element.jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
    } ?: emptyList()

@Suppress("unused")
private fun JsonObject.stringField(name: String): String? =
    this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

@Suppress("unused")
private fun JsonObject.intField(name: String): Int? =
    this[name]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
