package com.auralis.app.data.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6: only the explicit Disconnect button may erase the stored session. */
class SessionClearRulesTest {
    @Test
    fun `the disconnect button may clear`() {
        assertTrue(SessionClearRules.mayClear(SessionClearRules.DISCONNECT))
    }

    @Test
    fun `every automatic and unrelated path is refused`() {
        listOf(
            "Use a different account",
            "Clerk sign-out",
            "switch OFF",
            "app start",
            "expired:verified",
            "401",
            "session unreadable",
            "user signed out of Google",
        ).forEach { caller ->
            assertFalse("$caller must not clear the session", SessionClearRules.mayClear(caller))
        }
    }
}