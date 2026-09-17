package dev.ymusictv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
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
            runCatching {
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                    .putString("last_crash", throwable.stackTraceToString().take(12000))
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .commit()
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        super.onCreate(savedInstanceState)
        setContent { YMusicTvApp() }
    }
}

enum class Screen { MUSIC, AUDIO, SEARCH, PLAYER }

@Composable
fun YMusicTvApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("auth", 0) }
    val diagnostics = remember { context.getSharedPreferences("diagnostics", 0) }
    val api = remember { YandexMusicApi().apply { token = prefs.getString("access_token", null) } }
    var loggedIn by remember { mutableStateOf(false) }
    var checkingAuth by remember { mutableStateOf(true) }
    var code by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var authInProgress by remember { mutableStateOf(false) }
    var lastCrash by remember { mutableStateOf(diagnostics.getString("last_crash", null)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val saved = prefs.getString("access_token", null)
        val refresh = prefs.getString("refresh_token", null)
        if (!saved.isNullOrBlank()) {
            api.token = saved
            val ok = runCatching { api.accountName(); true }.getOrDefault(false)
            if (ok) {
                loggedIn = true
            } else if (!refresh.isNullOrBlank()) {
                runCatching { api.refreshAccessToken(refresh) }.onSuccess { t ->
                    prefs.edit()
                        .putString("access_token", t.accessToken)
                        .putString("refresh_token", t.refreshToken)
                        .putLong("expires_at", System.currentTimeMillis() + ((t.expiresIn ?: 3600) * 1000L))
                        .apply()
                    loggedIn = true
                }
            }
        }
        checkingAuth = false
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF090A0D)).padding(38.dp)) {
            if (checkingAuth) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Музыка", fontSize = 46.sp, color = YandexYellow)
                    Text("Проверяем авторизацию…", color = Color.LightGray)
                }
            } else if (!loggedIn) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Музыка", fontSize = 46.sp, color = YandexYellow)
                    Text("Яндекс Музыка для Android TV • diagnostic 0.9.2", fontSize = 21.sp, color = Color.LightGray)
                    Button(onClick = {
                        if (!authInProgress) scope.launch {
                            authInProgress = true
                            error = null
                            try {
                                val d = api.requestDeviceCode()
                                code = d.userCode
                                url = d.verificationUrl
                                val intervalSec = d.interval.coerceAtLeast(1)
                                val attempts = (d.expiresIn / intervalSec).coerceAtLeast(1)
                                repeat(attempts) {
                                    delay(intervalSec * 1000L)
                                    val poll = runCatching { api.pollDeviceToken(d.deviceCode) }
                                    if (poll.isFailure) {
                                        val e = poll.exceptionOrNull()
                                        error = "OAuth poll: ${e?.javaClass?.simpleName ?: "Error"}: ${e?.message ?: "без описания"}"
                                        return@launch
                                    }
                                    val t = poll.getOrNull()
                                    if (t != null) {
                                        prefs.edit()
                                            .putString("access_token", t.accessToken)
                                            .putString("refresh_token", t.refreshToken)
                                            .putLong("expires_at", System.currentTimeMillis() + ((t.expiresIn ?: 3600) * 1000L))
                                            .apply()
                                        diagnostics.edit().remove("last_crash").remove("last_crash_time").apply()
                                        lastCrash = null
                                        loggedIn = true
                                        return@launch
                                    }
                                }
                                error = "Код авторизации истёк"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                error = "Ошибка входа: ${e.javaClass.simpleName}: ${e.message ?: "без описания"}"
                            } finally {
                                authInProgress = false
                            }
                        }
                    }) {
                        Text(if (authInProgress) "Ожидание подтверждения…" else if (code == null) "Войти в Яндекс" else "Получить новый код")
                    }
                    code?.let { Text("Код: $it", fontSize = 40.sp, color = Color(0xFFFFDB4D)) }
                    url?.let { Text("Откройте на телефоне: $it", color = Color.White) }
                    error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 18.sp) }
                    lastCrash?.let { crash ->
                        Text("Предыдущий сбой приложения:", color = Color(0xFFFFB74D), fontSize = 16.sp)
                        Text(crash.take(1200), color = Color(0xFFFFCCBC), fontSize = 12.sp)
                        Button(onClick = {
                            diagnostics.edit().remove("last_crash").remove("last_crash_time").apply()
                            lastCrash = null
                        }) { Text("Очистить ошибку") }
                    }
                }
            } else {
                val player = remember { TvPlayer(context) }
                val wave = remember { WaveSession(api) }
                DisposableEffect(Unit) { onDispose { player.release() } }
                Home(api, player, wave) {
                    prefs.edit().clear().apply()
                    api.token = null
                    loggedIn = false
                }
            }
        }
    }
}

