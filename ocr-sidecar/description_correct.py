"""
Description-safe OCR correction (research run 19, winner opus / decision #125).

The run-18 confusable normalizer (`name_match.confusable_normalize`) maps Latin/digit homoglyphs to
Cyrillic *blindly*. For catalog NAME matching that's fine (fuzzy match absorbs it). For a human-read
DESCRIPTION it is unsafe: it turns `Букет` (OCR'd `Byкeт`) into the plausible non-word `Вукет` because
Latin `B` was a misread `Б` but the blind map only knows `B→В`.

This module is dictionary-GATED instead. Per token it builds a small candidate **lattice** (each
homoglyph char may stay OR map to any of its Cyrillic look-alikes — e.g. `B → {В, Б}`), then accepts
the candidate that `pymorphy3` validates as a real Russian word (or that is a known tea term), ranked
by corpus frequency — and **keeps the raw token when nothing validates** (never invents a word). It
never touches protected tokens (URLs, Latin brands, pinyin, units, numbers).

Real misrecognitions that aren't homoglyphs (`северо→соверо`, `Гунфу→Фунфу`) are left as-is for the
downstream enrichment LLM (Track C) — not guessed here.
"""
from __future__ import annotations

import itertools
import string

# Latin/digit glyph -> the Cyrillic letter(s) it may have been misread from. Multi-option entries
# (B -> В or Б) are the whole point: the dictionary gate picks the one that forms a real word.
_RMAP = {
    "A": "А", "B": "ВБ", "E": "Е", "K": "К", "M": "М", "H": "Н", "O": "О", "P": "Р", "C": "С",
    "T": "Т", "X": "Х", "Y": "У", "W": "Ш",
    "a": "а", "b": "вб", "c": "с", "e": "е", "h": "н", "k": "к", "m": "м", "o": "о", "p": "р",
    "t": "т", "u": "и", "x": "х", "y": "у", "w": "ш",
    "3": "з", "6": "б", "9": "а", "0": "о",
}
# ASCII letters with NO Cyrillic look-alike — their presence proves a token is genuinely Latin.
_UNAMBIGUOUS_LATIN = set(string.ascii_letters) - set(_RMAP)
# Tea-domain terms pymorphy3 won't know (transliterated Chinese names, grades, vendors). Lowercased.
# Accepted as "real words" so they aren't mangled or rejected. Extend as the catalog grows.
_TEA_LEXICON = {
    "гунфу", "пуэр", "улун", "шэн", "шу", "габа", "дянь", "хун", "маофэн", "лунцзин", "тегуаньинь",
    "те", "гуань", "инь", "сяо", "чжун", "чжэн", "шань", "цзинь", "ван", "мэй", "цзюнь", "буланшань",
    "уишань", "фуцзянь", "юньнань", "ассам", "цейлон", "сян", "гао", "дун", "фан", "жэнь", "бодрая",
    "панда", "медовый", "красный", "чёрный", "зелёный", "белый", "жёлтый",
}
_MAX_LATTICE = 256  # cap the per-token candidate explosion; fall back to a single best-guess beyond it
_EDGE_PUNCT = ".,;:!?»«\"'()[]{}—…•·"  # stripped from a token's ends before correction, then reattached


_MORPH = None  # lazily built once on first use (the pymorphy3 dict load is heavy)


def _morph():
    global _MORPH
    if _MORPH is None:
        import pymorphy3

        _MORPH = pymorphy3.MorphAnalyzer()
    return _MORPH


def _protected(token: str) -> bool:
    """Genuinely-Latin / non-correctable tokens we must never touch."""
    if not token:
        return True
    if any(ch in _UNAMBIGUOUS_LATIN for ch in token):  # Gaba, HONG, artoftea, мл-vs-ml
        return True
    if any(ch in "./:@\\" for ch in token):  # URLs, ratios 5/100, times, domains
        return True
    if any(ch.isdigit() for ch in token) and not any(ch in _RMAP for ch in token if ch.isalpha()):
        return True  # pure numeric / unit token (2024, 100, °C kept intact)
    return False


def _candidates(token: str):
    """All confusable-lattice variants of a token (each homoglyph may stay or map to a Cyrillic option)."""
    per_char = []
    size = 1
    for ch in token:
        opts = [ch] + list(_RMAP.get(ch, ""))
        per_char.append(opts)
        size *= len(opts)
        if size > _MAX_LATTICE:
            # Too ambiguous: just offer raw + the all-mapped best-guess, let the gate decide.
            mapped = "".join(_RMAP.get(c, c)[0] if c in _RMAP else c for c in token)
            return [token, mapped]
    return ("".join(p) for p in itertools.product(*per_char))


def _correct_token(token: str) -> str:
    # Peel surrounding sentence punctuation ("KYCTOB." / «Букет») so it doesn't trip the URL guard or
    # block word recognition; an INTERNAL dot (artoftea.ru) still marks the core as protected.
    i, j = 0, len(token)
    while i < j and token[i] in _EDGE_PUNCT:
        i += 1
    while j > i and token[j - 1] in _EDGE_PUNCT:
        j -= 1
    pre, core, post = token[:i], token[i:j], token[j:]
    if _protected(core):
        return token
    best = None
    best_freq = -1.0
    for cand in _candidates(core):
        # Parse ONCE and reuse for both the known-check and the score (OCR-P2-3): the rank loop used to
        # parse every candidate twice (once in _is_real_word, once here) — up to 512 morph parses per token.
        parses = _morph().parse(cand)
        if cand.lower() not in _TEA_LEXICON and not any(p.is_known for p in parses):
            continue
        # Rank known candidates by morphological confidence; prefer more-Cyrillic on ties.
        freq = max((p.score for p in parses), default=0.0)
        cyr = sum(1 for c in cand if "А" <= c <= "я")
        key = freq + 0.01 * cyr
        if key > best_freq:
            best_freq, best = key, cand
    return pre + (best if best is not None else core) + post  # keep raw core if nothing validates


def correct_description(text: str) -> str:
    """Dictionary-gated, description-safe correction of full OCR text (line/token preserving)."""
    out_lines = []
    for line in text.splitlines() or [text]:
        out_lines.append(" ".join(_correct_token(tok) for tok in line.split(" ")))
    return "\n".join(out_lines)
