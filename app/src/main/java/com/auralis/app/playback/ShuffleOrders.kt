package com.auralis.app.playback

import java.util.Random

/**
 * The ONE shuffle permutation used by playback.
 *
 * Media3's own [androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder] is anchored on
 * nothing: it randomises the whole playlist once and keeps that permutation, so enabling shuffle
 * can place songs the user has already passed ("behind" the current song) and the queue then ends
 * early with Repeat Off. Every AURALIS queue therefore uses this anchored order instead:
 *
 *  - [currentIndex] is FIRST, so the song that is playing stays playing;
 *  - every other index appears exactly once after it, so nothing repeats until the whole queue has
 *    played (and Repeat All wraps back to the anchor);
 *  - the same [seed] always gives the same order, so the permutation can be rebuilt for the same
 *    queue without the song changing under the user.
 *
 * Pure and Android-free so it is unit-tested on the JVM.
 */
fun anchoredShuffle(count: Int, currentIndex: Int, seed: Long): IntArray {
    if (count <= 0) return IntArray(0)
    val anchor = currentIndex.coerceIn(0, count - 1)
    val rest = (0 until count).filterTo(ArrayList(count)) { it != anchor }
    val random = Random(seed)
    // Fisher-Yates over the non-anchor indices only; the anchor never moves.
    for (i in rest.lastIndex downTo 1) {
        val j = random.nextInt(i + 1)
        val swap = rest[i]
        rest[i] = rest[j]
        rest[j] = swap
    }
    return IntArray(count).also { order ->
        order[0] = anchor
        for (i in rest.indices) order[i + 1] = rest[i]
    }
}