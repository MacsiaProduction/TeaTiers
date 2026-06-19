"""Unit tests for Track-1 OCR name recovery (decision #123). Pure logic, no models."""
from name_match import confusable_normalize, match_catalog


class TestConfusableNormalize:
    def test_maps_homoglyphs_back_in_a_cyrillic_line(self):
        # ПУЭР XYH ЧА: X->Х Y->У H->Н inside a Cyrillic-context line.
        assert confusable_normalize("ПУЭР XYH ЧА") == "ПУЭР ХУН ЧА"

    def test_maps_digit_homoglyphs_in_a_cyrillic_token(self):
        # Ц3ЮHЬ: the token already has unambiguous Cyrillic (Ц,Ю,Ь) -> map 3->з, H->Н.
        assert confusable_normalize("Инь Ц3ЮHЬ Мэй") == "Инь ЦзЮНЬ Мэй"

    def test_protects_a_genuinely_latin_token(self):
        # "Gaba"/"HONG"/"LO" contain unambiguous Latin letters -> never touched.
        assert confusable_normalize("Gaba Ladykiller") == "Gaba Ladykiller"
        assert confusable_normalize("HONG LO Oolong") == "HONG LO Oolong"

    def test_does_not_corrupt_a_numeric_token(self):
        # "2024"/"100" have non-mappable digits (2,4,1) -> not all-mappable -> preserved.
        assert confusable_normalize("Панда № 4 2024 100 мл") == "Панда № 4 2024 100 мл"

    def test_leaves_a_pure_latin_line_alone(self):
        assert confusable_normalize("teartroom.ru tel 8 921") == "teartroom.ru tel 8 921"


class TestMatchCatalog:
    # Several names share the common word "чай" — as the real 333-name catalog does — so a label that
    # only overlaps on "чай" is genuinely ambiguous (the margin guard then rejects it).
    catalog = ["Дянь Хун Медовый", "Чжэн Шань Сяо Чжун", "ПУЭР ХУН ЧА БУЛАНШАНЬ", "Грузинский чай",
               "Иван-чай", "Чёрный чай", "Лунцзин"]

    def test_recovers_a_homoglyph_garbled_name(self):
        # Raw OCR with Latin homoglyphs for a name that is in the catalog.
        r = match_catalog("Красный чай Дянь Хyн Медовый 2025г", self.catalog)
        assert r["name"] == "Дянь Хун Медовый"
        assert r["status"] in ("auto", "confirm")

    def test_clean_match_is_auto_accepted(self):
        r = match_catalog("Чжэн Шань Сяо Чжун высший сорт", self.catalog)
        assert r["name"] == "Чжэн Шань Сяо Чжун"
        assert r["status"] == "auto"

    def test_single_common_word_tie_is_rejected_not_mis_accepted(self):
        # A label whose only catalog overlap is the common word "чай" must NOT confidently pre-fill
        # "Грузинский чай" (the run-18 handwriting false-match) — margin guard -> reject -> manual.
        r = match_catalog("Dyn.Pam MaiXan ЧАЙ КАК ИСКУССТВО teartroom.ru", self.catalog)
        assert r["status"] == "reject"

    def test_empty_catalog_rejects(self):
        r = match_catalog("anything", [])
        assert r["status"] == "reject"
        assert r["name"] is None
