package thezaxis.aurora

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import thezaxis.aurora.data.AuroraRepository
import thezaxis.aurora.domain.*
import thezaxis.aurora.overlay.OverlayCoordinator
import thezaxis.aurora.system.AndroidPermissionChecker
import thezaxis.aurora.system.notificationAccessIntent
import thezaxis.aurora.system.overlayIntent
import thezaxis.aurora.system.usageAccessGranted
import thezaxis.aurora.system.usageAccessIntent

private val Ink = Color(0xFF0B0B0D); private val Surface = Color(0xFF1C1C22); private val Rule = Color(0xFF2A2A31)
private val Paper = Color(0xFFF5F2EC); private val Muted = Color(0xFFA8A39E); private val Success = Color(0xFF8CE3AE)

class MainActivity : ComponentActivity() {
    private var refreshTick by mutableIntStateOf(0)
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshTick++ }
    override fun onResume() { super.onResume(); refreshTick++ }
    override fun onPause() {
        OverlayCoordinator.leaveInApp(applicationContext)
        super.onPause()
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
        setContent { AuroraTheme { AuroraApp(refreshTick, settingsLauncher::launch) } }
    }
}

@Composable private fun AuroraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background=Ink, surface=Surface, onBackground=Paper, onSurface=Paper, primary=Paper, onPrimary=Ink, outline=Rule), content = content)
}
private enum class Screen { GATE, DASHBOARD, PICKER, GROUP, EDITOR, QUIET_APPS }
private data class InstalledApp(val packageName: String, val label: String, val icon: Drawable)

@Composable private fun AuroraApp(tick: Int, launchSettings: (Intent) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val repo = remember { AuroraRepository(context.applicationContext) }
    val configs by repo.configs.collectAsStateWithLifecycle(emptyList()); val prefs by repo.preferences.collectAsStateWithLifecycle(AuroraPreferences())
    var screen by rememberSaveable { mutableStateOf(Screen.GATE) }; var editing by remember { mutableStateOf<EdgeLightConfig?>(null) }
    var openQuietAfterUsageSetup by rememberSaveable { mutableStateOf(false) }
    val checker = remember(tick) { AndroidPermissionChecker(context) }; val ready = checker.notificationAccess() && checker.overlayAccess()
    LaunchedEffect(prefs.entered) { if (prefs.entered) screen = Screen.DASHBOARD }
    LaunchedEffect(tick, openQuietAfterUsageSetup) {
        if (openQuietAfterUsageSetup && usageAccessGranted(context)) { openQuietAfterUsageSetup=false; screen=Screen.QUIET_APPS }
    }
    // In Aurora itself all enabled lanes remain visible so their layout can be
    // understood and adjusted. The overlay falls back to live notifications on pause.
    LaunchedEffect(configs, prefs, screen, tick) {
        if (prefs.entered) OverlayCoordinator.showInApp(context.applicationContext, configs)
    }
    // The dashboard is the root destination. Every pushed app screen returns there
    // through Android's Back gesture/button instead of finishing the activity.
    BackHandler(enabled = screen != Screen.DASHBOARD && (screen != Screen.GATE || prefs.entered)) {
        when (screen) {
            Screen.PICKER, Screen.GROUP, Screen.QUIET_APPS -> screen = Screen.DASHBOARD
            Screen.EDITOR -> { editing = null; screen = Screen.DASHBOARD }
            Screen.GATE -> screen = Screen.DASHBOARD
            Screen.DASHBOARD -> Unit
        }
    }
    AnimatedContent(screen, label="screen") { current -> when(current) {
        Screen.GATE -> PermissionGate(ready, checker.notificationAccess(), checker.overlayAccess(), { launchSettings(notificationAccessIntent()) }, { launchSettings(overlayIntent(context)) }) { scope.launch { repo.updatePreferences { it.copy(entered=true) } }; screen=Screen.DASHBOARD }
        Screen.DASHBOARD -> Dashboard(configs,prefs,ready,{ screen=Screen.GATE }, { screen=Screen.PICKER }, { screen=Screen.GROUP }, { editing=it;screen=Screen.EDITOR }, { c -> scope.launch { repo.saveConfig(c.copy(enabled=!c.enabled)); OverlayCoordinator.refresh(context.applicationContext) } }, { p -> scope.launch { repo.updatePreferences { p(it) }; OverlayCoordinator.refresh(context.applicationContext) } }, { if(usageAccessGranted(context)) screen=Screen.QUIET_APPS else { openQuietAfterUsageSetup=true; launchSettings(usageAccessIntent()) } })
        Screen.PICKER -> AppPicker(configs, onBack={screen=Screen.DASHBOARD}) { app -> editing=EdgeLightConfig(repo.newId(),ConfigKind.APP,app.label,setOf(app.packageName));screen=Screen.EDITOR }
        Screen.GROUP -> GroupCreator(configs, onBack={screen=Screen.DASHBOARD}) { name, packages -> editing=EdgeLightConfig(repo.newId(),ConfigKind.GROUP,name,packages);screen=Screen.EDITOR }
        Screen.EDITOR -> Editor(editing ?: return@AnimatedContent, onBack={screen=Screen.DASHBOARD}, onDone={ value -> scope.launch { repo.saveConfig(value); screen=Screen.DASHBOARD } }, onRemove={ id -> scope.launch { repo.removeConfig(id); screen=Screen.DASHBOARD } })
        Screen.QUIET_APPS -> QuietAppsScreen(prefs.quietPackageNames,onBack={screen=Screen.DASHBOARD}) { names -> scope.launch { repo.updatePreferences { it.copy(quietPackageNames=names) }; OverlayCoordinator.refresh(context.applicationContext) } }
    }}
}

