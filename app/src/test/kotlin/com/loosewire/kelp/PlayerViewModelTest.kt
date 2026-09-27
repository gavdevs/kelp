package com.loosewire.kelp

import com.loosewire.kelp.protocol.PlaybackSnapshot
import com.loosewire.kelp.protocol.AuthSnapshot
import com.loosewire.kelp.protocol.Page
import com.loosewire.kelp.protocol.ReleaseSummary
import com.loosewire.kelp.protocol.ServerActivity
import com.loosewire.kelp.protocol.TrackSummary
import com.loosewire.kelp.protocol.KelpError
import com.loosewire.kelp.protocol.KelpErrorCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun seekUsesActualPlaybackDurationInsteadOfCatalogDuration() = runTest(dispatcher) {
        val client = PlayerFakeTideClient(
            PlaybackSnapshot(
                current = TrackSummary(
                    id = "track",
                    title = "Track",
                    artistName = "Artist",
                    durationMs = 240_000L,
                    explicit = false,
                ),
                durationMs = 30_000L,
            ),
        )
        val viewModel = PlayerViewModel(client)
        viewModel.refresh()
        runCurrent()

        viewModel.seekTo(0.5f)
        advanceTimeBy(121L)
        runCurrent()

        assertEquals(15_000L, client.lastSeekPositionMs)
    }

    @Test
    fun failedRefreshKeepsNowPlayingAndSuccessfulRefreshClearsTheError() = runTest(dispatcher) {
        val playback = PlaybackSnapshot(
            current = TrackSummary("track", "Track", "Artist", 240_000, false),
            positionMs = 30_000,
            isPlaying = true,
        )
        val client = PlayerFakeTideClient(playback)
        val viewModel = PlayerViewModel(client)
        viewModel.refresh()
        runCurrent()
        val failure = KelpError(KelpErrorCategory.Timeout, "Could not reach the player.")
        client.refreshFailure = failure
        viewModel.refresh()
        runCurrent()

        assertEquals(playback, viewModel.state.value.playback)
        assertEquals(failure, viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)

        client.refreshFailure = null
        viewModel.refresh()
        runCurrent()
        assertNull(viewModel.state.value.error)
        assertEquals(playback.current, viewModel.state.value.playback?.current)
    }
}

private class PlayerFakeTideClient(
    private val playback: PlaybackSnapshot,
) : KelpClient {
    var lastSeekPositionMs: Long? = null
        private set

    var refreshFailure: KelpError? = null
    override suspend fun playback(): KelpClientResult<PlaybackSnapshot> =
        refreshFailure?.let { KelpClientResult.Failure(it) } ?: KelpClientResult.Success(playback)

    override suspend fun authSnapshot(): KelpClientResult<AuthSnapshot> = error("Not used")

    override suspend fun loginActivity(): KelpClientResult<ServerActivity> = error("Not used")

    override suspend fun collection(cursor: String?): KelpClientResult<Page<ReleaseSummary>> =
        error("Not used")

    override suspend fun seekPlayback(positionMs: Long): KelpClientResult<PlaybackSnapshot> {
        lastSeekPositionMs = positionMs
        return KelpClientResult.Success(playback.copy(positionMs = positionMs))
    }
}
