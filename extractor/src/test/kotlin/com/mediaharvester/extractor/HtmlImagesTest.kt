package com.mediaharvester.extractor
import org.junit.Test
import org.junit.Assert.*
class HtmlImagesTest {
 @Test fun choosesHighestSrcsetAndLazyAndPicture() {
  val html="""<img src="/small.jpg" data-original="/original.jpg"><img srcset="/s.webp 320w, /l.webp 1920w"><picture><source srcset="/p1.avif 1x, /p2.avif 2x"><img src="/fallback.jpg"></picture><img src="/original.jpg"><meta property="og:image" content="/og.jpg"><div style="background-image:url('/bg.jpg')"></div>"""
  assertEquals(listOf("https://example.org/original.jpg","https://example.org/l.webp","https://example.org/p2.avif","https://example.org/og.jpg","https://example.org/bg.jpg"),HtmlImages.extract(html,"https://example.org/page").map {it.url})
 }
 @Test fun dropsNonHttpImagesAndDeduplicates() { assertEquals(1,HtmlImages.extract("""<img src="data:image/png;base64,x"><img src="/a.png"><img src="/a.png">""","https://example.org").size) }
 @Test fun knownResolutionIsPreserved() { val m=HtmlImages.extract("""<img src="/a.png" width="1200" height="800">""","https://example.org").single();assertEquals(1200,m.width);assertEquals(800,m.height) }
}
