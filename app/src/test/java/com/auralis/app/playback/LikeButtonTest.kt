package com.auralis.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification heart must reflect the exact song that is playing, across both the local
 * `favorites` table and the streamed `youtube_likes` table, and it must never carry the previous
 * song's state onto the next one.
 */
class LikeButtonTest {

    @Test
    fun `a local library song is liked only when it is in favorites`() {
        assertTrue(resolveLiked("local:1", favoriteIds = setOf("local:1"), youtubeLikedIds = emptySet()))
        assertFalse(resolveLiked("local:2", favoriteIds = setOf("local:1"), youtubeLikedIds = emptySet()))
    }

    @Test
    fun `a streamed song is liked when its video id is in the liked table`() {
        assertTrue(resolveLiked("yt:abc", favoriteIds = emptySet(), youtubeLikedIds = setOf("abc")))
        assertFalse(resolveLiked("yt:abc", favoriteIds = emptySet(), youtubeLikedIds = setOf("xyz")))
    }

    @Test
    fun `a streamed song never consults the local favorites table`() {
        // favorites is keyed by the full "yt:abc" id; the streamed lookup must use the bare video id.
        assertFalse(resolveLiked("yt:abc", favoriteIds = setOf("yt:abc"), youtubeLikedIds = emptySet()))
    }

    @Test
    fun `no current track is never liked`() {
        assertFalse(resolveLiked(null, favoriteIds = setOf("local:1"), youtubeLikedIds = setOf("abc")))
        assertFalse(resolveLiked("", favoriteIds = setOf("local:1"), youtubeLikedIds = setOf("abc")))
    }

    @Test
    fun `switching songs resolves the new song, not the old one`() {
        val favorites = setOf("local:old")
        assertTrue(resolveLiked("local:old", favorites, emptySet()))
        assertFalse(resolveLiked("local:new", favorites, emptySet()))
    }
}
