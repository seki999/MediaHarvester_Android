package com.mediaharvester.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource as s
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mediaharvester.domain.*
import com.mediaharvester.database.TaskEntity
import com.mediaharvester.data.Preferences
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarvesterApp(model: HarvesterViewModel,shared: String,consumeShare: ()->Unit,startService: ()->Unit,restart: ()->Unit) {
 val prefs by model.preferences.collectAsStateWithLifecycle()
 val state by model.screen.collectAsStateWithLifecycle()
 val tasks by model.tasks.collectAsStateWithLifecycle()
 val recent by model.recent.collectAsStateWithLifecycle()
 var page by rememberSaveable { mutableIntStateOf(0) }
 var input by rememberSaveable { mutableStateOf("") }
 LaunchedEffect(shared) { if(shared.isNotEmpty()) { input=shared;page=0;consumeShare() } }
 val dark=when(prefs.theme) { "dark"->true;"light"->false;else->isSystemInDarkTheme() }
 MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {
  Scaffold(topBar={ TopAppBar(title={ Text("MediaHarvester",style=MaterialTheme.typography.titleLarge) }) },bottomBar={
   NavigationBar {
    listOf(R.string.home,R.string.active,R.string.completed,R.string.settings).forEachIndexed { i,label ->
     NavigationBarItem(selected=page==i,onClick={page=i},icon={ Text(listOf("⌂","↓","✓","⚙")[i]) },label={Text(s(label))})
    }
   }
  }) { padding ->
   BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
    val wide=maxWidth>840.dp
    val width=if(wide) 1100.dp else maxWidth
    Column(Modifier.widthIn(max=width).fillMaxWidth().align(Alignment.TopCenter).padding(horizontal=16.dp)) {
     if(state.error.isNotEmpty()) Text(state.error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(8.dp))
     when(page) {
      0 -> {
       val clipboard=LocalClipboardManager.current
       OutlinedTextField(input,{input=it},label={Text(s(R.string.urls))},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=5)
       Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={model.parse(input)},enabled=!state.parsing) { Text(s(R.string.parse)) }
        TextButton(onClick={input=clipboard.getText()?.text.orEmpty()}) { Text(s(R.string.paste)) }
        if(state.parsing) TextButton(onClick=model::cancelParse) { Text(s(R.string.cancel)) }
       }
       if(state.parsing) LinearProgressIndicator(Modifier.fillMaxWidth())
       if(state.results.isEmpty()) {
        Text(s(R.string.recent),style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=20.dp,bottom=8.dp))
        LazyColumn { items(recent,key={it.url}) { item -> TextButton(onClick={input=item.url}) { Text(item.url,maxLines=2) } } }
       } else Results(state.results,prefs,{items->model.download(items,startService)})
      }
      1,2 -> LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
       val shown=tasks.filter { (it.state=="COMPLETED")== (page==2) }
       if(shown.isEmpty()) item { Text(s(R.string.empty)) }
       items(shown,key={it.id}) { task -> TaskCard(task,{action->model.action(task.id,action,startService)}) }
      }
      3 -> SettingsScreen(model,prefs,restart)
     }
    }
   }
  }
 }
}

