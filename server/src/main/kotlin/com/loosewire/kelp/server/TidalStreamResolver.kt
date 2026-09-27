package com.loosewire.kelp.server

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.loosewire.kelp.protocol.TrackSummary
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Just-in-time stream resolution against TIDAL's private v1
 * `tracks/{id}/playbackinfopostpaywall` endpoint, following phono's
 * `TidalStreamResolve` (github.com/jonathancaudill/phono).
 *
 * Responses carry a base64 manifest: either BTS (progressive signed URLs)
 * or clear DASH. Clear DASH MPDs use `group="main"` on AdaptationSets, which
 * crashes Media3's DashManifestParser (spec wants unsigned int) — the
 * attribute is stripped before the MPD is handed to ExoPlayer, the same
 * treatment high-kelp and mopidy-tidal give it.
 *
 * All methods block; call from a background thread.
 */
internal class TidalStreamResolver(private val auth: TidalStreamingAuth) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed interface ResolvedStream {
        val audioQuality: String

        data class Progressive(
            val url: String,
            override val audioQuality: String,
        ) : ResolvedStream

        data class ClearDash(
            val mpdXml: String,
            override val audioQuality: String,
        ) : ResolvedStream
    }

    fun resolveMediaItem(context: Context, track: TrackSummary): MediaItem =
        when (val resolved = resolve(track.id)) {
            is ResolvedStream.Progressive -> MediaItem.Builder()
                .setUri(resolved.url)
                .build()
            is ResolvedStream.ClearDash -> {
                val dir = File(context.cacheDir, "tidal_mpd").apply { mkdirs() }
                val file = File.createTempFile("tidal-", ".mpd", dir)
                file.writeText(resolved.mpdXml)
                MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()
            }
        }

    fun resolve(trackId: String): ResolvedStream {
        var lastError: Exception? = null
        for (quality in QUALITY_FALLBACK_LADDER) {
            try {
                val info = playbackInfo(trackId, quality)
                val mime = info.manifestMimeType
                val decoded = String(java.util.Base64.getDecoder().decode(info.manifest.trim()))
                when {
                    mime.contains("bts", ignoreCase = true) -> {
                        val manifest = json.decodeFromString<BtsManifest>(decoded)
                        val encryption = manifest.encryptionType.ifBlank { "NONE" }
                        if (!encryption.equals("NONE", ignoreCase = true)) {
                            lastError = IOException("TIDAL stream encrypted ($encryption) at $quality")
                            continue
                        }
                        val url = manifest.urls.firstOrNull()
                            ?: throw IOException("TIDAL bts manifest had no urls")
                        return ResolvedStream.Progressive(url, info.audioQuality.ifBlank { quality })
                    }
                    mime.contains("dash", ignoreCase = true) -> {
                        if (isWidevineDash(decoded)) {
                            lastError = IOException("TIDAL Widevine DASH at $quality")
                            continue
                        }
                        return ResolvedStream.ClearDash(
                            sanitizeMpd(decoded),
                            info.audioQuality.ifBlank { quality },
                        )
                    }
                    else -> lastError = IOException("TIDAL returned $mime at $quality")
                }
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IOException("TIDAL stream resolution failed")
    }

    private fun playbackInfo(trackId: String, quality: String): PlaybackInfo {
        var refreshRetried = false
        while (true) {
            val url = "https://api.tidal.com/v1/tracks/$trackId/playbackinfopostpaywall"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("audioquality", quality)
                .addQueryParameter("playbackmode", "STREAM")
                .addQueryParameter("assetpresentation", "FULL")
                .addQueryParameter("prefetch", "false")
                .addQueryParameter("countryCode", auth.countryCode())
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${auth.currentBearer()}")
                .header("X-Tidal-Token", auth.clientId)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 && !refreshRetried -> {
                        refreshRetried = true
                        auth.refreshAfterUnauthorized()
                            ?: throw StreamingAuthException("TIDAL playback session expired — sign in again")
                    }
                    !response.isSuccessful -> throw IOException(
                        "TIDAL playbackinfo HTTP ${response.code} at $quality",
                    )
                    else -> return json.decodeFromString(response.body?.string().orEmpty())
                }
            }
        }
    }

    /**
     * Strip non-numeric `group="main"` AdaptationSet attributes Media3 cannot
     * parse, plus inert ContentProtection stubs on clear streams.
     */
    private fun sanitizeMpd(mpdXml: String): String {
        var s = mpdXml
        s = s.replace(Regex("""\s+group="[^"]*""""), "")
        s = s.replace(Regex("""(?is)<ContentProtection\b[^>]*/\s*>"""), "")
        s = s.replace(Regex("""(?is)<ContentProtection\b[^>]*>.*?</ContentProtection>"""), "")
        return s
    }

    private fun isWidevineDash(mpdXml: String): Boolean {
        val lower = mpdXml.lowercase()
        if (!lower.contains("contentprotection")) return false
        if (lower.contains("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")) return true
        if (lower.contains("urn:uuid:edef8ba9")) return true
        return Regex("""default_kid\s*=\s*"[^"]+"""", RegexOption.IGNORE_CASE).containsMatchIn(mpdXml) &&
            lower.contains("cenc:pssh")
    }

    @Serializable
    private data class PlaybackInfo(
        val audioQuality: String = "",
        val manifestMimeType: String = "",
        val manifest: String = "",
    )

    /** BTS manifest (decoded): direct signed URLs, usually FLAC. */
    @Serializable
    private data class BtsManifest(
        val mimeType: String = "",
        val codecs: String = "",
        val encryptionType: String = "NONE",
        val urls: List<String> = emptyList(),
    )

    private companion object {
        /** UA shape used by the official Android client (matches phono/orpheusdl). */
        const val USER_AGENT = "TIDAL_ANDROID/1039 okhttp/4.12.0"

        /** CD-quality FLAC first; fall back before giving up. */
        val QUALITY_FALLBACK_LADDER = listOf("LOSSLESS", "HIGH", "LOW")
    }
}
