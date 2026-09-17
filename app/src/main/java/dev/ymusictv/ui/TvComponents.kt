package dev.ymusictv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import coil.compose.AsyncImage
import dev.ymusictv.model.HomeCard
import dev.ymusictv.model.Track

val YandexYellow = Color(0xFFFFCC00)
val Surface = Color(0xFF17181D)
val SurfaceFocused = Color(0xFF25272E)

@Composable
fun TvNavButton(text:String, selected:Boolean=false, onClick:()->Unit) {
    Button(onClick=onClick) { Text(if(selected) "●  $text" else text, fontSize=17.sp) }
}

@Composable
fun HomePoster(card:HomeCard, onClick:()->Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if(focused) SurfaceFocused else Surface, label="poster")
    Button(onClick=onClick, modifier=Modifier.width(205.dp).height(190.dp).onFocusChanged{focused=it.isFocused}) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(10.dp)).background(bg).then(if(focused) Modifier.border(2.dp,YandexYellow,RoundedCornerShape(10.dp)) else Modifier)) {
                card.coverUrl?.let { AsyncImage(model=it, contentDescription=card.title, modifier=Modifier.fillMaxSize(), contentScale=ContentScale.Crop) }
            }
            Text(card.title, maxLines=2, overflow=TextOverflow.Ellipsis, fontWeight=FontWeight.SemiBold, fontSize=15.sp)
            if(card.subtitle.isNotBlank()) Text(card.subtitle, maxLines=1, overflow=TextOverflow.Ellipsis, fontSize=12.sp, color=Color.LightGray)
        }
    }
}

@Composable
fun TrackRow(track:Track, playing:Boolean=false, onClick:()->Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick=onClick, modifier=Modifier.fillMaxWidth().height(64.dp).onFocusChanged{focused=it.isFocused}) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Surface)) {
                track.coverUrl?.let { AsyncImage(model=it,contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
            }
            Column(Modifier.weight(1f)) {
                Text((if(playing) "▶  " else "")+track.title, maxLines=1, overflow=TextOverflow.Ellipsis, fontSize=17.sp, color=if(playing) YandexYellow else Color.White)
                Text(track.artist, maxLines=1, overflow=TextOverflow.Ellipsis, fontSize=13.sp, color=Color.LightGray)
            }
            if(track.durationMs>0) Text("%d:%02d".format(track.durationMs/60000,(track.durationMs/1000)%60),color=Color.Gray)
        }
    }
}
