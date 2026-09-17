package dev.ymusictv.api

import dev.ymusictv.model.WaveOption
import dev.ymusictv.model.WaveRestrictions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object WaveRestrictionsApi {
    private val http = OkHttpClient()

    suspend fun load(token: String?): WaveRestrictions = withContext(Dispatchers.IO) {
        if (token.isNullOrBlank()) throw IOException("Нет OAuth-токена")
        val req = Request.Builder()
            .url("https://api.music.yandex.net/rotor/station/user:onyourwave/info")
            .header("Authorization", "OAuth $token")
            .header("User-Agent", "MusicTV/0.9.5.2 AndroidTV")
            .get().build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Wave info HTTP ${r.code}: ${text.take(240)}")
            val root = JSONObject(text)
            val rawResult = root.opt("result") ?: throw IOException("В Wave info нет result")
            val result: JSONObject = when (rawResult) {
                is JSONObject -> rawResult
                is JSONArray -> rawResult.optJSONObject(0) ?: throw IOException("Wave info вернул пустой result[]")
                else -> throw IOException("Неизвестный формат Wave info: ${rawResult.javaClass.simpleName}")
            }
            val station = result.optJSONObject("station") ?: result
            val restrictions = station.optJSONObject("restrictions2")
                ?: station.optJSONObject("restrictions")
                ?: result.optJSONObject("restrictions2")
                ?: result.optJSONObject("restrictions")
                ?: throw IOException("Яндекс не вернул restrictions/restrictions2")

            fun options(vararg keys: String): List<WaveOption> {
                var group: JSONObject? = null
                for (key in keys) {
                    group = restrictions.optJSONObject(key)
                    if (group != null) break
                }
                group ?: return emptyList()
                val arr = group.optJSONArray("possibleValues")
                    ?: group.optJSONArray("possible_values")
                    ?: return emptyList()
                val out = mutableListOf<WaveOption>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val value = item.optString("value")
                    val name = item.optString("name").ifBlank { value }
                    if (value.isNotBlank()) out += WaveOption(value, name)
                }
                return out
            }

            val parsed = WaveRestrictions(
                moods = options("moodEnergy", "mood_energy"),
                diversities = options("diversity"),
                languages = options("language")
            )
            if (parsed.moods.isEmpty() && parsed.diversities.isEmpty() && parsed.languages.isEmpty()) {
                throw IOException("restrictions получены, но possibleValues пусты")
            }
            parsed
        }
    }
}
