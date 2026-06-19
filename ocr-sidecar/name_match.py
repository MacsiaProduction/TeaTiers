"""
Track-1 OCR name recovery (research run 18, winner opus; decision #123).

Raw RapidOCR output on RU packaging is ~70% right but degraded by Cyrillic↔Latin/digit *homoglyph*
substitution (`ХУН→XYH`, `з→3`, `а→9`, `В→B`, `Ш→W`) + case + word-order. This module recovers the
canonical tea name by (1) a one-directional Latin/digit→Cyrillic **confusable normalizer** with a
per-token guard that never corrupts genuinely-Latin tokens (`Gaba`, `HONG LO`, `Lapsang`), and
(2) a **keep-best-of-both** fuzzy match (score each catalog name against BOTH the raw and the
normalized text, keep the higher) so normalization can only ever help.

No model change, no egress: pure post-processing over the text the local sidecar already produced.
"""
from __future__ import annotations

import string

from rapidfuzz import fuzz

# --- Confusable map: Latin / digit glyph -> the Cyrillic letter it was misread from. -------------
# Upper- and lower-case visual homoglyphs (Unicode-confusable for letters; shape-based for W/digits,
# which is why this stays narrow + guarded). One-directional on purpose (we never map Cyrillic->Latin).
_CONFUSABLE = {
    # uppercase letters
    "A": "А", "B": "В", "E": "Е", "K": "К", "M": "М", "H": "Н", "O": "О", "P": "Р", "C": "С",
    "T": "Т", "X": "Х", "Y": "У", "W": "Ш",
    # lowercase letters
    "a": "а", "c": "с", "e": "е", "o": "о", "p": "р", "x": "х", "y": "у", "w": "ш", "u": "и",
    # digits (lower-confidence, shape-based; the guard + keep-best-of-both contain the risk)
    "3": "з", "6": "б", "9": "а", "0": "о",
}
# Latin letters that have NO Cyrillic lookalike (= every ASCII letter not in the confusable map) —
# their presence proves a token is genuinely Latin, so we must NOT touch it (protects "Gaba", "HONG",
# "LO", "Lapsang"). Derived from the map so the two can't drift.
_UNAMBIGUOUS_LATIN = set(string.ascii_letters) - set(_CONFUSABLE)


def _is_cyrillic(ch: str) -> bool:
    return "Ѐ" <= ch <= "ӿ"


def _unambiguous_cyrillic(ch: str) -> bool:
    # A Cyrillic char that is NOT itself a homoglyph target (so it proves real Cyrillic context).
    return _is_cyrillic(ch) and ch not in _CONFUSABLE.values()


def _normalize_token(token: str, line_is_cyrillic: bool) -> str:
    # Never touch a token that contains an unambiguous Latin letter — it is genuinely Latin.
    if any(ch in _UNAMBIGUOUS_LATIN for ch in token):
        return token
    has_cyrillic = any(_is_cyrillic(ch) for ch in token)
    all_mappable = all((not ch.isalnum()) or _is_cyrillic(ch) or ch in _CONFUSABLE for ch in token)
    if has_cyrillic or (line_is_cyrillic and all_mappable):
        return "".join(_CONFUSABLE.get(ch, ch) for ch in token)
    return token


def confusable_normalize(text: str) -> str:
    """Map Latin/digit homoglyphs back to Cyrillic, per-line + per-token guarded (opus's rule)."""
    out_lines = []
    for line in text.splitlines() or [text]:
        line_is_cyrillic = any(_unambiguous_cyrillic(ch) for ch in line)
        out_lines.append(" ".join(_normalize_token(tok, line_is_cyrillic) for tok in line.split(" ")))
    return "\n".join(out_lines)


# --- Catalog match (keep-best-of-both). ----------------------------------------------------------
def _prep(s: str) -> str:
    # Case- + accent-insensitive (ё→е) + whitespace-normalized; applied to BOTH sides.
    return " ".join(s.lower().replace("ё", "е").split())


def _score(candidate: str, raw: str, norm: str) -> float:
    # token_set_ratio finds a short name inside the long full-label OCR text (name is a token subset);
    # WRatio adds length-robust overall similarity. Keep the best over raw AND normalized text.
    c = _prep(candidate)
    best = 0.0
    for text in (raw, norm):
        t = _prep(text)
        best = max(best, fuzz.token_set_ratio(c, t), fuzz.WRatio(c, t))
    return best


def match_catalog(ocr_text: str, catalog_names: list[str], auto: float = 88, confirm: float = 70,
                  margin_min: float = 8, confirm_margin_min: float = 5) -> dict:
    """
    Recover the catalog tea name from raw OCR text. Returns the best candidate, its score, the margin
    to the runner-up, and a status: 'auto' (accept silently into the review field), 'confirm' (pre-fill,
    user taps to accept), or 'reject' (fall back to raw OCR text for manual entry).

    Both accept bands require a **margin to the runner-up** (measured on run-18's photos): without it, a
    label sharing one common word with a short catalog name (e.g. "…ЧАЙ…" → "Грузинский чай") produces a
    confident-looking wrong match at margin≈0. A tie ⇒ reject ⇒ the user types it (safer than a wrong
    pre-fill). [decision #123 / bench_track1.py]
    """
    if not catalog_names:
        return {"name": None, "score": 0.0, "margin": 0.0, "status": "reject", "candidates": []}
    norm = confusable_normalize(ocr_text)
    scored = sorted(((_score(n, ocr_text, norm), n) for n in catalog_names), key=lambda x: x[0], reverse=True)
    best_score, best_name = scored[0]
    second = scored[1][0] if len(scored) > 1 else 0.0
    margin = best_score - second
    if best_score >= auto and margin >= margin_min:
        status = "auto"
    elif best_score >= confirm and margin >= confirm_margin_min:
        status = "confirm"
    else:
        status = "reject"
    return {"name": best_name, "score": round(best_score, 1), "margin": round(margin, 1),
            "status": status, "candidates": [(round(s, 1), n) for s, n in scored[:5]]}
