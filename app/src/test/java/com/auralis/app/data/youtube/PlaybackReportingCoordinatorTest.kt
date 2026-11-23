package com.auralis.app.data.youtube

import com.auralis.app.domain.model.AppSettings
import com.auralis.app.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator is the one reporting collector: it feeds the reporter from real playback state and
 * honours the switch and the session live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReportingCoordinatorTest {

    private class FakeGateway : PlaybackHistoryGateway {
        val opens = mutableListOf<String>()

        override suspend fun openPlayback(videoId: String): PlaybackReportResult {
            opens += videoId
            return PlaybackReportResult.Ok(PlaybackTracking("cpn", "https://pb", "https://wt"))
        }

        override suspend fun reportWatchtime(
            tracking: PlaybackTracking,
            listenedSeconds: Long,
            final: Boolean,
        ): PlaybackReportResult = PlaybackReportResult.Ok()
    }

    private fun playingState(positionMs: Long) = PlaybackController.State(
        connected = true,
        trackId = "yt:abc",
        playing = true,
        positionMs = positionMs,
        durationMs = 180_000L,
    )

    @Test
    fun `a youtube play is opened once the honest clock crosses two seconds`() = runTest {
        val playback = MutableStateFlow(PlaybackController.State())
        val settings = MutableStateFlow(AppSettings())
        val gateway = FakeGateway()
        var clock = 0L
        val coordinator = PlaybackReportingCoordinator(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            playbackState = playback,
            settings = settings,
            authenticated = { true },
            gateway = gateway,
            wallClockMs = { clock },
        )

        playback.value = playingState(0L)
        clock += 2_000L
        playback.value = playingState(2_000L)

        assertEquals(listOf("abc"), gateway.opens)
        assertTrue(coordinator.state.value.enabled)
        assertEquals(PlaybackReportOutcome.Sent, coordinator.state.value.outcome)
    }

    @Test
    fun `the switch off stops sends at once and is shown as off`() = runTest {
        val playback = MutableStateFlow(PlaybackController.State())
        val settings = MutableStateFlow(AppSettings(youtubeSyncHistory = false))
        val gateway = FakeGateway()
        var clock = 0L
        val coordinator = PlaybackReportingCoordinator(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            playbackState = playback,
            settings = settings,
            authenticated = { true },
            gateway = gateway,
            wallClockMs = { clock },
        )

        playback.value = playingState(0L)
        clock += 5_000L
        playback.value = playingState(5_000L)

        assertTrue(gateway.opens.isEmpty())
        assertEquals(PlaybackReportOutcome.Off, coordinator.state.value.outcome)
        assertFalse(coordinator.state.value.enabled)
    }
}
