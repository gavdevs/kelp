package com.loosewire.kelp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.loosewire.kelp.protocol.ArtistSummary
import com.loosewire.kelp.protocol.ReleaseSummary
import com.loosewire.kelp.protocol.ReleaseType
import com.loosewire.kelp.protocol.PlaylistSummary
import com.loosewire.kelp.protocol.TrackSummary
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

@Composable
internal fun CatalogSectionLabel(text: String) {
    LightText(
        text = text.uppercase(),
        variant = LightTextVariant.Superfine,
        lighten = true,
        modifier = Modifier.padding(
            top = 1f.gridUnitsAsDp(),
            bottom = 0.4f.gridUnitsAsDp(),
        ),
    )
}

@Composable
internal fun ArtistRow(artist: ArtistSummary, onClick: () -> Unit) {
    CatalogRow(
        title = artist.name,
        detail = "Artist",
        onClick = onClick,
    )
}

@Composable
internal fun ReleaseRow(release: ReleaseSummary, onClick: (() -> Unit)? = null) {
    CatalogRow(
        title = release.title,
        detail = "${release.artistName} · ${release.type.label}",
        onClick = onClick,
    )
}

@Composable
internal fun TrackRow(track: TrackSummary, onClick: (() -> Unit)? = null) {
    val explicitMarker = if (track.explicit) " · E" else ""
    CatalogRow(
        title = track.title,
        detail = "${track.artistName} · ${formatDuration(track.durationMs)}$explicitMarker",
        onClick = onClick,
    )
}

@Composable
internal fun PlaylistRow(
    playlist: PlaylistSummary,
    showSongCount: Boolean = true,
    onClick: () -> Unit,
) {
    val count = if (playlist.itemCount == 1) "1 song" else "${playlist.itemCount} songs"
    CatalogRow(
        title = playlist.name,
        detail = if (showSongCount) count else playlist.description?.takeIf(String::isNotBlank),
        detailMaxLines = if (showSongCount) 1 else 2,
        onClick = onClick,
    )
}

@Composable
private fun CatalogRow(
    title: String,
    detail: String?,
    detailMaxLines: Int = 1,
    onClick: (() -> Unit)?,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .let { modifier ->
            if (onClick == null) modifier else modifier.lightClickable(onClick = onClick)
        }
        .padding(
            end = 1f.gridUnitsAsDp(),
            top = 0.55f.gridUnitsAsDp(),
            bottom = 0.55f.gridUnitsAsDp(),
        )

    Column(modifier = rowModifier) {
        LightText(
            text = title,
            variant = LightTextVariant.Subheading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        detail?.let {
            LightText(
                text = it,
                variant = LightTextVariant.Fine,
                lighten = true,
                maxLines = detailMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal val ReleaseType.label: String
    get() = when (this) {
        ReleaseType.Album -> "Album"
        ReleaseType.Ep -> "EP"
        ReleaseType.Single -> "Single"
    }

internal fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
