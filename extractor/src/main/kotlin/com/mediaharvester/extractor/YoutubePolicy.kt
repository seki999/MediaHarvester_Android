package com.mediaharvester.extractor

import java.net.URI

internal object YoutubePolicy {
 fun isYoutube(url: String): Boolean {
  val host=runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
  return host=="youtu.be" || host=="youtube.com" || host.endsWith(".youtube.com")
 }
 // Start with upstream defaults; on 403 refresh using clients that expose HLS.
 // Keep cookies only on clients which support them; never bypass PO-token checks.
 fun client(attempt: Int, authenticated: Boolean): String? = when {
  attempt==0 -> null
  authenticated -> "tv,tv_downgraded,web_safari"
  attempt==1 -> "visionos,web_safari"
  else -> "tv,web_safari"
 }
 fun retry(message: String, attempt: Int): Boolean = attempt<2 && !message.contains("429") && listOf("403","timeout","timed out","fragment","connection").any { message.contains(it,true) }
}
