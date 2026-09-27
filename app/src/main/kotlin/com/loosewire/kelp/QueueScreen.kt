package com.loosewire.kelp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.loosewire.kelp.protocol.PlaybackSnapshot
import com.loosewire.kelp.protocol.PlayerCommand
import com.loosewire.kelp.protocol.RepeatMode
import com.loosewire.kelp.protocol.KelpError
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val kelpClient: KelpClient = BinderTideClient,
) : LightViewModel<Unit>() {
    data class UiState(
        val playback: PlaybackSnapshot? = null,
        val loading: Boolean = true,
        val error: KelpError? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var playbackRefreshJob: Job? = null
    private var seekJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        playbackRefreshJob?.cancel()
        playbackRefreshJob = viewModelScope.launch {
            while (isActive) {
                refreshPlayback()
                delay(PlaybackRefreshMillis)
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        playbackRefreshJob?.cancel()
        playbackRefreshJob = null
        seekJob?.cancel()
        seekJob = null
        super.onScreenHide(screen)
    }

    fun refresh() {
        viewModelScope.launch { refreshPlayback() }
    }

    private suspend fun refreshPlayback() {
        when (val result = kelpClient.playback()) {
            is KelpClientResult.Success -> _state.value = UiState(
                playback = result.data,
                loading = false,
                error = result.data.error,
            )
            is KelpClientResult.Failure -> _state.value = _state.value.copy(
                loading = false,
                error = result.error,
            )
        }
    }

    fun control(command: PlayerCommand) {
        viewModelScope.launch {
            when (val result = kelpClient.controlPlayback(command)) {
                is KelpClientResult.Success -> _state.value = UiState(
                    playback = result.data,
                    loading = false,
                    error = result.data.error,
                )
                is KelpClientResult.Failure -> _state.value = _state.value.copy(error = result.error)
            }
        }
    }

    fun seekTo(fraction: Float) {
        val playback = _state.value.playback ?: return
        val durationMs = playback.durationMs
        if (durationMs <= 0L) return

        val positionMs = (durationMs * fraction.coerceIn(0f, 1f)).toLong()
        _state.value = _state.value.copy(
            playback = playback.copy(positionMs = positionMs),
            error = null,
        )
        seekJob?.cancel()
        seekJob = viewModelScope.launch {
            delay(SeekDebounceMillis)
            when (val result = kelpClient.seekPlayback(positionMs)) {
                is KelpClientResult.Success -> _state.value = UiState(
                    playback = result.data,
                    loading = false,
                    error = result.data.error,
                )
                is KelpClientResult.Failure -> _state.value = _state.value.copy(error = result.error)
            }
        }
    }

    private companion object {
        const val PlaybackRefreshMillis = 1_000L
        const val SeekDebounceMillis = 120L
    }
}

class QueueScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PlayerViewModel>(sealedActivity) {
    override val viewModelClass: Class<PlayerViewModel>
        get() = PlayerViewModel::class.java

    override fun createViewModel(): PlayerViewModel = PlayerViewModel()

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text("Now Playing"),
                    rightButton = if (state.playback?.current != null) {
                        LightBarButton.LightIcon(
                            icon = LightIcons.LIST,
                            onClick = { navigateTo(::UpNextScreen) },
                            contentDescription = "Up next",
                        )
                    } else {
                        null
                    },
                )
                when {
                    state.loading -> Message("Loading…")
                    state.error != null && state.playback == null -> Message(state.error!!.message)
                    state.playback?.current == null -> Message(
                        "Nothing is playing.\n\nChoose a song, album, or playlist to begin.",
                    )
                    else -> PlayerContent(state.playback!!, state.error)
                }
            }
        }
    }

    @Composable
    private fun PlayerContent(playback: PlaybackSnapshot, error: KelpError?) {
        val track = playback.current ?: return
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LightText(
                        text = track.title,
                        variant = LightTextVariant.Subheading,
                        align = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LightText(
                        text = track.artistName,
                        variant = LightTextVariant.Fine,
                        align = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    track.albumTitle?.let {
                        LightText(
                            text = it,
                            variant = LightTextVariant.Fine,
                            lighten = true,
                            align = TextAlign.Center,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    playback.sourceName?.let {
                        LightText(
                            text = "From $it",
                            variant = LightTextVariant.Superfine,
                            lighten = true,
                            align = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 0.5f.gridUnitsAsDp()),
                        )
                    }
                    when {
                        error != null -> LightText(
                            text = error.message,
                            variant = LightTextVariant.Detail,
                            align = TextAlign.Center,
                        )
                        playback.isLoading -> LightText(
                            text = "Loading audio…",
                            variant = LightTextVariant.Detail,
                            align = TextAlign.Center,
                        )
                    }

                    val durationMs = playback.durationMs.coerceAtLeast(0L)
                    val positionMs = playback.positionMs.coerceIn(0L, durationMs)
                    val progress = if (durationMs > 0L) {
                        positionMs.toFloat() / durationMs.toFloat()
                    } else {
                        0f
                    }
                    LightTouchableProgressBar(
                        colors = LightThemeTokens.colors,
                        progress = progress,
                        onValueChange = viewModel::seekTo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        LightText(
                            text = formatDuration(positionMs),
                            variant = LightTextVariant.Superfine,
                            lighten = true,
                        )
                        LightText(
                            text = formatDuration(durationMs),
                            variant = LightTextVariant.Superfine,
                            lighten = true,
                        )
                    }

                    Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                    LightText(
                        text = buildString {
                            append(if (playback.shuffle) "Shuffle on" else "Shuffle off")
                            append("  ·  ")
                            append(playback.repeatMode.description)
                        },
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 0.5f.gridUnitsAsDp()),
                    )
                }
            }
            KelpActionBar {
                KelpActionIcon(
                    icon = LightIcons.SHUFFLE,
                    contentDescription = if (playback.shuffle) "Shuffle on" else "Shuffle off",
                    onClick = { viewModel.control(PlayerCommand.ToggleShuffle) },
                )
                KelpActionIcon(
                    icon = LightIcons.REWIND,
                    contentDescription = "Previous",
                    onClick = { viewModel.control(PlayerCommand.Previous) },
                )
                KelpActionIcon(
                    icon = if (playback.playWhenReady) LightIcons.PAUSE else LightIcons.PLAY,
                    contentDescription = if (playback.playWhenReady) "Pause" else "Play",
                    onClick = { viewModel.control(PlayerCommand.TogglePlayPause) },
                )
                KelpActionIcon(
                    icon = LightIcons.FAST_FORWARD,
                    contentDescription = "Next",
                    onClick = { viewModel.control(PlayerCommand.Next) },
                )
                KelpActionIcon(
                    icon = LightIcons.LOOP,
                    contentDescription = playback.repeatMode.description,
                    onClick = { viewModel.control(PlayerCommand.CycleRepeat) },
                )
            }
        }
    }

    @Composable
    private fun Message(message: String) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
    }

    private val RepeatMode.description: String
        get() = when (this) {
            RepeatMode.Off -> "Repeat off"
            RepeatMode.All -> "Repeat all"
            RepeatMode.One -> "Repeat one"
        }

}

class UpNextScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PlayerViewModel>(sealedActivity) {
    override val viewModelClass: Class<PlayerViewModel>
        get() = PlayerViewModel::class.java

    override fun createViewModel(): PlayerViewModel = PlayerViewModel()

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val upcoming = state.playback
            ?.let { it.queue.drop(it.currentIndex + 1) }
            .orEmpty()

        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text("Up Next"),
                )
                when {
                    state.loading -> UpNextMessage("Loading…")
                    state.error != null && state.playback == null ->
                        UpNextMessage(state.error!!.message)
                    upcoming.isEmpty() -> UpNextMessage("Nothing else is queued.")
                    else -> LightScrollView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 1f.gridUnitsAsDp()),
                    ) {
                        upcoming.forEach { TrackRow(it) }
                    }
                }
            }
        }
    }

    @Composable
    private fun UpNextMessage(message: String) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
    }
}
