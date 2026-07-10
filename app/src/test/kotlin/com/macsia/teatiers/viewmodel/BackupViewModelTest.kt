package com.macsia.teatiers.viewmodel

import android.net.Uri
import com.macsia.teatiers.data.backup.BackupManager
import com.macsia.teatiers.data.backup.BackupResult
import com.macsia.teatiers.data.repository.TeaEnrichmentManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Regression coverage for the UX2-P1-3 re-entrancy guard (mirrors AddTeaViewModel's `_isSaving`). */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val backupManager = mockk<BackupManager>()
    private val enrichmentManager = mockk<TeaEnrichmentManager>(relaxed = true)
    private val uri = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { backupManager.hasSafetyBackup() } returns false
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `importFrom ignores a second call while the first is in flight`() = runTest {
        // A double-tap (or a recomposition race) must not interleave two destructive replace-all
        // imports. Hold the first import open on a gate so the second call arrives mid-flight.
        val gate = CompletableDeferred<Unit>()
        coEvery { backupManager.importFrom(any()) } coAnswers {
            gate.await()
            BackupResult.Imported(teaCount = 1)
        }
        val viewModel = BackupViewModel(backupManager, enrichmentManager)

        viewModel.importFrom(uri)
        assertTrue(viewModel.busy.value)
        viewModel.importFrom(uri) // guard: busy is true, so this returns without launching
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { backupManager.importFrom(any()) }
    }

    @Test
    fun `the busy guard is shared across operations, not per-method`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { backupManager.importFrom(any()) } coAnswers {
            gate.await()
            BackupResult.Imported(teaCount = 1)
        }
        val viewModel = BackupViewModel(backupManager, enrichmentManager)

        viewModel.importFrom(uri)
        assertTrue(viewModel.busy.value)
        viewModel.share() // a different op must also be blocked while import is in flight
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 0) { backupManager.createShareUri() }
    }

    @Test
    fun `a successful import force-resumes enrichment of the restored teas (UX3-P2-17)`() = runTest {
        coEvery { backupManager.importFrom(any()) } returns BackupResult.Imported(teaCount = 3)
        val viewModel = BackupViewModel(backupManager, enrichmentManager)

        viewModel.importFrom(uri)
        advanceUntilIdle()

        // A restore swaps in a different tea set, so its queued teas must resume past the cooldown.
        verify(exactly = 1) { enrichmentManager.resumePending(force = true) }
    }

    @Test
    fun `a rejected import leaves enrichment untouched`() = runTest {
        coEvery { backupManager.importFrom(any()) } returns BackupResult.InvalidFile
        val viewModel = BackupViewModel(backupManager, enrichmentManager)

        viewModel.importFrom(uri)
        advanceUntilIdle()

        verify(exactly = 0) { enrichmentManager.resumePending(any()) }
    }

    @Test
    fun `undoing a restore also force-resumes enrichment of the reinstated teas (UX3-P2-17)`() = runTest {
        coEvery { backupManager.restoreSafetyBackup() } returns BackupResult.Imported(teaCount = 2)
        val viewModel = BackupViewModel(backupManager, enrichmentManager)

        viewModel.restoreSafetyBackup()
        advanceUntilIdle()

        verify(exactly = 1) { enrichmentManager.resumePending(force = true) }
    }
}