@Composable
private fun Results(results: List<Media>,prefs: Preferences,download: (List<Triple<Media,Format?,String>>)->Unit) {
 var minimum by rememberSaveable { mutableStateOf("0") }
 var imageFormat by rememberSaveable { mutableStateOf("all") }
 var selected by remember(results) { mutableStateOf(emptySet<String>()) }
 val images=results.filter { it.mime.startsWith("image/") && it.width>=(minimum.toIntOrNull() ?: 0) && (imageFormat=="all" || it.url.substringBefore('?').endsWith(".$imageFormat",true)) }
 Column {
  if(results.any { it.mime.startsWith("image/") }) {
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
    OutlinedTextField(minimum,{minimum=it.filter(Char::isDigit)},label={Text(s(R.string.min_width))},modifier=Modifier.weight(1f),singleLine=true)
    Choice(imageFormat,listOf("all","jpg","png","webp","avif"),{imageFormat=it},Modifier.weight(1f))
   }
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
    TextButton(onClick={selected=images.map {it.url}.toSet()}) { Text(s(R.string.select_all)) }
    TextButton(onClick={selected=emptySet()}) { Text(s(R.string.deselect)) }
    Button(onClick={download(images.filter {it.url in selected}.map {Triple(it,null,"")})},enabled=images.any { it.url in selected }) { Text("${s(R.string.download)} (${images.count {it.url in selected}})") }
   }
  }
  LazyVerticalGrid(columns=GridCells.Adaptive(170.dp),verticalArrangement=Arrangement.spacedBy(10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=16.dp)) {
   items(results.filterNot {it.mime.startsWith("image/")},key={it.url},span={GridItemSpan(maxLineSpan)}) { media -> VideoCard(media,prefs) {format,conversion -> download(listOf(Triple(media,format,conversion))) } }
   items(images,key={it.url}) { media ->
    Card(onClick={selected=if(media.url in selected) selected-media.url else selected+media.url}) {
     Box {
      AsyncImage(media.thumbnail,contentDescription=media.title,modifier=Modifier.fillMaxWidth().height(140.dp))
      Checkbox(media.url in selected,{checked->selected=if(checked) selected+media.url else selected-media.url},modifier=Modifier.align(Alignment.TopEnd))
     }
     Text(media.title,maxLines=2,modifier=Modifier.padding(8.dp))
     Text(if(media.width>0) "${media.width} × ${media.height}" else s(R.string.unknown_size),style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(8.dp))
    }
   }
  }
 }
}
@Composable
private fun VideoCard(media: Media,prefs: Preferences,download: (Format?,String)->Unit) {
 val default=media.formats.firstOrNull { prefs.quality==0 || it.height in 1..prefs.quality } ?: media.formats.filter {it.height>0}.minByOrNull {it.height} ?: media.formats.firstOrNull()
 var chosen by remember(media) { mutableStateOf(default) }
 var conversion by rememberSaveable { mutableStateOf("") }
 Card {
  Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   if(media.thumbnail.isNotEmpty()) AsyncImage(media.thumbnail,media.title,Modifier.fillMaxWidth().height(160.dp))
   Text(media.title,style=MaterialTheme.typography.titleMedium)
   Text("${media.mime} · ${if(media.duration>0) "${media.duration.toLong()} s" else ""} ${if(media.size>0) size(media.size) else s(R.string.unknown_size)}")
   if(media.formats.isNotEmpty()) {
    Choice(chosen?.label.orEmpty(),media.formats.map {it.label},{label->chosen=media.formats.first {it.label==label}})
    if(chosen?.audioOnly==true) Choice(conversion.ifEmpty {"original"},listOf("original","mp3","m4a","opus"),{conversion=if(it=="original") "" else it})
   }
   if(!media.website && (media.mime.startsWith("audio/") || media.mime.startsWith("video/"))) Choice(conversion.ifEmpty {"original"},listOf("original","mp3","m4a","opus") + if(media.mime.startsWith("video/")) listOf("mp4") else emptyList(),{conversion=if(it=="original") "" else it})
   Button(onClick={download(chosen,if(chosen?.audioOnly==true || !media.website) conversion else "")}) { Text(s(R.string.download)) }
  }
 }
}
@Composable
private fun TaskCard(task: TaskEntity,action: (String)->Unit) {
 val context=LocalContext.current
 val terminal=task.state in listOf("COMPLETED","CANCELLED")
 Card {
  Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   Text(task.title,style=MaterialTheme.typography.titleMedium)
   Text(s(when(task.state) { "QUEUED"->R.string.queued;"DOWNLOADING"->R.string.downloading;"MERGING"->R.string.merging;"VERIFYING"->R.string.verifying;"SAVING"->R.string.saving;"PAUSED"->R.string.paused;"WAITING_NETWORK"->R.string.waiting_network;"COMPLETED"->R.string.completed;"FAILED"->R.string.failed;else->R.string.cancelled }))
   if(task.total>0) LinearProgressIndicator(progress={(task.bytes.toFloat()/task.total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
   val eta=if(task.speed>0 && task.total>task.bytes) " · ${(task.total-task.bytes)/task.speed} s" else ""
   Text("${size(task.bytes)} / ${if(task.total>0) size(task.total) else "?"} · ${size(task.speed)}/s$eta",style=MaterialTheme.typography.bodySmall)
   if(task.error.isNotEmpty()) Text(task.error,color=MaterialTheme.colorScheme.error)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    if(!terminal) {
     if(task.state in listOf("PAUSED","WAITING_NETWORK","FAILED")) TextButton(onClick={action(if(task.state=="FAILED") "retry" else "resume")}) {Text(s(if(task.state=="FAILED") R.string.retry else R.string.resume))}
     else TextButton(onClick={action("pause")}) {Text(s(R.string.pause))}
     TextButton(onClick={action("cancel")}) {Text(s(R.string.cancel))}
    }
    if(task.state=="COMPLETED") {
     TextButton(onClick={runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(task.outputUri),task.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }}) {Text(s(R.string.open))}
     TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(task.mime).putExtra(Intent.EXTRA_STREAM,Uri.parse(task.outputUri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),null))}) {Text(s(R.string.share))}
    }
   }
  }
 }
}
@Composable
private fun SettingsScreen(model: HarvesterViewModel,prefs: Preferences,restart: ()->Unit) {
 val context=LocalContext.current
 var authorized by rememberSaveable { mutableStateOf("") }
 var cookieHost by remember { mutableStateOf(model.coordinator.cookies.host()) }
 var cookieError by remember { mutableStateOf("") }
 val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
  if(uri!=null) runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); model.setting(prefs.copy(directory=uri.toString())) }.onFailure { cookieError=it.message.orEmpty() }
 }
 val cookies=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if(uri!=null) model.maintenance { model.coordinator.cookies.import(uri,authorized); cookieHost=model.coordinator.cookies.host() }
 }
 LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
  item { Text(s(R.string.appearance),style=MaterialTheme.typography.titleMedium) }
  item { Choice(prefs.language,listOf("system","zh-CN","ja","en"),{model.setting(prefs.copy(language=it));restart()}) }
  item { Choice(prefs.theme,listOf("system","light","dark"),{model.setting(prefs.copy(theme=it))}) }
  item { Text(s(R.string.concurrent)); Choice(prefs.concurrency.toString(),listOf("1","2","3","4"),{model.setting(prefs.copy(concurrency=it.toInt()))}) }
  item { Text(s(R.string.quality)); Choice(if(prefs.quality==0) "best" else prefs.quality.toString(),listOf("best","2160","1080","720","480"),{model.setting(prefs.copy(quality=it.toIntOrNull() ?: 0))}); Text(s(R.string.quality_hint),style=MaterialTheme.typography.bodySmall) }
  item { Row(verticalAlignment=Alignment.CenterVertically) { Switch(prefs.wifi,{model.setting(prefs.copy(wifi=it))});Text(s(R.string.wifi)) } }
  item { Text(s(R.string.rate)); Choice(prefs.rate.toString(),listOf("0","262144","1048576","5242880"),{model.setting(prefs.copy(rate=it.toLong()))}) }
  item { Text(s(R.string.naming)); Choice(prefs.naming,listOf("title","timestamp"),{model.setting(prefs.copy(naming=it))}) }
  item { Text(s(R.string.folder));Text(prefs.directory.ifEmpty {"Downloads/MediaHarvester"},style=MaterialTheme.typography.bodySmall);Row { TextButton(onClick={folder.launch(null)}) {Text(s(R.string.choose))};TextButton(onClick={model.setting(prefs.copy(directory=""))}) {Text(s(R.string.reset))} } }
  item { Row(verticalAlignment=Alignment.CenterVertically) { Switch(prefs.gallery,{model.setting(prefs.copy(gallery=it))});Text(s(R.string.gallery)) } }
  item { HorizontalDivider();Text(s(R.string.cookies),style=MaterialTheme.typography.titleMedium);Text(s(R.string.cookie_consent),style=MaterialTheme.typography.bodySmall) }
  item { OutlinedTextField(authorized,{authorized=it},label={Text(s(R.string.authorized_site))},modifier=Modifier.fillMaxWidth(),singleLine=true) }
  item { Text(cookieHost);if(cookieError.isNotEmpty()) Text(cookieError);Row { TextButton(onClick={cookies.launch(arrayOf("text/*","application/octet-stream"))},enabled=authorized.startsWith("https://")) {Text(s(R.string.import_cookies))};TextButton(onClick={model.coordinator.cookies.delete();cookieHost=""}) {Text(s(R.string.delete_cookies))} } }
  item { HorizontalDivider();Text(s(R.string.data),style=MaterialTheme.typography.titleMedium) }
  item { TextButton(onClick={model.maintenance {model.coordinator.clearHistory()}}) {Text(s(R.string.clear_history))} }
  item { TextButton(onClick={model.maintenance {model.coordinator.clearTemporary()}}) {Text(s(R.string.clear_temp))} }
  item { Text(s(R.string.background_hint),style=MaterialTheme.typography.bodySmall) }
 }
}
@Composable
private fun Choice(value: String,options: List<String>,onChange: (String)->Unit,modifier: Modifier=Modifier) {
 var expanded by remember { mutableStateOf(false) }
 Box(modifier) {
  OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) { Text(optionLabel(value)) }
  DropdownMenu(expanded,onDismissRequest={expanded=false}) {options.forEach { option->DropdownMenuItem(text={Text(optionLabel(option))},onClick={onChange(option);expanded=false})} }
 }
}
private fun size(bytes: Long): String = when { bytes>=1024*1024 -> String.format(Locale.ROOT,"%.1f MiB",bytes/1048576.0);bytes>=1024 -> "${bytes/1024} KiB";else->"$bytes B" }

@Composable
private fun optionLabel(value: String): String = when(value) {
 "system"->s(R.string.system_label);"light"->s(R.string.light_label);"dark"->s(R.string.dark_label)
 "best"->s(R.string.best_label);"original"->s(R.string.original_label);"title"->s(R.string.title_label)
 "timestamp"->s(R.string.timestamp_label);"all"->s(R.string.all_label);else->value
}
