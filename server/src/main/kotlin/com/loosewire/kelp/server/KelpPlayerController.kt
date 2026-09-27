package com.loosewire.kelp.server

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.loosewire.kelp.protocol.KelpError
import com.loosewire.kelp.protocol.KelpErrorCategory
import com.loosewire.kelp.protocol.PlaybackSnapshot
import com.loosewire.kelp.protocol.PlayerCommand
import com.loosewire.kelp.protocol.RepeatMode
import com.loosewire.kelp.protocol.StartPlaybackRequest
import com.loosewire.kelp.protocol.TrackSummary
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Service-owned queue. All mutations and ExoPlayer access run on the main looper. */
@UnstableApi
internal class KelpPlayerController(
    context: Context,
    streamingAuth: TidalStreamingAuth,
    private val continuousLoader: suspend (TrackSummary) -> List<TrackSummary>,
) {
    private val appContext = context.applicationContext
    private val resolver = TidalStreamResolver(streamingAuth)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val logger = Logger.getLogger(KelpPlayerController::class.java.name)
    private var queue = emptyList<TrackSummary>()
    private var displayedQueue = emptyList<TrackSummary>()
    private var sourceName: String? = null
    private var continuousPlayback = false
    private var generation = 0
    private var continuation: Job? = null
    private var continuationSeed: String? = null
    private var positionJob: Job? = null
    private var retryTrackId: String? = null
    private var retryUsed = false
    private var playbackError: KelpError? = null

    @Volatile
    private var currentSnapshot = PlaybackSnapshot()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 90_000, 2_500, 5_000)
                .build(),
        )
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(5_000)
        .setMaxSeekToPreviousPositionMs(5_000)
        .build()
        .also { p ->
            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val id = mediaItem?.mediaId
                    if (retryTrackId != id) {
                        retryTrackId = id
                        retryUsed = false
                        playbackError = null
                    }
                    queue.find { it.id == id }?.let(RecentTracksStore::record)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    positionJob?.cancel()
                    positionJob = if (isPlaying) scope.launch {
                        while (isActive) {
                            currentSnapshot = currentSnapshot.copy(
                                positionMs = p.currentPosition.coerceAtLeast(0),
                            )
                            delay(500)
                        }
                    } else null
                }

                override fun onPlayerError(error: PlaybackException) {
                    logger.warning("TIDAL playback failed: ${error.errorCodeName}")
                    val expired = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                        .any { it.responseCode == 401 || it.responseCode == 403 }
                    if (expired && !retryUsed) {
                        retryUsed = true
                        val expectedGeneration = generation
                        val id = p.currentMediaItem?.mediaId
                        val position = p.currentPosition
                        val shouldPlay = p.playWhenReady
                        scope.launch {
                            // Let the failing player's event batch finish before replacing its sources.
                            kotlinx.coroutines.yield()
                            if (generation == expectedGeneration && p.currentMediaItem?.mediaId == id) {
                                prepareQueue(p.currentMediaItemIndex, position, shouldPlay)
                            }
                        }
                    } else {
                        playbackError = KelpError(
                            KelpErrorCategory.Unavailable,
                            "Could not play this song. Press Play to retry, or choose Next.",
                        )
                    }
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                        events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
                    ) rebuildDisplayedQueue()
                    publishSnapshot()
                    requestContinuation()
                }
            })
        }

    fun snapshot(): PlaybackSnapshot = currentSnapshot

    fun start(request: StartPlaybackRequest): PlaybackSnapshot {
        require(request.tracks.isNotEmpty()) { "Playback needs at least one song" }
        require(request.startIndex in request.tracks.indices) { "Invalid starting song" }
        generation++
        continuation?.cancel()
        continuation = null
        continuationSeed = null
        retryUsed = false
        playbackError = null
        queue = request.tracks.distinctBy { it.id }
        sourceName = request.sourceName
        continuousPlayback = request.continuousPlayback
        val index = queue.indexOfFirst { it.id == request.tracks[request.startIndex].id }
        prepareQueue(index, 0, true)
        return snapshot()
    }

    fun control(command: PlayerCommand, positionMs: Long? = null): PlaybackSnapshot {
        when (command) {
            PlayerCommand.TogglePlayPause -> when {
                player.playerError != null -> {
                    playbackError = null
                    retryUsed = false
                    prepareQueue(player.currentMediaItemIndex, player.currentPosition, true)
                }
                player.playWhenReady && player.playbackState != Player.STATE_ENDED -> player.pause()
                else -> {
                    if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                    player.play()
                }
            }
            PlayerCommand.Previous -> player.seekToPrevious()
            PlayerCommand.Next -> player.seekToNextMediaItem()
            PlayerCommand.ToggleShuffle -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            PlayerCommand.CycleRepeat -> player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            PlayerCommand.Seek -> if (positionMs != null && player.duration > 0) {
                player.seekTo(positionMs.coerceIn(0, player.duration))
            }
        }
        rebuildDisplayedQueue()
        publishSnapshot()
        return snapshot()
    }

    private fun prepareQueue(index: Int, positionMs: Long, play: Boolean) {
        if (queue.isEmpty()) return
        player.setMediaSources(queue.map(::source), index.coerceIn(queue.indices), positionMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = play
        rebuildDisplayedQueue()
        publishSnapshot()
    }

    private fun source(track: TrackSummary): MediaSource = TidalMediaSource(
        appContext,
        resolver,
        track,
        MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artistName)
                    .setAlbumTitle(track.albumTitle)
                    .setDurationMs(track.durationMs)
                    .build(),
            )
            .build(),
    )

    private fun rebuildDisplayedQueue() {
        val timeline = player.currentTimeline
        if (timeline.isEmpty || !player.shuffleModeEnabled) {
            displayedQueue = queue
            return
        }
        displayedQueue = buildList {
            var index = timeline.getFirstWindowIndex(true)
            while (index != C.INDEX_UNSET) {
                queue.getOrNull(index)?.let(::add)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
            }
        }
    }

    private fun publishSnapshot() {
        val current = queue.getOrNull(player.currentMediaItemIndex)
        currentSnapshot = PlaybackSnapshot(
            current = current,
            sourceName = sourceName,
            queue = displayedQueue,
            currentIndex = displayedQueue.indexOf(current),
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null,
            shuffle = player.shuffleModeEnabled,
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.All
                Player.REPEAT_MODE_ONE -> RepeatMode.One
                else -> RepeatMode.Off
            },
            isLoading = player.playbackState == Player.STATE_BUFFERING,
            error = playbackError,
        )
    }

    private fun requestContinuation() {
        if (!continuousPlayback || player.repeatMode != Player.REPEAT_MODE_OFF ||
            player.hasNextMediaItem() || queue.isEmpty() || player.playerError != null
        ) return
        val seed = queue.getOrNull(player.currentMediaItemIndex) ?: return
        if (continuationSeed == seed.id) return
        continuationSeed = seed.id
        val expectedGeneration = generation
        continuation = scope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) { continuousLoader(seed) }
                if (generation != expectedGeneration) return@launch
                val ids = queue.mapTo(HashSet(), TrackSummary::id)
                val additions = tracks.filter { ids.add(it.id) }
                if (additions.isEmpty()) return@launch
                val ended = player.playbackState == Player.STATE_ENDED
                val nextIndex = queue.size
                queue = queue + additions
                player.addMediaSources(additions.map(::source))
                if (ended && player.playWhenReady) {
                    player.seekTo(nextIndex, 0)
                    player.prepare()
                }
                rebuildDisplayedQueue()
                publishSnapshot()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == expectedGeneration) {
                    playbackError = KelpError(KelpErrorCategory.Network, "Could not load more songs.")
                    publishSnapshot()
                }
            }
        }
    }

    fun clear() {
        generation++
        continuation?.cancel()
        queue = emptyList()
        displayedQueue = emptyList()
        player.stop()
        player.clearMediaItems()
        currentSnapshot = PlaybackSnapshot()
    }

    fun release() {
        generation++
        scope.cancel()
        player.release()
        currentSnapshot = PlaybackSnapshot()
    }
}
