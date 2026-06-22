package com.macsia.teatiers.service

/**
 * The import-run lifecycle (decision #137-C4 / #139-R3 / 2026-06-22 refresh ING-P0-1). A run is the unit of
 * apply-authorization: scraped facts may be written to the canonical catalog ONLY from a run that has been
 * sealed (ingestion stopped) and fully reviewed (every decision resolved) -- i.e. in [REVIEWED] or
 * [APPLYING]. A generic "the runner finished" marker is NOT enough; removing that ambiguity (the old
 * 'succeeded') is the point of this machine.
 *
 *   created -> preflight_allowed -> ingesting -> awaiting_review -> reviewed -> applying -> applied
 *                              \________________ blocked / failed (terminal) ________________/
 *
 * [CREATED] is a legal initial state for a future async preflight; today [CatalogImportService.startRun]
 * validates ToS/robots/host synchronously and persists the run directly at [PREFLIGHT_ALLOWED] (a failed
 * preflight throws and persists nothing). Terminal states ([APPLIED]/[FAILED]/[BLOCKED]) are immutable --
 * no transition leaves them.
 */
enum class ImportRunState(val code: String, val terminal: Boolean) {
    CREATED("created", false),
    PREFLIGHT_ALLOWED("preflight_allowed", false),
    INGESTING("ingesting", false),
    AWAITING_REVIEW("awaiting_review", false),
    REVIEWED("reviewed", false),
    APPLYING("applying", false),
    APPLIED("applied", true),
    FAILED("failed", true),
    BLOCKED("blocked", true);

    fun canTransitionTo(to: ImportRunState): Boolean = to in TRANSITIONS.getValue(this)

    companion object {
        /** Legal forward transitions; every non-terminal state may also be aborted to failed/blocked. */
        private val TRANSITIONS: Map<ImportRunState, Set<ImportRunState>> = mapOf(
            CREATED to setOf(PREFLIGHT_ALLOWED, BLOCKED, FAILED),
            // An empty run (preflight passed, nothing staged) may still be sealed straight to review.
            PREFLIGHT_ALLOWED to setOf(INGESTING, AWAITING_REVIEW, BLOCKED, FAILED),
            INGESTING to setOf(AWAITING_REVIEW, BLOCKED, FAILED),
            AWAITING_REVIEW to setOf(REVIEWED, BLOCKED, FAILED),
            REVIEWED to setOf(APPLYING, BLOCKED, FAILED),
            APPLYING to setOf(APPLIED, FAILED),
            APPLIED to emptySet(),
            FAILED to emptySet(),
            BLOCKED to emptySet(),
        )

        /** Non-terminal status codes -- a source may have at most one run in any of these (one active run). */
        val ACTIVE_CODES: List<String> = entries.filter { !it.terminal }.map { it.code }

        fun of(code: String): ImportRunState = entries.firstOrNull { it.code == code }
            ?: throw RunStateException("unknown import run status '$code'")
    }
}
