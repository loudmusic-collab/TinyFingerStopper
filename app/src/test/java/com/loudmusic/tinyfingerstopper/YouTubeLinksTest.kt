package com.loudmusic.tinyfingerstopper

import com.loudmusic.tinyfingerstopper.player.YouTubeLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinksTest {

    private val id = "dQw4w9WgXcQ"

    @Test
    fun `bare id`() {
        assertEquals(id, YouTubeLinks.extractVideoId(id))
        assertEquals(id, YouTubeLinks.extractVideoId("  $id  "))
    }

    @Test
    fun `watch urls`() {
        assertEquals(id, YouTubeLinks.extractVideoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(
            id,
            YouTubeLinks.extractVideoId("https://m.youtube.com/watch?list=PL1&v=$id&t=42s"),
        )
    }

    @Test
    fun `short and embed urls`() {
        assertEquals(id, YouTubeLinks.extractVideoId("https://youtu.be/$id?si=abc"))
        assertEquals(id, YouTubeLinks.extractVideoId("https://www.youtube.com/embed/$id"))
        assertEquals(id, YouTubeLinks.extractVideoId("https://www.youtube.com/shorts/$id"))
    }

    @Test
    fun `shared text with surrounding words`() {
        assertEquals(
            id,
            YouTubeLinks.extractVideoId("Check this out https://youtu.be/$id - so good"),
        )
    }

    @Test
    fun `nothing usable`() {
        assertNull(YouTubeLinks.extractVideoId(null))
        assertNull(YouTubeLinks.extractVideoId(""))
        assertNull(YouTubeLinks.extractVideoId("   "))
        assertNull(YouTubeLinks.extractVideoId("https://example.com/watch?v=short"))
    }
}
