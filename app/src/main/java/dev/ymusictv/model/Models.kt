package dev.ymusictv.model

data class DeviceCode(val deviceCode:String, val userCode:String, val verificationUrl:String, val expiresIn:Int, val interval:Int)
data class OAuthToken(val accessToken:String, val refreshToken:String?, val expiresIn:Int?, val tokenType:String?)
data class Track(val id:String, val albumId:String? = null, val title:String, val artist:String, val coverUrl:String? = null, val durationMs:Long = 0, val artistId:String? = null)
data class WaveBatch(val batchId:String, val tracks:List<Track>)
data class LyricLine(val timeMs:Long, val text:String)
data class Lyrics(val lines:List<LyricLine>, val synced:Boolean)
data class WaveSettings(val mood:String="all", val diversity:String="default", val language:String="any")
data class WaveOption(val value:String, val name:String)
data class WaveRestrictions(
    val moods:List<WaveOption> = emptyList(),
    val diversities:List<WaveOption> = emptyList(),
    val languages:List<WaveOption> = emptyList()
)

data class HomeCard(val title:String, val subtitle:String="", val coverUrl:String?=null, val type:String="", val id:String="", val uid:String?=null, val kind:String?=null)
data class HomeSection(val title:String, val cards:List<HomeCard>)
