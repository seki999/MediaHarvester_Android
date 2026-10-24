package com.mediaharvester.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediaharvester.data.*
import com.mediaharvester.database.*
import com.mediaharvester.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ScreenState(val parsing: Boolean=false,val results: List<Media> = emptyList(),val error: String="")
class HarvesterViewModel(val coordinator: DownloadCoordinator): ViewModel() {
 val screen=MutableStateFlow(ScreenState())
 val tasks=coordinator.dao.observe().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val recent=coordinator.dao.recent().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val preferences=coordinator.settings.flow
 private var parsing: Job?=null
 init { viewModelScope.launch(Dispatchers.IO) { coordinator.initialize() } }
 fun parse(text: String) {
  parsing?.cancel()
  parsing=viewModelScope.launch {
   screen.value=ScreenState(parsing=true)
   val results=mutableListOf<Media>(); val errors=mutableListOf<String>()
   try {
    val urls=Urls.parse(text)
    for(url in urls) {
     try { results+=coordinator.extract(url); coordinator.dao.recent(RecentEntity(url)) }
     catch(e: TimeoutCancellationException) { errors+="${java.net.URI(url).host}: Timeout" }
     catch(e: CancellationException) { throw e }
     catch(e: Exception) { errors+="${java.net.URI(url).host}: ${if(e is TimeoutCancellationException) "Timeout" else e.javaClass.simpleName}" }
    }
    screen.value=ScreenState(results=results.distinctBy { it.url },error=errors.joinToString("\n"))
   } catch(e: CancellationException) { screen.value=screen.value.copy(parsing=false); throw e }
   catch(e: Exception) { screen.value=ScreenState(error=e.message.orEmpty()) }
  }
 }
 fun cancelParse() { parsing?.cancel(); screen.value=screen.value.copy(parsing=false) }
 fun download(items: List<Triple<Media,Format?,String>>,startService: () -> Unit) = viewModelScope.launch {
  try { items.forEach { coordinator.enqueue(it.first,it.second,it.third) }; startService() }
  catch(e: CancellationException) { throw e }
  catch(e: Exception) { coordinator.pauseAll("Cannot start foreground downloads; reopen the app and resume manually"); screen.value=screen.value.copy(error=e.message.orEmpty()) }
 }
 fun action(id: String,action: String,startService: () -> Unit) = viewModelScope.launch {
  try { coordinator.action(id,action); if(action in listOf("resume","retry")) startService() }
  catch(e: CancellationException) { throw e }
  catch(e: Exception) { coordinator.action(id,"pause","Cannot start foreground downloads; resume from the visible app"); screen.value=screen.value.copy(error=e.message.orEmpty()) }
 }
 fun setting(p: Preferences) = coordinator.settings.save(p)
 fun maintenance(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) { try { block() } catch(e: Exception) { screen.value=screen.value.copy(error=e.message.orEmpty()) } }
}
