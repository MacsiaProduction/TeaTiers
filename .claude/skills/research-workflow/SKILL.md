---
name: research-workflow
description: Create, organize, prompt for, and rate multi-model deep-research runs in this repo's research/ folder. Use when the user wants to start a research run, write a deep-research prompt, save model answers (Opus/GPT/Gemini/Kimi/Grok), rate which model answered best, or update the research RATING/LEADERBOARD files.
---

# Research workflow

The canonical, full spec lives in **`research/README.md`** — read it before creating
or editing anything under `research/`.

Essentials:

- **One folder per run:** `research/<NN>-<slug>/` (zero-padded number + kebab slug;
  first run is `01`). Begin by copying `research/_template/`.
- **`prompt.md`** holds the single prompt, sent **verbatim to every model** — never
  tailor it per model (fair comparison).
- **One answer file per model**, named by family slug: `opus.md`, `gpt.md`,
  `gemini.md`, `kimi.md`, `grok.md`, `fable.md`. Add a mode suffix (`-thinking`,
  `-deepresearch`, `-fast`, `-2`) only when it changes the output. **Paste each
  model's output verbatim — never edit it.**
- **`RATING.md`** ranks the models for that run (winner + why + what to
  reuse/discard). Then bump the winner in **`research/LEADERBOARD.md`**.
- Findings are syntheses, not primary sources: **cite by path and verify any
  version pin / API / library / dataset against the real source** before using them.

For the prompt-writing guide, naming table, rating rubric, and templates, read
`research/README.md`.
