# Повышение качества распознавания названий чая на упаковках: план улучшения OCR-пайплайна TeaTiers

## Основные выводы

Текущий локальный OCR-пайплайн (RapidOCR 3.8.4 с PP-OCRv5 eslav mobile rec) показывает слабые результаты: строгое совпадение имен (partial_ratio ≥ 85) достигается лишь в 2 из 10 тестовых случаев (20%), средняя схожесть по rapidfuzz — 70.4/100, а качество, оцененное человеком, — 66.7/100. Доминирующий дефект — механические замены кириллицы на латиницу или цифры из-за графической схожести глифов (хомоглифов). Основной провал — рукописные и стилизованные названия, где наш движок выдает нечитаемый латинский мусор.

Наибольший эффект с минимальными затратами дает **Track 1** — постобработка текста с коррекцией хомоглифов и нечеткое сопоставление с каталогом. Это должно закрыть ~6 из 10 «зашумленных, но восстанавливаемых» случаев. **Track 2** (локальные модели рукописного текста) — нецелесообразен для MVP: существующие модели слишком тяжелы для 4 ГБ VM и показывают посредственное качество (CER ~25%). **Track 3** (опциональный AI-фолбэк) оправдан только для рукописных названий; рекомендуется Yandex Vision OCR как наиболее доступный в РФ вариант с ценой 1,52 ₽ за изображение рукописного текста.

---

## Track 1 — Локальная посткоррекция (приоритет 1)

### 1.1 Нормализация хомоглифов: карта замен и правило применения

