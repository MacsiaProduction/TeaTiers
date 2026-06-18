package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaEntity
import com.macsia.teatiers.di.AppScope
import com.macsia.teatiers.domain.model.CatalogLocale
import com.macsia.teatiers.domain.model.CatalogTeaDetail
import com.macsia.teatiers.domain.model.EnrichmentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optimistic background catalog enrichment (plan §6, decisions.md #21/#28). A freshly-added tea
 * lands on the board instantly; this manager then calls `POST /resolve` off the UI and patches the
 * local row with ru/zh names + metadata when it returns, driving the card's [EnrichmentState]:
 *
 *   PENDING ─ resolve ─▶ Matched ───────────────▶ patch + DONE
 *                     └▶ Enriching ─ poll detail ─▶ DONE  ▶ patch + DONE
 *                     │                          └▶ FAILED ▶ FAILED
 *                     ├▶ Unresolved ─────────────▶ DONE (nothing to add; keep the typed tea)
 *                     ├▶ Offline ────────────────▶ QUEUED (re-runs on the next app-open via resumePending)
 *                     └▶ Error ──────────────────▶ FAILED (user can retry)
 *
 * Enriched names are editable suggestions, never authoritative (#21): the patch only fills a field
 * the user left blank-or-equivalent — a non-blank user value is kept when the catalog has none.
 * Work runs on the app scope so it outlives the add screen; a failure never crashes (fail-closed
 * to FAILED, which the card offers to retry, so a tea is never stuck loading — #28).
 */
@Singleton
class TeaEnrichmentManager @Inject constructor(
    private val dao: TeaDao,
    private val catalog: CatalogRepository,
    @AppScope private val scope: CoroutineScope,
) {

    // Poll cadence/budget while the server enriches a stub. Plain fields (not constructor params)
    // so Hilt still builds the manager; tests shorten the interval to run the loop without waiting.
    internal var pollIntervalMs: Long = 1_500L
    internal var maxPolls: Int = 8

    // Teas with enrichment in flight right now. resumePending() runs on every app-open + board-open,
    // so the same QUEUED/PENDING tea can be swept twice; this drops the redundant concurrent dispatch
    // (and the wasted resolve call / daily-budget token) without blocking a later legitimate retry.
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Fire-and-forget enrichment of a just-added tea ([name] = its typed primary name). */
    fun enrich(teaId: String, name: String, sourceText: String? = null) {
        scope.launch { runEnrichment(teaId, name, sourceText) }
    }

    /** User-driven retry of a FAILED tea: re-resolves with the row's current typed name. */
    fun retry(teaId: String) {
        scope.launch {
            val row = dao.loadTeaRow(teaId) ?: return@launch
            runEnrichment(teaId, row.nameRu, sourceText = null)
        }
    }

    /** Re-dispatch teas left PENDING/QUEUED by a prior run (process death / offline) — call at launch. */
    fun resumePending() {
        scope.launch {
            dao.teasNeedingEnrichment().forEach { row ->
                runEnrichment(row.id, row.nameRu, sourceText = null)
            }
        }
    }

    private suspend fun runEnrichment(teaId: String, name: String, sourceText: String?) {
        if (!inFlight.add(teaId)) return // already enriching this tea — skip the duplicate dispatch
        try {
            dao.updateEnrichmentState(teaId, EnrichmentState.PENDING.name)
            when (val result = catalog.resolve(name = name, locale = null, sourceText = sourceText)) {
                // A MATCHED row may still be a FAILED/PENDING LLM stub the server didn't re-arm (LLM tier
                // off, or the daily budget is spent). Don't force it to DONE — that would hide the failure
                // and drop the retry affordance (second-pass review P0). Patch to DONE only when the row
                // is actually settled (DONE, or null = not LLM-managed, e.g. a Wikidata/cached hit).
                is ResolveResult.Matched -> when (result.detail.enrichmentState) {
                    EnrichmentState.FAILED -> dao.updateEnrichmentState(teaId, EnrichmentState.FAILED.name)
                    EnrichmentState.PENDING -> pollUntilSettled(teaId, result.detail.id)
                    else -> applyPatch(teaId, result.detail)
                }
                is ResolveResult.Enriching -> pollUntilSettled(teaId, result.catalogTeaId)
                // Tried, nothing to add — leave the typed tea as-is and clear the spinner.
                ResolveResult.Unresolved -> dao.updateEnrichmentState(teaId, EnrichmentState.DONE.name)
                ResolveResult.Offline -> dao.updateEnrichmentState(teaId, EnrichmentState.QUEUED.name)
                ResolveResult.Error -> dao.updateEnrichmentState(teaId, EnrichmentState.FAILED.name)
            }
        } catch (_: Exception) {
            // Fail closed: any unexpected error leaves a retryable card, never a stuck spinner.
            runCatching { dao.updateEnrichmentState(teaId, EnrichmentState.FAILED.name) }
        } finally {
            inFlight.remove(teaId)
        }
    }

    private suspend fun pollUntilSettled(localTeaId: String, catalogTeaId: Long) {
        repeat(maxPolls) {
            delay(pollIntervalMs)
            when (val detail = catalog.detail(catalogTeaId)) {
                is CatalogDetailResult.Loaded -> when (detail.detail.enrichmentState) {
                    EnrichmentState.DONE -> {
                        applyPatch(localTeaId, detail.detail)
                        return
                    }
                    EnrichmentState.FAILED -> {
                        dao.updateEnrichmentState(localTeaId, EnrichmentState.FAILED.name)
                        return
                    }
                    // PENDING (or null while the row settles) — keep polling.
                    else -> Unit
                }
                CatalogDetailResult.Offline -> {
                    dao.updateEnrichmentState(localTeaId, EnrichmentState.QUEUED.name)
                    return
                }
                CatalogDetailResult.Error -> {
                    dao.updateEnrichmentState(localTeaId, EnrichmentState.FAILED.name)
                    return
                }
            }
        }
        // Server still working after the budget — mark retryable rather than spin forever.
        dao.updateEnrichmentState(localTeaId, EnrichmentState.FAILED.name)
    }

    /** Merges the resolved [detail] into the local row (catalog wins only where the user is blank). */
    private suspend fun applyPatch(teaId: String, detail: CatalogTeaDetail) {
        val current = dao.loadTeaRow(teaId) ?: return
        // catalogTeaId is UNIQUE (one user-tea per catalog row, by design). The local name-matcher
        // can't unify differently-scripted duplicates (e.g. "Tieguanyin" / "тегуанинь"), so two
        // user-teas can resolve to the SAME catalog id. If another tea already owns this link, writing
        // it here would throw on the UNIQUE index and dead-end this row at FAILED (and every retry would
        // re-throw). Settle this duplicate DONE instead, leaving the existing link untouched.
        val linkOwner = dao.findTeaIdByCatalogId(detail.id)
        if (linkOwner != null && linkOwner != teaId) {
            dao.updateEnrichmentState(teaId, EnrichmentState.DONE.name)
            return
        }
        dao.patchEnrichment(
            teaId = teaId,
            nameRu = detail.nameRu.orBlankFallback(current.nameRu),
            nameZh = detail.nameZh.orKeep(current.nameZh),
            pinyin = detail.pinyin.orKeep(current.pinyin),
            nameEn = detail.nameEn.orKeep(current.nameEn),
            type = detail.type.name,
            origin = detail.origin?.takeIf { it.isNotBlank() } ?: current.origin,
            shortBlurb = detail.descriptionFor(CatalogLocale.RU)?.short.orKeep(current.shortBlurb),
            catalogTeaId = detail.id,
            state = EnrichmentState.DONE.name,
        )
    }

    private fun String?.orKeep(existing: String?): String? =
        this?.takeIf { it.isNotBlank() } ?: existing

    private fun String?.orBlankFallback(existing: String): String =
        this?.takeIf { it.isNotBlank() } ?: existing
}
