package com.mediaharvester.app
import android.app.Application
import com.mediaharvester.data.DownloadCoordinator
class HarvesterApplication: Application() {
 private var instance: DownloadCoordinator?=null
 var downloads: DownloadCoordinator
  get()=instance ?: DownloadCoordinator(this).also {instance=it}
  set(value) {instance=value}
}
