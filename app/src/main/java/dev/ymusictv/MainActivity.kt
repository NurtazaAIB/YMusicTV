package dev.ymusictv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import dev.ymusictv.api.LyricsApi
import dev.ymusictv.api.WaveRestrictionsApi
import dev.ymusictv.api.YandexMusicApi
import dev.ymusictv.model.*
import dev.ymusictv.player.TvPlayer
import dev.ymusictv.player.WaveSession
import dev.ymusictv.ui.HomePoster
import dev.ymusictv.ui.TrackRow
import dev.ymusictv.ui.YandexYellow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("last_crash", throwable.stackTraceToString().take(12000)).putLong("last_crash_time", System.currentTimeMillis()).commit() }
            previousHandler?.uncaughtException(thread, throwable)
        }
        super.onCreate(savedInstanceState); setContent { YMusicTvApp() }
    }
}

enum class Screen { MUSIC, FAVORITES, AUDIO, SEARCH, ARTIST, PLAYER }

@Composable fun YMusicTvApp() {
    val context=LocalContext.current; val prefs=remember{context.getSharedPreferences("auth",0)}; val diagnostics=remember{context.getSharedPreferences("diagnostics",0)}
    val api=remember{YandexMusicApi().apply{token=prefs.getString("access_token",null)}}
    var loggedIn by remember{mutableStateOf(false)}; var checkingAuth by remember{mutableStateOf(true)}; var code by remember{mutableStateOf<String?>(null)}; var url by remember{mutableStateOf<String?>(null)}; var error by remember{mutableStateOf<String?>(null)}; var authInProgress by remember{mutableStateOf(false)}; var lastCrash by remember{mutableStateOf(diagnostics.getString("last_crash",null))}; val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){ val saved=prefs.getString("access_token",null); val refresh=prefs.getString("refresh_token",null); if(!saved.isNullOrBlank()){api.token=saved; val ok=runCatching{api.accountName();true}.getOrDefault(false); if(ok) loggedIn=true else if(!refresh.isNullOrBlank()) runCatching{api.refreshAccessToken(refresh)}.onSuccess{t->prefs.edit().putString("access_token",t.accessToken).putString("refresh_token",t.refreshToken).putLong("expires_at",System.currentTimeMillis()+((t.expiresIn?:3600)*1000L)).apply();loggedIn=true}}; checkingAuth=false }
    MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFF090A0D))) {
        if(checkingAuth) Column(Modifier.padding(38.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){Text("Музыка",fontSize=46.sp,color=YandexYellow);Text("Проверяем авторизацию…",color=Color.LightGray)}
        else if(!loggedIn) Column(Modifier.padding(38.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){ Text("Музыка",fontSize=46.sp,color=YandexYellow); Text("Яндекс Музыка для Android TV",fontSize=21.sp,color=Color.LightGray)
            Button(onClick={if(!authInProgress)scope.launch{authInProgress=true;error=null;try{val d=api.requestDeviceCode();code=d.userCode;url=d.verificationUrl;val interval=d.interval.coerceAtLeast(1);repeat((d.expiresIn/interval).coerceAtLeast(1)){delay(interval*1000L);val poll=runCatching{api.pollDeviceToken(d.deviceCode)};if(poll.isFailure){error="OAuth: ${poll.exceptionOrNull()?.message}";return@launch};poll.getOrNull()?.let{t->prefs.edit().putString("access_token",t.accessToken).putString("refresh_token",t.refreshToken).apply();diagnostics.edit().clear().apply();lastCrash=null;loggedIn=true;return@launch}};error="Код авторизации истёк"}catch(e:CancellationException){throw e}catch(e:Throwable){error="Ошибка входа: ${e.message}"}finally{authInProgress=false}}}){Text(if(authInProgress)"Ожидание подтверждения…" else "Войти в Яндекс")};code?.let{Text("Код: $it",fontSize=40.sp,color=YandexYellow)};url?.let{Text("Откройте на телефоне: $it",color=Color.White)};error?.let{Text(it,color=Color(0xFFFF8A80))};lastCrash?.let{Text(it.take(1000),color=Color(0xFFFFCCBC),fontSize=12.sp)} }
        else { val player=remember{TvPlayer(context)};val wave=remember{WaveSession(api)};DisposableEffect(Unit){onDispose{player.release()}};Home(api,player,wave){prefs.edit().clear().apply();api.token=null;loggedIn=false} }
    }}
}

