#!/usr/bin/env python3
"""
Description-correction benchmark (research run 19 / decision #125). Compares, on the 10 real-photo OCR
outputs, the run-18 BLIND confusable normalizer vs the run-19 dictionary-GATED corrector — showing that
gated cleans homoglyphs to real words WITHOUT the Букет→Вукет wrong-word bug, and keeps un-validatable
tokens raw rather than inventing. Run in the same venv as the other proof scripts (needs pymorphy3).
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
EXAMPLES = HERE.parent / "examples"
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "ocr-sidecar"))
from description_correct import correct_description  # noqa: E402
from name_match import confusable_normalize  # noqa: E402

_HOMO = set("ABEKMHOPCTXYWabcehkmoptuxyw")


def ws(s: str) -> str:
    return " ".join(s.split())


def mixed_script_tokens(s: str) -> int:
    """Residual garble: tokens mixing Cyrillic with a Latin homoglyph char."""
    return sum(
        1 for t in s.split()
        if any("А" <= c <= "я" for c in t) and any(c in _HOMO for c in t)
    )


def main() -> None:
    print(f"{'photo':6} {'raw':>4} {'blind':>6} {'gated':>6}   blind_invents_Вукет  gated_invents_Вукет")
    for i in range(1, 9):  # 1-8 = printed labels with real descriptions
        raw = ws((EXAMPLES / str(i) / "ours.txt").read_text(encoding="utf-8"))
        blind = ws(confusable_normalize(raw))
        gated = ws(correct_description(raw))
        print(f"#{i:<5} {mixed_script_tokens(raw):>4} {mixed_script_tokens(blind):>6} "
              f"{mixed_script_tokens(gated):>6}   {'Вукет' in blind!s:>17}    {'Вукет' in gated!s:>17}")
    print("\n=== #1 before/after (the Букет case) ===")
    raw = ws((EXAMPLES / "1" / "ours.txt").read_text(encoding="utf-8"))
    print("RAW  :", raw[:160])
    print("BLIND:", ws(confusable_normalize(raw))[:160], "  <- note 'Вукет' (wrong)")
    print("GATED:", ws(correct_description(raw))[:160], "  <- 'Букет' (right), un-fixable tokens kept raw")


if __name__ == "__main__":
    main()
