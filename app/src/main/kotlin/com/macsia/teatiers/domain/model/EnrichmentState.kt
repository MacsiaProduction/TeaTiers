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