@Composable private fun Home(api:YandexMusicApi,player:TvPlayer,wave:WaveSession,logout:()->Unit){
    var screen by remember{mutableStateOf(Screen.MUSIC)};var returnScreen by remember{mutableStateOf(Screen.MUSIC)};var artistId by remember{mutableStateOf<String?>(null)};var name by remember{mutableStateOf("…")};var now by remember{mutableStateOf<Track?>(null)};val history=remember{mutableStateListOf<Track>()};val scope=rememberCoroutineScope();LaunchedEffect(Unit){runCatching{api.accountName()}.onSuccess{name=it}};var retry by remember{mutableStateOf<String?>(null)}
    var queue by remember{mutableStateOf<List<Track>>(emptyList())};var queueIndex by remember{mutableIntStateOf(-1)};var waveMode by remember{mutableStateOf(false)}
    fun play(t:Track,rememberPrevious:Boolean=true){scope.launch{runCatching{api.audioUrl(t.id)}.onSuccess{url->if(rememberPrevious){now?.takeIf{it.id!=t.id}?.let{history.add(it)}};player.play(t,url);now=t;screen=Screen.PLAYER}}}
    fun playFromQueue(list:List<Track>,index:Int){if(index !in list.indices)return;queue=list;queueIndex=index;waveMode=false;play(list[index])}
    fun next(){scope.launch{if(waveMode){runCatching{wave.next(player.exo.currentPosition/1000,true)}.onSuccess{it?.let{play(it)}}}else if(queueIndex+1<queue.size){queueIndex++;play(queue[queueIndex])}}}
    fun previous(){if(history.isNotEmpty()){val t=history.removeAt(history.lastIndex);play(t,false)}else player.exo.seekTo(0)}
    BackHandler(enabled=screen==Screen.PLAYER){screen=returnScreen}
    DisposableEffect(Unit){player.onEnded{next()};player.onError{val t=now;if(t!=null&&retry!=t.id){retry=t.id;scope.launch{runCatching{api.audioUrl(t.id)}.onSuccess{player.play(t,it)}}}};onDispose{}}
    if(screen==Screen.PLAYER){
        PlayerScreen(api,player,wave,now,{play(it)},::previous,::next,{id->artistId=id;screen=Screen.ARTIST}){screen=returnScreen}
    } else Column(Modifier.fillMaxSize().padding(38.dp).onPreviewKeyEvent{e->if(e.nativeKeyEvent.action==KeyEvent.ACTION_DOWN)when(e.nativeKeyEvent.keyCode){KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE->{if(player.exo.isPlaying)player.exo.pause()else player.exo.play();true};KeyEvent.KEYCODE_MEDIA_PLAY->{player.exo.play();true};KeyEvent.KEYCODE_MEDIA_PAUSE->{player.exo.pause();true};KeyEvent.KEYCODE_MEDIA_PREVIOUS->{previous();true};else->false}else false},verticalArrangement=Arrangement.spacedBy(15.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Text("Музыка",fontSize=30.sp,color=Color.White,modifier=Modifier.weight(1f));Text(name,fontSize=16.sp,color=Color.LightGray);now?.let{Button(onClick={screen=Screen.PLAYER}){Text("♫ ${it.title.take(24)}")}};Button(onClick=logout){Text("Выйти")}}
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick={screen=Screen.MUSIC}){Text("Музыка")};Button(onClick={screen=Screen.FAVORITES}){Text("Любимое")};Button(onClick={screen=Screen.AUDIO}){Text("Аудио")};Button(onClick={screen=Screen.SEARCH}){Text("Поиск")}}
        when(screen){Screen.MUSIC->MusicScreen(api,wave,{t->waveMode=true;queue=emptyList();queueIndex=-1;returnScreen=Screen.MUSIC;play(t)},{list,i->returnScreen=Screen.MUSIC;playFromQueue(list,i)});Screen.FAVORITES->FavoritesScreen(api){list,i->returnScreen=Screen.FAVORITES;playFromQueue(list,i)};Screen.AUDIO->AudioScreen(api){list,i->returnScreen=Screen.AUDIO;playFromQueue(list,i)};Screen.SEARCH->SearchScreen(api){list,i->returnScreen=Screen.SEARCH;playFromQueue(list,i)};Screen.ARTIST->ArtistScreen(api,artistId){list,i->returnScreen=Screen.ARTIST;playFromQueue(list,i)};Screen.PLAYER->{}}
    }
}

