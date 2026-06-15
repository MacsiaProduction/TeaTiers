# Research workflow — `research/`

Canonical spec for how this folder works. A Claude Code skill
(`.claude/skills/research-workflow/SKILL.md`) and a Cursor rule
(`.cursor/rules/70-research.mdc`) both point here — **edit the workflow in this
file; keep those two entry points thin.**

## What this folder is for

A **run** = one research question sent to several LLMs (Opus, GPT, Gemini, Kimi,
…), with each model's answer saved **verbatim** side by side, then **rated** so we
learn which model to trust for which kind of question.

Why bother capturing every answer plus a rating:

- Deep-research models disagree and hallucinate — especially on version pins, API
  names, library availability, and dataset details. Keeping all answers makes the
  synthesis auditable.
- A `RATING.md` per run plus a running `LEADERBOARD.md` builds a track record of
  which model wins for which topic, so next time you know where to start.
- Treat these outputs as **reasoned syntheses, not primary sources** — verify any
  specific version, API, library, or dataset against the real source before relying
  on it.

## Folder layout

```
research/
├── README.md                ← this file (canonical spec; skill + rule point here)
├── LEADERBOARD.md           ← running win tally across all runs
├── _template/               ← copy this to start a run
│   ├── prompt.md
│   └── RATING.md
└── 01-tea-databases/        ← one run = one folder (NN-slug)
    ├── prompt.md            ← the single prompt, sent verbatim to every model
    ├── opus.md              ← Claude Opus answer (verbatim)
    ├── gpt.md               ← GPT answer (verbatim)
    ├── gemini.md
    ├── kimi.md
    └── RATING.md            ← ranking + winner for this run
```

## Naming

**Run folder — `<NN>-<slug>`:** `NN` is the next free integer, zero-padded to two
digits (first run is `01`). `slug` is a short kebab-case topic. Example:
`01-tea-databases`.

**Answer file — `<model>.md`:** one file per model, named by lowercase **family
slug**. Add a **mode suffix** only when it changes the output — keep the base slug
stable so ratings and the leaderboard aggregate cleanly.

| Slug | Model family | Slug | Model family |
|------|--------------|------|--------------|
| `opus` | Claude Opus (Anthropic) | `kimi` | Moonshot Kimi |
| `sonnet` | Claude Sonnet | `grok` | xAI Grok |
| `fable` | Claude Fable | `deepseek` | DeepSeek |
| `gpt` | OpenAI GPT | `qwen` | Qwen |
| `gemini` | Google Gemini | … | add a row when you use a new model |

Mode suffixes: `-thinking` (extended reasoning), `-deepresearch` (provider's
deep-research agent), `-fast` (non-thinking variant), `-2` / `-3` (a re-run of the
same model). Examples: `opus-deepresearch.md`, `gemini-2.md`, `gpt-thinking.md`.

## The workflow

1. **Start a run:** `cp -r research/_template research/<NN>-<slug>`
2. **Write `prompt.md`** (see *Writing the prompt* below). This is the **one**
   prompt; you send the **same text to every model** — never tailor per model, or
   the comparison is meaningless.
3. **Run it in each model.** Paste each model's full output **verbatim** into
   `<model>.md`. Do not edit, summarise, or "fix" the model's text — the raw answer
   is the evidence. Add a one-line header with the model's exact version + date +
   mode if you know them.
4. **Rate:** fill `RATING.md` — rank the models, name the winner, record what to
   reuse and what to discard.
5. **Update `LEADERBOARD.md`:** `+1 Wins` for the winner, `+1 Runs judged` for
   every model you scored.
6. **When you reuse a finding** in code/docs, cite it by path (e.g.
   `research/01-tea-databases/opus.md`) and **verify any version pin, API, library,
   or dataset against the real source first**.

## Writing the prompt (`prompt.md`)

- **Self-contained.** Deep-research tools lose chat state — paste the project
  context block every time, so the prompt stands alone. The template's context
  block is pre-filled for TeaTiers; trim or extend it per question.
- **Specific and verifiable.** Numbered, concrete questions, not "tell me about X".
  Ask for exact version pins, and tell the model to **flag anything it is not
  certain exists**.
- **Set evidence standards.** Prefer maintained upstream source / official docs
  over blog posts; require links with publication dates; prefer recent sources.
- **State the deliverable.** End with an explicit `Return:` line (a table, a
  cheat-sheet, N links) so every model's output is comparable.
- **Same prompt across models.** If a tool's input limit forces a change, note it
  under *Adaptations* at the bottom of `prompt.md`.

The starting template is `_template/prompt.md`.

## Rating (`RATING.md`)

Score five dimensions 1–5 — **Accuracy, Depth, Actionability, Hallucination↓,
Clarity** — where Hallucination is inverted (1 = none, 5 = many; lower is better).

- The **rank + named winner is the real output.** The numeric Score is only a
  tiebreaker: ranks are more stable than absolute 1–5 scores, which drift and
  inflate. **When torn between two scores, pick the lower.**
- Optional weighted Score:
  `0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc.`
- Always write three lines: **Winner** (one line why), **Reuse** (what to lift, and
  where), **Discard** (claims to ignore — unverified version pins, hallucinated
  APIs/libraries). The **Discard** line is the most valuable part: it stops a
  hallucinated pin from leaking into the code.

The starting template is `_template/RATING.md`.

## Leaderboard (`LEADERBOARD.md`)

One table: `Model | Wins | Runs judged | Notable strengths`. After each `RATING.md`,
add `+1 Wins` for the winner and `+1 Runs judged` for each model scored.

**Win-count is the signal** — it tells you which model to reach for first for a
given kind of question. Do **not** average per-run scores across runs; different
prompts aren't comparable, so the leaderboard tracks counts only.

## Rules (dos & don'ts)

- **DO** keep exactly one `prompt.md` per folder, sent verbatim to every model.
- **DO** paste answers verbatim; never edit a model's text.
- **DO** fill `RATING.md` while the answers are fresh, then update the leaderboard.
- **DO** cite by path and verify pins/APIs/datasets against the source before use.
- **DON'T** tailor the prompt per model.
- **DON'T** put run folders anywhere but under `research/`.

## How this is wired (cross-tool)

This README is the single source of truth. Two thin entry points reference it so
both assistants pick the workflow up:

- **Claude Code** — `.claude/skills/research-workflow/SKILL.md`. Auto-triggers on
  research-folder work from its `description`; or invoke it manually with
  `/research-workflow`.
- **Cursor** — `.cursor/rules/70-research.mdc`. Auto-attaches on `research/**` and
  loads this file via `@research/README.md`.

Change the workflow **here**; keep both pointers thin. (If you later add Codex or
other AGENTS.md-aware tools, surface these rules to them with an `AGENTS.md` that
points back to this file.)
