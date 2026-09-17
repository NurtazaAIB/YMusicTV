package dev.ymusictv.api

import dev.ymusictv.model.Track
import dev.ymusictv.model.WaveSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Modern My Wave protocol used by current Yandex clients: serialized seeds + rotor session. */
class ModernWaveApi(
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient = OkHttpClient()
) {
    data class Batch(val sessionId: String, val batchId: String, val tracks: List<Track>)

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun start(settings: WaveSettings): Batch = withContext(Dispatchers.IO) {
        val seeds = resolveSeeds(settings)
        if (seeds.isEmpty()) throw IOException("Яндекс не вернул serializedSeed для выбранных настроек")

        val payload = JSONObject()
            .put("seeds", JSONArray(seeds))
            .put("includeTracksInResponse", true)
            .put("includeWaveModel", true)
            .put("interactive", true)

        val result = postResult("https://api.music.yandex.net/rotor/session/new", payload)
        parseBatch(result)
    }

    suspend fun next(sessionId: String, afterTrackId: String?): Batch = withContext(Dispatchers.IO) {
        val payload = JSONObject()
        if (!afterTrackId.isNullOrBlank()) payload.put("queue", JSONArray().put(afterTrackId))
        payload.put("feedbacks", JSONArray())
        val result = postResult("https://api.music.yandex.net/rotor/session/$sessionId/tracks", payload)
        parseBatch(result, sessionId)
    }

    /** Resolve server-owned serializedSeed values instead of inventing language/mood codes. */
    private fun resolveSeeds(settings: WaveSettings): List<String> {
        val result = getResult("https://api.music.yandex.net/rotor/wave/settings")
        val restrictions = result.optJSONObject("settingRestrictions")
            ?: throw IOException("Wave settings: нет settingRestrictions")

        fun seed(groupKeys: List<String>, wanted: String): String? {
            var group: JSONObject? = null
            for (key in groupKeys) {
                group = restrictions.optJSONObject(key)
                if (group != null) break
            }
            val values = group?.optJSONArray("possibleValues") ?: return null
            for (i in 0 until values.length()) {
                val item = values.optJSONObject(i) ?: continue
                if (item.optString("value") == wanted) {
                    return item.optString("serializedSeed").takeIf { it.isNotBlank() }
                }
            }
            return null
        }

        return listOfNotNull(
            seed(listOf("moodEnergy", "mood_energy", "settingMoodEnergy"), settings.mood),
            seed(listOf("diversity", "settingDiversity"), settings.diversity),
            seed(listOf("language", "settingLanguage"), settings.language)
        ).distinct()
    }

    private fun parseBatch(result: JSONObject, fallbackSessionId: String? = null): Batch {
        val sessionId = result.optString("radioSessionId").ifBlank { fallbackSessionId.orEmpty() }
        if (sessionId.isBlank()) throw IOException("Новая Волна не вернула radioSessionId")
        val batchId = result.optString("batchId")
        val sequence = result.optJSONArray("sequence") ?: JSONArray()
        val tracks = mutableListOf<Track>()
        for (i in 0 until sequence.length()) {
            val track = sequence.optJSONObject(i)?.optJSONObject("track") ?: continue
            tracks += parseTrack(track)
        }
        if (tracks.isEmpty()) throw IOException("Новая Волна вернула пустую sequence")
        return Batch(sessionId, batchId, tracks)
    }

    private fun parseTrack(j: JSONObject): Track {
        val artists = j.optJSONArray("artists") ?: JSONArray()
        val artist = (0 until artists.length()).joinToString(", ") {
            artists.optJSONObject(it)?.optString("name").orEmpty()
        }
        val cover = j.optString("coverUri").takeIf { it.isNotBlank() }
            ?.replace("%%", "400x400")
            ?.let { if (it.startsWith("http")) it else "https://$it" }
        val albumId = j.optJSONArray("albums")?.optJSONObject(0)?.opt("id")?.toString()
        return Track(
            id = j.opt("id").toString(),
            albumId = albumId,
            title = j.optString("title", "Без названия"),
            artist = artist,
            coverUrl = cover,
            durationMs = j.optLong("durationMs")
        )
    }

    private fun getResult(url: String): JSONObject {
        val req = auth(Request.Builder().url(url)).get().build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Wave settings HTTP ${r.code}: ${text.take(220)}")
            return JSONObject(text).optJSONObject("result") ?: throw IOException("Wave settings: пустой result")
        }
    }

    private fun postResult(url: String, payload: JSONObject): JSONObject {
        val req = auth(Request.Builder().url(url))
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Wave session HTTP ${r.code}: ${text.take(260)}")
            return JSONObject(text).optJSONObject("result") ?: throw IOException("Wave session: пустой result")
        }
    }

    private fun auth(builder: Request.Builder): Request.Builder {
        val token = tokenProvider().orEmpty()
        if (token.isBlank()) throw IOException("Нет OAuth-токена")
        return builder
            .header("Authorization", "OAuth $token")
            .header("User-Agent", "MusicTV/0.9.5.4 AndroidTV")
    }
}