@Composable private fun MusicScreen(api:YandexMusicApi,wave:WaveSession,playWave:(Track)->Unit,playQueue:(List<Track>,Int)->Unit){
    var tracks by remember{mutableStateOf<List<Track>>(emptyList())};var title by remember{mutableStateOf("Главная")};var error by remember{mutableStateOf<String?>(null)};var settings by remember{mutableStateOf(WaveSettings())};var restrictions by remember{mutableStateOf<WaveRestrictions?>(null)};var settingsLoading by remember{mutableStateOf(false)};var sections by remember{mutableStateOf<List<HomeSection>>(emptyList())};var showSettings by remember{mutableStateOf(false)};val scope=rememberCoroutineScope();LaunchedEffect(Unit){sections=runCatching{api.homeSections()}.getOrDefault(emptyList())}
    fun loadSettings(){scope.launch{settingsLoading=true;error=null;runCatching{WaveRestrictionsApi.load(api.token)}.onSuccess{r->restrictions=r;settings=settings.copy(mood=r.moods.firstOrNull{it.value==settings.mood}?.value?:r.moods.firstOrNull()?.value?:settings.mood,diversity=r.diversities.firstOrNull{it.value==settings.diversity}?.value?:r.diversities.firstOrNull()?.value?:settings.diversity,language=r.languages.firstOrNull{it.value==settings.language}?.value?:r.languages.firstOrNull()?.value?:settings.language)}.onFailure{error="Настройки Волны: ${it.message}"};settingsLoading=false}}
    fun waveStart(){scope.launch{error=null;runCatching{wave.start(settings)}.onSuccess{t->title="Моя Волна";t?.let{tracks=listOf(it);playWave(it)}}.onFailure{error=it.message}}};fun chart(){scope.launch{runCatching{api.chart()}.onSuccess{title="Чарт";tracks=it}.onFailure{error=it.message}}}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,fontSize=30.sp,color=Color.White);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={waveStart()}){Text("▶ Моя Волна")};Button(onClick={chart()}){Text("Чарт")};Button(onClick={showSettings=!showSettings;if(showSettings)loadSettings()}){Text(if(showSettings)"✕ Закрыть настройки" else "⚙ Настроить волну")}}
        if(showSettings){when{settingsLoading->Text("Получаем доступные настройки от Яндекса…",color=Color.LightGray);restrictions!=null->WaveSettingsPanel(settings,restrictions!!){settings=it};else->Text("Нет данных о настройках Волны",color=Color.Gray)}}
        error?.let{Text(it,color=Color(0xFFFF8A80))};if(tracks.isNotEmpty())TrackList(tracks,playQueue)else LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxWidth().heightIn(max=455.dp)){items(sections,key={it.title}){section->Column(verticalArrangement=Arrangement.spacedBy(7.dp)){Text(section.title,fontSize=22.sp,color=Color.White);LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)){items(section.cards.take(12),key={it.id+it.title}){card->HomePoster(card){scope.launch{if(!card.uid.isNullOrBlank()&&!card.kind.isNullOrBlank()){tracks=runCatching{api.playlistTracks(card.uid!!,card.kind!!)}.getOrDefault(emptyList());title=card.title}}}}}}}}
    }
}

@Composable private fun WaveSettingsPanel(s:WaveSettings,r:WaveRestrictions,onChange:(WaveSettings)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(7.dp),modifier=Modifier.fillMaxWidth().background(Color(0xFF15171C)).padding(12.dp)){
        if(r.moods.isNotEmpty()){Text("Настроение",color=Color.LightGray,fontSize=17.sp);LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(r.moods,key={it.value}){o->Button(onClick={onChange(s.copy(mood=o.value))}){Text(if(s.mood==o.value)"✓ ${o.name}" else o.name)}}}}
        if(r.diversities.isNotEmpty()){Text("Рекомендации",color=Color.LightGray,fontSize=17.sp);LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(r.diversities,key={it.value}){o->Button(onClick={onChange(s.copy(diversity=o.value))}){Text(if(s.diversity==o.value)"✓ ${o.name}" else o.name)}}}}
        if(r.languages.isNotEmpty()){Text("Язык",color=Color.LightGray,fontSize=17.sp);LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(r.languages,key={it.value}){o->Button(onClick={onChange(s.copy(language=o.value))}){Text(if(s.language==o.value)"✓ ${o.name}" else o.name)}}}}
        Text("Варианты получены непосредственно от Яндекс Музыки",color=Color.Gray,fontSize=13.sp)
    }
}

private fun clock(ms:Long):String { val total=(ms.coerceAtLeast(0)/1000);return "%d:%02d".format(total/60,total%60) }

