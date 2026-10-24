package com.mediaharvester.downloader

import com.mediaharvester.domain.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.net.InetAddress

class HttpEngineTest {
 @get:Rule val folder=TemporaryFolder()
 private lateinit var server: MockWebServer
 private val payload=ByteArray(256*1024) { (it%251).toByte() }
 @Before fun start() { server=MockWebServer();server.start() }
 @After fun stop() { server.shutdown() }
 private fun response(data: ByteArray=payload)=MockResponse().setBody(Buffer().write(data)).setHeader("Content-Type","application/octet-stream").setHeader("ETag","\"v1\"")
 private fun engine()=HttpEngine(OkHttpClient.Builder().readTimeout(2,TimeUnit.SECONDS).build())
 private fun checkpoint(part: java.io.File, etag: String = "\"v1\"", total: Long = payload.size.toLong()): TransferMeta {
  val meta=TransferMeta(etag,total)
  val hash=java.security.MessageDigest.getInstance("SHA-256").apply { update(part.readBytes()) }
  java.io.RandomAccessFile(part,"rw").use { it.fd.sync() }
  ResumeCheckpoint.save(part,url,meta,part.length(),hash)
  return meta
 }
 private val url get()=server.url("/file").toString()
 @Test fun downloadsWholeHttpFile()= runBlocking {
  server.enqueue(response());val part=folder.newFile();var progress=0L
  engine().download(url,part,TransferMeta(),0,{}, {progress=it.bytes})
  assertArrayEquals(payload,part.readBytes());assertEquals(payload.size.toLong(),progress)
 }
 @Test fun crashAfterNewValidatorCannotRetainOldBytes()=runBlocking {
  val part=folder.newFile().apply{writeText("STALE BYTES")}
  var stored=TransferMeta("\"old\"",payload.size.toLong())
  server.enqueue(response().setHeader("ETag","\"new\""))
  assertTrue(runCatching {engine().download(url,part,stored,0,{stored=it;throw java.io.IOException("simulated process death after metadata commit")},{})}.isFailure)
  assertEquals(0L,part.length());assertEquals("\"new\"",stored.etag)
  server.enqueue(response().setHeader("ETag","\"new\""))
  engine().download(url,part,stored,0,{},{});assertArrayEquals(payload,part.readBytes())
 }
 @Test fun validatedRangeAppendsExactly()=runBlocking {
  val offset=8192;val part=folder.newFile().apply {writeBytes(payload.copyOf(offset))}
  server.enqueue(response(payload.copyOfRange(offset,payload.size)).setResponseCode(206).setHeader("Content-Range","bytes $offset-${payload.size-1}/${payload.size}"))
  engine().download(url,part,checkpoint(part),0,{}, {})
  assertArrayEquals(payload,part.readBytes());val request=server.takeRequest();assertEquals("bytes=$offset-",request.getHeader("Range"));assertEquals("\"v1\"",request.getHeader("If-Range"))
 }
 @Test fun noStrongValidatorRestarts()=runBlocking {
  for(etag in listOf("","W/\"weak\"")) {
   server.enqueue(response());val part=folder.newFile().apply {writeText("WRONG")}
   engine().download(url,part,TransferMeta(etag,payload.size.toLong()),0,{}, {})
   assertNull(server.takeRequest().getHeader("Range"));assertArrayEquals(payload,part.readBytes())
  }
 }
 @Test fun ignoredRangeReplacesRatherThanAppends()=runBlocking {
  server.enqueue(response());val part=folder.newFile().apply {writeText("partial")}
  engine().download(url,part,checkpoint(part),0,{},{});assertArrayEquals(payload,part.readBytes())
 }
 @Test fun malformedOrChangedRangesRestartSafely()=runBlocking {
  val ranges=listOf("bytes 0-5/${payload.size}","bytes 100-99/${payload.size}","bytes 100-999999/${payload.size}","bytes 100-199/*","bytes 100-199/99999", "garbage")
  for(range in ranges) {
   server.enqueue(response(ByteArray(100)).setResponseCode(206).setHeader("Content-Range",range));server.enqueue(response())
   val part=folder.newFile().apply {writeBytes(payload.copyOf(100))}
   engine().download(url,part,checkpoint(part),0,{},{});assertArrayEquals(payload,part.readBytes())
  }
 }
 @Test fun changedEtagDoesNotReusePartialData()=runBlocking {
  server.enqueue(response(payload.copyOfRange(100,payload.size)).setResponseCode(206).setHeader("Content-Range","bytes 100-${payload.size-1}/${payload.size}").setHeader("ETag","\"changed\""));server.enqueue(response())
  val part=folder.newFile().apply{writeBytes(ByteArray(100){42})};engine().download(url,part,checkpoint(part),0,{},{});assertArrayEquals(payload,part.readBytes())
 }
 @Test fun unsolicitedPartialResponseFails()=runBlocking {
  repeat(2){server.enqueue(response().setResponseCode(206).setHeader("Content-Range","bytes 0-${payload.size-1}/${payload.size}"))}
  assertTrue(runCatching {engine().download(url,folder.newFile(),TransferMeta(),0,{}, {})}.exceptionOrNull() is UnsafeRange)
 }
 @Test fun range416AlwaysRestarts()=runBlocking {
  server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range","bytes */${payload.size}"));server.enqueue(response())
  val part=folder.newFile().apply{writeBytes(payload.copyOf(100))};engine().download(url,part,checkpoint(part),0,{},{});assertArrayEquals(payload,part.readBytes())
 }
 @Test fun disconnectPreservesBytesThenRecovers()=runBlocking {
  server.enqueue(response().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
  val part=folder.newFile();var meta=TransferMeta()
  assertTrue(runCatching {engine().download(url,part,meta,0,{meta=it},{})}.isFailure)
  assertTrue(part.length() in 1 until payload.size.toLong());val offset=part.length().toInt()
  server.enqueue(response(payload.copyOfRange(offset,payload.size)).setResponseCode(206).setHeader("Content-Range","bytes $offset-${payload.size-1}/${payload.size}"))
  engine().download(url,part,meta,0,{},{});assertArrayEquals(payload,part.readBytes())
 }
 @Test fun cancellationStopsSocketAndPreservesPart()=runBlocking {
  server.enqueue(response().throttleBody(8192,100,TimeUnit.MILLISECONDS))
  val part=folder.newFile();val ready=CompletableDeferred<Unit>()
  val job=launch(Dispatchers.IO){engine().download(url,part,TransferMeta(),0,{}, {if(it.bytes>0) ready.complete(Unit)})}
  withTimeout(5000){ready.await()};job.cancelAndJoin();assertTrue(job.isCancelled);assertTrue(part.length()<payload.size)
 }
 @Test fun blocksHtmlAndEmptyPayloads()=runBlocking {
  for(response in listOf(MockResponse().setBody("<html>login</html>").setHeader("Content-Type","text/html"),MockResponse().setBody(""))) {
   server.enqueue(response);assertTrue(runCatching {engine().download(url,folder.newFile(),TransferMeta(),0,{}, {})}.isFailure)
  }
 }
 @Test fun doesNotAcceptRangeWithWrongContentLength()=runBlocking {
  server.enqueue(response(ByteArray(30)).setResponseCode(206).setHeader("Content-Range","bytes 100-199/${payload.size}"));server.enqueue(response())
  val part=folder.newFile().apply{writeBytes(payload.copyOf(100))};engine().download(url,part,checkpoint(part),0,{},{});assertArrayEquals(payload,part.readBytes())
 }
 @Test fun productionClientReportsBlockedAddressWithoutDispatcherCrash()=runBlocking {
  val error=runCatching {PublicNetwork.client().newCall(okhttp3.Request.Builder().url("http://127.0.0.1:12345/").build()).awaitResponse()}.exceptionOrNull()
  assertTrue(error is java.io.IOException)
 }
 @Test fun deniesLocalAndPrivateDestinations() {
  listOf("127.0.0.1","10.0.0.1","192.168.0.1","169.254.169.254","100.64.0.1","::1","fc00::1").forEach {assertTrue(it,PublicNetwork.privateAddress(InetAddress.getByName(it)))}
  assertFalse(PublicNetwork.privateAddress(InetAddress.getByName("8.8.8.8")))
 }
 @Test fun corruptCheckpointOrBytesNeverAuthorizeAppend()=runBlocking {
  repeat(2) { corruptJournal ->
   val part=folder.newFile().apply { writeBytes(payload.copyOf(8192)) }; val meta=checkpoint(part)
   if(corruptJournal==1) java.io.File(part.path+".resume").writeText("bytes=garbage")
   else java.io.RandomAccessFile(part,"rw").use { it.writeByte(255) }
   server.enqueue(response())
   engine().download(url,part,meta,0,{}, {})
   assertNull(server.takeRequest().getHeader("Range"));assertArrayEquals(payload,part.readBytes())
   assertTrue(part.parentFile.listFiles()!!.any { it.name.startsWith(part.name+".retained-") })
  }
 }
 @Test fun unsyncedSuffixIsTruncatedToVerifiedCheckpoint()=runBlocking {
  val part=folder.newFile().apply { writeBytes(payload.copyOf(8192)) }; val meta=checkpoint(part)
  part.appendBytes(ByteArray(1024){42})
  server.enqueue(response(payload.copyOfRange(8192,payload.size)).setResponseCode(206).setHeader("Content-Range","bytes 8192-${payload.size-1}/${payload.size}"))
  engine().download(url,part,meta,0,{}, {})
  assertEquals("bytes=8192-",server.takeRequest().getHeader("Range"));assertArrayEquals(payload,part.readBytes())
 }
 @Test fun missingJournalPreservesLegacyBytesAndStartsFresh()=runBlocking {
  val part=folder.newFile().apply { writeText("legacy-part") }
  server.enqueue(response());engine().download(url,part,TransferMeta("\"v1\"",payload.size.toLong()),0,{}, {})
  assertNull(server.takeRequest().getHeader("Range"));assertArrayEquals(payload,part.readBytes())
  assertTrue(part.parentFile.listFiles()!!.any { it.name.startsWith(part.name+".retained-") })
 }
 @Test fun checkpointCannotBeReusedForAnotherUrl()=runBlocking {
  val part=folder.newFile().apply { writeBytes(payload.copyOf(8192)) }; val meta=checkpoint(part)
  server.enqueue(response());engine().download(server.url("/other").toString(),part,meta,0,{}, {})
  assertNull(server.takeRequest().getHeader("Range"));assertArrayEquals(payload,part.readBytes())
 }
 @Test fun verifiedCompleteFileSurvivesRestartWithoutRedownload()=runBlocking {
  val part=folder.newFile();server.enqueue(response())
  engine().download(url,part,TransferMeta(),0,{}, {})
  engine().download(url,part,TransferMeta(),0,{}, {})
  assertEquals(1,server.requestCount);assertArrayEquals(payload,part.readBytes())
 }
 @Test fun pauseThenNewEngineResumesUsingDurableJournal()=runBlocking {
  server.enqueue(response().throttleBody(8192,100,TimeUnit.MILLISECONDS))
  val part=folder.newFile();val ready=CompletableDeferred<Unit>()
  val job=launch(Dispatchers.IO) {engine().download(url,part,TransferMeta(),0,{}, {if(it.bytes>0) ready.complete(Unit)})}
  withTimeout(5000){ready.await()};job.cancelAndJoin()
  val offset=ResumeCheckpoint.load(part,url)!!.bytes.toInt()
  assertTrue(offset in 1 until payload.size);server.takeRequest()
  server.enqueue(response(payload.copyOfRange(offset,payload.size)).setResponseCode(206).setHeader("Content-Range","bytes $offset-${payload.size-1}/${payload.size}"))
  engine().download(url,part,TransferMeta(),0,{}, {})
  assertEquals("bytes=$offset-",server.takeRequest().getHeader("Range"));assertArrayEquals(payload,part.readBytes())
 }
 @Test fun rejectsMalformedStrongEtags() {
  listOf("W/\"weak\"","\"embedded\"quote\"","\"has space\"","bare").forEach { assertFalse(ResumeCheckpoint.strongEtag(it)) }
  assertTrue(ResumeCheckpoint.strongEtag("\"valid-v1\""))
 }

 @Test fun journalWriteFailureRetainsEarlierVerifiedPrefix()=runBlocking {
  val part=folder.newFile().apply {writeBytes(payload.copyOf(8192))};val meta=checkpoint(part)
  java.io.File(part.path+".resume.tmp").mkdir()
  server.enqueue(response(payload.copyOfRange(8192,payload.size)).setResponseCode(206).setHeader("Content-Range","bytes 8192-${payload.size-1}/${payload.size}"))
  val error=runCatching {engine().download(url,part,meta,0,{}, {})}.exceptionOrNull()
  assertTrue(error is java.io.IOException)
  assertEquals(8192L,ResumeCheckpoint.load(part,url)!!.bytes)
  assertArrayEquals(payload.copyOf(8192),part.readBytes().copyOf(8192))
 }
 @Test fun cancellationWhileVerifyingCheckpointDoesNotChangePart() {
  val part=folder.newFile().apply {writeBytes(payload.copyOf(8192))};checkpoint(part)
  try { ResumeCheckpoint.load(part,url) { throw CancellationException("paused during checksum") };fail("expected cancellation") }
  catch(_: CancellationException) {assertEquals(8192L,part.length());assertNotNull(ResumeCheckpoint.load(part,url))}
 }
 @Test fun duplicateEtagsCannotAuthorizeAppend()=runBlocking {
  val part=folder.newFile().apply{writeBytes(payload.copyOf(100))};val meta=checkpoint(part)
  server.enqueue(response(payload.copyOfRange(100,payload.size)).setResponseCode(206).setHeader("Content-Range","bytes 100-${payload.size-1}/${payload.size}").addHeader("ETag","\"v1\""))
  server.enqueue(response());engine().download(url,part,meta,0,{}, {})
  server.takeRequest();assertNull(server.takeRequest().getHeader("Range"));assertArrayEquals(payload,part.readBytes())
 }

}
