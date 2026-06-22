# `research/` moved to the `researches` repo

TeaTiers' deep-research runs, ratings, leaderboard, the research workflow skill,
and the `eliza_research.py` tooling now live in a **separate, shared repo** so they
can be reused across projects:

- **Repo:** https://github.com/MacsiaProduction/researches
- **Local sibling clone:** `../researches/`
- **This project's runs:** `../researches/projects/TeaTiers/<NN>-<slug>/`
- **Workflow spec:** `../researches/README.md` (canonical; the old
  `research/README.md` workflow content moved there).

`context/` decisions and plans still cite runs by the old `research/<NN>-<slug>/`
path. Those runs are unchanged in content — only **relocated and renamed**. To find
a cited file:

1. `research/<NN>-<slug>/` → `../researches/projects/TeaTiers/<NN>-<slug>/`
2. Translate the old model filename via the map below.

## Model file rename map

Answer files are now `<family>-<version>-<effort>.md` (effort dropped when the
model has no effort knob):

| Old name | New name |
|---|---|
| `opus.md` | `opus-4.8-xhigh.md` |
| `opus-2.md` | `opus-4.8-xhigh-2.md` |
| `gpt.md` | `gpt-5.5-xhigh.md` |
| `gemini.md` | `gemini-3.5-flash-high.md` |
| `deepseek.md` | `deepseek-v4-flash.md` |
| `deepseek-flash.md` | `deepseek-v4-flash.md` |
| `qwen.md` | `qwen3-max-xhigh.md` |
| `alice.md` | `alice-235b-xhigh.md` |
| `gemini-3.5-flash-rs.md` | `gemini-3.5-flash-high-2.md` |
| `deepseek-v4-pro-rs.md` | `deepseek-v4-pro-high.md` |
| `minimax-rs.md` | `minimax-m2-7.md` |
| `glm-5.2-rs.md` | `glm-5.2-high.md` |

Non-model docs (`prompt.md`, `RATING.md`, `RECONSIDER.md`, `deepagent.md`, and the
run-13 `examples/` OCR ground-truth) keep their names.

> Example: decision-log citation `research/07-flavor-prompt-tuning/opus.md` →
> `../researches/projects/TeaTiers/07-flavor-prompt-tuning/opus-4.8-xhigh.md`.