@Composable
private fun Home(api: YandexMusicApi, player: TvPlayer, wave: WaveSession, logout: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.MUSIC) }
    var name by remember { mutableStateOf("…") }
    var now by remember { mutableStateOf<Track?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { api.accountName() }.onSuccess { name = it }
    }

    var streamRetryFor by remember { mutableStateOf<String?>(null) }

    fun play(t: Track) {
        scope.launch {
            runCatching { api.audioUrl(t.id) }
                .onSuccess { streamUrl ->
                    player.play(t, streamUrl)
                    now = t
                    screen = Screen.PLAYER
                }
        }
    }

    DisposableEffect(Unit) {
        player.onEnded {
            scope.launch {
                runCatching { wave.next(player.exo.duration.coerceAtLeast(0) / 1000, false) }
                    .onSuccess { it?.let { t -> streamRetryFor = null; play(t) } }
            }
        }
        player.onError {
            val t = now
            if (t != null && streamRetryFor != t.id) {
                streamRetryFor = t.id
                scope.launch {
                    runCatching { api.audioUrl(t.id) }
                        .onSuccess { freshUrl -> player.play(t, freshUrl) }
                }
            }
        }
        onDispose { }
    }

    Column(
        Modifier.onPreviewKeyEvent { e ->
            if (e.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (player.exo.isPlaying) player.exo.pause() else player.exo.play()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { player.exo.play(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.exo.pause(); true }
                    else -> false
                }
            } else false
        },
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Музыка", fontSize = 30.sp, color = Color.White, modifier = Modifier.weight(1f))
            Text(name, fontSize = 16.sp, color = Color.LightGray)
            now?.let { Button(onClick = { screen = Screen.PLAYER }) { Text("♫ ${it.title.take(24)}") } }
            Button(onClick = logout) { Text("Выйти") }
        }
        if (screen != Screen.PLAYER) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { screen = Screen.MUSIC }) { Text("Музыка") }
                Button(onClick = { screen = Screen.AUDIO }) { Text("Аудио") }
                Button(onClick = { screen = Screen.SEARCH }) { Text("Поиск") }
            }
        }
        when (screen) {
            Screen.MUSIC -> MusicScreen(api, wave) { t -> play(t) }
            Screen.AUDIO -> AudioScreen(api, ::play)
            Screen.SEARCH -> SearchScreen(api, ::play)
            Screen.PLAYER -> PlayerScreen(api, player, wave, now, { t -> play(t) }) { screen = Screen.MUSIC }
        }
    }
}