@Composable private fun PermissionGate(ready:Boolean, listener:Boolean, overlay:Boolean, listenerAction:()->Unit, overlayAction:()->Unit, enter:()->Unit) {
    Scaffold(containerColor=Ink) { pad -> Column(Modifier.fillMaxSize().padding(pad).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("Aurora", color=Paper, fontFamily=FontFamily.Serif, fontSize=22.sp, fontWeight=FontWeight.SemiBold); Spacer(Modifier.height(25.dp))
        Text("A calmer way\nto notice.", color=Paper, fontFamily=FontFamily.Serif, fontSize=42.sp, lineHeight=44.sp)
        Text("Aurora keeps selected notifications on your device and turns them into a quiet light at the edge of your screen.", color=Muted, fontSize=16.sp, lineHeight=23.sp)
        PermissionRow("1", "Notification access", "Lets Aurora react to chosen app notifications.", listener, listenerAction)
        PermissionRow("2", "Display over other apps", "Lets the light sit above the app you are using.", overlay, overlayAction)
        PermissionRow("3", "Battery optimisation", "Optional — some phones may delay lights when sleeping.", true, null, optional=true)
        Spacer(Modifier.weight(1f)); Button(onClick=enter, enabled=ready, modifier=Modifier.fillMaxWidth().height(54.dp), shape=RoundedCornerShape(28.dp), colors=ButtonDefaults.buttonColors(containerColor=Paper,contentColor=Ink)) { Text("Enter Aurora",fontWeight=FontWeight.Bold) }
        if(!ready) Text("Both required permissions need to be ready first.", color=Muted, fontSize=12.sp, modifier=Modifier.align(Alignment.CenterHorizontally))
    }}
}
@Composable private fun PermissionRow(n:String,title:String,description:String,ready:Boolean, action:(()->Unit)?, optional:Boolean=false) { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface).border(1.dp,Rule,RoundedCornerShape(20.dp)).padding(16.dp), horizontalArrangement=Arrangement.spacedBy(14.dp)) { Text(n,color=Muted,fontFamily=FontFamily.Serif,fontSize=20.sp); Column(Modifier.weight(1f)) { Text(title,fontWeight=FontWeight.SemiBold); Text(description,color=Muted,fontSize=13.sp,lineHeight=18.sp); Spacer(Modifier.height(10.dp)); if(ready) Text(if(optional) "Optional" else "● Ready",color=Success,fontSize=13.sp) else TextButton(onClick={action?.invoke()}) { Text("Open settings") } } } }

