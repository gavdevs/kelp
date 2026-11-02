package com.loosewire.kelp

import com.loosewire.kelp.protocol.AuthSnapshot
import com.loosewire.kelp.protocol.AuthState
import com.loosewire.kelp.protocol.KelpError
import com.loosewire.kelp.protocol.KelpErrorCategory
import com.loosewire.kelp.protocol.Page
import com.loosewire.kelp.protocol.ReleaseSummary
import com.loosewire.kelp.protocol.ServerActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
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
    fun signOutFailureIsVisibleAndRetryDoesNotDuplicatePendingRequest() = runTest(dispatcher) {
        val pending = CompletableDeferred<KelpClientResult<AuthSnapshot>>()
        val failure = KelpError(KelpErrorCategory.Network, "Could not sign out.")
        var calls = 0
        val client = object : KelpClient {
            override suspend fun logout(): KelpClientResult<AuthSnapshot> {
                calls += 1
                return if (calls == 1) pending.await()
                else KelpClientResult.Success(AuthSnapshot(AuthState.Unauthenticated))
            }

            override suspend fun authSnapshot(): KelpClientResult<AuthSnapshot> = error("Not used")
            override suspend fun loginActivity(): KelpClientResult<ServerActivity> = error("Not used")
            override suspend fun collection(cursor: String?): KelpClientResult<Page<ReleaseSummary>> = error("Not used")
        }
        val viewModel = SettingsViewModel(UnusedSettingsPreferences, client)

        viewModel.signOut()
        viewModel.signOut()
        runCurrent()
        assertEquals(1, calls)
        assertTrue(viewModel.signingOut.value)
        assertFalse(viewModel.signedOut.value)

        pending.complete(KelpClientResult.Failure(failure))
        runCurrent()
        assertEquals(failure, viewModel.signOutError.value)
        assertFalse(viewModel.signingOut.value)
        assertFalse(viewModel.signedOut.value)

        viewModel.signOut()
        assertNull(viewModel.signOutError.value)
        runCurrent()
        assertTrue(viewModel.signedOut.value)
        assertFalse(viewModel.signingOut.value)
        viewModel.signOut()
        runCurrent()
        assertEquals(2, calls)
    }
}

private object UnusedSettingsPreferences : KelpPreferences {
    override val playback = flowOf(KelpPlaybackPreferences())
    override suspend fun setContinuousPlayback(enabled: Boolean): Unit = error("Not used")
}
