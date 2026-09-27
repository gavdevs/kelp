package com.loosewire.kelp.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import com.loosewire.kelp.protocol.TrackSummary
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Resolve only sources ExoPlayer prepares, not every signed URL in a long queue. */
@UnstableApi
internal class TidalMediaSource(
    private val context: Context,
    private val resolver: TidalStreamResolver,
    private val track: TrackSummary,
    private val item: MediaItem,
) : CompositeMediaSource<Unit>() {
    private var scope: CoroutineScope? = null
    private var child: MediaSource? = null
    private var failure: IOException? = null
    private var manifestFile: File? = null

    override fun getMediaItem(): MediaItem = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        // MediaSource lifecycle belongs to ExoPlayer's playback looper, NOT the UI looper.
        val sourceScope = CoroutineScope(
            SupervisorJob() + Handler(checkNotNull(Looper.myLooper())).asCoroutineDispatcher(),
        )
        scope = sourceScope
        sourceScope.launch {
            var resolved: MediaItem? = null
            var installed = false
            try {
                val mediaItem = withContext(Dispatchers.IO) {
                    resolver.resolveMediaItem(context, track).also { resolved = it }
                }.buildUpon().setMediaId(item.mediaId).setMediaMetadata(item.mediaMetadata).build()
                manifestFile = mediaItem.manifestFile()
                val source = DefaultMediaSourceFactory(context).createMediaSource(mediaItem)
                child = source
                prepareChildSource(Unit, source)
                installed = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = IOException("Could not resolve TIDAL audio", error)
            } finally {
                // withContext can discard an IO result when cancellation wins the return race.
                if (!installed) resolved?.manifestFile()?.delete()
            }
        }
    }

    override fun maybeThrowSourceInfoRefreshError() {
        failure?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun onChildSourceInfoRefreshed(id: Unit, mediaSource: MediaSource, timeline: Timeline) {
        refreshSourceInfo(timeline)
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = checkNotNull(child).createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        checkNotNull(child).releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        scope?.cancel()
        scope = null
        super.releaseSourceInternal()
        child = null
        failure = null
        manifestFile?.delete()
        manifestFile = null
    }

    private fun MediaItem.manifestFile(): File? = localConfiguration?.uri
        ?.takeIf { it.scheme == "file" }
        ?.path?.let(::File)
}
