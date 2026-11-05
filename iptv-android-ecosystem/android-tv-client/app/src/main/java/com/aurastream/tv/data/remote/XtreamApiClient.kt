package com.aurastream.tv.data.remote

import com.aurastream.tv.data.remote.dto.XtreamCredentials
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

class XtreamAuthException(message: String) : IOException(message)

data class XtreamAccount(
    val status: String,
    val expiresAtEpochSec: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
)

data class XtreamCategory(val id: String, val name: String)

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val episodeNum: Int,
    val title: String,
    val extension: String,
    val plot: String?,
    val durationSecs: Int?,
    val imageUrl: String?,
)

data class XtreamVodInfo(
    val plot: String?,
    val genre: String?,
    val releaseYear: String?,
    val backdropUrl: String?,
    val rating: String?,
    val durationSecs: Int?,
)

data class XtreamShortEpg(val title: String, val description: String?, val start: Long, val end: Long)

/**
 * Xtream Codes `player_api.php` client.
 *
 * Large endpoints (`get_live_streams`, `get_vod_streams`, `get_series`) are consumed
 * with a streaming [JsonReader]: each array element is materialised individually and
 * handed to [onBatch] in chunks, so 50k+ item catalogues never sit in memory at once.
 * Field access is defensive because panels disagree on types (ids as numbers or strings,
 * `false` instead of null, base64 EPG titles, ...).
 */
class XtreamApiClient(private val client: OkHttpClient) {

    fun baseUrl(c: XtreamCredentials): HttpUrl = c.host.trimEnd('/').toHttpUrl()

    private fun apiUrl(c: XtreamCredentials, action: String? = null, vararg extra: Pair<String, String>): HttpUrl =
        baseUrl(c).newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", c.username)
            .addQueryParameter("password", c.password)
            .apply {
                if (action != null) addQueryParameter("action", action)
                extra.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()

    fun xmltvUrl(c: XtreamCredentials): String = baseUrl(c).newBuilder()
        .addPathSegment("xmltv.php")
        .addQueryParameter("username", c.username)
        .addQueryParameter("password", c.password)
        .build().toString()

    fun liveUrl(c: XtreamCredentials, streamId: String, extension: String = "ts"): String =
        streamUrl(c, "live", streamId, extension)

    fun movieUrl(c: XtreamCredentials, streamId: String, extension: String): String =
        streamUrl(c, "movie", streamId, extension.ifBlank { "mp4" })

    fun episodeUrl(c: XtreamCredentials, episodeId: String, extension: String): String =
        streamUrl(c, "series", episodeId, extension.ifBlank { "mp4" })

    private fun streamUrl(c: XtreamCredentials, kind: String, id: String, ext: String): String =
        baseUrl(c).newBuilder()
            .addPathSegment(kind)
            .addPathSegment(c.username)
            .addPathSegment(c.password)
            .addPathSegment("$id.$ext")
            .build().toString()

    suspend fun authenticate(c: XtreamCredentials): XtreamAccount = withContext(Dispatchers.IO) {
        val root = fetchJson(apiUrl(c))
        val info = (root as? JsonObject)?.obj("user_info")
            ?: throw XtreamAuthException("Invalid response from Xtream server")
        if (info.int("auth") == 0) throw XtreamAuthException("Xtream login rejected (wrong username or password)")
        val status = info.str("status") ?: "Active"
        if (!status.equals("Active", ignoreCase = true)) throw XtreamAuthException("Xtream account status: $status")
        XtreamAccount(
            status = status,
            expiresAtEpochSec = info.str("exp_date")?.toLongOrNull(),
            maxConnections = info.int("max_connections"),
            activeConnections = info.int("active_cons"),
        )
    }

    suspend fun categories(c: XtreamCredentials, type: String): List<XtreamCategory> = withContext(Dispatchers.IO) {
        val action = when (type) {
            "live" -> "get_live_categories"
            "movie" -> "get_vod_categories"
            else -> "get_series_categories"
        }
        val arr = fetchJson(apiUrl(c, action)) as? JsonArray ?: return@withContext emptyList()
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("category_id") ?: return@mapNotNull null
            XtreamCategory(id, o.str("category_name") ?: id)
        }
    }

