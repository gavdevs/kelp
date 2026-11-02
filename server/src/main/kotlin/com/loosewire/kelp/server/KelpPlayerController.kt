package com.loosewire.kelp.server

import android.content.Context
import android.os.Looper
import com.loosewire.kelp.protocol.PlaybackSnapshot
import com.loosewire.kelp.protocol.PlayerCommand
import com.loosewire.kelp.protocol.RepeatMode
import com.loosewire.kelp.protocol.StartPlaybackRequest
import com.loosewire.kelp.protocol.TrackSummary
import com.thelightphone.sdk.audio.LightAudioQueueCommand
import com.thelightphone.sdk.audio.LightAudioQueueItem
import com.thelightphone.sdk.audio.LightAudioQueueResolver
import com.thelightphone.sdk.audio.LightAudioQueueSessions
import com.thelightphone.sdk.audio.LightAudioQueueSnapshot
import com.thelightphone.sdk.audio.LightAudioRepeatMode
import com.thelightphone.sdk.audio.LightAudioResolvedSource
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Server-only TIDAL adapter. The SDK service owns the queue, player and resolution jobs;
 * this controller can disappear with an RPC client without interrupting playback.
 * Synchronous mutations are binder-thread entry points, never main-thread waits.
 */
internal class KelpPlayerController(context: Context) {
    private val appContext = context.applicationContext

    fun snapshot(): PlaybackSnapshot = LightAudioQueueSessions.state.value.toPlaybackSnapshot()

    fun start(request: StartPlaybackRequest): PlaybackSnapshot {
        require(request.tracks.isNotEmpty()) { "Playback needs at least one song" }
        require(request.startIndex in request.tracks.indices) { "Invalid starting song" }
        requireBinderThread()
        val tracks = request.tracks.distinctBy(TrackSummary::id)
        val requestedId = request.tracks[request.startIndex].id
        val items = queueItems(tracks, nextGeneration.incrementAndGet().toString())
        return runBlocking {
            withTimeout(PLAYER_TIMEOUT_MS) {
                LightAudioQueueSessions.start(
                    context = appContext,
                    items = items,
                    startIndex = tracks.indexOfFirst { it.id == requestedId },
                    sourceName = request.sourceName,
                    continuousPlayback = request.continuousPlayback,
                ).toPlaybackSnapshot()
            }
        }
    }

    suspend fun control(command: PlayerCommand, positionMs: Long? = null): PlaybackSnapshot =
        LightAudioQueueSessions.control(
            context = appContext,
            command = when (command) {
                PlayerCommand.TogglePlayPause -> LightAudioQueueCommand.TogglePlayPause
                PlayerCommand.Previous -> LightAudioQueueCommand.Previous
                PlayerCommand.Next -> LightAudioQueueCommand.Next
                PlayerCommand.ToggleShuffle -> LightAudioQueueCommand.ToggleShuffle
                PlayerCommand.CycleRepeat -> LightAudioQueueCommand.CycleRepeat
                PlayerCommand.Seek -> LightAudioQueueCommand.Seek
            },
            positionMs = positionMs,
        ).toPlaybackSnapshot()

    /** Sign-out stops the service and cancels resolution before credentials are cleared. */
    fun release() {
        requireBinderThread()
        runBlocking { LightAudioQueueSessions.stop() }
    }

    companion object {
        private const val PLAYER_TIMEOUT_MS = 10_000L
        private val nextGeneration = AtomicLong()
        private val tracksLock = Any()

        // The SDK retains these exact items for snapshots and resolver callbacks. Weak keys
        // retain metadata while any old snapshot is in flight, not for the process lifetime.
        // Generation-qualified ids keep replacement queues with the same track ids distinct.
        private val tracksByItem = WeakHashMap<LightAudioQueueItem, TrackSummary>()

        fun initialize(
            context: Context,
            continuousLoader: suspend (TrackSummary) -> List<TrackSummary>,
        ) {
            val appContext = context.applicationContext
            val streams = TidalStreamResolver {
                checkNotNull(KelpRuntime.streamingAuth()) { "TIDAL playback session is unavailable" }
            }
            LightAudioQueueSessions.configure(object : LightAudioQueueResolver {
                override suspend fun resolve(item: LightAudioQueueItem): LightAudioResolvedSource =
                    withContext(Dispatchers.IO) {
                        val track = trackFor(item)
                        RecentTracksStore.record(track)
                        when (val resolved = streams.resolve(track.id)) {
                            is TidalStreamResolver.ResolvedStream.Progressive ->
                                LightAudioResolvedSource(LightAudioSource.UrlSource(resolved.url))
                            is TidalStreamResolver.ResolvedStream.ClearDash -> {
                                val directory = File(appContext.cacheDir, "tidal_mpd").apply { mkdirs() }
                                val file = File(
                                    directory,
                                    "${item.id.substringBefore(':')}_${track.id}_${resolved.audioQuality}.mpd",
                                )
                                file.writeText(resolved.mpdXml)
                                LightAudioResolvedSource(
                                    LightAudioSource.FileSource(file),
                                    mimeType = "application/dash+xml",
                                )
                            }
                        }
                    }

                override suspend fun continuation(seed: LightAudioQueueItem): List<LightAudioQueueItem> =
                    withContext(Dispatchers.IO) {
                        val additions = continuousLoader(trackFor(seed))
                        val currentItems = LightAudioQueueSessions.state.value.items
                        if (currentItems.none { it.id == seed.id }) return@withContext emptyList()
                        val generation = seed.id.substringBefore(':')
                        val existing = currentItems.mapTo(HashSet()) { it.id }
                        // Never replace a live weak key with an equal recommendation key.
                        // The engine also deduplicates; filter here to preserve its metadata.
                        queueItems(
                            additions.filter { existing.add("$generation:${it.id}") },
                            generation,
                        )
                    }
            })
        }

        fun requireBinderThread() {
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "Synchronous playback RPCs must run off the main thread"
            }
        }

        private fun queueItems(tracks: List<TrackSummary>, generation: String): List<LightAudioQueueItem> =
            synchronized(tracksLock) {
                tracks.map { track ->
                    LightAudioQueueItem(
                        id = "$generation:${track.id}",
                        metadata = LightMediaMetadata(
                            title = track.title,
                            artist = track.artistName,
                            album = track.albumTitle,
                            durationMs = track.durationMs,
                        ),
                    ).also { tracksByItem[it] = track }
                }
            }

        private fun trackFor(item: LightAudioQueueItem): TrackSummary = synchronized(tracksLock) {
            checkNotNull(tracksByItem[item]) { "TIDAL queue metadata is unavailable" }
        }

        private fun LightAudioQueueSnapshot.toPlaybackSnapshot(): PlaybackSnapshot {
            // The protocol has no playback-error field. Surface an RPC error instead of
            // returning the last playing snapshot after a resolver or service failure.
            check(error == null) { "SDK audio playback failed: ${error?.diagnostic}" }
            val tracks = items.map(::trackFor)
            return PlaybackSnapshot(
                current = tracks.getOrNull(currentIndex),
                sourceName = sourceName,
                queue = tracks,
                currentIndex = currentIndex,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                shuffle = shuffle,
                repeatMode = when (repeatMode) {
                    LightAudioRepeatMode.Off -> RepeatMode.Off
                    LightAudioRepeatMode.All -> RepeatMode.All
                    LightAudioRepeatMode.One -> RepeatMode.One
                },
            )
        }
    }
}
