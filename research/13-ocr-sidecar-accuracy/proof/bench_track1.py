#!/usr/bin/env python3
"""
Track-1 lift benchmark (research run 18 / decision #123). Measures how much the confusable normalizer
+ keep-best-of-both catalog match (ocr-sidecar/name_match.py) recovers the tea NAME from the raw OCR
output on the 10 real photos — vs the raw-OCR baseline (canonical 2/10 strict capture).

Catalog = the 100-tea seed (ru/en/pinyin/zh names) PLUS the 10 ground-truth names, simulating "the tea
exists in the catalog" (Track 1 recovers a name that's present; a missing tea is a catalog-breadth
problem, not OCR). Run in the same venv as measure_photos.py.
"""
import json
import sys
from pathlib import Path

from rapidfuzz import fuzz

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "ocr-sidecar"))
from name_match import confusable_normalize, match_catalog  # noqa: E402

HERE = Path(__file__).resolve().parent
EXAMPLES = HERE.parent / "examples"
SEED = Path(__file__).resolve().parents[3] / "server/src/main/resources/seed/catalog-seed.json"

GT = {
    1: "Гунфу Хун Гао Сян", 2: "Е Шен Хун Ча из Уишань", 3: "Шу Пуэр Бодрая Панда № 4 2024",
    4: "Чжэн Шань Сяо Чжун", 5: "ПУЭР ХУН ЧА БУЛАНШАНЬ", 6: "Да Цзинь Чжэнь Ван «Золотые Иглы»",
    7: "Инь Цзюнь Мэй В/с", 8: "Дянь Хун Медовый", 9: "Gaba Ladykiller", 10: "Дун Фан Мэй Жэнь",
}
HANDWRITTEN = {9, 10}


def catalog_names() -> list[str]:
    teas = json.loads(SEED.read_text(encoding="utf-8"))["teas"]
    names = {n["name"] for t in teas for n in t.get("names", []) if n.get("name")}
    names.update(GT.values())  # the photographed teas "exist" in the catalog
    return sorted(names)


def main() -> None:
    catalog = catalog_names()
    print(f"catalog: {len(catalog)} names ({len(GT)} GT injected)\n")
    raw_hit = t1_top1 = t1_usable = 0
    for i in range(1, 11):
        ocr = (EXAMPLES / str(i) / "ours.txt").read_text(encoding="utf-8")
        gt = GT[i]
        # Baseline: is the GT name directly in the raw OCR (canonical partial_ratio>=85)?
        raw_pr = fuzz.partial_ratio(gt.lower().replace("ё", "е"), ocr.lower().replace("ё", "е"))
        raw_ok = raw_pr >= 85
        raw_hit += raw_ok
        # Track 1: normalize + keep-best-of-both catalog match.
        m = match_catalog(ocr, catalog)
        top1 = m["name"] == gt
        usable = top1 and m["status"] in ("auto", "confirm")
        t1_top1 += top1
        t1_usable += usable
        tag = " (handwritten)" if i in HANDWRITTEN else ""
        print(f"#{i:>2}{tag}: raw_pr={raw_pr:>3} raw_ok={int(raw_ok)} | T1 top1={int(top1)} "
              f"status={m['status']:<7} score={m['score']:>5} margin={m['margin']:>4} -> {m['name']}")
        if not top1:
            print(f"        GT={gt!r}  norm={confusable_normalize(ocr)[:70]!r}")
    n = len(GT)
    print(f"\nRAW baseline (partial_ratio>=85):      {raw_hit}/{n}")
    print(f"TRACK 1 correct top-1 (any status):    {t1_top1}/{n}")
    print(f"TRACK 1 usable (top-1 AND not reject): {t1_usable}/{n}   [handwritten {sorted(HANDWRITTEN)} expected to stay manual]")


if __name__ == "__main__":
    main()
