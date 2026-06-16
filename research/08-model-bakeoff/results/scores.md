# Bake-off scores (research run 08)

Run date 2026-06-16 · temperature 0 · zero-shot over the gold set + grounded injection set.

Lower MAE is better (target ≤ 1.0). `share_2_3` near the gold value = good calibration;
far above = central-tendency bias. `inj_breach` target = 0.

| Model | endpoint | valid | overall MAE | type acc | share 2–3 (gold) | 0/5 use (gold) | inj breach | lat s | tokens in/out | cost ₽ |
|-------|----------|:-----:|:-----------:|:--------:|:----------------:|:--------------:|:----------:|:-----:|:-------------:|:------:|
| YandexGPT Lite | native | 24/24 | 0.795 | 17/24 | 0.3 (0.31) | 0.18 (0.31) | 2/4 | 4.58 | 38592/10009 | 9.72 |
| YandexGPT Pro 5.1 | native | 24/24 | 0.496 | 21/24 | 0.33 (0.31) | 0.32 (0.31) | 2/4 | 2.52 | 38480/10027 | 38.81 |
| Alice LLM | native | 24/24 | 0.405 | 22/24 | 0.38 (0.31) | 0.31 (0.31) | 2/4 | 2.63 | 38592/10253 | 31.6 |
| Alice Flash | openai | 24/24 | 0.473 | 21/24 | 0.37 (0.31) | 0.25 (0.31) | 2/4 | 2.39 | 38592/10121 | 5.88 |
| Qwen3-235B | openai | 24/24 | 0.492 | 21/24 | 0.35 (0.31) | 0.37 (0.31) | 1/4 | 9.4 | 44717/8821 | 26.77 |
| DeepSeek V4 Flash | openai | 24/24 | 0.33 | 23/24 | 0.32 (0.31) | 0.32 (0.31) | 0/4 | 4.08 | 78127/8844 | 27.86 |

## Per-dimension MAE

| Model | BITTERNESS | SWEETNESS | ASTRINGENCY | FRUITINESS | FLORAL | GRASSY | SPICY | SMOKY | EARTHY_NUTTY | UMAMI | ROASTED |
|-------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| YandexGPT Lite | 0.58 | 0.58 | 0.96 | 0.96 | 1.04 | 0.96 | 0.79 | 0.67 | 0.75 | 0.33 | 1.12 |
| YandexGPT Pro 5.1 | 0.5 | 0.42 | 0.67 | 0.67 | 0.71 | 0.67 | 0.17 | 0.12 | 0.46 | 0.46 | 0.62 |
| Alice LLM | 0.38 | 0.5 | 0.58 | 0.42 | 0.79 | 0.38 | 0.12 | 0.12 | 0.38 | 0.38 | 0.42 |
| Alice Flash | 0.5 | 0.38 | 0.62 | 0.62 | 0.62 | 0.5 | 0.25 | 0.17 | 0.46 | 0.38 | 0.71 |
| Qwen3-235B | 0.54 | 0.71 | 0.58 | 0.67 | 0.46 | 0.42 | 0.33 | 0.12 | 0.54 | 0.54 | 0.5 |
| DeepSeek V4 Flash | 0.42 | 0.42 | 0.5 | 0.38 | 0.38 | 0.38 | 0.29 | 0.04 | 0.25 | 0.29 | 0.29 |

_Raw per-model outputs (verbatim): `results/<key>.jsonl`._
