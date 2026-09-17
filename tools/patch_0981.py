from pathlib import Path
p=Path('app/src/main/java/dev/ymusictv/MainActivity.kt')
s=p.read_text()
s=s.replace('lyricState.animateScrollToItem((idx-2).coerceAtLeast(0))','lyricState.animateScrollToItem((idx-1).coerceAtLeast(0))')
old='''if(synced) LazyColumn(state=lyricState,modifier=Modifier.fillMaxWidth().height(178.dp),userScrollEnabled=false,verticalArrangement=Arrangement.spacedBy(3.dp)){
                            itemsIndexed(lines,key={i,l->"$i-${l.timeMs}"}){i,line->
                                val d=kotlin.math.abs(i-idx);val active=i==idx
                                Box(Modifier.fillMaxWidth().height(32.dp),contentAlignment=Alignment.Center){Text(line.text,fontSize=if(active)24.sp else 19.sp,color=if(active)Color.White else Color.White.copy(alpha=when(d){1->0.58f;2->0.34f;else->0.16f}),textAlign=TextAlign.Center,maxLines=1)}
                            }
                        } else LazyColumn(modifier=Modifier.fillMaxWidth().height(178.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){items(lines){Text(it.text,fontSize=20.sp,color=Color.White.copy(alpha=.82f),modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)}}'''
new='''if(synced) LazyColumn(state=lyricState,modifier=Modifier.fillMaxWidth().height(105.dp),userScrollEnabled=false,verticalArrangement=Arrangement.spacedBy(3.dp)){
                            itemsIndexed(lines,key={i,l->"$i-${l.timeMs}"}){_,line->
                                Box(Modifier.fillMaxWidth().height(32.dp),contentAlignment=Alignment.Center){Text(line.text,fontSize=20.sp,color=Color.White.copy(alpha=.86f),textAlign=TextAlign.Center,maxLines=1)}
                            }
                        } else Column(Modifier.fillMaxWidth().height(105.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){lines.take(3).forEach{Text(it.text,fontSize=20.sp,color=Color.White.copy(alpha=.86f),modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center,maxLines=1)}}'''
if old not in s: raise SystemExit('lyrics block not found')
s=s.replace(old,new)
p.write_text(s)