@Composable private fun Dashboard(configs:List<EdgeLightConfig>, prefs:AuroraPreferences, ready:Boolean, repair:()->Unit, add:()->Unit, group:()->Unit, edit:(EdgeLightConfig)->Unit, toggle:(EdgeLightConfig)->Unit, update:((AuroraPreferences)->AuroraPreferences)->Unit, quietApps:()->Unit) {
    Scaffold(containerColor=Ink) { pad -> LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal=20.dp), contentPadding=PaddingValues(bottom=36.dp), verticalArrangement=Arrangement.spacedBy(0.dp)) {
        item { Row(Modifier.fillMaxWidth().padding(top=22.dp),verticalAlignment=Alignment.CenterVertically) { Text("Aurora",fontFamily=FontFamily.Serif,fontSize=22.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f)); StatusChip(ready,repair) }; Spacer(Modifier.height(34.dp)); Text("Light on\nthe edge.",fontFamily=FontFamily.Serif,fontSize=41.sp,lineHeight=42.sp); Spacer(Modifier.height(12.dp)); Text("Every app owns a sliver of your screen edge. When it speaks, the sliver glows.",color=Muted,lineHeight=21.sp); PhonePreview(configs,Modifier.fillMaxWidth().height(300.dp).padding(vertical=28.dp)); SectionTitle("Apps · ${configs.size}","Add",add) }
        items(configs,key={it.id}) { ConfigRow(it, {edit(it)}, {toggle(it)}); Divider() }
        item { AddRow("＋","Add an app","Choose a light for an installed app",add); Divider(); AddRow("＋","New group","Several apps, one glow",group); Divider(); SettingsBlock(prefs,update,quietApps) }
    }}
}
@Composable private fun StatusChip(ready:Boolean, repair:()->Unit) { Surface(shape=RoundedCornerShape(20.dp),color=Ink,border=ButtonDefaults.outlinedButtonBorder) { Text(if(ready) "●  Ready" else "!  Setup",color=if(ready) Success else Color(0xFFFFB86B),fontSize=12.sp,modifier=Modifier.clickable(onClick=repair).padding(horizontal=12.dp,vertical=8.dp)) } }
@Composable private fun SectionTitle(title:String,action:String,click:()->Unit) { Row(Modifier.fillMaxWidth().padding(bottom=10.dp),verticalAlignment=Alignment.CenterVertically) { Text(title,color=Muted,fontSize=13.sp,modifier=Modifier.weight(1f)); Text(action,modifier=Modifier.clickable(onClick=click),fontWeight=FontWeight.SemiBold,fontSize=13.sp) } }
@Composable private fun ConfigRow(c:EdgeLightConfig, click:()->Unit, toggle:()->Unit) { Row(Modifier.fillMaxWidth().heightIn(min=66.dp).clickable(onClick=click).padding(vertical=11.dp),verticalAlignment=Alignment.CenterVertically) { ColorDot(c.colorToken,22); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(c.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold); Text("${c.animation.name.lowercase().replaceFirstChar(Char::uppercase)} · ${c.edge.name.lowercase()} edge · ${(c.lengthFraction*100).toInt()}%",color=Muted,fontSize=12.sp) }; Switch(c.enabled,{toggle()},colors=SwitchDefaults.colors(checkedThumbColor=Ink,checkedTrackColor=Paper,uncheckedThumbColor=Muted,uncheckedTrackColor=Surface)) } }
@Composable private fun AddRow(icon:String,title:String,sub:String,onClick:()->Unit) { Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically) { Text(icon,Modifier.size(34.dp).border(1.dp,Rule,RoundedCornerShape(10.dp)).wrapContentSize(),color=Paper); Spacer(Modifier.width(12.dp)); Column { Text(title,fontWeight=FontWeight.SemiBold); Text(sub,color=Muted,fontSize=12.sp) } } }
@Composable private fun SettingsBlock(p:AuroraPreferences, update:((AuroraPreferences)->AuroraPreferences)->Unit, quietApps:()->Unit) { Spacer(Modifier.height(32.dp)); Text("Notifications",color=Muted,fontSize=13.sp); SettingSwitch("Tap to open","A tap on a light opens its notification.",p.tapToOpen) { update { it.copy(tapToOpen=it.tapToOpen.not()) } }; SettingSwitch("Swipe to open","An inward swipe reveals a compact peek.",p.swipeToOpen) { update { it.copy(swipeToOpen=!it.swipeToOpen) } }; PreviewAnimationSetting(p.previewCardAnimation) { animation -> update { it.copy(previewCardAnimation=animation) } }; SettingNavigation("Quiet while using",if(p.quietPackageNames.isEmpty()) "Hide edge lights in selected foreground apps." else "${p.quietPackageNames.size} app${if(p.quietPackageNames.size==1) "" else "s"} selected.",quietApps); SettingSwitch("Open on arrival","Off: lights wait quietly until you choose them.",p.openOnArrival) { update { it.copy(openOnArrival=!it.openOnArrival) } }; SettingSwitch("Clear after reading","Only clear items that Android permits Aurora to dismiss.",p.clearAfterReading) { update { it.copy(clearAfterReading=!it.clearAfterReading) } }; Spacer(Modifier.height(28.dp)); Text("Lock screen & screen off",color=Muted,fontSize=13.sp); Text("These options depend on device policy. Aurora never reveals text over a secure lock screen.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=10.dp)); SettingSwitch("Glow on the lock screen","When Android allows overlays while locked.",p.showOnLockScreen) { update { it.copy(showOnLockScreen=!it.showOnLockScreen) } }; SettingSwitch("Wake for new notifications","A brief dim surface, subject to your phone's limits.",p.wakeForNewNotifications) { update { it.copy(wakeForNewNotifications=!it.wakeForNewNotifications) } }; SliderSetting("Brightness",p.wakeBrightnessPercent,1..100) { v -> update { it.copy(wakeBrightnessPercent=v) } }; SliderSetting("Stay on for",p.wakeDurationSeconds,3..60,"s") { v -> update { it.copy(wakeDurationSeconds=v) } }; SettingSwitch("Show the clock","On Aurora's dim wake surface.",p.showClock) { update { it.copy(showClock=!it.showClock) } } }
@Composable private fun PreviewAnimationSetting(selected:PreviewCardAnimation,onSelect:(PreviewCardAnimation)->Unit) { Divider(Modifier.padding(top=14.dp)); Column(Modifier.fillMaxWidth().padding(vertical=13.dp)) { Text("Preview card animation",fontWeight=FontWeight.SemiBold); Text("Choose how a peek leaves and returns to its matching light.",color=Muted,fontSize=12.sp,lineHeight=16.sp,modifier=Modifier.padding(top=2.dp)); Row(Modifier.padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { PreviewCardAnimation.entries.forEach { animation -> val label=if(animation==PreviewCardAnimation.EDGE_SLIDE) "Edge slide" else "Slide & grow"; FilterChip(selected=selected==animation,onClick={onSelect(animation)},label={Text(label,fontSize=12.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Paper,selectedLabelColor=Ink,labelColor=Paper),border=FilterChipDefaults.filterChipBorder(enabled=true,selected=selected==animation,borderColor=Rule,selectedBorderColor=Paper)) } } } }
@Composable private fun SettingSwitch(title:String,desc:String,checked:Boolean,onChange:()->Unit) { Divider(Modifier.padding(top=14.dp)); Row(Modifier.fillMaxWidth().padding(vertical=13.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,fontWeight=FontWeight.SemiBold); Text(desc,color=Muted,fontSize=12.sp,lineHeight=16.sp) }; Switch(checked,onCheckedChange={onChange()},colors=SwitchDefaults.colors(checkedThumbColor=Ink,checkedTrackColor=Paper)) } }
@Composable private fun SettingNavigation(title:String,desc:String,onClick:()->Unit) { Divider(Modifier.padding(top=14.dp)); Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=13.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,fontWeight=FontWeight.SemiBold); Text(desc,color=Muted,fontSize=12.sp,lineHeight=16.sp) }; Text("›",color=Muted,fontSize=24.sp,modifier=Modifier.padding(start=12.dp)) } }
@Composable private fun SliderSetting(title:String,value:Int,range:IntRange,suffix:String="%",onValue:(Int)->Unit) { Divider(Modifier.padding(top=14.dp)); Row(Modifier.fillMaxWidth().padding(top=13.dp)){ Text(title,Modifier.weight(1f),fontWeight=FontWeight.SemiBold); Text("$value$suffix",color=Muted) }; Slider(value.toFloat(),{onValue(it.toInt())},valueRange=range.first.toFloat()..range.last.toFloat(),modifier=Modifier.height(36.dp),colors=SliderDefaults.colors(thumbColor=Paper,activeTrackColor=Paper,inactiveTrackColor=Rule)) }

