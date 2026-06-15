# 04-eu-egress-llm — verifying free LLM tiers reachable via a Germany (EU) egress for tea enrichment

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, …). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

## Context

Project: **TeaTiers** — a personal, local-first tier-list Android app for teas. A single
Linux backend (Kotlin + Spring Boot 3.x + PostgreSQL), **hosted in Russia**, serves a
multilingual (ru/en/zh + pinyin) tea catalog and enriches unknown teas **on demand**
(translate/normalize names, verify facts), then **auto-publishes them flagged
`unverified` with a confidence score**. Enrichment is server-side only.

What changed since `research/03-ai-enrichment/`: that run ruled out Western providers on
**reachability** from Russia. We now have a **Germany (EU) VPN** and will route **only
the enrichment LLM HTTP calls** through it (split-tunnel); Wikidata + YandexGPT stay
direct. So Gemini/DeepSeek/Groq/Mistral are now reachable and back in contention.

Locked decisions relevant here (see `context/decisions.md` #15/#16):
- Plan: **Wikidata → Wikipedia** (free, direct) first; **Google Gemini free tier** (via
  the DE egress) as the **primary LLM**; **YandexGPT Lite** (direct, no VPN) as the
  always-works **fallback**. Tea-name strings carry **no user data**, so egress to
  Google / China / EU / US is all acceptable.
- The recurring trap: a provider's terms must **permit storing model output in our DB
  and re-serving it** (this already disqualified Yandex *Maps* and had to be confirmed
  for YandexGPT). Verify it here for each candidate.

## Objective

Verify whether **Google Gemini's free tier can actually be the primary enrichment LLM
from a Germany/EU egress** — i.e. usable without a billing card, ToS-permitted to store
and re-serve output, with acceptable ru/zh tea-naming quality — and rank the realistic
free alternatives (DeepSeek, Groq, Mistral) reachable the same way, so we can confirm or
revise the "Gemini primary, YandexGPT fallback" plan.

## Questions

1. **Gemini free tier from the EU — the decisive question.** From a German/EU IP and
   account: is the **Google Gemini API free tier** available **without enabling billing
   / without a credit card**? Which models + current free-tier limits (RPM / RPD / TPM)
   for the free Flash / Flash-Lite tier? Has the historical "free tier not available in
   EEA/UK/CH" restriction been lifted? Pin model ids and cite official Google pages.
2. **Gemini output-storage ToS.** Do the Gemini API / Google AI Studio **free-tier**
   terms permit **storing generated output in our database and re-serving it** to our
   users (commercially, in our own product)? Who owns the output? Quote the exact terms.
   Separately, confirm whether free-tier prompts/outputs are **used to train/improve
   Google models** (and whether that can be disabled) — we accept it for non-sensitive
   tea names but need it documented.
3. **DeepSeek (optional zh booster).** Free allowance for new accounts (one-time token
   bonus vs perpetual?), whether **signup needs a non-RU phone/card** (the VPN fixes IP,
   not payment), ToS on storing/serving output, China-hosting implications, and zh/ru
   tea-naming quality. Pin the API + current pricing after the free bonus.
4. **Groq & Mistral (free fallbacks).** For each: real free-tier limits, signup friction
   (card/phone), EU reachability/terms, ToS on storing output, and ru/zh quality. Is
   either a better *free* fallback than YandexGPT for our use?
5. **Head-to-head ru/zh tea naming.** Rank Gemini vs DeepSeek vs YandexGPT (and Groq/
   Mistral if relevant) specifically on **correctly transliterating Chinese tea trade
   names into Russian** (大红袍 → "Да Хун Пао", NOT "Большой красный халат") and emitting
   correct pinyin + Chinese characters. Note where each tends to fail.
6. **Recommendation.** Given "free first, YandexGPT as no-VPN safety net", confirm or
   revise the primary/fallback order, and specify the JSON-schema / structured-output
   support and the exact request config (model id, endpoint, how to disable training
   where possible) for the recommended primary.

## Evidence standards

- Prefer official provider docs / pricing & terms pages over blog posts.
- Pin exact model ids / free-tier numbers / API versions; explicitly flag anything you
  could not confirm on an official page — especially **EU free-tier-without-card** and
  **output-storage** terms.
- Cite every claim with a link and its publication/last-checked date; prefer recent
  sources.

## Return

1. A **comparison table**: `Provider | Free tier (EU, no card?) | Stores+serves output OK? | Trains on free data? | ru/zh tea-naming quality | Signup friction | Notes`.
2. A **clear verdict on Gemini-as-primary** (confirmed / revise to YandexGPT-primary) with the one-line reason.
3. The **recommended primary's request config** (model id, endpoint, structured-output/JSON-schema, training-opt-out if any) and the fallback order.
4. 5–8 dated reference links and an explicit **"uncertain / could not verify"** list (especially Gemini EU-free-tier-without-card and output-storage terms).

---

Models run: <opus, gpt, gemini, kimi>   ·   Date: 2026-06-15

## Adaptations (if any)

- <model>: <what you changed for this tool, and why>
