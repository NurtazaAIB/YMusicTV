package dev.ymusictv.api

import dev.ymusictv.model.HomeCard
import dev.ymusictv.model.HomeSection
import dev.ymusictv.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

object DynamicHomeApi {
    private const val API = "https://api.music.yandex.net"
    private const val UA = "MusicTV/0.10.5 AndroidTV"
    private val http = OkHttpClient()

    suspend fun sections(token:String?):List<HomeSection> = withContext(Dispatchers.IO) {
        val blocks=listOf("personalplaylists","mixes","new-playlists","new-releases","recently-played","recommended-playlists","editorial-new-releases").joinToString(",")
        val root=json("$API/landing3?blocks=${URLEncoder.encode(blocks,"UTF-8")}",token)
        val result=root.optJSONObject("result") ?: throw IOException("Пустой landing3")
        val blocks=result.optJSONArray("blocks") ?: JSONArray()
        val out=mutableListOf<HomeSection>()
        for(i in 0 until blocks.length()){
            val block=blocks.optJSONObject(i) ?: continue
            val entities=block.optJSONArray("entities") ?: continue
            val cards=mutableListOf<HomeCard>()
            for(k in 0 until entities.length()){
                val entity=entities.optJSONObject(k) ?: continue
                val type=entity.optString("type")
                val d0=entity.optJSONObject("data") ?: continue
                val d=if(type=="personal-playlist") d0.optJSONObject("data") ?: d0 else d0
                val title=d.optString("title").ifBlank{d.optString("name")}
                if(title.isBlank()) continue
                val raw=d.optString("coverUri").ifBlank{d.optJSONObject("cover")?.optString("uri").orEmpty()}
                val cover=raw.takeIf{it.isNotBlank()}?.replace("%%","400x400")?.let{if(it.startsWith("http"))it else "https://$it"}
                cards+=HomeCard(title,d.optString("description"),cover,type,d.opt("id")?.toString().orEmpty(),d.opt("uid")?.toString(),d.opt("kind")?.toString())
            }
            if(cards.isNotEmpty()) out+=HomeSection(block.optString("title").ifBlank{"Для вас"},cards)
        }
        out
    }

    suspend fun albumTracks(token:String?,albumId:String):List<Track> = withContext(Dispatchers.IO){
        val root=json("$API/albums/${URLEncoder.encode(albumId,"UTF-8")}/with-tracks",token)
        val result=root.optJSONObject("result") ?: return@withContext emptyList()
        val volumes=result.optJSONArray("volumes") ?: JSONArray()
        val out=mutableListOf<Track>()
        for(i in 0 until volumes.length()){
            val volume=volumes.optJSONArray(i) ?: continue
            for(k in 0 until volume.length()) volume.optJSONObject(k)?.let{out+=track(it)}
        }
        out
    }

    private fun track(j:JSONObject):Track{
        val artists=j.optJSONArray("artists") ?: JSONArray()
        val artist=(0 until artists.length()).joinToString(", "){artists.optJSONObject(it)?.optString("name").orEmpty()}
        val raw=j.optString("coverUri")
        val cover=raw.takeIf{it.isNotBlank()}?.replace("%%","400x400")?.let{if(it.startsWith("http"))it else "https://$it"}
        val albumId=j.optJSONArray("albums")?.optJSONObject(0)?.opt("id")?.toString()
        val artistId=artists.optJSONObject(0)?.opt("id")?.toString()
        return Track(j.opt("id").toString(),albumId,j.optString("title","Без названия"),artist,cover,j.optLong("durationMs"),artistId)
    }

    private fun json(url:String,token:String?):JSONObject{
        val b=Request.Builder().url(url).header("User-Agent",UA)
        if(!token.isNullOrBlank()) b.header("Authorization","OAuth $token")
        http.newCall(b.get().build()).execute().use{r->
            val text=r.body?.string().orEmpty()
            if(!r.isSuccessful) throw IOException("HTTP ${r.code}: ${text.take(160)}")
            return JSONObject(text)
        }
    }
}