@Composable private fun PlayerScreen(api:YandexMusicApi,player:TvPlayer,wave:WaveSession,track:Track?,play:(Track)->Unit,previous:()->Unit,next:()->Unit,openArtist:(String)->Unit,back:()->Unit){
    val lyricsApi=remember(api){LyricsApi(tokenProvider={api.token})}
    var showLyrics by remember(track?.id){mutableStateOf(true)};var lyrics by remember{mutableStateOf<Lyrics?>(null)};var pos by remember{mutableLongStateOf(0L)};var duration by remember{mutableLongStateOf(0L)};var playing by remember{mutableStateOf(player.exo.isPlaying)};var message by remember{mutableStateOf<String?>(null)};val scope=rememberCoroutineScope();val lyricState=rememberLazyListState()
    LaunchedEffect(track?.id){lyrics=null;message=null;showLyrics=true;track?.let{t->runCatching{lyricsApi.load(t.id, t.durationMs)}.onSuccess{lyrics=it}.onFailure{message="Текст недоступен"}}}
    LaunchedEffect(Unit){while(true){pos=player.exo.currentPosition.coerceAtLeast(0);duration=player.exo.duration.takeIf{it>0}?:track?.durationMs?:0L;playing=player.exo.isPlaying;delay(200)}}
    val lines=lyrics?.lines.orEmpty();val synced=lyrics?.synced==true;val idx=if(synced)lines.indexOfLast{it.timeMs<=pos}.coerceAtLeast(0) else 0;val progress=if(duration>0)(pos.toFloat()/duration.toFloat()).coerceIn(0f,1f) else 0f
    LaunchedEffect(idx,lines.size,showLyrics){if(showLyrics&&synced&&lines.isNotEmpty())lyricState.animateScrollToItem((idx-2).coerceAtLeast(0))}
    Box(Modifier.fillMaxSize()){
        Crossfade(targetState=track?.coverUrl,animationSpec=tween(900),label="playerBackground"){cover->cover?.let{AsyncImage(model=it,contentDescription=null,modifier=Modifier.fillMaxSize().blur(18.dp),contentScale=ContentScale.Crop)}}
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB0000000),Color(0x42000000),Color(0xC8000000)))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xA8000000),Color.Transparent,Color(0xA8000000)))))
        Column(Modifier.fillMaxSize().padding(horizontal=48.dp,vertical=30.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Button(onClick=back,modifier=Modifier.size(58.dp).shadow(10.dp,CircleShape),contentPadding=PaddingValues(0.dp)){Text("‹",fontSize=28.sp)};Spacer(Modifier.weight(1f))}
            Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(38.dp),verticalAlignment=Alignment.CenterVertically){
                Box(Modifier.size(300.dp).shadow(28.dp,RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Color(0xFF17181D))){track?.coverUrl?.let{AsyncImage(model=it,contentDescription=track.title,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}}
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(11.dp)){Text(track?.title?:"Плеер",fontSize=38.sp,color=Color.White);Text(track?.artist.orEmpty(),fontSize=22.sp,color=Color(0xFFB8B8BD))
                    LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)))
                    Row(Modifier.fillMaxWidth()){Text(clock(pos),color=Color(0xFFB8B8BD),fontSize=14.sp);Spacer(Modifier.weight(1f));Text(clock(duration),color=Color(0xFFB8B8BD),fontSize=14.sp)}
                    Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically){
                        Button(onClick=previous,modifier=Modifier.size(64.dp).shadow(12.dp,CircleShape),contentPadding=PaddingValues(0.dp)){Text("⏮",fontSize=22.sp)}
                        Button(onClick={if(player.exo.isPlaying)player.exo.pause()else player.exo.play()},modifier=Modifier.size(64.dp).shadow(if(playing)18.dp else 12.dp,CircleShape),contentPadding=PaddingValues(0.dp)){Text(if(playing)"Ⅱ" else "▶",fontSize=26.sp)}
                        Button(onClick=next,modifier=Modifier.size(64.dp).shadow(12.dp,CircleShape),contentPadding=PaddingValues(0.dp)){Text("⏭",fontSize=22.sp)}
                        Button(onClick={scope.launch{track?.let{t->runCatching{api.likeTrack(t.id)}.onSuccess{message="Добавлено в Любимое ♥"}.onFailure{message=it.message}}}},modifier=Modifier.size(64.dp).shadow(12.dp,CircleShape),contentPadding=PaddingValues(0.dp)){Text("♡",fontSize=25.sp)}
                        Button(onClick={track?.artistId?.let(openArtist)},enabled=track?.artistId!=null,modifier=Modifier.size(64.dp).shadow(12.dp,CircleShape),contentPadding=PaddingValues(0.dp)){Text("♙",fontSize=25.sp)}
                    }
                    message?.let{Text(it,color=Color(0xFFD6B5FF),fontSize=13.sp)}
                    if(showLyrics&&lines.isNotEmpty()){
                        if(synced) LazyColumn(state=lyricState,modifier=Modifier.fillMaxWidth().height(178.dp),userScrollEnabled=false,verticalArrangement=Arrangement.spacedBy(3.dp)){
                            itemsIndexed(lines,key={i,l->"$i-${l.timeMs}"}){i,line->
                                val d=kotlin.math.abs(i-idx);val active=i==idx
                                Box(Modifier.fillMaxWidth().height(32.dp),contentAlignment=Alignment.Center){Text(line.text,fontSize=if(active)24.sp else 19.sp,color=if(active)Color.White else Color.White.copy(alpha=when(d){1->0.58f;2->0.34f;else->0.16f}),textAlign=TextAlign.Center,maxLines=1)}
                            }
                        } else LazyColumn(modifier=Modifier.fillMaxWidth().height(178.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){items(lines){Text(it.text,fontSize=20.sp,color=Color.White.copy(alpha=.82f),modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)}}
                    }
                }
            }
        }
    }
}