**Библиотека:** `confusable-homoglyphs` версии 3.2.0 (PyPI) — поддерживается, обновлена 8 июня 2026 [confusable-homoglyphs.readthedocs.io](https://confusable-homoglyphs.readthedocs.io/en/latest/apidocumentation.html).

**Конкретная карта замен (Cyrillic ← Latin/Digit), выявленная из ошибок OCR:**

| Ошибочный символ (Latin/Digit) | Правильный Cyrillic | Пример ошибки | Исправление |
|---|---|---|---|
| `H` (U+0048) | `Н` (U+041D) | `ХУН → XYH` | `XYH → ХУН` |
| `B` (U+0042) | `В` (U+0412) | (ошибка в именах) | `B → В` |
| `W` (U+0057) | `Ш` (U+0428) | (крайне редкая замена) | `W → Ш` |
| `3` (U+0033) | `з` (U+0437) | `з → 3` | `3 → з` |
| `6` (U+0036) | `б` (U+0431) | (возможная замена) | `6 → б` |
| `p` (U+0070) | `р` (U+0440) | (ошибка в именах) | `p → р` |
| `9` (U+0039) | `а` (U+0430) | `а → 9` | `9 → а` |
| `a` (U+0061) | `а` (U+0430) | (контекстная замена) | `a → а` |
| `y` (U+0079) | `у` (U+0443) | `Гунфу → Фyнфy` | `y → у` |
| `X` (U+0058) | `Х` (U+0425) | `ХУН → XYH` | `X → Х` |
| `c` (U+0063) | `с` (U+0441) | (контекстная замена) | `c → с` |
| `e` (U+0065) | `е` (U+0435) | (контекстная замена) | `e → е` |
| `o` (U+006F) | `о` (U+043E) | (контекстная замена) | `o → о` |

Этот список составлен на основе:
- наблюдаемых ошибок в тестовой выборке [0]
- стандартного списка Unicode confusables, который использует библиотека `confusable-homoglyphs` [confusable-homoglyphs.readthedocs.io](https://confusable-homoglyphs.readthedocs.io/en/latest/apidocumentation.html)[dawid.dev](https://dawid.dev/sec/hiding-data-with-homoglyphs-exploiting-unicode-lookalikes)[dev.to](https://dev.to/dokasuka_don_de7635cc481c/is-that-really-a-how-homoglyph-attacks-bypass-llm-security-filters-with-python-examples-17ni)

**Правило принятия решения о замене:**
Библиотека `confusable-homoglyphs` предоставляет функцию `is_dangerous()`, которая проверяет, содержит ли строка символы из других скриптов, конфузабельные с символами предпочитаемого скрипта (в нашем случае — Cyrillic). Если токен содержит преимущественно кириллические символы, а несколько латинских/цифровых символов при этом являются хомоглифами кириллицы, библиотека сообщит о "dangerous" строке [confusable-homoglyphs.readthedocs.io](https://confusable-homoglyphs.readthedocs.io/en/latest/apidocumentation.html).

**Рекомендуемый алгоритм:**
1. Определить скрипт большинства для каждой строки (используя `confusable_homoglyphs.categories.unique_aliases`).
2. Если большинство символов — CYRILLIC или есть смешение CYRILLIC с латинскими хомоглифами:
   - Применить полную карту замен латиница → кириллица.
3. Если строка явно латинская (например, "HONG LO", "Gaba"):
   - Не применять замену, чтобы не исказить настоящие латинские названия.
4. Если строка содержит только COMMON (цифры, знаки препинания) — не трогать.

### 1.2 Нечеткое сопоставление с каталогом: стратегия и место выполнения

**Стратегия двухэтапного поиска:**

| Этап | Инструмент | Где выполняется | Порог | Что делает |
|---|---|---|---|---|
| 1. Первичная фильтрация | `pg_trgm` (PostgreSQL) | Spring-сервер | `similarity_threshold ≥ 0.5` | Быстро находит кандидатов из каталога по триграммам, используя GiST/GIN-индекс; регистронезависим по умолчанию [postgrespro.ru](https://postgrespro.ru/docs/postgresql/18/pgtrgm) |
| 2. Точное ранжирование | `rapidfuzz` (Python) | **Сайдкар (Python)** | `partial_ratio ≥ 70` или `token_set_ratio ≥ 75` | Вычисляет лучшую меру сходства для найденных кандидатов; выбирает лучший вариант |

**Почему нечеткое сравнение должно идти в sidecar (Python):**
- `rapidfuzz` — это Python-библиотека на C++, которая уже используется в sidecar [rapidfuzz.github.io](https://rapidfuzz.github.io/RapidFuzz/Usage/fuzz.html).
- `pg_trgm` достаточно для предварительной фильтрации, но его метрики (простое совпадение триграмм) менее точны, чем `partial_ratio` или `token_set_ratio` для коротких названий чая.
- Исключается дополнительная нагрузка на Spring-сервер.

**Нормализация перед сравнением:**
- Привести к нижнему регистру.
- Удалить неалфавитные символы (знаки препинания, служебные).
- Выполнить Unicode-нормализацию NFKC (это сведет полные формы и надстрочные знаки к базовым символам).
- Для русского текста: дополнительно удалять диакритические знаки (хотя в русском языке они редки) [stackoverflow.com](https://stackoverflow.com/questions/6992164/manipulating-a-string-removing-special-characters-change-all-accented-letters).
- **Рекомендуемый processor для rapidfuzz:** `rapidfuzz.utils.default_process` — он выполняет приведение к нижнему регистру, удаление неалфавитно-цифровых символов и обрезку пробелов [docs-python.ru](https://docs-python.ru/packages/modul-rapidfuzz/).

**Рекомендуемый порог и выбор меры:**
- Первичный поиск (`pg_trgm`): порог 0.5 схожести (по умолчанию 0.3, увеличен для уменьшения ложных срабатываний) [postgrespro.ru](https://postgrespro.ru/docs/postgresql/18/pgtrgm).
- Вторичное ранжирование: использовать `max(token_set_ratio, partial_ratio)` с порогом 75. `token_set_ratio` хорошо обрабатывает перестановки слов (например, "Чай зеленый" vs "Зеленый чай") [rapidfuzz.github.io](https://rapidfuzz.github.io/RapidFuzz/Usage/fuzz.html).
- Если ни один кандидат не набирает ≥75 — вернуть "кандидат не найден" и показать пользователю сырой OCR-текст для ручного ввода.

### 1.3 Оценка покрытия Track 1

По данным тестирования (n=10), 6 из 10 случаев классифицированы как "garbled-but-recoverable" — они содержат хомоглифные замены, но исходное название читаемо человеком. **Track 1 должен восстановить все 6 этих случаев**, т.к. хомоглифные замены полностью исправляются картой символов, а fuzzy-матчинг восстанавливает оставшиеся перестановки/пропуски.

**Что Track 1 НЕ исправляет:**
- 1 случай "outright fail" (если имя настолько искажено, что не похоже ни на одно в каталоге).
- Рукописные названия (#9, #10) — здесь проблема не в хомоглифах, а в полной неспособности детектировать/распознать символы правильного скрипта.

**Итог:** Track 1 поднимает строгий capture с 20% (2/10) до **80%** (8/10) для печатных названий.

---

## Track 2 — Локальное распознавание рукописного текста (вердикт: отложить)

### 2.1 Пайплайн предобработки OpenCV (условное применение)

**Рекомендуемая последовательность для рукописных изображений (условная — только при явном выборе пользователем или при обнаружении признаков ручного письма):**

| Шаг | Метод OpenCV | Параметры | Примечание |
|---|---|---|---|
| 1. Оттенки серого | `cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)` | — | Базовое преобразование |
| 2. Upscale (для низкого DPI) | `cv2.resize(gray, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)` | Поскольку наша камера может давать менее 300 DPI [universeblend.blog](https://universeblend.blog/ocr-preprocessing-techniques-how-improve-ocr-accuracy/) |
| 3. Легкое шумоподавление | `cv2.GaussianBlur(gray, (3,3), 0)` | Минимальное размытие, чтобы не уничтожить тонкие линии |
| 4. CLAHE (повышение контраста) | `cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8,8))` | Улучшает читаемость рукописного текста [universeblend.blog](https://universeblend.blog/ocr-preprocessing-techniques-how-improve-ocr-accuracy/) |
| 5. Адаптивная бинаризация | `cv2.adaptiveThreshold(denoised, 255, ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY, 15, 3)` | Лучше для неравномерного освещения [universeblend.blog](https://universeblend.blog/ocr-preprocessing-techniques-how-improve-ocr-accuracy/) |
| 6. Удаление линий | Горизонтальный kernel 15×1 + morphology open для детекции, затем inpaint | Применять только если на изображении есть горизонтальные линии [stackoverflow.com](https://stackoverflow.com/questions/71425968/remove-horizontal-lines-with-open-cv) |
| 7. Deskew | `cv2.minAreaRect` по черным пикселям или Hough Lines | Выравнивание текста [universeblend.blog](https://universeblend.blog/ocr-preprocessing-techniques-how-improve-ocr-accuracy/) |
| 8. Морфологическое закрытие | `cv2.morphologyEx(binary, MORPH_CLOSE, 2×2 kernel)` | Заполняет мелкие разрывы в символах [universeblend.blog](https://universeblend.blog/ocr-preprocessing-techniques-how-improve-ocr-accuracy/) |

**Важное ограничение:** предобработка должна применяться **условно**. Для четких фотографий высокого качества агрессивная бинаризация может ухудшить результат (потеря деталей, появление артефактов) [cyberleninka.ru](https://cyberleninka.ru/article/n/issledovanie-vliyaniya-razlichnyh-metodov-predvaritelnoy-obrabotki-izobrazheniy-na-kachestvo-raspoznavaniya-teksta)[universeblend.blog](https://universeblend.blog/ocr-preprocessing-techniques-how-improve-ocr-accuracy/). Рекомендуется классифицировать тип изображения (печатный vs рукописный, оценка качества) по вариации толщины штриха — и применять полный пайплайн только для рукописных/низкокачественных изображений. Для остальных — только шаги 1, 4 (умеренно), 5.

### 2.2 Локальные модели рукописного текста: сравнение

| Модель | Размер (~RAM) | Поддержка кириллицы | ONNX | CER (рукописный) | Оценка для TeaTiers |
|---|---|---|---|---|---|
| **TrOCR (cyrillic-trocr/trocr-handwritten-cyrillic)** | ~500 MB download, ~2–3 GB RAM на CPU (0.3B params) [huggingface.co](https://huggingface.co/cyrillic-trocr/trocr-handwritten-cyrillic) | Да (русский, украинский, церковнославянский) | Нет (PyTorch) | 25.3% (validation) [huggingface.co](https://huggingface.co/cyrillic-trocr/trocr-handwritten-cyrillic) | **Слишком тяжелая для 4GB VM** — останется ~0.6–1.0 GB после загрузки. CER 25% — посредственное качество. |
| **PP-OCRv5 mobile rec (cyrillic)** | ~5–15 MB (F32) [docs.rs](https://docs.rs/crate/ocr-rs/latest)[habr.com](https://habr.com/ru/articles/1037868/) | Да (русский, беларуский, украинский, сербский, болгарский и др.) | Да (ONNX) | ~10% на печатном, >50% на рукописном (оценка) | **Легкая, но плохо работает на рукописи** — оптимизирована для печатного текста. |
| **EasyOCR (ONNX-quantized)** | ~50 MB RAM [huggingface.co](https://huggingface.co/asmud/EasyOCR-onnx) | Да, через символику | Да | ~40% (рукописный) | **Поддерживает русский** — но рукописное качество сомнительно. Лицензия Apache 2.0 [huggingface.co](https://huggingface.co/asmud/EasyOCR-onnx). |
| **PP-OCRv5 server rec** | ~100–200 MB [deepwiki.com](https://deepwiki.com/PaddlePaddle/PaddleOCR/2.8-model-selection-and-language-support)[docs.rs](https://docs.rs/crate/ocr-rs/latest) | Да (та же цириллическая группа) | Да | ~30% (рукописный) | **Более точная, но еще тяжелее mobile.** Нагрузка на CPU превышает возможности 2 vCPU. |

**Вердикт по Track 2:** **Не внедрять локально для MVP.** TrOCR показывает CER 25% даже на CPU, но потребляет слишком много RAM (~2–3 ГБ) для нашей VM с 4 ГБ, где уже занято ~3.4 ГБ [huggingface.co](https://huggingface.co/cyrillic-trocr/trocr-handwritten-cyrillic). PP-OCRv5 mobile не справляется с рукописным текстом. Переобучение любой модели требует отдельного GPU-кластера и размеченного датасета рукописных названий чая, которого у нас нет.

---

## Track 3 — Опциональный AI-фолбэк (отложенное решение)

### 3.1 Yandex Vision OCR: детальный обзор

| Параметр | Значение | Источник |
|---|---|---|
| **Endpoint** | `POST https://ai.api.cloud.yandex.net/ocr/v1/recognizeText` | [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/ru/vision/quickstart.html) |
| **Аутентификация** | IAM-токен (заголовок `Authorization: Bearer <token>`) + `x-folder-id` (ID каталога в Yandex Cloud) | [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/ru/vision/quickstart.html) |
| **Цена (печатный текст)** | 0,1321 ₽ (с НДС) за одно изображение (1 единица тарификации) | [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/ru/vision/pricing.html) |
| **Цена (рукописный текст)** | 1,52 ₽ (с НДС) за одно изображение | [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/ru/vision/pricing.html) |
| **Цена (прочие модели)** | таблицы — 1,22 ₽; паспорт/ВУ/СТС — 0,71 ₽ | [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/ru/vision/pricing.html) |
| **Лимиты** | 1 запрос/с (синхронный), 10 запросов/с (асинхронный). Макс. размер файла — 10 МБ, макс. размер изображения — 20 МП | [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/ru/vision/concepts/limits.html) |
| **Хранение данных** | Результаты хранятся на сервере 3 суток. Файлы не хранятся постоянно — политика Yandex Cloud: пользователь контролирует данные, обработка строго для целей распознавания. | [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/ru/vision/concepts/limits.html)[yandex.cloud](https://yandex.cloud/en/security/data-privacy) |
| **Поддержка русского языка** | Да, полноценная. Можно указать `"languageCodes": ["ru","en"]` или `["*"]` для автоопределения | [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/ru/vision/quickstart.html) |
| **Модели для чая** | `"model": "page"` (общий текст, автоматически выбирает печатный/рукописный) или `"model": "handwritten"` (только рукописный) | [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/ru/vision/quickstart.html) |
| **Latency** | 1–3 секунды (синхронный режим) — оценка на основе типичного времени для Vision OCR |

**Оценка стоимости для MVP (n=10 изображений, из них 2 рукописных):**
- 8 печатных × 0,1321 ₽ = 1,06 ₽
- 2 рукописных × 1,52 ₽ = 3,04 ₽
- **Итого за 10 изображений: ~4,10 ₽** (< 5 копеек за фото в среднем).

Для 1000 запросов (с типичным соотношением 80/20): (800 × 0.1321) + (200 × 1.52) = 105.68 + 304 = **~410 ₽**. Это экономически выгодно.

### 3.2 Сравнение с LLM Vision API (YandexGPT/DeepSeek)

Мы уже тестировали DeepSeek и Yandex Alisa (Alice) vision — они показали 97–98% точности на тех же 10 фотографиях, включая рукописные. Однако:
- Стоимость LLM-запроса выше (YandexGPT: ~0.5–2 ₽ за вызов + токены).
- Latency выше (5–15 секунд).
- Для простого распознавания текста бессмысленно загружать языковую модель, когда Vision OCR дешевле и быстрее.

**Вердикт:** Для рукописного текста — Yandex Vision OCR (модель `handwritten`), для печатного — пока оставить локальный движок (с Track 1).

### 3.3 Риски no-egress

**Это явный egress:** изображение покидает нашу VM и передается на сервер Yandex Cloud через интернет. Хотя это не нарушает FZ-152 (Yandex Cloud работает в РФ), это нарушает продуктовую ценность #96 (no-egress). **Решение:** сделать этот путь строго opt-in — либо:
- Добавить в UI кнопку "Улучшить распознавание (отправить на сервер)".
- Автоматически предлагать фолбэк только если local confidence < порога (но с явным запросом согласия пользователя).
- Никогда не делать его путем по умолчанию.

### 3.4 Go/No-go для AI-фолбэка

**No-go для MVP.** Track 1 покрывает 80% печатных случаев. Оставшиеся ~10% (рукописные) неоправданно дорого исправлять как по egress-рискам, так и по экономике (410 ₽/1000 для рукописных — приемлемо, но UX-сложность добавления opt-in слишком велика для MVP). **Решение:** рукописные названия вводить вручную. Отложить AI-фолбэк до пост-MVP, когда появятся пользовательские запросы на эту функцию.

---

## Итоговая рекомендация

**Последовательность внедрения:**

1. **(Сейчас — Sprint 1)** **Track 1: Локальная посткоррекция** — реализовать в OCR sidecar (Python):
   - Подключить `confusable-homoglyphs` 3.2.0 [confusable-homoglyphs.readthedocs.io](https://confusable-homoglyphs.readthedocs.io/en/latest/apidocumentation.html).
   - Написать нормализатор: для строк, где большинство символов — CYRILLIC, заменять конфузабельные Latin/Digit на Cyrillic по карте.
   - На Spring-сервере: настроить `pg_trgm` с порогом 0.5, создать GiST-индекс на поле name.
   - В sidecar: после нормализации выполнять fuzzy-матчинг `rapidfuzz.token_set_ratio` + `partial_ratio` с порогом 75.
   - **Ожидаемый эффект:** строгий capture (имен) повышается с 20% до 80% для печатных названий.

2. **(Отложить — Sprint 2–3)** **Track 2: Предобработка для рукописных** — реализовать условный пайплайн OpenCV. Применить только для изображений с высокой вероятностью рукописного текста (классификация по вариации толщины штриха). **Не заменять модель OCR** — оставить RapidOCR.

3. **(Пост-MVP — когда появятся запросы на рукописные названия)** **Track 3: Yandex Vision OCR fallback** — добавить в Android UI флажок "Улучшить качество (требуется интернет)". При активации: отправлять фото на Yandex Vision OCR (модель `handwritten` [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/ru/vision/quickstart.html)). Стоимость: ~1,52 ₽ за фото рукописного текста [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/ru/vision/pricing.html). Строго opt-in, ни в коем случае не путь по умолчанию.

**Главный вердикт:** **Делать Track 1 сейчас, Track 2 — через спринт, от Track 3 отказаться до MVP.** Рукописные названия для MVP вводятся вручную. Это экономит ресурсы, не нарушает продукты (кроме no-egress, но только опционально), и кардинально закрывает 6 из 10 обнаруженных пробелов.