@Composable
private fun MusicScreen(api: YandexMusicApi, wave: WaveSession, play: (Track) -> Unit) {
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var title by remember { mutableStateOf("Главная") }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(WaveSettings()) }
    var sections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        sections = runCatching { api.homeSections() }.getOrDefault(emptyList())
    }

    fun waveStart() {
        scope.launch {
            runCatching { wave.start(settings) }
                .onSuccess { t ->
                    title = "Моя Волна"
                    if (t != null) {
                        tracks = listOf(t)
                        play(t)
                    }
                }
                .onFailure { error = it.message }
        }
    }

    fun chart() {
        scope.launch {
            runCatching { api.chart() }
                .onSuccess { title = "Чарт"; tracks = it }
                .onFailure { error = it.message }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 30.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { waveStart() }) { Text("▶ Моя Волна") }
            Button(onClick = { chart() }) { Text("Чарт") }
            Button(onClick = {
                settings = settings.copy(mood = if (settings.mood == "calm") "active" else "calm")
                waveStart()
            }) { Text("Настроение: ${if (settings.mood == "calm") "спокойное" else "активное"}") }
            Button(onClick = {
                settings = settings.copy(diversity = if (settings.diversity == "discover") "favorite" else "discover")
                waveStart()
            }) { Text(if (settings.diversity == "discover") "Знакомое" else "Новое") }
        }
        error?.let { Text(it, color = Color(0xFFFF8A80)) }
        if (tracks.isNotEmpty()) {
            TrackList(tracks, play)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 455.dp)
            ) {
                items(sections, key = { it.title }) { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(section.title, fontSize = 22.sp, color = Color.White)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(section.cards.take(12), key = { it.id + it.title }) { card ->
                                HomePoster(card) {
                                    scope.launch {
                                        if (!card.uid.isNullOrBlank() && !card.kind.isNullOrBlank()) {
                                            tracks = runCatching { api.playlistTracks(card.uid!!, card.kind!!) }.getOrDefault(emptyList())
                                            title = card.title
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerScreen(
    api: YandexMusicApi,
    player: TvPlayer,
    wave: WaveSession,
    track: Track?,
    play: (Track) -> Unit,
    back: () -> Unit
) {
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(track?.id) {
        track?.let { lyrics = runCatching { api.lyrics(it.id) }.getOrNull() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            pos = player.exo.currentPosition.coerceAtLeast(0)
            delay(250)
        }
    }

    val lines = lyrics?.lines.orEmpty()
    val idx = lines.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = back) { Text("← Назад") }
        Text(track?.title ?: "Плеер", fontSize = 36.sp, color = Color.White)
        Text(track?.artist.orEmpty(), fontSize = 22.sp, color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { player.exo.seekTo((player.exo.currentPosition - 15000).coerceAtLeast(0)) }) { Text("−15 сек") }
            Button(onClick = { if (player.exo.isPlaying) player.exo.pause() else player.exo.play() }) {
                Text(if (player.exo.isPlaying) "Пауза" else "▶ Играть")
            }
            Button(onClick = {
                scope.launch {
                    runCatching { wave.likeCurrent() }
                        .onSuccess { message = "Добавлено в любимое ♥" }
                        .onFailure { message = it.message }
                }
            }) { Text("♥ Нравится") }
            Button(onClick = {
                scope.launch {
                    runCatching { wave.next(player.exo.currentPosition / 1000, true) }
                        .onSuccess { it?.let(play) }
                        .onFailure { message = it.message }
                }
            }) { Text("Следующий »") }
            Button(onClick = {
                scope.launch {
                    runCatching { wave.dislikeAndSkip(player.exo.currentPosition / 1000) }
                        .onSuccess { it?.let(play) }
                        .onFailure { message = it.message }
                }
            }) { Text("Не рекомендовать") }
        }
        message?.let { Text(it, color = Color(0xFFFFDB4D)) }
        if (lines.isEmpty()) {
            Text("Для этого трека синхронизированный текст не найден", color = Color.Gray)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lines.getOrNull(idx - 1)?.let { Text(it.text, fontSize = 22.sp, color = Color.Gray) }
                lines.getOrNull(idx)?.let { Text(it.text, fontSize = 32.sp, color = Color(0xFFFFDB4D)) }
                lines.getOrNull(idx + 1)?.let { Text(it.text, fontSize = 22.sp, color = Color.LightGray) }
            }
        }
    }
}

@Composable
private fun SearchScreen(api: YandexMusicApi, play: (Track) -> Unit) {
    var q by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Поиск", fontSize = 30.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text("Трек, исполнитель, сказка") }, modifier = Modifier.width(520.dp))
            Button(onClick = { scope.launch { tracks = runCatching { api.searchTracks(q) }.getOrDefault(emptyList()) } }) { Text("Найти") }
        }
        TrackList(tracks, play)
    }
}

@Composable
private fun AudioScreen(api: YandexMusicApi, play: (Track) -> Unit) {
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var title by remember { mutableStateOf("Аудио") }
    val scope = rememberCoroutineScope()

    fun search(titleText: String, query: String) {
        title = titleText
        scope.launch { tracks = runCatching { api.searchTracks(query) }.getOrDefault(emptyList()) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 30.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { search("Қазақша ертегілер", "қазақша ертегілер") }) { Text("Қазақша ертегілер") }
            Button(onClick = { search("Сказки на русском", "детские сказки") }) { Text("Сказки на русском") }
            Button(onClick = { search("Аудиокниги", "аудиокнига") }) { Text("Аудиокниги") }
            Button(onClick = { search("Подкасты", "подкаст") }) { Text("Подкасты") }
        }
        TrackList(tracks, play)
    }
}

@Composable
private fun TrackList(tracks: List<Track>, play: (Track) -> Unit) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 455.dp)
    ) {
        items(tracks, key = { it.id }) { t -> TrackRow(t, onClick = { play(t) }) }
    }
}
