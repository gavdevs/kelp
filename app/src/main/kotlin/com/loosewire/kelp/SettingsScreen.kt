package com.loosewire.kelp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.loosewire.kelp.protocol.KelpError
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferences: KelpPreferences,
    private val kelpClient: KelpClient = BinderTideClient,
) : LightViewModel<Unit>() {
    val playback = preferences.playback.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = KelpPlaybackPreferences(),
    )

    private val _signedOut = MutableStateFlow(false)
    val signedOut = _signedOut.asStateFlow()
    private val _signingOut = MutableStateFlow(false)
    val signingOut = _signingOut.asStateFlow()
    private val _signOutError = MutableStateFlow<KelpError?>(null)
    val signOutError = _signOutError.asStateFlow()

    fun signOut() {
        if (_signedOut.value || _signingOut.value) return
        _signingOut.value = true
        _signOutError.value = null
        viewModelScope.launch {
            try {
                when (val result = kelpClient.logout()) {
                    is KelpClientResult.Success -> _signedOut.value = true
                    is KelpClientResult.Failure -> _signOutError.value = result.error
                }
            } finally {
                _signingOut.value = false
            }
        }
    }

    fun toggleContinuousPlayback() {
        viewModelScope.launch {
            preferences.setContinuousPlayback(!playback.value.continuousPlayback)
        }
    }
}

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel = SettingsViewModel(
        DataStoreTidePreferences(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val playback by viewModel.playback.collectAsState()
        val signedOut by viewModel.signedOut.collectAsState()
        val signingOut by viewModel.signingOut.collectAsState()
        val signOutError by viewModel.signOutError.collectAsState()

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
                    center = LightTopBarCenter.Text("Settings"),
                )
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    SectionLabel("PLAYBACK")
                    ReadOnlySettingRow(
                        label = "Streaming quality",
                        value = "Lossless preferred · lower quality when unavailable",
                    )

                    Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                    SectionLabel("BEHAVIOR")
                    SettingRow(
                        label = "Continuous playback",
                        value = if (playback.continuousPlayback) {
                            "On · Keep playing similar songs"
                        } else {
                            "Off · Stop at the end of the queue"
                        },
                        onClick = viewModel::toggleContinuousPlayback,
                    )
                    ReadOnlySettingRow(
                        label = "Background playback",
                        value = "Continue outside Kelp · controls on LightOS Home",
                    )
                    ReadOnlySettingRow(
                        label = "Theme",
                        value = "Follow LightOS",
                    )

                    Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                    SectionLabel("ACCOUNT")
                    if (signedOut) {
                        ReadOnlySettingRow(label = "TIDAL", value = "Signed out")
                    } else if (signingOut) {
                        ReadOnlySettingRow(label = "TIDAL", value = "Signing out…")
                    } else {
                        SettingRow(
                            label = "TIDAL",
                            value = if (signOutError == null) "Tap to sign out" else "Sign out failed · tap to retry",
                            onClick = viewModel::signOut,
                        )
                        signOutError?.let { error ->
                            LightText(
                                text = error.message,
                                variant = LightTextVariant.Fine,
                                modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
                            )
                        }
                    }
                    ReadOnlySettingRow(label = "Kelp", value = "Experimental · online playback")
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Superfine,
            lighten = true,
            modifier = Modifier.padding(
                top = 1f.gridUnitsAsDp(),
                bottom = 0.25f.gridUnitsAsDp(),
            ),
        )
    }

    @Composable
    private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(
                    end = 1f.gridUnitsAsDp(),
                    top = 0.6f.gridUnitsAsDp(),
                    bottom = 0.6f.gridUnitsAsDp(),
                ),
        ) {
            LightText(text = label, variant = LightTextVariant.Copy)
            LightText(
                text = value,
                variant = LightTextVariant.Fine,
                lighten = true,
            )
        }
    }

    @Composable
    private fun ReadOnlySettingRow(label: String, value: String) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    end = 1f.gridUnitsAsDp(),
                    top = 0.6f.gridUnitsAsDp(),
                    bottom = 0.6f.gridUnitsAsDp(),
                ),
        ) {
            LightText(text = label, variant = LightTextVariant.Copy)
            LightText(
                text = value,
                variant = LightTextVariant.Fine,
                lighten = true,
            )
        }
    }

}
