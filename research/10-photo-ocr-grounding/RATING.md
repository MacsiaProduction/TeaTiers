# Rating - 10-photo-ocr-grounding

Prompt: ./prompt.md   ·   Date judged: 2026-06-17

Scale 1-5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many -> lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output - the numeric Score is only a tiebreaker. See ../README.md -> *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus     |    4     |   5   |       5       |    1     |    5    |  4.05 |  1   |
| deepseek |    4     |   4   |       3       |    2     |    4    |  3.15 |  2   |
| gemini   |    3     |   5   |       3       |    3     |    5    |  3.00 |  3   |

<!-- Optional Score = 0.35*Accuracy + 0.20*Depth + 0.25*Actionability + 0.10*Clarity - 0.10*Halluc. -->

**Winner:** opus - most complete and constraint-honest: keeps photo bytes on-device, nails the
licensing traps (Surya GPL-3.0 + CC-BY-NC-SA non-commercial weights → avoid; RapidOCR = Apache-2.0
ONNX port of PaddleOCR), and gives the most reusable `sourceText` pipeline (mandatory review/confirm,
length cap, sanitization, prompt-injection guards, copyright "don't store raw prose", an `OcrEngine`
interface). **Key correction (deepseek + gemini are right here):** opus's *MVP* pick (ML Kit bundled)
is wrong for a Russia-first app — ML Kit's Latin recognizer does **not** read Cyrillic, so for ru
packaging the real path is opus's *post-MVP* engine (on-device **RapidOCR / PaddleOCR PP-OCRv5/v6**,
Apache-2.0, no GMS, unified Cyrillic+Latin+Hanzi) from the start. deepseek led with exactly that
(PP-OCRv6 Tiny on-device) but with shaky Paddle-Lite Maven coords/version specifics. gemini ranks
last for THIS project despite strong engineering: its server-side PaddleOCR sidecar **sends photo
bytes off-device** (violates the local-first privacy posture) and **adds an always-on Python service**
(violates the operational-simplicity lock #19).

**Reuse:**
- The `sourceText` handling (opus): mandatory user **review/confirm** before submit, length cap +
  sanitize (strip control/zero-width, NFC-normalize), prompt-injection guard = treat OCR text as
  inert *data* wrapped in delimiters, copyright = derive structured facts + short blurb, never store
  raw vendor prose. This is exactly the contract for the **#25 paste-a-description UI** built now.
- Engine path (correction from deepseek/gemini): on-device **RapidOCR (ONNX Runtime Mobile)** or
  PaddleOCR PP-OCRv5/v6 mobile (Cyrillic via the East-Slavic/eslav rec model), behind an `OcrEngine`
  interface; **verify** model files' Apache-2.0 license + sizes and Paddle-Lite/ONNX Maven coords
  upstream before coding (deepseek's `com.baidu.paddle:lite_ocr_all:0.0.1` is unconfirmed).
- gemini's prompt-injection XML-boundary example and 20-scenario ru/en/zh test matrix are reusable as
  the OCR test corpus.

**Discard:**
- ML Kit (bundled or Play Services) as the OCR engine for ru packaging — no Cyrillic model; Play
  Services variant also breaks the no-GMS/RuStore constraint.
- gemini's **server-side photo upload / FastAPI PaddleOCR sidecar for MVP** — violates the
  photo-stays-on-device privacy posture and the no-extra-service lock (#19).
- Surya (GPL-3.0 code + CC-BY-NC-SA non-commercial weights) and EasyOCR (no production Android path).
- Unverified pins: deepseek's Paddle-Lite Maven coords/`lite_ocr_all:0.0.1`, PP-OCRv6 size claims, and
  gemini's "8.5 GB VRAM" server figure — verify upstream.

> LEADERBOARD: +1 Wins opus; +1 Runs judged for opus, deepseek, gemini.
