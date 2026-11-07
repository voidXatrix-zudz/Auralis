package com.auralis.app

import com.auralis.app.ui.nav.Destination
import com.auralis.app.ui.nav.NavState
import com.auralis.app.ui.nav.Tab
import org.junit.Assert.assertEquals
import org.junit.Test

class NavStateTest {
    @Test fun startsOnHomeWithEmptyStack() {
        val s = NavState()
        assertEquals(Tab.HOME, s.tab)
        assertEquals(null, s.top)
    }

    @Test fun selectingATabClearsTheStack() {
        val s = NavState().push(Destination.Settings).selectTab(Tab.LIBRARY)
        assertEquals(Tab.LIBRARY, s.tab)
        assertEquals(emptyList<Destination>(), s.stack)
    }

    @Test fun pushThenPopReturnsToTheTab() {
        val s = NavState(tab = Tab.SEARCH).push(Destination.Settings).push(Destination.About)
        assertEquals(Destination.About, s.top)
        val popped = s.pop().pop()
        assertEquals(null, popped.top)
        assertEquals(Tab.SEARCH, popped.tab)
    }

    @Test fun popOnEmptyStackIsANoOp() {
        assertEquals(NavState(), NavState().pop())
    }

    @Test fun pushingTheCurrentTopTwiceDoesNotDuplicate() {
        val s = NavState().push(Destination.Settings).push(Destination.Settings)
        assertEquals(1, s.stack.size)
    }
}
