package com.macsia.teatiers.service

import com.macsia.teatiers.domain.ImportRun
import com.macsia.teatiers.repository.ImportRunRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The single guarded gateway for every import-run status change (decision #137-C4: "one locked transition
 * service"). It loads the run FOR UPDATE, refuses any transition the [ImportRunState] machine disallows (so
 * a terminal run can never be rewritten and apply can never run from an un-reviewed state), stamps
 * finished_at on terminal states, and runs inside the caller's transaction.
 */
@Service
class ImportRunStateMachine(private val importRunRepository: ImportRunRepository) {

    /** Load the run with a write lock and transition it; rejects a missing or illegal transition. */
    @Transactional
    fun transition(runId: Long, to: ImportRunState): ImportRun {
        val run = importRunRepository.findByIdForUpdate(runId)
            ?: throw RunStateException("import run $runId does not exist")
        return transition(run, to)
    }

    /**
     * Transition an already-loaded (and already write-locked) run -- used by callers that have just locked
     * the row for their own invariant checks (e.g. [CatalogImportService.ingest]) to avoid a second lock.
     */
    @Transactional
    fun transition(run: ImportRun, to: ImportRunState): ImportRun {
        val from = ImportRunState.of(run.status)
        if (!from.canTransitionTo(to)) {
            throw RunStateException("import run ${run.id} cannot transition ${from.code} -> ${to.code}")
        }
        run.status = to.code
        if (to.terminal) run.finishedAt = Instant.now()
        return importRunRepository.save(run)
    }
}
