package dev.ymusictv.api

import android.util.Base64
import dev.ymusictv.model.LyricLine
import dev.ymusictv.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LyricsApi(
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val API = "https://api.music.yandex.net"
        private const val SIGN_KEY = "p93jhgh689SBReK6ghtw62"
        private const val USER_AGENT = "MusicTV/0.9.8.7 AndroidTV"\n        // Current official Android client identifies itself with this API header.\n        private const val MUSIC_CLIENT = "YandexMusicAndroid/24026442"
    }

    suspend fun load(trackId: String): Lyrics? = withContext(Dispatchers.IO) {
        val fullId = trackId.trim()
        val numericId = fullId.substringBefore(':')

        // Always try the synchronized source first. Supplement is only a plain-text fallback.
        runCatching { loadSigned(numericId, "LRC") }.getOrNull()
            ?: loadSupplement(fullId)
            ?: if (numericId != fullId) loadSupplement(numericId) else null
            ?: runCatching { loadSigned(numericId, "TEXT") }.getOrNull()
    }

    private fun builder(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("X-Yandex-Music-Client", MUSIC_CLIENT)\n        .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
        .header("Accept", "application/json, text/plain, */*")
        .apply { tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "OAuth $it") } }

    private fun builder(url: okhttp3.HttpUrl) = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
        .header("Accept", "application/json, text/plain, */*")
        .apply { tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "OAuth $it") } }

    private fun loadSupplement(trackId: String): Lyrics? {
        val request = builder("$API/tracks/$trackId/supplement").get().build()
        val body = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string().orEmpty()
        }
        if (body.isBlank()) return null
        val root = JSONObject(body)
        val result = root.optJSONObject("result") ?: root
        val lyric = result.optJSONObject("lyrics") ?: return null
        if (lyric.has("hasRights") && !lyric.optBoolean("hasRights", true)) {
            return plainLyrics(lyric.optString("lyrics"))
        }
        val raw = sequenceOf("fullLyrics", "full_lyrics", "lyrics")
            .map { lyric.optString(it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return plainLyrics(raw)
    }

    private fun loadSigned(trackId: String, format: String): Lyrics? {
        val timestamp = System.currentTimeMillis() / 1000L
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SIGN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.encodeToString(mac.doFinal("$trackId$timestamp".toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val url = "$API/tracks/$trackId/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("format", format)
            .addQueryParameter("timeStamp", timestamp.toString())
            .addQueryParameter("sign", signature)
            .build()
        val envelope = http.newCall(builder(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) return null
            JSONObject(r.body?.string().orEmpty())
        }
        val result = envelope.optJSONObject("result") ?: envelope
        val download = result.optString("downloadUrl").takeIf { it.isNotBlank() } ?: return null
        val raw = http.newCall(builder(download).get().build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string().orEmpty().removePrefix("\uFEFF")
        }
        if (format == "TEXT") return plainLyrics(raw)
        val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val parsed = mutableListOf<LyricLine>()
        raw.lineSequence().forEach { source ->
            val matches = stamp.findAll(source).toList()
            if (matches.isEmpty()) return@forEach
            val text = source.substring(matches.last().range.last + 1).trim()
            if (text.isBlank()) return@forEach
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: 0
                val sec = m.groupValues[2].toLongOrNull() ?: 0
                val f = m.groupValues[3]
                val ms = when (f.length) {
                    1 -> (f.toLongOrNull() ?: 0) * 100
                    2 -> (f.toLongOrNull() ?: 0) * 10
                    3 -> f.toLongOrNull() ?: 0
                    else -> 0
                }
                parsed += LyricLine((min * 60 + sec) * 1000 + ms, text)
            }
        }
        return Lyrics(parsed.sortedBy { it.timeMs }, true).takeIf { parsed.isNotEmpty() }
    }

    private fun plainLyrics(raw: String): Lyrics? {
        if (raw.isBlank()) return null
        val lines = raw.replace("\r\n", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapIndexed { i, s -> LyricLine(i.toLong(), s) }
            .toList()
        return Lyrics(lines, false).takeIf { lines.isNotEmpty() }
    }
}
