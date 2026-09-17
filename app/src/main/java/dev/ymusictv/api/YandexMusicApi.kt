package dev.ymusictv.api

import dev.ymusictv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.util.UUID

class YandexMusicApi(private val http: OkHttpClient = OkHttpClient()) {
    companion object {
        const val CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
        private const val CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300"
        const val API = "https://api.music.yandex.net"
        const val OAUTH = "https://oauth.yandex.ru"
        private const val SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA"
    }
    var token: String? = null
    private var accountUid: String? = null

    suspend fun requestDeviceCode(): DeviceCode = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_id", UUID.randomUUID().toString().replace("-", "").take(10))
            .add("device_name", "Музыка TV")
            .build()
        executeJson(Request.Builder().url("$OAUTH/device/code").post(body).build()).let { j ->
            DeviceCode(
                j.getString("device_code"),
                j.getString("user_code"),
                j.getString("verification_url"),
                j.getInt("expires_in"),
                j.optInt("interval", 5)
            )
        }
    }

    suspend fun pollDeviceToken(deviceCode: String): OAuthToken? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "device_code")
            .add("code", deviceCode)
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .build()
        val req = Request.Builder().url("$OAUTH/token").post(body).build()
        http.newCall(req).execute().use { r ->
            val j = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
            if (!r.isSuccessful) {
                when (j.optString("error")) {
                    "authorization_pending", "slow_down" -> return@withContext null
                    "expired_token" -> throw IOException("Код авторизации истёк")
                    "access_denied" -> throw IOException("Авторизация отменена")
                    else -> throw IOException(j.optString("error_description", "OAuth HTTP ${r.code}"))
                }
            }
            OAuthToken(
                j.getString("access_token"),
                j.optString("refresh_token").ifBlank { null },
                j.optInt("expires_in").takeIf { it > 0 },
                j.optString("token_type").ifBlank { null }
            ).also { token = it.accessToken }
        }
    }

    suspend fun refreshAccessToken(refreshToken: String): OAuthToken = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .build()
        val j = executeJson(Request.Builder().url("$OAUTH/token").post(body).build())
        OAuthToken(
            j.getString("access_token"),
            j.optString("refresh_token").ifBlank { refreshToken },
            j.optInt("expires_in").takeIf { it > 0 },
            j.optString("token_type").ifBlank { null }
        ).also { token = it.accessToken }
    }

    suspend fun accountName(): String {
        val result = getResult("$API/account/status"); accountUid = result.optJSONObject("account")?.opt("uid")?.toString()
        return result.optJSONObject("account")?.optString("displayName")?.ifBlank { null } ?: result.optJSONObject("account")?.optString("login")?.ifBlank { null } ?: "Яндекс"
    }

    suspend fun myWave(queue: String? = null): WaveBatch {
        val suffix = buildString { append("?settings2=true"); queue?.let { append("&queue=").append(URLEncoder.encode(it, "UTF-8")) } }
        val result = getResult("$API/rotor/station/user:onyourwave/tracks$suffix"); val tracks = mutableListOf<Track>(); val sequence = result.optJSONArray("sequence") ?: JSONArray()
        for (i in 0 until sequence.length()) sequence.optJSONObject(i)?.optJSONObject("track")?.let { tracks += parseTrack(it) }
        return WaveBatch(result.optString("batchId"), tracks)
    }

    suspend fun chart(option: String = ""): List<Track> {
        val suffix = if (option.isBlank()) "" else "/${URLEncoder.encode(option, "UTF-8")}"; val result = getResult("$API/landing3/chart$suffix")
        val arr = result.optJSONArray("chart") ?: result.optJSONObject("chart")?.optJSONArray("tracks") ?: JSONArray(); val out = mutableListOf<Track>()
        for (i in 0 until arr.length()) { val item = arr.optJSONObject(i) ?: continue; val track = item.optJSONObject("track") ?: item; if (track.has("id")) out += parseTrack(track) }
        return out
    }

    suspend fun setWaveSettings(settings: WaveSettings) = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("moodEnergy", settings.mood).add("diversity", settings.diversity).add("language", settings.language).add("type", "rotor").build()
        http.newCall(authBuilder("$API/rotor/station/user:onyourwave/settings2").post(body).build()).execute().use { if (!it.isSuccessful) throw IOException("Wave settings HTTP ${it.code}") }
    }

    suspend fun lyrics(trackId: String): Lyrics? = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis() / 1000; val numericId = trackId.substringBefore(':'); val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("p93jhgh689SBReK6ghtw62".toByteArray(), "HmacSHA256")); val sign = Base64.encodeToString(mac.doFinal("$numericId$timestamp".toByteArray()), Base64.NO_WRAP)
        val result = try { getResult("$API/tracks/$trackId/lyrics?format=LRC&timeStamp=$timestamp&sign=${URLEncoder.encode(sign, "UTF-8")}") } catch (_: Exception) { return@withContext null }
        val download = result.optString("downloadUrl").ifBlank { return@withContext null }; val text = executeText(Request.Builder().url(download).get().build()); val re = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]\\s*(.*)")
        val lines = text.lineSequence().mapNotNull { line -> val m = re.matchEntire(line.trim()) ?: return@mapNotNull null; val min=m.groupValues[1].toLong(); val sec=m.groupValues[2].toLong(); val frac=m.groupValues[3]; val ms=when(frac.length){1->frac.toLong()*100;2->frac.toLong()*10;3->frac.toLong();else->0}; LyricLine((min*60+sec)*1000+ms,m.groupValues[4]) }.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }.toList()
        if(lines.isNotEmpty()) Lyrics(lines,true) else Lyrics(text.lines().filter{it.isNotBlank()}.mapIndexed{i,t->LyricLine(i*5000L,t)},false)
    }

    suspend fun likeTrack(trackId: String): Boolean = libraryAction("likes", trackId)
    suspend fun dislikeTrack(trackId: String): Boolean = libraryAction("dislikes", trackId)
    private suspend fun libraryAction(kind: String, trackId: String): Boolean = withContext(Dispatchers.IO) {
        var uid = accountUid; if (uid == null) { val status = getResult("$API/account/status"); uid = status.optJSONObject("account")?.opt("uid")?.toString(); accountUid = uid }
        if (uid.isNullOrBlank()) throw IOException("Не удалось определить ID аккаунта")
        val body = FormBody.Builder().add("track-ids", trackId.substringBefore(':')).build(); val req = authBuilder("$API/users/$uid/$kind/tracks/add-multiple").post(body).build()
        http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw IOException("$kind HTTP ${r.code}: ${r.body?.string().orEmpty().take(120)}"); true }
    }

    suspend fun searchTracks(text: String): List<Track> {
        if (text.isBlank()) return emptyList(); val result = getResult("$API/search?text=${URLEncoder.encode(text, "UTF-8")}&type=track&page=0&nocorrect=false"); val arr = result.optJSONObject("tracks")?.optJSONArray("results") ?: JSONArray()
        return List(arr.length()) { i -> parseTrack(arr.getJSONObject(i)) }
    }

    suspend fun audioUrl(trackId: String): String = withContext(Dispatchers.IO) {
        val infos = getResultArray("$API/tracks/$trackId/download-info"); var chosen: JSONObject? = null
        for (i in 0 until infos.length()) { val x = infos.getJSONObject(i); if (x.optString("codec") == "mp3" && (chosen == null || x.optInt("bitrateInKbps") > chosen!!.optInt("bitrateInKbps"))) chosen = x }
        val info = chosen ?: throw IOException("Для трека не найден MP3-поток"); val xml = executeText(authRequest(info.getString("downloadInfoUrl")))
        fun tag(name: String) = Regex("<$name>(.*?)</$name>").find(xml)?.groupValues?.get(1)?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">") ?: throw IOException("Нет <$name> в download-info")
        val host = tag("host"); val path = tag("path"); val ts = tag("ts"); val s = tag("s"); val sign = md5(SIGN_SALT + path.drop(1) + s); "https://$host/get-mp3/$sign/$ts$path"
    }

    suspend fun waveFeedback(type: String, trackId: String? = null, batchId: String? = null, playedSeconds: Long? = null) = withContext(Dispatchers.IO) {
        val json = JSONObject().put("type", type).put("timestamp", java.time.Instant.now().toString()); trackId?.let { json.put("trackId", it) }; playedSeconds?.let { json.put("totalPlayedSeconds", it) }
        val url = "$API/rotor/station/user:onyourwave/feedback" + (batchId?.let { "?batch-id=${URLEncoder.encode(it, "UTF-8")}" } ?: ""); val req = authBuilder(url).post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw IOException("Feedback HTTP ${it.code}") }
    }

    suspend fun homeSections(): List<HomeSection> {
        val blocks = listOf("personalplaylists", "mixes", "new-playlists", "new-releases").joinToString(",")
        val result = getResult("$API/landing3?blocks=${URLEncoder.encode(blocks, "UTF-8")}")
        val arr = result.optJSONArray("blocks") ?: JSONArray()
        val sections = mutableListOf<HomeSection>()
        for (i in 0 until arr.length()) {
            val block = arr.optJSONObject(i) ?: continue
            val cards = mutableListOf<HomeCard>()
            val entities = block.optJSONArray("entities") ?: JSONArray()
            for (k in 0 until entities.length()) {
                val entity = entities.optJSONObject(k) ?: continue
                val type = entity.optString("type")
                val data0 = entity.optJSONObject("data") ?: continue
                val data = if (type == "personal-playlist") data0.optJSONObject("data") ?: data0 else data0
                val rawTitle = data.optString("title").ifBlank { data.optString("name") }
                if (rawTitle.isBlank()) continue
                val coverRaw = data.optString("coverUri").ifBlank { data.optJSONObject("cover")?.optString("uri").orEmpty() }
                val cover = coverRaw.takeIf { it.isNotBlank() }?.replace("%%", "400x400")?.let { if (it.startsWith("http")) it else "https://$it" }
                cards += HomeCard(rawTitle, data.optString("description"), cover, type, data.opt("id")?.toString().orEmpty(), data.opt("uid")?.toString(), data.opt("kind")?.toString())
            }
            if (cards.isNotEmpty()) sections += HomeSection(block.optString("title").ifBlank { "Для вас" }, cards.take(20))
        }
        return sections
    }

    suspend fun playlistTracks(uid: String, kind: String): List<Track> {
        val result = getResult("$API/users/${URLEncoder.encode(uid, "UTF-8")}/playlists/${URLEncoder.encode(kind, "UTF-8")}"); val arr = result.optJSONArray("tracks") ?: JSONArray(); val out = mutableListOf<Track>()
        for (i in 0 until arr.length()) { val item = arr.optJSONObject(i) ?: continue; val track = item.optJSONObject("track") ?: item; if (track.has("id")) out += parseTrack(track) }; return out
    }

    suspend fun podcasts(): List<HomeCard> { val result = getResult("$API/landing3/podcasts"); val ids = result.optJSONArray("podcasts") ?: JSONArray(); return List(ids.length()) { i -> HomeCard(title="Подкаст ${i+1}", type="podcast", id=ids.opt(i).toString()) } }

    private fun parseTrack(j: JSONObject): Track {
        val artists = j.optJSONArray("artists") ?: JSONArray(); val artist = (0 until artists.length()).joinToString(", ") { artists.optJSONObject(it)?.optString("name").orEmpty() }; val cover = j.optString("coverUri").takeIf { it.isNotBlank() }?.replace("%%", "400x400")?.let { if (it.startsWith("http")) it else "https://$it" }; val albumId = j.optJSONArray("albums")?.optJSONObject(0)?.opt("id")?.toString()
        return Track(j.opt("id").toString(), albumId, j.optString("title", "Без названия"), artist, cover, j.optLong("durationMs"))
    }

    private suspend fun getResult(url: String): JSONObject = getJson(url).optJSONObject("result") ?: throw IOException("Пустой result")
    private suspend fun getResultArray(url: String): JSONArray = getJson(url).optJSONArray("result") ?: throw IOException("Пустой result")
    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) { executeJson(authRequest(url)) }
    private fun authRequest(url: String) = authBuilder(url).get().build()
    private fun authBuilder(url: String): Request.Builder = Request.Builder().url(url).header("User-Agent", "Музыка/0.9 AndroidTV").apply { token?.let { header("Authorization", "OAuth $it") } }
    private fun executeJson(req: Request): JSONObject = JSONObject(executeText(req))
    private fun executeText(req: Request): String = http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${r.body?.string().orEmpty().take(200)}"); r.body?.string() ?: throw IOException("Пустой ответ") }
    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
