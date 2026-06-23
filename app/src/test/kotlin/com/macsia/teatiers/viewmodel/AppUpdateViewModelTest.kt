package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.data.remote.dto.AppManifestDto
import com.macsia.teatiers.data.update.ApkDownloader
import com.macsia.teatiers.data.update.ApkVerification
import com.macsia.teatiers.data.update.ApkVerifier
import com.macsia.teatiers.data.update.AppInstaller
import com.macsia.teatiers.data.update.AppUpdateChecker
import com.macsia.teatiers.data.update.UpdateAvailability
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {

    private val checker = mockk<AppUpdateChecker>()
    private val downloader = mockk<ApkDownloader>()
    private val verifier = mockk<ApkVerifier>()
    private val installer = mockk<AppInstaller>()

    private val manifest = AppManifestDto(
        latestVersionCode = 5,
        latestVersionName = "0.2.0",
        apkUrl = "https://example.test/app.apk",
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = AppUpdateViewModel(checker, downloader, verifier, installer)

    @Test
    fun `check surfaces an optional update`() = runTest {
        coEvery { checker.check(any(), any()) } returns UpdateAvailability.Optional(manifest)
        val viewModel = vm()

        viewModel.check()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertInstanceOf(UpdateUiState.Available::class.java, state)
        assertEquals(manifest, (state as UpdateUiState.Available).manifest)
        assertFalse(state.forced)
    }

    @Test
    fun `check surfaces a forced update`() = runTest {
        coEvery { checker.check(any(), any()) } returns UpdateAvailability.Forced(manifest)
        val viewModel = vm()

        viewModel.check()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertInstanceOf(UpdateUiState.Available::class.java, state)
        assertEquals(true, (state as UpdateUiState.Available).forced)
    }

    @Test
    fun `check with no update stays idle`() = runTest {
        coEvery { checker.check(any(), any()) } returns UpdateAvailability.None
        val viewModel = vm()

        viewModel.check()
        advanceUntilIdle()

        assertEquals(UpdateUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `install verifies then installs and clears state on success`() = runTest {
        val apk = File.createTempFile("update", ".apk").apply { writeText("x") }
        coEvery { checker.check(any(), any()) } returns UpdateAvailability.Optional(manifest)
        coEvery { downloader.download(manifest) } returns apk
        every { verifier.verify(apk, manifest, any()) } returns ApkVerification.Ok
        coEvery { installer.install(apk) } returns AppInstaller.Outcome.Success
        val viewModel = vm()

        viewModel.check()
        advanceUntilIdle()
        viewModel.installUpdate()
        advanceUntilIdle()

        assertEquals(UpdateUiState.Idle, viewModel.state.value)
        coVerify(exactly = 1) { installer.install(apk) }
        assertFalse(apk.exists()) // verified blob is cleaned up
    }

    @Test
    fun `install fails when the download fails`() = runTest {
        coEvery { checker.check(any(), any()) } returns UpdateAvailability.Optional(manifest)
        coEvery { downloader.download(manifest) } returns null
        val viewModel = vm()

        viewModel.check()
        advanceUntilIdle()
        viewModel.installUpdate()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertInstanceOf(UpdateUiState.Failed::class.java, state)
        assertEquals("download", (state as UpdateUiState.Failed).reason)
        coVerify(exactly = 0) { installer.install(any()) }
    }

    @Test
    fun `install is refused and the apk deleted when verification rejects`() = runTest {
        val apk = File.createTempFile("update", ".apk").apply { writeText("x") }
        coEvery { checker.check(any(), any()) } returns UpdateAvailability.Optional(manifest)
        coEvery { downloader.download(manifest) } returns apk
        every { verifier.verify(apk, manifest, any()) } returns ApkVerification.Rejected("sha256 mismatch")
        val viewModel = vm()

        viewModel.check()
        advanceUntilIdle()
        viewModel.installUpdate()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertInstanceOf(UpdateUiState.Failed::class.java, state)
        assertEquals("verify:sha256 mismatch", (state as UpdateUiState.Failed).reason)
        coVerify(exactly = 0) { installer.install(any()) }
        assertFalse(apk.exists()) // a rejected APK must never linger
    }

    @Test
    fun `install surfaces an installer failure`() = runTest {
        val apk = File.createTempFile("update", ".apk").apply { writeText("x") }
        coEvery { checker.check(any(), any()) } returns UpdateAvailability.Optional(manifest)
        coEvery { downloader.download(manifest) } returns apk
        every { verifier.verify(apk, manifest, any()) } returns ApkVerification.Ok
        coEvery { installer.install(apk) } returns AppInstaller.Outcome.Failed("declined")
        val viewModel = vm()

        viewModel.check()
        advanceUntilIdle()
        viewModel.installUpdate()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertInstanceOf(UpdateUiState.Failed::class.java, state)
        assertEquals("install:declined", (state as UpdateUiState.Failed).reason)
        // AND-P2-3: cleaned up even when the install fails. The download→install body is now wrapped in
        // try/finally, so the verified blob is also deleted on the install-throws / coroutine-cancelled
        // paths that the old post-`when` delete skipped (same finally; exercised here + on the success and
        // verify-reject `return@launch` paths above).
        assertFalse(apk.exists())
    }

    @Test
    fun `dismiss clears an optional update but keeps a forced one`() = runTest {
        coEvery { checker.check(any(), any()) } returns UpdateAvailability.Forced(manifest)
        val viewModel = vm()
        viewModel.check()
        advanceUntilIdle()

        viewModel.dismiss()
        assertInstanceOf(UpdateUiState.Available::class.java, viewModel.state.value) // forced survives

        coEvery { checker.check(any(), any()) } returns UpdateAvailability.Optional(manifest)
        viewModel.check()
        advanceUntilIdle()
        viewModel.dismiss()
        assertEquals(UpdateUiState.Idle, viewModel.state.value)
    }
}
