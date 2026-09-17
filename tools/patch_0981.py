from pathlib import Path
p=Path('app/src/main/java/dev/ymusictv/MainActivity.kt')
s=p.read_text()
old_effect='''    LaunchedEffect(idx,lines.size,showLyrics){if(showLyrics&&synced&&lines.isNotEmpty())lyricState.animateScrollToItem((idx-2).coerceAtLeast(0))}'''
new_effect='''    val nextTime=lines.getOrNull(idx+1)?.timeMs ?: (lines.getOrNull(idx)?.timeMs?.plus(4000L) ?: pos+4000L)
    val currentTime=lines.getOrNull(idx)?.timeMs ?: pos
    val lyricFraction=if(synced&&nextTime>currentTime)((pos-currentTime).toFloat()/(nextTime-currentTime).toFloat()).coerceIn(0f,1f) else 0f
    LaunchedEffect(pos,lines.size,showLyrics){
        if(showLyrics&&synced&&lines.isNotEmpty()){
            val base=(idx-1).coerceAtLeast(0)
            lyricState.scrollToItem(base,(lyricFraction*35f).toInt())
        }
    }'''
if old_effect not in s: raise SystemExit('old lyric effect not found')
s=s.replace(old_effect,new_effect)
old='''if(synced) LazyColumn(state=lyricState,modifier=Modifier.fillMaxWidth().height(178.dp),userScrollEnabled=false,verticalArrangement=Arrangement.spacedBy(3.dp)){
                            itemsIndexed(lines,key={i,l->"$i-${l.timeMs}"}){i,line->
                                val d=kotlin.math.abs(i-idx);val active=i==idx
                                Box(Modifier.fillMaxWidth().height(32.dp),contentAlignment=Alignment.Center){Text(line.text,fontSize=if(active)24.sp else 19.sp,color=if(active)Color.White else Color.White.copy(alpha=when(d){1->0.58f;2->0.34f;else->0.16f}),textAlign=TextAlign.Center,maxLines=1)}
                            }
                        } else LazyColumn(modifier=Modifier.fillMaxWidth().height(178.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){items(lines){Text(it.text,fontSize=20.sp,color=Color.White.copy(alpha=.82f),modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)}}'''
new='''if(synced) LazyColumn(state=lyricState,modifier=Modifier.fillMaxWidth().height(105.dp),userScrollEnabled=false,verticalArrangement=Arrangement.spacedBy(3.dp)){
                            itemsIndexed(lines,key={i,l->"$i-${l.timeMs}"}){i,line->
                                val relative=i-idx
                                val alpha=when(relative){
                                    -1 -> (0.34f*(1f-lyricFraction)).coerceIn(0.05f,0.34f)
                                    0 -> (1f-0.66f*lyricFraction).coerceIn(0.34f,1f)
                                    1 -> (0.34f+0.66f*lyricFraction).coerceIn(0.34f,1f)
                                    2 -> (0.34f*lyricFraction).coerceIn(0.05f,0.34f)
                                    else -> 0.05f
                                }
                                val edge=relative==-1 || relative==2
                                Box(Modifier.fillMaxWidth().height(32.dp),contentAlignment=Alignment.Center){Text(line.text,fontSize=20.sp,color=Color.White.copy(alpha=alpha),modifier=if(edge)Modifier.blur(1.5.dp) else Modifier,textAlign=TextAlign.Center,maxLines=1)}
                            }
                        } else Column(Modifier.fillMaxWidth().height(105.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){lines.take(3).forEachIndexed{i,line->Text(line.text,fontSize=20.sp,color=Color.White.copy(alpha=if(i==1).86f else .34f),modifier=(if(i==1)Modifier else Modifier.blur(1.5.dp)).fillMaxWidth(),textAlign=TextAlign.Center,maxLines=1)}}'''
if old not in s: raise SystemExit('lyrics block not found')
s=s.replace(old,new)
p.write_text(s)