    /** Streams `get_live_streams` / `get_vod_streams` / `get_series` in batches. */
    suspend fun streamCatalogue(
        c: XtreamCredentials,
        type: String,
        batchSize: Int = 1000,
        onBatch: suspend (List<JsonObject>) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val action = when (type) {
            "live" -> "get_live_streams"
            "movie" -> "get_vod_streams"
            else -> "get_series"
        }
        val request = Request.Builder().url(apiUrl(c, action)).build()
        var total = 0
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Xtream $action failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty body for $action")
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                if (reader.peek() != JsonToken.BEGIN_ARRAY) return@use // some panels return {} when empty
                reader.beginArray()
                val batch = ArrayList<JsonObject>(batchSize)
                while (reader.hasNext()) {
                    val el = JsonParser.parseReader(reader)
                    if (el is JsonObject) batch += el
                    if (batch.size >= batchSize) {
                        coroutineContext.ensureActive()
                        onBatch(ArrayList(batch))
                        total += batch.size
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) {
                    onBatch(batch)
                    total += batch.size
                }
            }
        }
        total
    }

    suspend fun seriesEpisodes(c: XtreamCredentials, seriesId: String): List<XtreamEpisode> = withContext(Dispatchers.IO) {
        val root = fetchJson(apiUrl(c, "get_series_info", "series_id" to seriesId)) as? JsonObject
            ?: return@withContext emptyList()
        val episodes = root.get("episodes")
        val result = ArrayList<XtreamEpisode>()
        val seasonsToLists: List<Pair<String?, JsonArray>> = when (episodes) {
            is JsonObject -> episodes.entrySet().mapNotNull { (k, v) -> (v as? JsonArray)?.let { k to it } }
            is JsonArray -> episodes.mapNotNull { (it as? JsonArray)?.let { arr -> null to arr } }
            else -> emptyList()
        }
        for ((seasonKey, list) in seasonsToLists) {
            for (el in list) {
                val e = el as? JsonObject ?: continue
                val info = e.obj("info")
                val id = e.str("id") ?: continue
                result += XtreamEpisode(
                    id = id,
                    season = e.int("season") ?: seasonKey?.toIntOrNull() ?: 1,
                    episodeNum = e.int("episode_num") ?: (result.size + 1),
                    title = e.str("title") ?: "Episode ${e.str("episode_num") ?: ""}".trim(),
                    extension = e.str("container_extension") ?: "mp4",
                    plot = info?.str("plot"),
                    durationSecs = info?.int("duration_secs"),
                    imageUrl = info?.str("movie_image"),
                )
            }
        }
        result.sortedWith(compareBy({ it.season }, { it.episodeNum }))
    }

    suspend fun vodInfo(c: XtreamCredentials, vodId: String): XtreamVodInfo? = withContext(Dispatchers.IO) {
        val root = fetchJson(apiUrl(c, "get_vod_info", "vod_id" to vodId)) as? JsonObject ?: return@withContext null
        val info = root.obj("info") ?: return@withContext null
        XtreamVodInfo(
            plot = info.str("plot") ?: info.str("description"),
            genre = info.str("genre"),
            releaseYear = (info.str("releasedate") ?: info.str("release_date"))?.take(4),
            backdropUrl = info.firstString("backdrop_path"),
            rating = info.str("rating"),
            durationSecs = info.int("duration_secs"),
        )
    }

    suspend fun shortEpg(c: XtreamCredentials, streamId: String, limit: Int = 4): List<XtreamShortEpg> =
        withContext(Dispatchers.IO) {
            val root = fetchJson(apiUrl(c, "get_short_epg", "stream_id" to streamId, "limit" to "$limit")) as? JsonObject
            val listings = root?.get("epg_listings") as? JsonArray ?: return@withContext emptyList()
            listings.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val start = o.str("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
                val end = o.str("stop_timestamp")?.toLongOrNull() ?: return@mapNotNull null
                XtreamShortEpg(decodeB64(o.str("title")) ?: "", decodeB64(o.str("description")), start * 1000, end * 1000)
            }
        }

    private fun fetchJson(url: HttpUrl): JsonElement {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw XtreamAuthException("Xtream server refused access (HTTP ${response.code})")
            if (!response.isSuccessful) throw IOException("Xtream request failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty Xtream response")
            return JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                JsonParser.parseReader(reader)
            }
        }
    }

    private fun decodeB64(value: String?): String? = value?.let {
        runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(it)
    }
}

// ---------------------------------------------------------------- JSON helpers

internal fun JsonObject.str(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    if (!el.isJsonPrimitive) return null
    val p = el.asJsonPrimitive
    if (p.isBoolean) return null // panels send `false` for "no value"
    return p.asString.takeIf { it.isNotBlank() && it != "null" }
}

internal fun JsonObject.int(key: String): Int? = str(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

/** Reads a string or the first string of an array (e.g. `backdrop_path`). */
internal fun JsonObject.firstString(key: String): String? = when (val el = get(key)) {
    is JsonArray -> el.firstOrNull { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    else -> str(key)
}
