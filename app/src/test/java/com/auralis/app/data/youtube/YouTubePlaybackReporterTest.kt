package com.auralis.app.data.youtube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ping policy: at most three pings per play (start, watch-time, final), a new play detected from
 * the play generation, a bounded refusal backoff instead of a permanent switch-off, and an
 * immediate stop when the switch is off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubePlaybackReporterTest {

    private class FakeGateway : PlaybackHistoryGateway {
        val opens = mutableListOf<String>()
        val watchtimes = mutableListOf<Watchtime>()
        var openResult: PlaybackReportResult =
            PlaybackReportResult.Ok(PlaybackTracking("cpn-1", "https://pb", "https://wt"))
        var watchResult: PlaybackReportResult = PlaybackReportResult.Ok()

        data class Watchtime(val seconds: Long, val final: Boolean, val cpn: String)

        override suspend fun openPlayback(videoId: String): PlaybackReportResult {
            opens += videoId
            return openResult
        }

        override suspend fun reportWatchtime(
            tracking: PlaybackTracking,
            listenedSeconds: Long,
            final: Boolean,
        ): PlaybackReportResult {
            watchtimes += Watchtime(listenedSeconds, final, tracking.cpn)
            return watchResult
        }
    }

    /** An unconfined scope processes each sample inline, so assertions need no extra pumping. */
    private fun TestScope.reporter(
        gateway: PlaybackHistoryGateway,
        enabled: () -> Boolean = { true },
        nowMs: () -> Long = System::currentTimeMillis,
    ) = YouTubePlaybackReporter(
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        gateway = gateway,
        enabled = enabled,
        nowMs = nowMs,
    )

    private fun sample(
        videoId: String? = "v",
        generation: Long = 1L,
        listenedMs: Long,
        durationMs: Long = 180_000L,
        playing: Boolean = true,
    ) = PlaybackSample(videoId, generation, listenedMs, durationMs, playing)

    @Test
    fun `the play is registered only after two seconds of real listening, once`() = runTest {
        val gateway = FakeGateway()
        val reporter = reporter(gateway)

        reporter.onSample(sample(listenedMs = 1_500))
        assertTrue(gateway.opens.isEmpty())

        reporter.onSample(sample(listenedMs = 2_500))
        reporter.onSample(sample(listenedMs = 3_500))
        assertEquals(listOf("v"), gateway.opens)
    }

    @Test
    fun `watch-time waits for min thirty seconds or half the title, then a final ping closes the play`() = runTest {
        val gateway = FakeGateway()
        val reporter = reporter(gateway)

        reporter.onSample(sample(listenedMs = 2_500))
        reporter.onSample(sample(listenedMs = 29_000))
        assertTrue(gateway.watchtimes.isEmpty())

        reporter.onSample(sample(listenedMs = 31_000))
        assertEquals(listOf(FakeGateway.Watchtime(31L, false, "cpn-1")), gateway.watchtimes)

        // No second watch-time ping for the same play.
        reporter.onSample(sample(listenedMs = 45_000))
        assertEquals(1, gateway.watchtimes.size)

        // A new generation (track change) closes the previous play with final=1.
        reporter.onSample(sample(videoId = "w", generation = 2L, listenedMs = 0L))
        assertEquals(FakeGateway.Watchtime(45L, true, "cpn-1"), gateway.watchtimes.last())
        reporter.onSample(sample(videoId = "w", generation = 2L, listenedMs = 2_500))
        assertEquals(listOf("v", "w"), gateway.opens)
    }

    @Test
    fun `a short title uses half its length as the watch-time threshold`() = runTest {
        val gateway = FakeGateway()
        val reporter = reporter(gateway)

        reporter.onSample(sample(listenedMs = 2_500, durationMs = 12_000L))
        reporter.onSample(sample(listenedMs = 6_000, durationMs = 12_000L))
        assertEquals(listOf(FakeGateway.Watchtime(6L, false, "cpn-1")), gateway.watchtimes)
    }

    @Test
    fun `a repeat of the same song counts as a new play`() = runTest {
        val gateway = FakeGateway()
        val reporter = reporter(gateway)

        reporter.onSample(sample(generation = 1L, listenedMs = 2_500))
        advanceUntilIdle()
        reporter.onSample(sample(generation = 2L, listenedMs = 2_500))
        assertEquals(listOf("v", "v"), gateway.opens)
    }

    @Test
    fun `a definitive pause closes the play out`() = runTest {
        val gateway = FakeGateway()
        var clock = 0L
        val reporter = reporter(gateway, nowMs = { clock })

        reporter.onSample(sample(listenedMs = 2_500, playing = true))
        assertEquals(listOf("v"), gateway.opens)

        clock = 10_000L
        reporter.onSample(sample(listenedMs = 2_500, playing = false))
        assertTrue(gateway.watchtimes.isEmpty())

        clock += YouTubePlaybackReporter.FINAL_AFTER_PAUSE_MS
        reporter.onSample(sample(listenedMs = 2_500, playing = false))
        assertEquals(FakeGateway.Watchtime(2L, true, "cpn-1"), gateway.watchtimes.single())
    }

    @Test
    fun `no tracking block means no ping at all`() = runTest {
        val gateway = FakeGateway().apply { openResult = PlaybackReportResult.NoTrackingUrl }
        val reporter = reporter(gateway)

        reporter.onSample(sample(listenedMs = 2_500))
        reporter.onSample(sample(listenedMs = 45_000))
        assertTrue(gateway.watchtimes.isEmpty())
        assertEquals(listOf("v"), gateway.opens)
    }

    @Test
    fun `turning the switch off stops sends and turning it back on resumes`() = runTest {
        val gateway = FakeGateway()
        var enabled = true
        val reporter = reporter(gateway, enabled = { enabled })

        enabled = false
        reporter.onSample(sample(listenedMs = 45_000))
        assertTrue(gateway.opens.isEmpty())
        assertTrue(gateway.watchtimes.isEmpty())

        enabled = true
        reporter.onSample(sample(listenedMs = 46_000))
        assertEquals(listOf("v"), gateway.opens)
    }

    @Test
    fun `a refusal backs off for a while instead of switching off for the run`() = runTest {
        val gateway = FakeGateway().apply { openResult = PlaybackReportResult.Refused(403) }
        var clock = 0L
        val reporter = reporter(gateway, nowMs = { clock })

        reporter.onSample(sample(generation = 1L, listenedMs = 2_500))
        assertEquals(1, gateway.opens.size)

        // Inside the backoff window a new play is not even attempted.
        clock += 30_000L
        reporter.onSample(sample(generation = 2L, listenedMs = 2_500))
        assertEquals(1, gateway.opens.size)

        // After the window it is attempted again.
        clock += 60_000L
        reporter.onSample(sample(generation = 3L, listenedMs = 2_500))
        assertEquals(2, gateway.opens.size)
    }
}
