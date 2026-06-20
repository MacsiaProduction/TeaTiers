"""Unit tests for the description-safe dictionary-gated corrector (decision #125)."""
from description_correct import correct_description


def test_fixes_homoglyph_to_a_real_word():
    # KYCTOB -> КУСТОВ (a real Russian wordform), trailing period preserved.
    assert correct_description("дикорастущих KYCTOB.") == "дикорастущих КУСТОВ."


def test_keeps_the_real_word_not_a_wrong_cyrillic_lookalike():
    # The Букет->Вукет bug: Latin B was a misread Б, not В. Букет is a real word, Вукет is not.
    out = correct_description("Byкeт сладко-карамельный")
    assert "Букет" in out
    assert "Вукет" not in out


def test_protects_genuinely_latin_tokens_and_urls():
    assert correct_description("Gaba Ladykiller") == "Gaba Ladykiller"
    assert correct_description("artoftea.ru HONG LO") == "artoftea.ru HONG LO"


def test_keeps_numeric_and_unit_tokens():
    assert correct_description("№ 4 2024 100 мл") == "№ 4 2024 100 мл"


def test_keeps_raw_when_nothing_validates_never_invents():
    # "Фyнфy" can't map to any known Russian word (the Г->Ф is a real misrecognition, not a homoglyph),
    # so it is left RAW rather than turned into a plausible non-word.
    out = correct_description("Фyнфy Хyн")
    assert "Фyнфy" in out          # untouched — not invented into a fake word
    assert "Хун" in out            # Хyн -> Хун (a real word) IS fixed


def test_accepts_known_tea_lexicon_terms():
    # пуэр / гунфу aren't in pymorphy but are in the tea lexicon, so their homoglyph forms recover.
    assert "пуэр" in correct_description("ШУ ПУЭP").lower()
