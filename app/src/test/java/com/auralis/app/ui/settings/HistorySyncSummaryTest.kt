package com.auralis.app.ui.settings

import com.auralis.app.data.youtube.PlaybackReportOutcome
import com.auralis.app.data.youtube.PlaybackReportingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-line history-sync status Settings and Logs show: what was last sent and pulled, plus a
 * short, secret-free reason when a send or a pull failed.
 */
class HistorySyncSummaryTest {

    private val now = 10_000_000L

    @Test
    fun `the switch off reads as off`() {
        val text = historySyncSummary(PlaybackReportingState(enabled = false), now)
        assertTrue(text.startsWith("Off"))
    }

    @Test
    fun `nothing sent or pulled yet is said plainly`() {
        val text = historySyncSummary(PlaybackReportingState(enabled = true), now)
        assertEquals("Last sent never · last pulled never", text)
    }

    @Test
    fun `last sent and last pulled are shown as ages`() {
        val state = PlaybackReportingState(
            enabled = true,
            outcome = PlaybackReportOutcome.Sent,
            lastSuccessAt = now - 120_000L,
            lastPulledAt = now - 3_600_000L,
        )
        assertEquals("Last sent 2m ago · last pulled 1h ago", historySyncSummary(state, now))
    }

    @Test
    fun `a refusal is shown with its status code`() {
        val state = PlaybackReportingState(
            enabled = true,
            outcome = PlaybackReportOutcome.Refused,
            lastSuccessAt = now - 5_000L,
            lastRefusalCode = 403,
        )
        assertTrue(historySyncSummary(state, now).endsWith(" · refused (HTTP 403)"))
    }

    @Test
    fun `a missing tracking block and a failed pull each get a short word`() {
        val noTracking = PlaybackReportingState(enabled = true, outcome = PlaybackReportOutcome.NoTrackingUrl)
        assertTrue(historySyncSummary(noTracking, now).endsWith(" · no tracking block"))

        val pullFailed = PlaybackReportingState(enabled = true, outcome = PlaybackReportOutcome.Sent, pullFailed = true)
        assertTrue(historySyncSummary(pullFailed, now).endsWith(" · pull failed"))
    }
}
