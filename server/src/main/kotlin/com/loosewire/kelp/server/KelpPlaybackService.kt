package com.loosewire.kelp.server

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.loosewire.kelp.protocol.PlaybackSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** The only owner of Kelp's player and platform media session; UI visibility is irrelevant. */
@UnstableApi
class KelpPlaybackService : MediaSessionService() {
    private var controller: KelpPlayerController? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val auth = KelpRuntime.streamingAuth()
        if (auth == null) {
            ready.completeExceptionally(IllegalStateException("Playback is not initialized"))
            stopSelf()
            return
        }
        val playback = KelpPlayerController(this, auth, KelpServiceMethods::similarTracks)
        controller = playback
        val builder = MediaSession.Builder(this, playback.player)
            .setId(packageName)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    // System transports may navigate/control the queue, never inject arbitrary URLs.
                    val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                        .remove(Player.COMMAND_SET_MEDIA_ITEM)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(commands)
                        .build()
                }
            })
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        // Binder RPCs start playback directly, so no MediaController connection
        // arrives to auto-register onGetSession's result with MediaSessionService.
        session = builder.build().also(::addSession)
        instance = this
        ready.complete(playback)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        instance = null
        session?.release()
        session = null
        controller?.release()
        controller = null
        ready = CompletableDeferred()
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: KelpPlaybackService? = null
        // Accessed only on Main, including start/stop and lifecycle callbacks.
        private var ready = CompletableDeferred<KelpPlayerController>()

        internal fun snapshot(): PlaybackSnapshot = instance?.controller?.snapshot() ?: PlaybackSnapshot()

        internal suspend fun <T> withController(
            context: Context,
            block: (KelpPlayerController) -> T,
        ): T = withContext(Dispatchers.Main.immediate) {
            val existing = instance?.controller
            val playback = existing ?: run {
                // Called by an explicit foreground playback action, never snapshot polling.
                context.startService(Intent(context, KelpPlaybackService::class.java))
                withTimeout(5_000) { ready.await() }
            }
            block(playback)
        }

        internal suspend fun stop() = withContext(Dispatchers.Main.immediate) {
            instance?.let { service ->
                service.controller?.clear()
                service.stopSelf()
            }
        }
    }
}
