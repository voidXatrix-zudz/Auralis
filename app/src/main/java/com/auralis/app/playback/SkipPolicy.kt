package com.auralis.app.playback

/**
 * Where a manual Previous goes when the song has played for less than the restart threshold.
 *
 * [previous] is Media3's previous index (respecting shuffle order and repeat mode, or -1 when there
 * is none), [current] the playing index and [lastContext] the last item of the list the user
 * started from (-1 when the queue has no context block).
 *
 * The only thing that is rewritten is a repeat-all WRAP: standing inside the context block and
 * stepping back from its first song lands at the end of the queue, which is the autoplay tail, so
 * it is clamped back to the last context song. When the current song is already inside the tail the
 * previous index is a real step back through songs the user just heard and is used as it is. The
 * old rule clamped every previous index beyond the context, so Previous from the second autoplay
 * song jumped over the song in between straight to the song the user started from.
 *
 * Returns null when there is nothing before the current song (the caller restarts it).
 *
 * Pure and Android-free so it is unit-tested on the JVM.
 */
fun previousSkipTarget(previous: Int, current: Int, lastContext: Int): Int? = when {
    previous < 0 -> null
    lastContext in 0 until previous && current <= lastContext -> lastContext
    else -> previous
}
