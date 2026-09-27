package com.loosewire.kelp.server

import android.content.ComponentName
import android.content.Context
import com.loosewire.kelp.protocol.AlbumRequest
import com.loosewire.kelp.protocol.ArtistRequest
import com.loosewire.kelp.protocol.ArtistReleasesRequest
import com.loosewire.kelp.protocol.ArtistTracksRequest
import com.loosewire.kelp.protocol.CollectionRequest
import com.loosewire.kelp.protocol.PlaylistRequest
import com.loosewire.kelp.protocol.PlayerCommandRequest
import com.loosewire.kelp.protocol.StartPlaybackRequest
import com.loosewire.kelp.protocol.SearchRequest
import com.loosewire.kelp.protocol.SearchPageRequest
import com.loosewire.kelp.protocol.ServerActivity
import com.loosewire.kelp.protocol.KelpError
import com.loosewire.kelp.protocol.KelpErrorCategory
import com.loosewire.kelp.protocol.KelpRemoteMethod
import com.thelightphone.sdk.shared.LightResult
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException

object KelpServiceMethods {
    private val logger = Logger.getLogger(KelpServiceMethods::class.java.name)
    private var loginActivityComponent = "com.loosewire.kelp/.server.LoginActivity"
    private lateinit var applicationContext: Context
    private val catalogLock = Any()
    @Volatile
    private var catalog: Catalog? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        RecentTracksStore.initialize(applicationContext)
        loginActivityComponent = ComponentName(
            context.applicationContext,
            LoginActivity::class.java,
        ).flattenToString()
    }

    fun initializeCatalog(catalog: Catalog?) {
        synchronized(catalogLock) {
            this.catalog = catalog
        }
    }

    fun dispatch(methodId: String, payload: String?): LightResult<String> = try {
        when (methodId) {
            KelpRemoteMethod.GetAuthSnapshot.id -> LightResult.Success(
                KelpRemoteMethod.GetAuthSnapshot.encodeResponse(
                    KelpRuntime.currentAuthSnapshot(),
                ),
            )

            KelpRemoteMethod.GetLoginActivity.id -> LightResult.Success(
                KelpRemoteMethod.GetLoginActivity.encodeResponse(
                    ServerActivity(loginActivityComponent),
                ),
            )

            KelpRemoteMethod.Logout.id -> {
                runBlocking {
                    KelpPlaybackService.stop()
                    KelpRuntime.logout()
                }
                synchronized(catalogLock) {
                    catalog = null
                }
                LightResult.Success(
                    KelpRemoteMethod.Logout.encodeResponse(KelpRuntime.currentAuthSnapshot()),
                )
            }

            KelpRemoteMethod.GetCollection.id -> {
                val request = KelpRemoteMethod.GetCollection.decodeRequest(payload ?: "{}")
                handleCatalog(
                    operation = { it.collection(request.cursor) },
                    encode = KelpRemoteMethod.GetCollection::encodeResponse,
                )
            }

            KelpRemoteMethod.GetArtists.id -> {
                val request = KelpRemoteMethod.GetArtists.decodeRequest(payload ?: "{}")
                handleCatalog(
                    operation = { it.artists(request.cursor) },
                    encode = KelpRemoteMethod.GetArtists::encodeResponse,
                )
            }

            KelpRemoteMethod.GetTracks.id -> {
                val request = KelpRemoteMethod.GetTracks.decodeRequest(payload ?: "{}")
                handleCatalog(
                    operation = { it.tracks(request.cursor) },
                    encode = KelpRemoteMethod.GetTracks::encodeResponse,
                )
            }

            KelpRemoteMethod.GetHome.id -> handleCatalog(
                operation = Catalog::home,
                encode = KelpRemoteMethod.GetHome::encodeResponse,
            )

            KelpRemoteMethod.Search.id -> {
                val request = KelpRemoteMethod.Search.decodeRequest(payload ?: "{}")
                if (request.query.isBlank()) {
                    failure(
                        KelpErrorCategory.Protocol,
                        "Enter something to search for.",
                        LightResult.ErrorCode.InvalidParameters,
                    )
                } else {
                    handleSearch(request)
                }
            }

            KelpRemoteMethod.SearchPage.id -> {
                val request = KelpRemoteMethod.SearchPage.decodeRequest(payload ?: "{}")
                if (request.query.isBlank()) {
                    failure(
                        KelpErrorCategory.Protocol,
                        "Enter something to search for.",
                        LightResult.ErrorCode.InvalidParameters,
                    )
                } else {
                    handleSearchPage(request)
                }
            }

            KelpRemoteMethod.GetArtistDetail.id -> {
                val request = KelpRemoteMethod.GetArtistDetail.decodeRequest(payload ?: "{}")
                handleArtistDetail(request)
            }

            KelpRemoteMethod.GetArtistReleases.id -> {
                val request = KelpRemoteMethod.GetArtistReleases.decodeRequest(payload ?: "{}")
                handleArtistReleases(request)
            }

            KelpRemoteMethod.GetArtistTracks.id -> {
                val request = KelpRemoteMethod.GetArtistTracks.decodeRequest(payload ?: "{}")
                handleArtistTracks(request)
            }

            KelpRemoteMethod.GetAlbumDetail.id -> {
                val request = KelpRemoteMethod.GetAlbumDetail.decodeRequest(payload ?: "{}")
                handleAlbumDetail(request)
            }

            KelpRemoteMethod.GetPlaylistDetail.id -> {
                val request = KelpRemoteMethod.GetPlaylistDetail.decodeRequest(payload ?: "{}")
                handlePlaylistDetail(request)
            }

            KelpRemoteMethod.GetPlayback.id -> LightResult.Success(
                KelpRemoteMethod.GetPlayback.encodeResponse(KelpPlaybackService.snapshot()),
            )

            KelpRemoteMethod.StartPlayback.id -> {
                val request = KelpRemoteMethod.StartPlayback.decodeRequest(payload ?: "{}")
                handleStartPlayback(request)
            }

            KelpRemoteMethod.ControlPlayback.id -> {
                val request = KelpRemoteMethod.ControlPlayback.decodeRequest(payload ?: "{}")
                handlePlayerCommand(request)
            }

            else -> failure(
                KelpErrorCategory.Protocol,
                "Kelp does not support this server request.",
                LightResult.ErrorCode.InvalidParameters,
            )
        }
    } catch (error: SerializationException) {
        logger.warning(
            "Invalid Kelp RPC payload for method $methodId: ${error.message ?: error.javaClass.simpleName}",
        )
        failure(
            KelpErrorCategory.Protocol,
            "Kelp received an invalid request.",
            LightResult.ErrorCode.InvalidParameters,
        )
    } catch (error: Exception) {
        logger.severe("Kelp RPC failed for method $methodId: ${error.javaClass.simpleName}")
        failure(KelpErrorCategory.Unavailable, "The Kelp server could not complete the request.")
    }

    private fun handleSearch(request: SearchRequest): LightResult<String> = handleCatalog(
        operation = { it.search(request.query.trim()) },
        encode = KelpRemoteMethod.Search::encodeResponse,
    )

    private fun handleSearchPage(request: SearchPageRequest): LightResult<String> = handleCatalog(
        operation = { it.searchPage(request.query.trim(), request.section, request.cursor) },
        encode = KelpRemoteMethod.SearchPage::encodeResponse,
    )

    private fun handleArtistDetail(request: ArtistRequest): LightResult<String> = handleCatalog(
        operation = { it.artistDetail(request.artist) },
        encode = KelpRemoteMethod.GetArtistDetail::encodeResponse,
    )

    private fun handleArtistReleases(request: ArtistReleasesRequest): LightResult<String> = handleCatalog(
        operation = { it.artistReleases(request.artist, request.section, request.cursor) },
        encode = KelpRemoteMethod.GetArtistReleases::encodeResponse,
    )

    private fun handleArtistTracks(request: ArtistTracksRequest): LightResult<String> = handleCatalog(
        operation = { it.artistTracks(request.artist, request.cursor) },
        encode = KelpRemoteMethod.GetArtistTracks::encodeResponse,
    )

    private fun handleAlbumDetail(request: AlbumRequest): LightResult<String> = handleCatalog(
        operation = { it.albumDetail(request.album) },
        encode = KelpRemoteMethod.GetAlbumDetail::encodeResponse,
    )

    private fun handlePlaylistDetail(request: PlaylistRequest): LightResult<String> = handleCatalog(
        operation = { it.playlistDetail(request.playlist) },
        encode = KelpRemoteMethod.GetPlaylistDetail::encodeResponse,
    )

    private fun handleStartPlayback(request: StartPlaybackRequest): LightResult<String> =
        withPlayer { controller ->
            runCatching { controller.start(request) }.fold(
                onSuccess = { LightResult.Success(KelpRemoteMethod.StartPlayback.encodeResponse(it)) },
                onFailure = {
                    failure(
                        KelpErrorCategory.Protocol,
                        "Choose a valid song to start playback.",
                        LightResult.ErrorCode.InvalidParameters,
                    )
                },
            )
        }

    private fun handlePlayerCommand(request: PlayerCommandRequest): LightResult<String> =
        withPlayer { controller ->
            val snapshot = controller.control(request.command, request.positionMs)
            LightResult.Success(KelpRemoteMethod.ControlPlayback.encodeResponse(snapshot))
        }

    private fun withPlayer(
        block: (KelpPlayerController) -> LightResult<String>,
    ): LightResult<String> {
        return try {
            runBlocking {
                withTimeout(collectionTimeoutMillis) {
                    KelpPlaybackService.withController(applicationContext, block)
                }
            }
        } catch (_: TimeoutCancellationException) {
            failure(KelpErrorCategory.Timeout, "The player took too long to respond. Please retry.")
        } catch (error: TidalCatalogException) {
            failure(error.category, error.safeMessage)
        } catch (error: Exception) {
            logger.warning("TIDAL player request failed: ${error.javaClass.simpleName}")
            failure(KelpErrorCategory.Unavailable, "The TIDAL player could not complete that action.")
        }
    }

    private fun <T> handleCatalog(
        operation: suspend (Catalog) -> T,
        encode: (T) -> String,
    ): LightResult<String> {
        val cat = currentCatalog() ?: return failure(
            KelpErrorCategory.Unavailable,
            "The TIDAL catalog is not available yet.",
        )
        return try {
            val result = runBlocking {
                withTimeout(collectionTimeoutMillis) {
                    operation(cat)
                }
            }
            LightResult.Success(encode(result))
        } catch (_: TimeoutCancellationException) {
            logger.log(Level.WARNING, "TIDAL catalog request timed out")
            failure(KelpErrorCategory.Timeout, "Loading from TIDAL timed out. Please retry.")
        } catch (error: TidalCatalogException) {
            logger.warning(
                "TIDAL catalog request failed: category=${error.category}, status=${error.statusCode ?: "none"}",
            )
            val lightCode = if (error.category == KelpErrorCategory.Authentication) {
                LightResult.ErrorCode.NoPermission
            } else {
                LightResult.ErrorCode.Unknown
            }
            failure(error.category, error.safeMessage, lightCode)
        } catch (error: SerializationException) {
            logger.warning(
                "TIDAL catalog response could not be decoded: ${error.message ?: error.javaClass.simpleName}",
            )
            failure(
                KelpErrorCategory.Protocol,
                "TIDAL returned an incompatible catalog response. Please retry.",
            )
        } catch (_: IOException) {
            logger.log(Level.WARNING, "TIDAL catalog network request failed")
            failure(KelpErrorCategory.Network, "Could not reach TIDAL. Check your connection and retry.")
        }
    }

    private fun currentCatalog(): Catalog? {
        catalog?.let { return it }
        val provider = KelpRuntime.tidalAuth()?.credentialsProvider ?: return null
        return synchronized(catalogLock) {
            catalog ?: TidalCatalog(provider).also { catalog = it }
        }
    }

    internal suspend fun similarTracks(track: com.loosewire.kelp.protocol.TrackSummary) =
        currentCatalog()?.similarTracks(track).orEmpty()

    private fun failure(
        category: KelpErrorCategory,
        message: String,
        code: LightResult.ErrorCode = LightResult.ErrorCode.Unknown,
    ): LightResult.Error = LightResult.Error(code, KelpError(category, message).encode())

    internal var collectionTimeoutMillis = 10_000L
        private set

    internal fun setCollectionTimeoutForTests(timeoutMillis: Long) {
        collectionTimeoutMillis = timeoutMillis
    }
}
