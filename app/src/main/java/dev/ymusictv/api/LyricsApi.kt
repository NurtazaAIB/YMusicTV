package dev.ymusictv.api

import android.util.Base64
import dev.ymusictv.model.LyricLine
import dev.ymusictv.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Lyrics flow matching the Android-client protocol:
 * signed metadata request -> downloadUrl -> LRC/TEXT body.
 */
class LyricsApi(
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val API = "https://api.music.yandex.net"
        private const val SIGN_KEY = "p93jhgh689SBReK6ghtw62"
        private const val USER_AGENT = "MusicTV/0.9.7 AndroidTV"
    }

    suspend fun load(trackId: String): Lyrics? = withContext(Dispatchers.IO) {
        val numericId = trackId.substringBefore(':')
        loadFormat(numericId, "LRC") ?: loadFormat(numericId, "TEXT")
    }

    private fun authorizedBuilder(url: okhttp3.HttpUrl): Request.Builder =
        Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .apply { tokenProvider()?.let { header("Authorization", "OAuth $it") } }

    private fun authorizedBuilder(url: String): Request.Builder =
        Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .apply { tokenProvider()?.let { header("Authorization", "OAuth $it") } }

    private fun loadFormat(trackId: String, format: String): Lyrics? {
        val timestamp = System.currentTimeMillis() / 1000L
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SIGN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.encodeToString(
            mac.doFinal("$trackId$timestamp".toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )

        val url = "$API/tracks/$trackId/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("format", format)
            .addQueryParameter("timeStamp", timestamp.toString())
            .addQueryParameter("sign", signature)
            .build()
        val request = authorizedBuilder(url).get().build()

        val envelope = http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("Lyrics HTTP ${response.code}: ${body.take(180)}")
            JSONObject(body)
        }
        val result = envelope.optJSONObject("result") ?: envelope
        val downloadUrl = result.optString("downloadUrl").takeIf { it.isNotBlank() } ?: return null

        // The official-client-style transport keeps OAuth on subsequent retrievals too.
        val raw = http.newCall(authorizedBuilder(downloadUrl).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Lyrics download HTTP ${response.code}: ${body.take(120)}")
            body
        }.removePrefix("\uFEFF")

        if (raw.isBlank()) return null
        if (format == "TEXT") {
            val plain = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
                .mapIndexed { index, text -> LyricLine(index * 5000L, text) }.toList()
            return Lyrics(plain, false).takeIf { plain.isNotEmpty() }
        }

        val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val parsed = mutableListOf<LyricLine>()
        raw.lineSequence().forEach { source ->
            val matches = stamp.findAll(source).toList()
            if (matches.isEmpty()) return@forEach
            val text = source.substring(matches.last().range.last + 1).trim()
            if (text.isBlank()) return@forEach
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fraction = m.groupValues[3]
                val ms = when (fraction.length) {
                    1 -> fraction.toLongOrNull()?.times(100) ?: 0L
                    2 -> fraction.toLongOrNull()?.times(10) ?: 0L
                    3 -> fraction.toLongOrNull() ?: 0L
                    else -> 0L
                }
                parsed += LyricLine((min * 60L + sec) * 1000L + ms, text)
            }
        }
        return Lyrics(parsed.sortedBy { it.timeMs }, true).takeIf { parsed.isNotEmpty() }
    }
}
