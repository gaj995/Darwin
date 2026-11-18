package io.github.gajdarwin.cast

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import org.json.JSONObject
import kotlin.math.roundToInt

private val RemoteMint=Color(0xFFBBF2CD)
private val RemoteMuted=Color(0xFFA9B9C0)
private val RemoteCard=Color(0xFF1B282F)

@Composable fun DarwinRemotePanel(
    paired:Boolean,state:JSONObject,connection:String,
    send:(String,JSONObject)->Unit,onConnect:()->Unit,onTracks:()->Unit,onSubtitles:()->Unit,onStop:()->Unit
) {
    val haptic=LocalHapticFeedback.current
    fun command(op:String,data:JSONObject=JSONObject()){haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);send(op,data)}
    fun finite(name:String,default:Float=0f)=state.optDouble(name,default.toDouble()).toFloat().let{if(it.isFinite())it else default}
    val phase=state.optString("phase")
    val hasPlayback=phase in listOf("playing","paused","buffering","starting","connecting","resolving")
    val controllablePlayback=paired&&phase in listOf("playing","paused","buffering")
    val time=finite("time").coerceAtLeast(0f)
    val duration=finite("duration").coerceAtLeast(0f)
    val live=state.optBoolean("live")
    val seekStart=finite("seekStart").coerceAtLeast(0f)
    val seekEnd=finite("seekEnd",duration).coerceAtLeast(seekStart)
    val seekable=controllablePlayback&&state.optBoolean("seekable")&&seekEnd>seekStart
    var scrub by remember(state.optString("castId"),state.optString("title")){mutableStateOf<Float?>(null)}
    var section by remember { mutableIntStateOf(if(hasPlayback)1 else 0) }
    var sectionChosen by remember { mutableStateOf(false) }
    LaunchedEffect(hasPlayback){if(!sectionChosen)section=if(hasPlayback)1 else 0}
    LaunchedEffect(paired,seekable){if(!paired||!seekable)scrub=null}

    // An absent readback is unknown, never a fabricated zero volume.
    val volume=(state.opt("volume") as? Number)?.toDouble()?.takeIf{it.isFinite()&&it in 0.0..100.0}?.roundToInt()
    val muted=state.opt("muted") as? Boolean
    val canVolume=paired&&(!state.has("volumeAvailable")||state.optBoolean("volumeAvailable"))
    var volumeDraft by remember { mutableStateOf<Float?>(null) }
    var pendingVolume by remember { mutableStateOf<Int?>(null) }
    var pendingMute by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(volume,pendingVolume){if(pendingVolume!=null&&volume==pendingVolume)pendingVolume=null}
    LaunchedEffect(muted,pendingMute){if(pendingMute!=null&&muted==pendingMute)pendingMute=null}
    LaunchedEffect(paired,canVolume){if(!canVolume){volumeDraft=null;pendingVolume=null;pendingMute=null}}
    LaunchedEffect(pendingVolume){if(pendingVolume!=null){delay(2500);pendingVolume=null}}
    LaunchedEffect(pendingMute){if(pendingMute!=null){delay(2500);pendingMute=null}}
    val shownVolume=if(paired)(volumeDraft?.roundToInt()?:pendingVolume?:volume)else null
    val shownMuted=if(paired)(pendingMute?:muted)else null

    @Composable fun Volume() {
        Card(colors=CardDefaults.cardColors(containerColor=RemoteCard),shape=RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_volume),null,tint=RemoteMint,modifier=Modifier.size(24.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(L("TV volume"),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                        Text(L(when{!paired->"Connect to adjust";!canVolume->"Volume control unavailable";shownMuted==true->"Muted";shownMuted==false->"Sound on";else->"Sound status unavailable"}),fontSize=12.sp,color=if(shownMuted==true)RemoteMint else RemoteMuted)
                    }
                    Row(verticalAlignment=Alignment.Bottom,modifier=Modifier.semantics(mergeDescendants=true){contentDescription=L(if(shownVolume!=null)"TV volume $shownVolume out of 100"else"TV volume level unavailable")}) {
                        Text(L(shownVolume?.toString()?:"—"),fontSize=30.sp,fontWeight=FontWeight.SemiBold,color=if(paired)Color(0xFFF0F5F3)else RemoteMuted)
                        if(shownVolume!=null)Text(L(" / 100"),fontSize=12.sp,color=RemoteMuted,modifier=Modifier.padding(bottom=5.dp))
                    }
                }
                if(canVolume&&volume!=null) {
                    Slider(
                        value=(volumeDraft?:pendingVolume?.toFloat()?:volume.toFloat()).coerceIn(0f,100f),
                        onValueChange={volumeDraft=it},
                        onValueChangeFinished={
                            volumeDraft?.roundToInt()?.coerceIn(0,100)?.let{level->
                                if(canVolume){pendingVolume=level;command("volume",JSONObject().put("level",level))}
                            }
                            volumeDraft=null
                        },
                        valueRange=0f..100f,enabled=canVolume,
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics{contentDescription=L("TV volume");stateDescription=if(shownVolume!=null)L("$shownVolume out of 100")else L("TV volume level unavailable")}
                    )
                } else if(paired&&canVolume) {
                    Text(L("The TV has not reported its level. Use −1, +1 or mute."),fontSize=12.sp,color=RemoteMuted,lineHeight=17.sp)
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick={shownVolume?.let{pendingVolume=(it-1).coerceAtLeast(0)};command("volume",JSONObject().put("delta",-1))},
                        enabled=canVolume&&(shownVolume==null||shownVolume>0),
                        modifier=Modifier.heightIn(min=48.dp).widthIn(min=64.dp).semantics{contentDescription=L("Lower TV volume by one")},
                        contentPadding=PaddingValues(horizontal=14.dp)
                    ){Text(L("−1"),fontSize=16.sp,fontWeight=FontWeight.SemiBold)}
                    FilledTonalButton(
                        onClick={if(shownMuted==null)command("volume",JSONObject().put("mute",true))else{pendingMute=!shownMuted;command("volume",JSONObject().put("muted",!shownMuted))}},
                        enabled=canVolume,modifier=Modifier.weight(1f).heightIn(min=48.dp).semantics{contentDescription=L(when(shownMuted){true->"Unmute TV audio";false->"Mute TV audio";else->"Toggle TV mute"})},
                        colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(shownMuted==true)RemoteMint else Color(0xFF30414A),contentColor=if(shownMuted==true)Color(0xFF14251C)else Color(0xFFF0F5F3)),
                        contentPadding=PaddingValues(horizontal=10.dp)
                    ){
                        Icon(painterResource(if(shownMuted==true)R.drawable.ic_volume else R.drawable.ic_mute),null,modifier=Modifier.size(20.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(L(when(shownMuted){true->"Unmute";false->"Mute";else->"Toggle mute"}),fontSize=13.sp)
                    }
                    FilledTonalButton(
                        onClick={shownVolume?.let{pendingVolume=(it+1).coerceAtMost(100)};command("volume",JSONObject().put("delta",1))},
                        enabled=canVolume&&(shownVolume==null||shownVolume<100),
                        modifier=Modifier.heightIn(min=48.dp).widthIn(min=64.dp).semantics{contentDescription=L("Raise TV volume by one")},
                        contentPadding=PaddingValues(horizontal=14.dp)
                    ){Text(L("+1"),fontSize=16.sp,fontWeight=FontWeight.SemiBold)}
                }
                if(shownMuted==true)Text(L("Your volume level is kept while muted."),color=RemoteMuted,fontSize=12.sp)
            }
        }
    }
    @Composable fun Playback() {
        Card(colors=CardDefaults.cardColors(containerColor=RemoteCard),shape=RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                    Text(L("VIDEO PLAYBACK"),color=RemoteMint,fontSize=10.sp,letterSpacing=1.5.sp,fontWeight=FontWeight.SemiBold)
                    Text(L(phase.replaceFirstChar{it.uppercase()}.ifBlank{"Ready"}),color=RemoteMuted,fontSize=12.sp)
                }
                Text(state.optString("title").ifBlank{L("Cast a video to get started")},fontSize=19.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(!hasPlayback)Text(L("Choose a video in Browse, Files or your Library."),fontSize=13.sp,color=RemoteMuted)
                Column {
                    Text(L("Seek in video"),fontSize=12.sp,color=RemoteMuted)
                    Slider(
                        value=(scrub?:time).coerceIn(seekStart,seekEnd.coerceAtLeast(seekStart+1f)),
                        onValueChange={scrub=it},
                        onValueChangeFinished={if(seekable)scrub?.let{command("seek",JSONObject().put("time",it.toDouble()))};scrub=null},
                        valueRange=seekStart..seekEnd.coerceAtLeast(seekStart+1f),enabled=seekable,
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics{contentDescription=L("Video playback position");stateDescription=L(remoteTime(scrub?:time)+(if(duration>0)" of "+remoteTime(duration)else""))}
                    )
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Text(L(if(live&&seekable)"−"+remoteTime((seekEnd-(scrub?:time)).coerceAtLeast(0f))else remoteTime(scrub?:time)),fontSize=13.sp,color=Color(0xFFE7EEEE))
                        Text(L(if(live)"LIVE"else if(duration>0)remoteTime(duration)else"—"),fontSize=13.sp,color=RemoteMuted)
                    }
                    if(live&&!seekable)Text(L("This live stream has no rewind window."),fontSize=12.sp,color=RemoteMuted,modifier=Modifier.padding(top=8.dp))
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                    FilledTonalButton(onClick={command("action",JSONObject().put("action","back10"))},enabled=seekable,modifier=Modifier.height(56.dp).semantics{contentDescription=L("Rewind video ten seconds")},contentPadding=PaddingValues(14.dp)){Text(L("−10s"),fontWeight=FontWeight.SemiBold)}
                    Button(onClick={command("action",JSONObject().put("action","toggle"))},enabled=controllablePlayback,modifier=Modifier.weight(1f).height(56.dp),shape=RoundedCornerShape(18.dp),contentPadding=PaddingValues(horizontal=10.dp)){
                        Icon(painterResource(if(phase=="paused")R.drawable.ic_play else R.drawable.ic_pause),null,modifier=Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp));Text(L(if(phase=="paused")"Resume"else"Pause"),fontSize=13.sp)
                    }
                    FilledTonalButton(onClick={command("action",JSONObject().put("action","fwd10"))},enabled=seekable,modifier=Modifier.height(56.dp).semantics{contentDescription=L("Forward video ten seconds")},contentPadding=PaddingValues(14.dp)){Text(L("+10s"),fontWeight=FontWeight.SemiBold)}
                }
            }
        }
    }
    @Composable fun Dpad() {
        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(L("TV navigation"),fontSize=16.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.align(Alignment.Start))
            Box(Modifier.size(220.dp).background(Brush.linearGradient(listOf(Color(0xFF293E46),Color(0xFF17262E))),CircleShape).border(1.dp,Color.White.copy(alpha=.07f),CircleShape)) {
                fun key(k:String){command("remote_key",JSONObject().put("key",k))}
                @Composable fun Arrow(icon:Int,label:String,modifier:Modifier,k:String){
                    val interactions=remember{MutableInteractionSource()}
                    val pressed by interactions.collectIsPressedAsState()
                    var sentForPress by remember{mutableStateOf(false)}
                    val repeatKey by rememberUpdatedState(newValue={send("remote_key",JSONObject().put("key",k))})
                    LaunchedEffect(pressed,paired){
                        if(pressed&&paired){
                            sentForPress=true;key(k)
                            delay(320)
                            while(isActive){repeatKey();delay(180)}
                        }else{yield();sentForPress=false}
                    }
                    IconButton(onClick={if(!sentForPress)key(k);sentForPress=false},enabled=paired,interactionSource=interactions,modifier=modifier.size(64.dp)){Icon(painterResource(icon),L(label),modifier=Modifier.size(28.dp),tint=if(paired)Color(0xFFF0F5F3)else RemoteMuted.copy(alpha=.5f))}
                }
                Arrow(R.drawable.ic_up,"Move up",Modifier.align(Alignment.TopCenter).padding(top=2.dp),"up")
                Arrow(R.drawable.ic_down,"Move down",Modifier.align(Alignment.BottomCenter).padding(bottom=2.dp),"down")
                Arrow(R.drawable.ic_left,"Move left",Modifier.align(Alignment.CenterStart).padding(start=2.dp),"left")
                Arrow(R.drawable.ic_right,"Move right",Modifier.align(Alignment.CenterEnd).padding(end=2.dp),"right")
                Button(onClick={key("ok")},enabled=paired,modifier=Modifier.align(Alignment.Center).size(80.dp),shape=CircleShape,contentPadding=PaddingValues(0.dp)){Text(L("OK"),fontSize=22.sp,fontWeight=FontWeight.SemiBold)}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                RemoteTile("Back",R.drawable.ic_back,paired,Modifier.weight(1f)){command("remote_key",JSONObject().put("key","back"))}
                RemoteTile("Darwin home",R.drawable.ic_home,paired,Modifier.weight(1f)){onStop()}
            }
            Text(L("Left and right seek during playback. OK confirms the selected time."),color=RemoteMuted,fontSize=12.sp,lineHeight=17.sp)
        }
    }
    @Composable fun Extras() {
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                RemoteTile("Audio & quality",R.drawable.ic_tune,paired,Modifier.weight(1f),onTracks)
                RemoteTile("Subtitles",R.drawable.ic_captions,paired,Modifier.weight(1f),onSubtitles)
            }
            TextButton(onClick=onStop,enabled=paired&&hasPlayback,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
                Icon(painterResource(R.drawable.ic_stop),null,modifier=Modifier.size(17.dp));Spacer(Modifier.width(8.dp));Text(L("Stop casting"))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=720.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(6.dp).background(if(paired)RemoteMint else Color(0xFFE5BD83),CircleShape))
                        Text(L(if(paired&&connection=="Connected to TV")"Samsung TV · Connected"else connection),fontSize=12.sp,color=RemoteMuted,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
                if(!paired)Button(onClick=onConnect,modifier=Modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=14.dp)){Text(L("Connect TV"),fontSize=13.sp)}
            }
            if(wide)Row(horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.Top){
                Column(Modifier.weight(.9f),verticalArrangement=Arrangement.spacedBy(18.dp)){Dpad();Extras()}
                Column(Modifier.weight(1.1f),verticalArrangement=Arrangement.spacedBy(16.dp)){Volume();Playback()}
            } else {
                Volume()
                Row(Modifier.fillMaxWidth().background(RemoteCard,RoundedCornerShape(17.dp)).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    listOf("Navigation","Playback").forEachIndexed{index,label->
                        val select={section=index;sectionChosen=true}
                        if(section==index)Button(onClick=select,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(13.dp)){Text(L(label))}
                        else TextButton(onClick=select,modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text(L(label),color=RemoteMuted)}
                    }
                }
                if(section==0)Dpad()else Playback()
                Extras()
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable private fun RemoteTile(label:String,icon:Int,enabled:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    FilledTonalButton(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=64.dp),shape=RoundedCornerShape(18.dp),contentPadding=PaddingValues(10.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){Icon(painterResource(icon),null,modifier=Modifier.size(21.dp));Text(L(label),fontSize=12.sp,maxLines=2)}
    }
}
private fun remoteTime(v:Float):String {val s=v.toInt().coerceAtLeast(0);return if(s>=3600)(s/3600).toString()+":"+"%02d".format(s/60%60)+":"+"%02d".format(s%60)else(s/60).toString()+":"+"%02d".format(s%60)}
