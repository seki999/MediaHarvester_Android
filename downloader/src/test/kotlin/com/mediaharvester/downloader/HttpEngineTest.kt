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
  engine().download(url,part,TransferMeta("\"v1\"",payload.size.toLong()),0,{}, {})
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
  engine().download(url,part,TransferMeta("\"v1\"",payload.size.toLong()),0,{},{});assertArrayEquals(payload,part.readBytes())
 }
 @Test fun malformedOrChangedRangesRestartSafely()=runBlocking {
  val ranges=listOf("bytes 0-5/${payload.size}","bytes 100-99/${payload.size}","bytes 100-999999/${payload.size}","bytes 100-199/*","bytes 100-199/99999", "garbage")
  for(range in ranges) {
   server.enqueue(response(ByteArray(100)).setResponseCode(206).setHeader("Content-Range",range));server.enqueue(response())
   val part=folder.newFile().apply {writeBytes(payload.copyOf(100))}
   engine().download(url,part,TransferMeta("\"v1\"",payload.size.toLong()),0,{},{});assertArrayEquals(payload,part.readBytes())
  }
 }
 @Test fun changedEtagDoesNotReusePartialData()=runBlocking {
  server.enqueue(response(payload.copyOfRange(100,payload.size)).setResponseCode(206).setHeader("Content-Range","bytes 100-${payload.size-1}/${payload.size}").setHeader("ETag","\"changed\""));server.enqueue(response())
  val part=folder.newFile().apply{writeBytes(ByteArray(100){42})};engine().download(url,part,TransferMeta("\"v1\"",payload.size.toLong()),0,{},{});assertArrayEquals(payload,part.readBytes())
 }
 @Test fun unsolicitedPartialResponseFails()=runBlocking {
  repeat(2){server.enqueue(response().setResponseCode(206).setHeader("Content-Range","bytes 0-${payload.size-1}/${payload.size}"))}
  assertTrue(runCatching {engine().download(url,folder.newFile(),TransferMeta(),0,{}, {})}.exceptionOrNull() is UnsafeRange)
 }
 @Test fun range416AlwaysRestarts()=runBlocking {
  server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range","bytes */${payload.size}"));server.enqueue(response())
  val part=folder.newFile().apply{writeBytes(payload.copyOf(100))};engine().download(url,part,TransferMeta("\"v1\"",payload.size.toLong()),0,{},{});assertArrayEquals(payload,part.readBytes())
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
  val job=launch(Dispatchers.IO){engine().download(url,part,TransferMeta(),0,{}, {ready.complete(Unit)})}
  withTimeout(5000){ready.await()};job.cancelAndJoin();assertTrue(job.isCancelled);assertTrue(part.length()<payload.size)
 }
 @Test fun blocksHtmlAndEmptyPayloads()=runBlocking {
  for(response in listOf(MockResponse().setBody("<html>login</html>").setHeader("Content-Type","text/html"),MockResponse().setBody(""))) {
   server.enqueue(response);assertTrue(runCatching {engine().download(url,folder.newFile(),TransferMeta(),0,{}, {})}.isFailure)
  }
 }
 @Test fun doesNotAcceptRangeWithWrongContentLength()=runBlocking {
  server.enqueue(response(ByteArray(30)).setResponseCode(206).setHeader("Content-Range","bytes 100-199/${payload.size}"));server.enqueue(response())
  val part=folder.newFile().apply{writeBytes(payload.copyOf(100))};engine().download(url,part,TransferMeta("\"v1\"",payload.size.toLong()),0,{},{});assertArrayEquals(payload,part.readBytes())
 }
 @Test fun productionClientReportsBlockedAddressWithoutDispatcherCrash()=runBlocking {
  val error=runCatching {PublicNetwork.client().newCall(okhttp3.Request.Builder().url("http://127.0.0.1:12345/").build()).awaitResponse()}.exceptionOrNull()
  assertTrue(error is java.io.IOException)
 }
 @Test fun deniesLocalAndPrivateDestinations() {
  listOf("127.0.0.1","10.0.0.1","192.168.0.1","169.254.169.254","100.64.0.1","::1","fc00::1").forEach {assertTrue(it,PublicNetwork.privateAddress(InetAddress.getByName(it)))}
  assertFalse(PublicNetwork.privateAddress(InetAddress.getByName("8.8.8.8")))
 }
}
