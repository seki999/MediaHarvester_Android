package com.mediaharvester.extractor

import com.mediaharvester.domain.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object HtmlImages {
 fun extract(html: String, base: String): List<Media> {
  val doc = Jsoup.parse(html, base)
  return doc.select("img, picture, meta[property=og:image], meta[name=twitter:image], [style*=background]")
   .mapNotNull { element ->
    if (element.tagName() == "img" && element.parent()?.tagName() == "picture") return@mapNotNull null
    val candidates = mutableListOf<Pair<String, Double>>()
    val nodes = if (element.tagName() == "picture") element.select("source,img").toList() else listOf(element)
    nodes.forEach { e ->
     listOf("srcset", "data-srcset").forEach { attribute ->
      e.attr(attribute).split(',').forEach { candidate ->
       val bits = candidate.trim().split(Regex("\\s+")); val score = bits.getOrNull(1)?.dropLast(1)?.toDoubleOrNull() ?: 1.0
       if (bits[0].isNotBlank()) candidates += absolute(e, bits[0]) to score
      }
     }
     listOf("data-original", "data-lazy-src", "data-src", "src", "content").forEachIndexed { index, attribute ->
      if (e.hasAttr(attribute)) candidates += absolute(e, e.attr(attribute)) to (0.9 - index * 0.1)
     }
     Regex("url\\(['\"]?([^)'\"]+)").find(e.attr("style"))?.groupValues?.get(1)?.let { candidates += absolute(e,it) to 0.5 }
    }
    val best = candidates.filter { it.first.startsWith("https://") || it.first.startsWith("http://") }.maxByOrNull { it.second } ?: return@mapNotNull null
    val image = nodes.lastOrNull { it.tagName() == "img" } ?: element
    val width = image.attr("width").toIntOrNull() ?: 0
    val height = image.attr("height").toIntOrNull() ?: 0
    val title = best.first.substringBefore('?').substringAfterLast('/').ifBlank { "image" }
    Media(best.first, title, "image/" + title.substringAfterLast('.', "jpeg"), best.first, width, height)
   }.distinctBy { it.url }.take(1000)
 }
 private fun absolute(element: Element, value: String): String = runCatching { java.net.URI(element.baseUri()).resolve(value).toString() }.getOrDefault("")
}
