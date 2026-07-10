package com.macsia.teatiers.domain.model

/**
 * Background catalog-enrichment lifecycle of a user-tea (plan §6, decisions.md #21/#28). A
 * freshly-added tea is optimistic: it lands on the board at once, then `/resolve` patches in
 * ru/zh names + metadata when it returns (or stays as typed if offline).
 *
 * - [NONE]   custom/seed tea that is not catalog-enriched (the default).
 * - [PENDING] a resolve/enrich call is in flight; the card shows an "enriching" hint.
 * - [QUEUED]  offline when the call was attempted; it re-runs on the next launch (reconnect).
 * - [RATE_LIMITED] the server-side edge/LLM-budget limiter was hit, not a connectivity issue; it
 *   re-runs the same way as [QUEUED] but the card says so instead of falsely claiming "no network".
 * - [DONE]    resolved — names/metadata were patched in (or there was nothing to add).
 * - [FAILED]  the call errored; the card offers a manual retry so a tea is never stuck loading.
 */
enum class EnrichmentState { NONE, PENDING, QUEUED, RATE_LIMITED, DONE, FAILED }

/**
 * States a user can re-drive with a manual retry: an outright [FAILED], or a deferred resolve left
 * [QUEUED] (offline) / [RATE_LIMITED] (server busy). Drives the retry affordance on every surface that
 * shows a tea (board card, detail) so a stuck tea is never a dead end (UX3-P1-2/P1-4). [PENDING] is
 * already in flight; [NONE]/[DONE] need nothing.
 */
val EnrichmentState.isRetryable: Boolean
    get() = this == EnrichmentState.FAILED || this == EnrichmentState.QUEUED || this == EnrichmentState.RATE_LIMITED
