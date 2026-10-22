package com.mediaharvester.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.mediaharvester.domain.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*

class Publisher(private val context: Context) {
 private val resolver get() = context.contentResolver
 fun allocate(name: String, mime: String, tree: String, gallery: Boolean): Uri {
  val safe = safeName(name)
  if (tree.isNotEmpty()) {
   val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: error("Folder permission unavailable")
   val stem = safe.substringBeforeLast('.', safe); val ext = safe.substringAfterLast('.', "")
   val available = (0..9999).asSequence().map { if (it == 0) safe else "$stem ($it)${if(ext.isEmpty()) "" else ".$ext"}" }.firstOrNull { root.findFile(it) == null } ?: error("Too many names")
   return root.createFile(mime, available)?.uri ?: error("Cannot create output")
  }
  val images = gallery && mime.startsWith("image/")
  val collection = if (images) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
  val folder = if (images) "Pictures/MediaHarvester/" else "Download/MediaHarvester/"
  val stem = safe.substringBeforeLast('.', safe); val ext = safe.substringAfterLast('.', "")
  val candidate = (0..9999).asSequence().map { if(it==0) safe else "$stem ($it)${if(ext.isEmpty()) "" else ".$ext"}" }.first { n ->
   resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(n, folder), null)?.use { !it.moveToFirst() } ?: true
  }
  return resolver.insert(collection, ContentValues().apply {
   put(MediaStore.MediaColumns.DISPLAY_NAME, candidate); put(MediaStore.MediaColumns.MIME_TYPE, mime)
   put(MediaStore.MediaColumns.RELATIVE_PATH, folder); put(MediaStore.MediaColumns.IS_PENDING, 1)
  }) ?: error("Cannot allocate MediaStore item")
 }
 suspend fun write(source: File, uri: Uri, digest: String) = withContext(Dispatchers.IO) {
  resolver.openOutputStream(uri, "wt")?.use { output ->
   source.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { ensureActive(); val n = input.read(buffer); if(n<0) break; output.write(buffer,0,n) }; output.flush() }
  } ?: error("Cannot write output")
  require(verify(uri,digest)) { "Saved output checksum mismatch" }
  if (uri.authority == MediaStore.AUTHORITY) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
 }
 suspend fun verify(uri: Uri, digest: String): Boolean = withContext(Dispatchers.IO) {
  runCatching {
   resolver.openInputStream(uri)?.use { input ->
    val hash = MessageDigest.getInstance("SHA-256"); val b = ByteArray(65536)
    while (true) { ensureActive(); val n = input.read(b); if(n<0) break; hash.update(b,0,n) }
    hash.digest().joinToString("") { "%02x".format(it) } == digest
   } ?: false
  }.getOrElse { if (it is CancellationException) throw it; false }
 }
 fun finish(uri: Uri) { if(uri.authority==MediaStore.AUTHORITY) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null) }
 fun remove(uri: String) { if(uri.isNotEmpty()) runCatching { resolver.delete(Uri.parse(uri), null, null) } }
 fun readable(uri: String) = runCatching { resolver.openFileDescriptor(Uri.parse(uri),"r")?.use { true } ?: false }.getOrDefault(false)
}