@Composable private fun FavoritesScreen(api:YandexMusicApi,play:(List<Track>,Int)->Unit){var tracks by remember{mutableStateOf<List<Track>>(emptyList())};var loading by remember{mutableStateOf(true)};var error by remember{mutableStateOf<String?>(null)};LaunchedEffect(Unit){loading=true;runCatching{api.likedTracks()}.onSuccess{tracks=it}.onFailure{error=it.message};loading=false};Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Любимое",fontSize=30.sp,color=Color.White);when{loading->Text("Загружаем сохранённые треки…",color=Color.LightGray);error!=null->Text(error!!,color=Color(0xFFFF8A80));tracks.isEmpty()->Text("В Яндекс Музыке пока нет сохранённых треков",color=Color.Gray);else->TrackList(tracks,play)}}}
@Composable private fun SearchScreen(api:YandexMusicApi,play:(List<Track>,Int)->Unit){var q by remember{mutableStateOf("")};var tracks by remember{mutableStateOf<List<Track>>(emptyList())};val scope=rememberCoroutineScope();Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Поиск",fontSize=30.sp,color=Color.White);Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(q,{q=it},label={Text("Трек, исполнитель, сказка")},modifier=Modifier.width(520.dp));Button(onClick={scope.launch{tracks=runCatching{api.searchTracks(q)}.getOrDefault(emptyList())}}){Text("Найти")}};TrackList(tracks,play)}}
@Composable private fun AudioScreen(api:YandexMusicApi,play:(List<Track>,Int)->Unit){var tracks by remember{mutableStateOf<List<Track>>(emptyList())};var title by remember{mutableStateOf("Аудио")};val scope=rememberCoroutineScope();fun search(t:String,q:String){title=t;scope.launch{tracks=runCatching{api.searchTracks(q)}.getOrDefault(emptyList())}};Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,fontSize=30.sp,color=Color.White);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={search("Ертегі","қазақша ертегілер")}){Text("Ертегі")};Button(onClick={search("Сказка","детские сказки")}){Text("Сказка")};Button(onClick={search("Аудиокниги","аудиокнига")}){Text("Аудиокниги")};Button(onClick={search("Подкасты","подкаст")}){Text("Подкасты")}};TrackList(tracks,play)}}
@Composable private fun TrackList(tracks:List<Track>,play:(List<Track>,Int)->Unit){LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp),modifier=Modifier.fillMaxWidth().heightIn(max=455.dp)){itemsIndexed(tracks,key={_,t->t.id}){i,t->TrackRow(t,onClick={play(tracks,i)})}}}

@Composable private fun ArtistScreen(api:YandexMusicApi,artistId:String?,play:(List<Track>,Int)->Unit){var title by remember(artistId){mutableStateOf("Артист")};var tracks by remember(artistId){mutableStateOf<List<Track>>(emptyList())};var error by remember(artistId){mutableStateOf<String?>(null)};LaunchedEffect(artistId){if(artistId!=null){runCatching{title=api.artistName(artistId);tracks=api.artistTracks(artistId)}.onFailure{error=it.message}}};Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,fontSize=30.sp,color=Color.White);error?.let{Text(it,color=Color(0xFFFF8A80))};TrackList(tracks,play)}}