@Composable private fun PhonePreview(configs:List<EdgeLightConfig>,modifier:Modifier=Modifier, editable:((Float)->Unit)?=null) { val segments=remember(configs){SegmentLayout.resolve(configs)}; Box(modifier,contentAlignment=Alignment.Center) { Canvas(Modifier.width(150.dp).fillMaxHeight().pointerInput(editable){ if(editable!=null) detectDragGestures { change,_ -> editable((change.position.y/size.height).coerceIn(0f,1f)) } }) { val l= size.width*.86f; val left=(size.width-l)/2; val top=4.dp.toPx(); val bottom=size.height-4.dp.toPx(); drawRoundRect(Color(0xFF15151A),topLeft=androidx.compose.ui.geometry.Offset(left,top),size=androidx.compose.ui.geometry.Size(l,bottom-top),cornerRadius=androidx.compose.ui.geometry.CornerRadius(27.dp.toPx()),style=Stroke(1.dp.toPx())); segments.forEach {s-> val c=configs.firstOrNull{it.id==s.configId}?:return@forEach; val color=Color(c.colorToken.argb); val glowOnly=c.thicknessDp<=0f; val stroke=if(glowOnly) 7.dp.toPx() else c.thicknessDp.dp.toPx(); val previewColor=if(glowOnly) color.copy(alpha=.42f) else color; val y0=top+(bottom-top)*s.start; val y1=top+(bottom-top)*s.end; when(s.edge){Edge.RIGHT->drawLine(previewColor,androidx.compose.ui.geometry.Offset(left+l,y0),androidx.compose.ui.geometry.Offset(left+l,y1),strokeWidth=stroke);Edge.LEFT->drawLine(previewColor,androidx.compose.ui.geometry.Offset(left,y0),androidx.compose.ui.geometry.Offset(left,y1),strokeWidth=stroke);Edge.TOP->drawLine(previewColor,androidx.compose.ui.geometry.Offset(left+l*s.start,top),androidx.compose.ui.geometry.Offset(left+l*s.end,top),strokeWidth=stroke);Edge.BOTTOM->drawLine(previewColor,androidx.compose.ui.geometry.Offset(left+l*s.start,bottom),androidx.compose.ui.geometry.Offset(left+l*s.end,bottom),strokeWidth=stroke)} } } } }
@Composable private fun ColorDot(color:AuroraColor,size:Int) { Box(Modifier.size(size.dp).background(Color(color.argb),CircleShape)) }

