package dev.ymusictv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import coil.compose.AsyncImage
import dev.ymusictv.model.HomeCard
import dev.ymusictv.model.Track

val YandexYellow = Color(0xFFFFCC00)
val Surface = Color(0xFF17181D)
val SurfaceFocused = Color(0xFF25272E)

private val FocusGlass = Color(0xC0181A20)
private val FocusGlassSoft = Color(0xA0181A20)
private val Transparent = Color.Transparent

@Composable
fun TvNavButton(text:String, selected:Boolean=false, onClick:()->Unit) {
    var focused by remember { mutableStateOf(false) }
    val active = focused || selected
    val scale by animateFloatAsState(if(focused) 1.07f else 1f, tween(140), label="navScale")
    val bg by animateColorAsState(if(active) FocusGlass else Transparent, tween(140), label="navBg")
    Button(
        onClick=onClick,
        modifier=Modifier.height(48.dp).scale(scale).onFocusChanged{ focused=it.isFocused },
        shape=ButtonDefaults.shape(shape=RoundedCornerShape(15.dp)),
        colors=ButtonDefaults.colors(
            containerColor=bg,
            contentColor=if(active) Color.White else Color(0xFFD5D5D8),
            focusedContainerColor=FocusGlass,
            focusedContentColor=Color.White,
            pressedContainerColor=Color(0xD0262931)
        ),
        contentPadding=PaddingValues(horizontal=18.dp, vertical=8.dp)
    ) { Text(text, fontSize=17.sp, fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
fun GlassButton(text:String, onClick:()->Unit, modifier:Modifier=Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if(focused) 1.06f else 1f, tween(140), label="glassScale")
    val bg by animateColorAsState(if(focused) FocusGlass else Transparent, tween(140), label="glassBg")
    Button(
        onClick=onClick,
        modifier=modifier.height(48.dp).scale(scale).onFocusChanged{focused=it.isFocused},
        shape=ButtonDefaults.shape(shape=RoundedCornerShape(15.dp)),
        colors=ButtonDefaults.colors(
            containerColor=bg, contentColor=Color.White,
            focusedContainerColor=FocusGlass, focusedContentColor=Color.White,
            pressedContainerColor=Color(0xD0262931)
        ),
        contentPadding=PaddingValues(horizontal=17.dp, vertical=8.dp)
    ){ Text(text,fontSize=16.sp) }
}

@Composable
fun HomePoster(card:HomeCard, onClick:()->Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(1f, tween(150), label="posterScale")
    val bg by animateColorAsState(if(focused) FocusGlassSoft else Transparent, tween(150), label="posterBg")
    Button(
        onClick=onClick,
        modifier=Modifier.width(205.dp).height(166.dp).scale(scale).onFocusChanged{focused=it.isFocused},
        shape=ButtonDefaults.shape(shape=RoundedCornerShape(16.dp)),
        colors=ButtonDefaults.colors(
            containerColor=bg, contentColor=Color.White,
            focusedContainerColor=FocusGlassSoft, focusedContentColor=Color.White,
            pressedContainerColor=Color(0xC8262931)
        ),
        contentPadding=PaddingValues(8.dp)
    ) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(11.dp)).background(Surface)) {
                card.coverUrl?.let { AsyncImage(model=it, contentDescription=card.title, modifier=Modifier.fillMaxSize(), contentScale=ContentScale.Crop) }
            }
            Text(card.title, maxLines=2, overflow=TextOverflow.Ellipsis, fontWeight=FontWeight.SemiBold, fontSize=15.sp)
        }
    }
}

@Composable
fun TrackRow(track:Track, playing:Boolean=false, onClick:()->Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(1f, tween(120), label="trackScale")
    val bg by animateColorAsState(if(focused) FocusGlassSoft else Transparent, tween(120), label="trackBg")
    Button(
        onClick=onClick,
        modifier=Modifier.fillMaxWidth().height(64.dp).scale(scale).onFocusChanged{focused=it.isFocused},
        shape=ButtonDefaults.shape(shape=RoundedCornerShape(14.dp)),
        colors=ButtonDefaults.colors(
            containerColor=bg, contentColor=Color.White,
            focusedContainerColor=FocusGlassSoft, focusedContentColor=Color.White,
            pressedContainerColor=Color(0xC8262931)
        ),
        contentPadding=PaddingValues(horizontal=10.dp,vertical=7.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(14.dp), verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)).background(Surface)) {
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
