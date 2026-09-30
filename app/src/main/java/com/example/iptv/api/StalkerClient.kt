package com.example.iptv.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class StalkerClient(private val portalUrl: String, private val macAddress: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = if (portalUrl.contains("://")) {
        portalUrl.substringBefore("/", portalUrl)
    } else {
        "http://$portalUrl"
    }

    private var token: String = ""
    private var loadUrl: String = ""

    private val loadPaths = listOf(
        "/stalker_portal/server/load.php",
        "/server/load.php",
        "/portal.php",
        "/c/server/load.php"
    )

    private val ua = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    suspend fun connect(): Boolean {
        for (path in loadPaths) {
            loadUrl = baseUrl + path
            try {
                val params = "type=stb&action=handshake&token=&JsHttpRequest=1-xml"
                val request = Request.Builder()
                    .url("$loadUrl?$params")
                    .addHeader("User-Agent", ua)
                    .addHeader("X-User-Agent", "Model: MAG250; Link: WiFi")
                    .addHeader("Referer", "$baseUrl/c/")
                    .addHeader("Cookie", "mac=$macAddress; stb_lang=en; timezone=Europe/Tirane")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    val tokenValue = json.getAsJsonObject("js")?.get("token")?.asString
                    if (!tokenValue.isNullOrEmpty()) {
                        token = tokenValue
                        return true
                    }
                }
                response.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
    }

    suspend fun getGenres(): Map<String, String> {
        return try {
            val params = "type=itv&action=get_genres&JsHttpRequest=1-xml"
            val response = makeRequest(params)
            
            val genres = mutableMapOf<String, String>()
            val items = response.getAsJsonArray("js")
            
            for (item in items) {
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: continue
                if (id.isEmpty() || id == "*") continue
                
                val title = obj.get("title")?.asString 
                    ?: obj.get("name")?.asString 
                    ?: id
                genres[id] = title
            }
            genres
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    suspend fun getChannels(): List<ChannelData> {
        return try {
            val genres = getGenres()
            val params = "type=itv&action=get_all_channels&JsHttpRequest=1-xml"
            val response = makeRequest(params)
            
            val channels = mutableListOf<ChannelData>()
            val data = response.getAsJsonObject("js").getAsJsonArray("data")
            
            for (item in data) {
                val obj = item.asJsonObject
                val name = obj.get("name")?.asString ?: continue
                val cmd = obj.get("cmd")?.asString ?: ""
                val genreId = obj.get("tv_genre_id")?.asString ?: ""
                val group = genres[genreId] ?: "Pa kategori"
                
                channels.add(ChannelData(name, cmd, group))
            }
            channels
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun resolveChannel(cmd: String): String {
        return try {
            val params = "type=itv&action=create_link&cmd=$cmd&forced_storage=undefined&disable_ad=0&JsHttpRequest=1-xml"
            val response = makeRequest(params)
            
            val url = response.getAsJsonObject("js")?.get("cmd")?.asString ?: ""
            url.trim()
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private suspend fun makeRequest(params: String): JsonObject {
        val request = Request.Builder()
            .url("$loadUrl?$params")
            .addHeader("User-Agent", ua)
            .addHeader("X-User-Agent", "Model: MAG250; Link: WiFi")
            .addHeader("Referer", "$baseUrl/c/")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Cookie", "mac=$macAddress; stb_lang=en; timezone=Europe/Tirane")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: "{}"
        response.close()
        
        return JsonParser.parseString(body).asJsonObject
    }

    fun exportUrls(channels: List<ChannelData>, urls: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        
        for (channel in channels) {
            val url = urls[channel.name] ?: continue
            sb.append("#EXTINF:-1 group-title=\"${channel.group}\",${channel.name}\n")
            sb.append("$url\n")
        }
        
        return sb.toString()
    }
}

data class ChannelData(
    val name: String,
    val cmd: String,
    val group: String
)