@Composable private fun AppPicker(configs:List<EdgeLightConfig>,onBack:()->Unit,onPick:(InstalledApp)->Unit) {
    val context=LocalContext.current; var query by rememberSaveable { mutableStateOf("") }; val apps=rememberInstalledApps()
    Scaffold(containerColor=Ink,topBar={ BackTop(onBack) }) { pad -> Column(Modifier.padding(pad).padding(horizontal=20.dp)) { Text("Choose an app",fontFamily=FontFamily.Serif,fontSize=38.sp); Spacer(Modifier.height(18.dp)); OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("Search apps")},singleLine=true,shape=RoundedCornerShape(16.dp),colors=fieldColors()); Spacer(Modifier.height(22.dp)); if (apps == null) { LoadingApps() } else { val filtered=apps.filter { it.label.contains(query,true) || it.packageName.contains(query,true) }.filterNot { it.packageName==context.packageName || configs.any { c->it.packageName in c.packageNames && c.kind==ConfigKind.APP } }; if(query.isBlank()) Text("All apps",color=Muted,fontSize=13.sp); if(filtered.isEmpty()) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("No matching apps",color=Muted)} else LazyColumn { items(filtered,key={it.packageName}){ app-> AppRow(app,{onPick(app)}) } } } } }
}
private fun discoverApps(context:Context):List<InstalledApp> { val pm=context.packageManager; val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER); return pm.queryIntentActivities(intent,PackageManager.MATCH_ALL).map { r-> InstalledApp(r.activityInfo.packageName,r.loadLabel(pm).toString(),r.loadIcon(pm)) }.distinctBy{it.packageName}.sortedBy{it.label.lowercase()} }
@Composable private fun rememberInstalledApps():List<InstalledApp>? { val appContext=LocalContext.current.applicationContext; val apps by produceState<List<InstalledApp>?>(null,appContext) { value=withContext(Dispatchers.Default) { discoverApps(appContext) } }; return apps }
@Composable private fun LoadingApps() { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Column(horizontalAlignment=Alignment.CenterHorizontally) { CircularProgressIndicator(modifier=Modifier.size(24.dp),color=Paper,strokeWidth=2.dp); Text("Loading apps…",color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=12.dp)) } } }
@Composable private fun AppRow(app:InstalledApp,onClick:()->Unit) { Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=13.dp),verticalAlignment=Alignment.CenterVertically){ AppIcon(app.icon); Spacer(Modifier.width(13.dp)); Column { Text(app.label,fontWeight=FontWeight.SemiBold); Text(app.packageName,color=Muted,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis) } } }
@Composable private fun AppIcon(drawable:Drawable) { AndroidView(factory={ ImageView(it).apply { setImageDrawable(drawable) } },modifier=Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))) }
@Composable private fun QuietAppsScreen(initial:Set<String>,onBack:()->Unit,onSave:(Set<String>)->Unit) {
    val context=LocalContext.current; val apps=rememberInstalledApps(); var selected by remember(initial) { mutableStateOf(initial) }; var query by rememberSaveable { mutableStateOf("") }
    Scaffold(containerColor=Ink,topBar={BackTop(onBack)},bottomBar={Surface(color=Ink){Button({onBack()},Modifier.fillMaxWidth().padding(20.dp).height(52.dp),shape=RoundedCornerShape(28.dp),colors=ButtonDefaults.buttonColors(containerColor=Paper,contentColor=Ink)){Text("Done")}}}) { pad ->
        Column(Modifier.padding(pad).padding(horizontal=20.dp)) {
            Text("Quiet while\nusing",fontFamily=FontFamily.Serif,fontSize=38.sp,lineHeight=40.sp)
            Text("Aurora hides active edge lights while one of these apps is in front. Usage access is used only on this device to identify the foreground app.",color=Muted,fontSize=13.sp,lineHeight=18.sp,modifier=Modifier.padding(top=10.dp,bottom=18.dp))
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),placeholder={Text("Search apps")},singleLine=true,shape=RoundedCornerShape(16.dp),colors=fieldColors())
            if(apps==null) LoadingApps() else { val shown=apps.filter{it.packageName!=context.packageName && (it.label.contains(query,true)||it.packageName.contains(query,true))}; if(shown.isEmpty()) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("No matching apps",color=Muted)} else LazyColumn(Modifier.padding(top=10.dp)){items(shown,key={it.packageName}){app-> val checked=app.packageName in selected; Row(Modifier.fillMaxWidth().clickable{selected=if(checked) selected-app.packageName else selected+app.packageName;onSave(selected)}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(checked,{selected=if(checked) selected-app.packageName else selected+app.packageName;onSave(selected)},colors=CheckboxDefaults.colors(checkedColor=Paper,checkmarkColor=Ink));Spacer(Modifier.width(8.dp));AppIcon(app.icon);Spacer(Modifier.width(12.dp));Text(app.label,fontWeight=FontWeight.SemiBold)}}} }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BackTop(back:()->Unit) { TopAppBar(title={Text("Aurora",fontFamily=FontFamily.Serif)},navigationIcon={ Text("‹",fontSize=36.sp,modifier=Modifier.padding(start=18.dp,end=12.dp).clickable(onClick=back).semantics{contentDescription="Back"}) },colors=TopAppBarDefaults.topAppBarColors(containerColor=Ink,titleContentColor=Paper,navigationIconContentColor=Paper)) }
