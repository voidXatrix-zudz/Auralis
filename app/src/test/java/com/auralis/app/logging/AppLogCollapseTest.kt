package com.auralis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T4: identical consecutive lines collapse to one line with an `(xN)` suffix. */
class AppLogCollapseTest {
    @Test
    fun `three identical lines render once with a count`() {
        val collapser = ConsecutiveCollapser()

        assertNull(collapser.add("k", "line"))
        assertNull(collapser.add("k", "line"))
        assertNull(collapser.add("k", "line"))

        val completed = collapser.add("other", "next")
        assertEquals("line", ConsecutiveCollapser.render(completed!!).substringBefore(" (x"))
        assertEquals("line (x3)", ConsecutiveCollapser.render(completed))
        assertEquals("next", ConsecutiveCollapser.render(collapser.flush()!!))
    }

    @Test
    fun `a single line keeps no suffix and a different key starts a new run`() {
        val collapser = ConsecutiveCollapser()
        collapser.add("k", "one")

        val first = collapser.flush()
        assertEquals("one", ConsecutiveCollapser.render(first!!))
    }

    @Test
    fun `an interleaved different line ends the run`() {
        val collapser = ConsecutiveCollapser()
        collapser.add("a", "A")
        collapser.add("a", "A")

        val completed = collapser.add("b", "B")
        assertEquals("A (x2)", ConsecutiveCollapser.render(completed!!))
        assertEquals("B", ConsecutiveCollapser.render(collapser.flush()!!))
    }
}