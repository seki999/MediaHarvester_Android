package com.mediaharvester.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mediaharvester.domain.Urls
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class Preferences(val language: String = "system", val theme: String = "system", val concurrency: Int = 2, val wifi: Boolean = false, val rate: Long = 0, val directory: String = "", val gallery: Boolean = false, val quality: Int = 0, val naming: String = "title")
class Settings(context: Context) {
 private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
 val flow = MutableStateFlow(Preferences(store.getString("language","system")!!, store.getString("theme","system")!!, store.getInt("concurrency",2), store.getBoolean("wifi",false), store.getLong("rate",0), store.getString("directory","")!!, store.getBoolean("gallery",false), store.getInt("quality",0), store.getString("naming","title")!!))
 fun save(value: Preferences) {
  val p = value.copy(concurrency=value.concurrency.coerceIn(1,4),rate=value.rate.coerceIn(0,100_000_000))
  store.edit().putString("language",p.language).putString("theme",p.theme).putInt("concurrency",p.concurrency).putBoolean("wifi",p.wifi).putLong("rate",p.rate).putString("directory",p.directory).putBoolean("gallery",p.gallery).putInt("quality",p.quality).putString("naming",p.naming).apply(); flow.value=p
 }
}
// Netscape cookies are encrypted at rest. Only an explicitly authorized exact host is exported temporarily.
class CookieVault(private val context: Context) {
 private val file = File(context.noBackupFilesDir,"cookies.enc")
 private val consent = context.getSharedPreferences("cookie-consent",Context.MODE_PRIVATE)
 private fun key(): SecretKey {
  val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  return (ks.getKey("mh-cookies",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder("mh-cookies",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 fun import(uri: Uri, authorizedUrl: String) {
  val authorized=URI(Urls.validate(authorizedUrl));require(authorized.scheme=="https") { "Cookie authorization requires HTTPS" };val host=authorized.host.lowercase()
  val content = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(1024*1024+1) } ?: error("Cannot read cookies")
  require(content.size<=1024*1024) { "Cookie file exceeds 1 MiB" }
  val text = content.toString(Charsets.UTF_8)
  require(text.lineSequence().any { it.startsWith("# Netscape HTTP Cookie File") || it.startsWith("# HTTP Cookie File") }) { "Expected Netscape cookies.txt" }
  val lines = text.lineSequence().filter { !it.startsWith('#') || it.startsWith("#HttpOnly_") }.filter { it.isNotBlank() }.filter { line ->
   val parts=line.removePrefix("#HttpOnly_").split('\t')
   parts.size==7 && (host==parts[0].trimStart('.') || parts[1]=="TRUE" && host.endsWith("."+parts[0].trimStart('.')))
  }.toList()
  require(lines.isNotEmpty()) { "No cookies match authorized website" }
  store(lines,host)
 }
 // Same as desktop save_pasted_cookie(): accept a browser "Cookie:" request header for the authorized HTTPS site.
 fun importHeader(header: String, authorizedUrl: String) {
  val authorized=URI(Urls.validate(authorizedUrl));require(authorized.scheme=="https") { "Cookie authorization requires HTTPS" };val host=authorized.host.lowercase()
  var text=header.trim(); if(text.lowercase().startsWith("cookie:")) text=text.substring(7).trim()
  require(text.isNotEmpty() && text.length<=131072 && text.none { it.code<32 || it.code==127 }) { "Paste the complete single-line Cookie header" }
  val pairs=text.split(';').filter { it.isNotBlank() }.map { part ->
   val name=part.trim().substringBefore('=',""); val value=part.trim().substringAfter('=',"")
   require(part.contains('=') && name.matches(Regex("[!#$%&'*+\\-.^_`|~0-9a-zA-Z]+")) && !name.startsWith("$")) { "Invalid Cookie format; copy the whole name=value; name=value line" }
   name to value.trim()
  }
  require(pairs.isNotEmpty() && pairs.size<=200) { "Cookie is empty or has too many entries" }
  // Instagram's authorized media API also uses i.instagram.com.
  val domain=if(host=="instagram.com" || host.endsWith(".instagram.com")) ".instagram.com" else host
  val sub=if(domain.startsWith(".")) "TRUE" else "FALSE"
  store(pairs.map { (name,value) -> "$domain\t$sub\t/\tTRUE\t0\t$name\t$value" },host)
 }
 private fun store(lines: List<String>, host: String) {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
  val data=("# Netscape HTTP Cookie File\n"+lines.joinToString("\n")+"\n").toByteArray()
  val temporary=File(file.path+".tmp"); temporary.writeBytes(cipher.iv+cipher.doFinal(data)); check(temporary.renameTo(file))
  consent.edit().putString("host",host).commit()
 }
 fun export(url: String): File? {
  if(!file.exists() || URI(url).scheme!="https" || URI(url).host.lowercase()!=consent.getString("host",null)) return null
  val b=file.readBytes(); val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,b.copyOfRange(0,12)))
  return File.createTempFile("cookies-",".txt",context.cacheDir).apply { writeBytes(cipher.doFinal(b.copyOfRange(12,b.size))) }
 }
 fun delete() { file.delete(); consent.edit().clear().commit(); clearTemporary() }
 fun clearTemporary() { context.cacheDir.listFiles()?.filter { it.name.startsWith("cookies-") }?.forEach { it.delete() } }
 fun host(): String = consent.getString("host", "").orEmpty()
}
