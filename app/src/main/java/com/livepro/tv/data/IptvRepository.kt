package com.livepro.tv.data

import com.livepro.tv.model.ActivationStatus
import com.livepro.tv.model.CategoryItem
import com.livepro.tv.model.ChannelItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class IptvRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun loadPlaylist(status: ActivationStatus): Pair<List<CategoryItem>, List<ChannelItem>> =
        withContext(Dispatchers.IO) {
            if (status.serverType == "m3u" && status.m3uUrl.isNotBlank()) {
                fetchM3u(status.m3uUrl)
            } else if (status.host.isNotBlank()) {
                fetchXtreamLive(status.host, status.username, status.password)
            } else {
                Pair(emptyList(), emptyList())
            }
        }

    private fun fetchXtreamLive(
        host: String,
        user: String,
        pass: String
    ): Pair<List<CategoryItem>, List<ChannelItem>> {
        val categories = mutableListOf(CategoryItem("ALL", "جميع القنوات"))
        val channels = mutableListOf<ChannelItem>()

        val catUrl = "$host/player_api.php?username=$user&password=$pass&action=get_live_categories"
        client.newCall(Request.Builder().url(catUrl).build()).execute().use { res ->
            if (res.isSuccessful) {
                val arr = JSONArray(res.body?.string().orEmpty())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    categories.add(
                        CategoryItem(
                            id = obj.optString("category_id"),
                            name = obj.optString("category_name")
                        )
                    )
                }
            }
        }

        val streamUrlApi = "$host/player_api.php?username=$user&password=$pass&action=get_live_streams"
        client.newCall(Request.Builder().url(streamUrlApi).build()).execute().use { res ->
            if (res.isSuccessful) {
                val arr = JSONArray(res.body?.string().orEmpty())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val streamId = obj.optString("stream_id")
                    channels.add(
                        ChannelItem(
                            id = streamId,
                            name = obj.optString("name"),
                            streamUrl = "$host/live/$user/$pass/$streamId.ts",
                            categoryId = obj.optString("category_id"),
                            logoUrl = obj.optString("stream_icon"),
                            number = i + 1
                        )
                    )
                }
            }
        }
        return Pair(categories, channels)
    }

    private fun fetchM3u(m3uUrl: String): Pair<List<CategoryItem>, List<ChannelItem>> {
        val categoriesMap = linkedMapOf("ALL" to "جميع القنوات")
        val channels = mutableListOf<ChannelItem>()

        client.newCall(Request.Builder().url(m3uUrl).build()).execute().use { res ->
            if (!res.isSuccessful) return Pair(emptyList(), emptyList())
            val lines = res.body?.string().orEmpty().lines()
            var currentName = ""
            var currentGroup = "عام"
            var currentLogo = ""
            var index = 1

            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.startsWith("#EXTINF")) {
                    currentGroup = Regex("group-title="([^"]+)"").find(line)?.groupValues?.get(1) ?: "عام"
                    currentLogo = Regex("tvg-logo="([^"]+)"").find(line)?.groupValues?.get(1) ?: ""
                    currentName = line.substringAfterLast(",").trim().ifBlank { "Channel $index" }
                    categoriesMap.putIfAbsent(currentGroup, currentGroup)
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    channels.add(
                        ChannelItem(
                            id = index.toString(),
                            name = currentName.ifBlank { "Channel $index" },
                            streamUrl = line,
                            categoryId = currentGroup,
                            logoUrl = currentLogo,
                            number = index
                        )
                    )
                    index++
                    currentName = ""
                }
            }
        }

        val categories = categoriesMap.map { CategoryItem(it.key, it.value) }
        return Pair(categories, channels)
    }
}