@Composable private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedTextColor=Paper,unfocusedTextColor=Paper,focusedBorderColor=Paper,unfocusedBorderColor=Rule,focusedLabelColor=Muted,unfocusedLabelColor=Muted,cursorColor=Paper)

@Composable private fun GroupCreator(configs:List<EdgeLightConfig>,onBack:()->Unit,onCreate:(String,Set<String>)->Unit) {
    val apps = rememberInstalledApps()
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    Scaffold(
        containerColor = Ink,
        topBar = { BackTop(onBack) },
        bottomBar = {
            Surface(color = Ink) {
                Button(
                    onClick = { onCreate(name.trim(), selected) },
                    enabled = name.isNotBlank() && selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(20.dp).height(52.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Paper, contentColor = Ink)
                ) { Text("Create group") }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal=20.dp)) {
            Text("New group",fontFamily=FontFamily.Serif,fontSize=38.sp)
            Text("Apps in a group share one light and one peek panel.",color=Muted,modifier=Modifier.padding(top=8.dp,bottom=20.dp))
            OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Group name")},singleLine=true,shape=RoundedCornerShape(16.dp),colors=fieldColors())
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("Social","Messaging","Work","Family","News").forEach { chip ->
                    AssistChip({ name=chip },{ Text(chip) },colors=AssistChipDefaults.assistChipColors(containerColor=Surface,labelColor=Paper),border=AssistChipDefaults.assistChipBorder(borderColor=Rule,enabled=true))
                }
            }
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),placeholder={Text("Search apps")},singleLine=true,shape=RoundedCornerShape(16.dp),colors=fieldColors())
            if (apps == null) {
                LoadingApps()
            } else {
                val shown = apps.filter { it.label.contains(query, true) }
                LazyColumn(Modifier.padding(top=10.dp)) {
                    items(shown, key={it.packageName}) { app ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if(app.packageName in selected) selected-app.packageName else selected+app.packageName }.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(app.packageName in selected,{ selected=if(app.packageName in selected) selected-app.packageName else selected+app.packageName },colors=CheckboxDefaults.colors(checkedColor=Paper,checkmarkColor=Ink))
                            Spacer(Modifier.width(8.dp)); AppIcon(app.icon); Spacer(Modifier.width(12.dp))
                            Column { Text(app.label,fontWeight=FontWeight.SemiBold); if(configs.any{app.packageName in it.packageNames}) Text("Will move from its current light",color=Muted,fontSize=12.sp) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Editor(initial:EdgeLightConfig,onBack:()->Unit,onDone:(EdgeLightConfig)->Unit,onRemove:(String)->Unit) { var value by remember(initial.id){mutableStateOf(initial)};var confirmRemove by remember{mutableStateOf(false)};val scope=rememberCoroutineScope();val context=LocalContext.current
    LaunchedEffect(value) { OverlayCoordinator.preview(context, value) }
    DisposableEffect(initial.id) { onDispose { OverlayCoordinator.stopPreview(context) } }
    if(confirmRemove) AlertDialog(onDismissRequest={confirmRemove=false},title={Text("Remove this light?")},text={Text("Its notification lights will stop immediately.")},confirmButton={TextButton({onRemove(value.id)}){Text("Remove",color=Color(0xFFFF8A80))}},dismissButton={TextButton({confirmRemove=false}){Text("Keep")}},containerColor=Surface,textContentColor=Paper,titleContentColor=Paper)
    Scaffold(containerColor=Ink,topBar={ TopAppBar(title={Text("Edit light",fontFamily=FontFamily.Serif)},navigationIcon={Text("‹",fontSize=36.sp,modifier=Modifier.padding(start=18.dp,end=12.dp).clickable(onClick=onBack).semantics{contentDescription="Back"})},actions={Text("Remove",color=Color(0xFFFF8A80),modifier=Modifier.padding(14.dp).clickable{confirmRemove=true})},colors=TopAppBarDefaults.topAppBarColors(containerColor=Ink,titleContentColor=Paper)) },bottomBar={Surface(color=Ink){Button({onDone(value)},Modifier.fillMaxWidth().padding(20.dp).height(52.dp),shape=RoundedCornerShape(28.dp),colors=ButtonDefaults.buttonColors(containerColor=Paper,contentColor=Ink)){Text("Done")}}}) {pad-> LazyColumn(Modifier.padding(pad).padding(horizontal=20.dp),contentPadding=PaddingValues(bottom=86.dp)){item{Text(value.name,fontFamily=FontFamily.Serif,fontSize=34.sp);if(value.kind==ConfigKind.GROUP) Text("Shared group light",color=Muted,fontSize=13.sp);PhonePreview(listOf(value),Modifier.fillMaxWidth().height(240.dp).padding(vertical=20.dp)){fraction->value=value.copy(positionFraction=fraction)};Label("Edge");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){Edge.entries.forEach{edge-> FilterChip(edge==value.edge,{value=value.copy(edge=edge)},{Text(edge.name.lowercase().replaceFirstChar(Char::uppercase))},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Paper,selectedLabelColor=Ink,labelColor=Paper),border=FilterChipDefaults.filterChipBorder(enabled=true,selected=edge==value.edge,borderColor=Rule,selectedBorderColor=Paper))}};Label("Colour");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){AuroraColor.entries.forEach{color->Box(Modifier.size(36.dp).background(Color(color.argb),CircleShape).then(if(color==value.colorToken)Modifier.border(3.dp,Paper,CircleShape).padding(3.dp)else Modifier).clickable{value=value.copy(colorToken=color)}.semantics{contentDescription="${color.name.lowercase()} colour"})}};Label("Animation");Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){GlowAnimation.entries.forEach{a-> Column(Modifier.width(105.dp).clip(RoundedCornerShape(14.dp)).background(if(a==value.animation)Paper else Surface).clickable{value=value.copy(animation=a)}.padding(12.dp)){ColorDot(value.colorToken,20);Spacer(Modifier.height(9.dp));Text(a.name.lowercase().replaceFirstChar(Char::uppercase),color=if(a==value.animation)Ink else Paper,fontSize=13.sp)}}};Text(animationDescription(value.animation),color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=10.dp));Label("Shape");EditorSlider("Position",value.positionFraction,0f..1f,"%") {value=value.copy(positionFraction=it)};EditorSlider("Length",value.lengthFraction,.04f..8f,"%",scale=100f) {value=value.copy(lengthFraction=it)};EditorSlider("Thickness",value.thicknessDp,0f..12f,"dp") {value=value.copy(thicknessDp=it)};EditorSlider("Bloom",value.bloomDp,0f..32f,"dp") {value=value.copy(bloomDp=it)};Spacer(Modifier.height(18.dp));Text("Some channels can still show native banners. Manage those from Android's notification settings.",color=Muted,fontSize=13.sp,lineHeight=18.sp);TextButton({val i=Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName);context.startActivity(i)}){Text("Open notification settings")};OutlinedButton({OverlayCoordinator.test(context,value)},Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(28.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Paper),border=ButtonDefaults.outlinedButtonBorder){Text("Try it on this phone")}}} }
}
@Composable private fun Label(text:String){Text(text,color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=24.dp,bottom=10.dp))}
@Composable private fun EditorSlider(label:String,value:Float,range:ClosedFloatingPointRange<Float>,suffix:String,scale:Float=1f,onValue:(Float)->Unit){
    val percent = suffix == "%"; val displayScale = if (percent) 100f else scale
    val actualRange = if (label == "Length") .04f..1f else range
    Row(Modifier.fillMaxWidth()){Text(label,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Text("${(value*displayScale).toInt()}$suffix",color=Muted)}
    Slider(value,{onValue(if(label == "Length") it.coerceIn(.04f,1f) else it)},valueRange=actualRange,colors=SliderDefaults.colors(thumbColor=Paper,activeTrackColor=Paper,inactiveTrackColor=Rule))
}
private fun animationDescription(animation:GlowAnimation)=when(animation){GlowAnimation.BREATHE->"A slow, soft pulse that stays easy on the eyes.";GlowAnimation.AURORA->"A gentle drift of colour and bloom.";GlowAnimation.COMET->"A bright head glides along a fading tail.";GlowAnimation.PULSE->"A quick expansion for fresh arrivals.";GlowAnimation.EMBER->"A restrained, warm flicker.";GlowAnimation.NEON->"A steady core with a small electrical shimmer.";GlowAnimation.WAVE->"A quiet luminance wave travels the segment."}
