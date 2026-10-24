package com.mediaharvester.extractor

import org.junit.Assert.*
import org.junit.Test

class YoutubePolicyTest {
 @Test fun onlyYoutubeHostsGetYoutubeOptions() {
  listOf("https://m.youtube.com/watch?v=x","https://youtu.be/x","https://www.youtube.com/watch?v=x").forEach { assertTrue(YoutubePolicy.isYoutube(it)) }
  listOf("https://youtube.com.evil.example/x","https://example.org/youtube.com","https://notyoutube.com").forEach { assertFalse(YoutubePolicy.isYoutube(it)) }
 }
 @Test fun forbiddenChangesClientInsteadOfRepeatingIdenticalRequests() {
  assertNull(YoutubePolicy.client(0,false))
  assertNotEquals(YoutubePolicy.client(1,false),YoutubePolicy.client(2,false))
  assertFalse(YoutubePolicy.client(1,true)!!.contains("visionos"))
  assertTrue(YoutubePolicy.retry("HTTP Error 403: Forbidden",0))
  assertTrue(YoutubePolicy.retry("HTTP Error 403: Forbidden",1))
  assertFalse(YoutubePolicy.retry("HTTP Error 403: Forbidden",2))
 }
 @Test fun rateLimitsAndUnavailableFormatsDoNotTriggerRetryStorms() {
  assertFalse(YoutubePolicy.retry("HTTP Error 429: Too Many Requests",0))
  assertFalse(YoutubePolicy.retry("Requested format is not available",0))
  assertTrue(YoutubePolicy.retry("connection timed out",0))
 }
}
