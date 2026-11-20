package com.auralis.app.logging

/**
 * Collapses identical consecutive log lines so a loop cannot flood the rotating log.
 *
 * The completed run is returned when the next different line arrives (or [flush] is called) and a
 * run of three or more is rendered as `line (x3)`. Error lines are never buffered by [AppLog], so a
 * crash still reaches the file immediately.
 */
class ConsecutiveCollapser {
    data class Run(val key: String, val text: String, val count: Int)

    private var current: Run? = null

    /** Adds one line. Returns the previous run when this line ends it; the new line is pending. */
    fun add(key: String, text: String): Run? {
        val previous = current
        if (previous != null && previous.key == key) {
            current = previous.copy(count = previous.count + 1)
            return null
        }
        current = Run(key, text, 1)
        return previous
    }

    /** Ends the pending run, if any. */
    fun flush(): Run? = current.also { current = null }

    companion object {
        /** `text` with a `(xN)` suffix once the run repeats; multi-line runs keep their stack. */
        fun render(run: Run): String {
            if (run.count < 2) return run.text
            val suffix = " (x${run.count})"
            val newline = run.text.indexOf('\n')
            return if (newline >= 0) {
                run.text.substring(0, newline) + suffix + run.text.substring(newline)
            } else {
                run.text + suffix
            }
        }
    }
}