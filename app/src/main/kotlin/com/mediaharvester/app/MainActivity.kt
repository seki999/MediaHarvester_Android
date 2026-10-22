package com.mediaharvester.app

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.res.Resources
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mediaharvester.ui.*
import java.util.Locale

class MainActivity: ComponentActivity() {
 private var shared by mutableStateOf("")
 private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
 override fun attachBaseContext(newBase: Context) {
  val language=newBase.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("language","system") ?: "system"
  val config=Configuration(newBase.resources.configuration)
  if(language!="system") config.setLocale(Locale.forLanguageTag(language)) else config.setLocales(Resources.getSystem().configuration.locales)
  super.attachBaseContext(newBase.createConfigurationContext(config))
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val coordinator=(application as HarvesterApplication).downloads
  receive(intent)
  if(Build.VERSION.SDK_INT>=33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
  setContent {
   val model: HarvesterViewModel=viewModel(factory=object: ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T=HarvesterViewModel(coordinator) as T
   })
   HarvesterApp(model,shared,{ shared="" },{ startForegroundService(Intent(this,DownloadService::class.java)) },{ recreate() })
  }
 }
 override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); receive(intent) }
 private fun receive(intent: Intent) {
  if(intent.action in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) shared=intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.clipData?.let { clip -> (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(this).toString() } }.orEmpty()
 }
}
