package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.CatalogRefEntity
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaSampleEntity
import com.macsia.teatiers.di.AppScope
import com.macsia.teatiers.domain.model.CatalogLocale
import com.macsia.teatiers.domain.model.CatalogTeaDetail
import com.macsia.teatiers.domain.model.EnrichmentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    // Teas with enrichment in flight right now. enrich()/retry()/resumePending() can all target the same
    // tea; this drops the redundant concurrent dispatch (and the wasted resolve call / daily-budget token)
    // without blocking a later legitimate retry.
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    // resumePending() is wired to both the boards-list and per-board screens, so it would otherwise re-sweep
    // every PENDING/QUEUED tea on every board-open — burning a metered /resolve token each time a tea is
    // stuck PENDING (AND-P1-7). Gate it to run at most ONCE per process (true app-launch semantics).
    private val resumed = AtomicBoolean(false)

    /** Fire-and-forget enrichment of a just-added tea ([name] = its typed primary name). */
    fun enrich(teaId: String, name: String, sourceText: String? = null) {
        scope.launch { runEnrichment(teaId, name, sourceText) }
    }

    /** User-driven retry of a FAILED tea: re-resolves with the row's current typed name. */
    fun retry(teaId: String) {
        scope.launch {
            val row = dao.loadTeaRow(teaId) ?: return@launch
            runEnrichment(teaId, row.resolveName() ?: return@launch, sourceText = null)
        }
    }

    /**
     * Re-dispatch teas left PENDING/QUEUED by a prior run (process death / offline). Safe to call from
     * several screens — it runs at most once per process (AND-P1-7), so opening a board can't re-burn a
     * /resolve token on a tea that's stuck PENDING. A user-driven [retry] is the per-tea escape hatch.
     */
    fun resumePending() {
        if (!resumed.compareAndSet(false, true)) return
        scope.launch {
            dao.teasNeedingEnrichment().forEach { row ->
                runEnrichment(row.id, row.resolveName() ?: return@forEach, sourceText = null)
            }
        }
    }

    /**
     * The name to re-resolve a sample by — its first non-blank locale name (P1-2: `nameRu` may be
     * null). Null only for a nameless row (shouldn't occur; the add/edit form enforces ≥1 name).
     */
    private fun TeaSampleEntity.resolveName(): String? =
        listOfNotNull(nameRu, nameEn, pinyin, nameZh).firstOrNull { it.isNotBlank() }

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

    /**
     * Merges the resolved [detail] into the local row (catalog wins only where the user is blank, #21).
     * The load-merge-write is a single Room @Transaction (review 2026-06-19) so a concurrent user edit
     * landing between the read and the write can't be clobbered. Since v7 (#132) `catalogTeaId` is no
     * longer UNIQUE, so every sample resolving to a ref links to it (the DAO stubs the `catalog_refs`
     * row first so the FK holds); two differently-scripted duplicates may both link to the same ref.
     */
    private suspend fun applyPatch(teaId: String, detail: CatalogTeaDetail) {
        dao.applyEnrichmentPatch(
            teaId = teaId,
            candidateNameRu = detail.nameRu,
            candidateNameZh = detail.nameZh,
            candidatePinyin = detail.pinyin,
            candidateNameEn = detail.nameEn,
            type = detail.type.name,
            candidateOrigin = detail.origin,
            candidateShortBlurb = detail.descriptionFor(CatalogLocale.RU)?.short,
            ref = detail.toRefEntity(),
            state = EnrichmentState.DONE.name,
        )
    }

    /** The cached catalog-ref facts to write alongside the link (the catalog-refresh writer payload). */
    private fun CatalogTeaDetail.toRefEntity(): CatalogRefEntity = CatalogRefEntity(
        id = id,
        type = type.name,
        originCountry = originCountry,
        region = region,
        cultivar = cultivar,
        oxidationMin = oxidationMin,
        oxidationMax = oxidationMax,
        brand = brand,
        verificationStatus = provenance.verificationStatus,
        confidence = provenance.confidence?.toDouble(),
        enrichmentState = enrichmentState?.name,
        shortBlurb = descriptionFor(CatalogLocale.RU)?.short,
        source = provenance.source,
        sourceUrl = provenance.sourceUrl,
        license = provenance.license,
        fetchedAtEpochMs = System.currentTimeMillis(),
    )
}
