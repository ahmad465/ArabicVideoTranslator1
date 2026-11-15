package com.example.arabicvideotranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun watchUrl() = assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/watch?v=$id"))
    @Test fun watchUrlWithExtraParams() =
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/watch?feature=share&v=$id&t=42s"))
    @Test fun mobileAndMusicHosts() {
        assertEquals(id, YouTubeUrl.extractVideoId("https://m.youtube.com/watch?v=$id"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://music.youtube.com/watch?v=$id"))
    }
    @Test fun shortLink() {
        assertEquals(id, YouTubeUrl.extractVideoId("https://youtu.be/$id"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://youtu.be/$id?si=abc"))
    }
    @Test fun shortsEmbedLive() {
        assertEquals(id, YouTubeUrl.extractVideoId("https://youtube.com/shorts/$id"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/embed/$id"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/live/$id?feature=share"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube-nocookie.com/embed/$id"))
    }
    @Test fun schemeLessAndWhitespace() {
        assertEquals(id, YouTubeUrl.extractVideoId("  youtube.com/watch?v=$id  "))
        assertEquals(id, YouTubeUrl.extractVideoId("youtu.be/$id"))
    }
    @Test fun bareId() = assertEquals(id, YouTubeUrl.extractVideoId(id))
    @Test fun idWithDashUnderscore() = assertEquals("a-b_c-d_e-f", YouTubeUrl.extractVideoId("https://youtu.be/a-b_c-d_e-f"))

    @Test fun rejectsOtherDomains() {
        assertNull(YouTubeUrl.extractVideoId("https://vimeo.com/watch?v=$id"))
        assertNull(YouTubeUrl.extractVideoId("https://example.com/$id"))
    }
    @Test fun rejectsLookalikeHosts() {
        assertNull(YouTubeUrl.extractVideoId("https://youtube.com.evil.com/watch?v=$id"))
        assertNull(YouTubeUrl.extractVideoId("https://notyoutube.com/watch?v=$id"))
        assertNull(YouTubeUrl.extractVideoId("https://evil.com/?u=youtube.com/watch?v=$id"))
    }
    @Test fun rejectsMalformedIds() {
        assertNull(YouTubeUrl.extractVideoId("https://www.youtube.com/watch?v=short"))
        assertNull(YouTubeUrl.extractVideoId("https://www.youtube.com/watch?v=${id}X"))
        assertNull(YouTubeUrl.extractVideoId("https://youtu.be/dQw4w9WgXc!"))
        assertNull(YouTubeUrl.extractVideoId("https://www.youtube.com/watch?v=\"><script>"))
    }
    @Test fun rejectsMissingIdAndGarbage() {
        assertNull(YouTubeUrl.extractVideoId("https://www.youtube.com/"))
        assertNull(YouTubeUrl.extractVideoId("https://www.youtube.com/watch"))
        assertNull(YouTubeUrl.extractVideoId("https://www.youtube.com/playlist?list=PL1234567890A"))
        assertNull(YouTubeUrl.extractVideoId(""))
        assertNull(YouTubeUrl.extractVideoId("   "))
        assertNull(YouTubeUrl.extractVideoId(null))
        assertNull(YouTubeUrl.extractVideoId("not a url at all"))
        assertNull(YouTubeUrl.extractVideoId("javascript:alert(1)"))
        assertNull(YouTubeUrl.extractVideoId("ftp://youtu.be/$id"))
    }
}
