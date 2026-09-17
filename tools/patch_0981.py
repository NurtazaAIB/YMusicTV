from pathlib import Path
p=Path('app/src/main/java/dev/ymusictv/MainActivity.kt')
s=p.read_text()
if 'import androidx.compose.ui.graphics.graphicsLayer' not in s:
    anchor='import androidx.compose.ui.graphics.Color\n'
    if anchor not in s: raise SystemExit('Color import anchor not found')
    s=s.replace(anchor,anchor+'import androidx.compose.ui.graphics.graphicsLayer\n',1)
old_effect='''    LaunchedEffect(idx,lines.size,showLyrics){if(showLyrics&&synced&&lines.isNotEmpty())lyricState.animateScrollToItem((idx-2).coerceAtLeast(0))}'''
new_effect='''    val nextTime=lines.getOrNull(idx+1)?.timeMs ?: (lines.getOrNull(idx)?.timeMs?.plus(4000L) ?: pos+4000L)
    val currentTime=lines.getOrNull(idx)?.timeMs ?: pos
    val lyricFraction=if(synced&&nextTime>currentTime)((pos-currentTime).toFloat()/(nextTime-currentTime).toFloat()).coerceIn(0f,1f) else 0f
    val unsyncedPosition=if(!synced&&duration>0&&lines.size>1)(pos.toFloat()/duration.toFloat()*(lines.size-1)).coerceIn(0f,(lines.size-1).toFloat()) else 0f
    val unsyncedIndex=unsyncedPosition.toInt()
    val unsyncedFraction=unsyncedPosition-unsyncedIndex'''
if old_effect not in s: raise SystemExit('old lyric effect not found')
s=s.replace(old_effect,new_effect)
old='''                    message?.let{Text(it,color=Color(0xFFD6B5FF),fontSize=13.sp)}
                    if(showLyrics&&lines.isNotEmpty()){
                        if(synced) LazyColumn(state=lyricState,modifier=Modifier.fillMaxWidth().height(178.dp),userScrollEnabled=false,verticalArrangement=Arrangement.spacedBy(3.dp)){
                            itemsIndexed(lines,key={i,l->"$i-${l.timeMs}"}){i,line->
                                val d=kotlin.math.abs(i-idx);val active=i==idx
                                Box(Modifier.fillMaxWidth().height(32.dp),contentAlignment=Alignment.Center){Text(line.text,fontSize=if(active)24.sp else 19.sp,color=if(active)Color.White else Color.White.copy(alpha=when(d){1->0.58f;2->0.34f;else->0.16f}),textAlign=TextAlign.Center,maxLines=1)}
                            }
                        } else LazyColumn(modifier=Modifier.fillMaxWidth().height(178.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){items(lines){Text(it.text,fontSize=20.sp,color=Color.White.copy(alpha=.82f),modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)}}
                    }'''
new='''                    message?.let{Text(it,color=Color(0xFFD6B5FF),fontSize=13.sp)}'''
if old not in s: raise SystemExit('inline lyrics block not found')
s=s.replace(old,new)
needle='''        Column(Modifier.fillMaxSize().padding(horizontal=48.dp,vertical=30.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){'''
overlay='''        if(showLyrics&&lines.isNotEmpty()){
            Box(Modifier.fillMaxSize().padding(start=386.dp,end=48.dp,top=54.dp),contentAlignment=Alignment.TopCenter){
                Box(Modifier.fillMaxWidth().height(108.dp).graphicsLayer { clip = true }){
                    val activeIndex=if(synced)idx else unsyncedIndex
                    val activeFraction=if(synced)lyricFraction else unsyncedFraction
                    val visible=listOf(activeIndex-1,activeIndex,activeIndex+1,activeIndex+2)
                    visible.forEach{lineIndex->
                        lines.getOrNull(lineIndex)?.let{line->
                            val relative=lineIndex-activeIndex
                            val baseY=(relative+1)*34f-activeFraction*34f
                            val center=((baseY+17f)/102f).coerceIn(0f,1f)
                            val edgeDistance=kotlin.math.abs(center-.5f)*2f
                            val alpha=(1f-edgeDistance*.88f).coerceIn(.03f,.94f)
                            val blurDp=(edgeDistance*3.0f).dp
                            Text(line.text,fontSize=20.sp,color=Color.White.copy(alpha=alpha),textAlign=TextAlign.Center,maxLines=1,modifier=Modifier.fillMaxWidth().offset(y=baseY.dp).blur(blurDp))
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal=48.dp,vertical=30.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){'''
if needle not in s: raise SystemExit('main player column not found')
s=s.replace(needle,overlay,1)
p.write_text(s)
