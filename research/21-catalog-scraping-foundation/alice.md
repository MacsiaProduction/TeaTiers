# Проектирование системы scrape→catalog для TeaTiers (Run 21: исправление архитектуры)

## Главные выводы

Проведённый анализ выявил и устранил семь критических проблем решения #131. Предложен **строимый** дизайн импортного пайплайна, основанный на Pinyin-мосте для кросс-скриптовой идентификации, схеме source-record с per-field provenance, стабильных публичных UUID, мягком удалении, ToS-гейте и MIT/BSD-совместимом стеке библиотек. Рекомендация: начать с 5 PR-шагов, начиная с миграции схемы (UUID, source_record, upsert) и механизма слияния, а затем реализовать скрейпер на httpx+selectolax.

---

## 1. Кросс-скриптовая каноническая идентификация (вопрос 1)

**Проблема:** `dedup_key` не мог объединить `Да Хун Пао`, `Da Hong Pao` и ``.

**Решение — 5-уровневый каскад слияния** (на основе подхода polyglot-er [github.com](https://github.com/danieleschmidt/polyglot-er)):

| Уровень | Метод | Порог | Комментарий |
|---------|-------|-------|-------------|
| 0 | Unicode-нормализация + точное совпадение | exact | `nfkc_normalize` + сравнение |
| 1 | Pinyin-нормализация | exact | Все имена → Pinyin (см. ниже) |
| 2 | Левенштейн / Jaro-Winkler | >0.85 | На нормализованных строках |
| 3 | pg\_trgm (`similarity()`) | >0.7 | Существующий GIN-индекс на `name_norm` |
| 4 | Очередь ручного ревью | — | Для неоднозначных совпадений |

**Pinyin-мост:**
- **Cyrillic → Pinyin:** `cyrtranslit` (MIT, v0.4) для кириллицы→латиница [pythonhosted.org](https://pythonhosted.org/cyrtranslit/), затем `pypinyin` (MIT, v0.55.0) для латиницы→Pinyin [pypi.org](https://pypi.org/project/pypinyin/).
- **Chinese → Pinyin:** `pypinyin` напрямую.
- **Pinyin → Cyrillic:** библиотека `palladizator` (zh→ru, MIT-подобная) [github.com](https://github.com/liminovna/palladizator) или кастомная таблица Палладия.
- **Альтернатива:** библиотека `transliterate` (GPLv3, v1.10.2) — мощнее, но GPLv3 конфликтует с Apache-2.0 репозитория [repology.org](https://repology.org/project/python:transliterate/information); для пилота выбрана связка `cyrtranslit` + `pypinyin`.

**Seed через Wikidata:** Wikidata предоставляет метки и псевдонимы для чаёв на многих языках [enterprise.wikimedia.com](https://enterprise.wikimedia.com/project-data/wikidata-api/); импорт популярных Q-ID (например, Q2161092 для Да Хун Пао) даёт готовый словарь кросс-скриптовых алиасов.

---

## 2. Source-record staging & idempotency (вопрос 2)

Предложена таблица `source_record`:

```sql
CREATE TABLE source_record (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_run_id UUID NOT NULL,
    source VARCHAR(20) NOT NULL CHECK (source IN ('wikidata','scrape','curated','user')),
    external_id VARCHAR(255),          -- например, Q2161092 или URL-якорь
    canonical_url TEXT,                 -- URL страницы товара
    retrieved_at TIMESTAMPTZ NOT NULL,
    content_hash BYTEA NOT NULL,       -- SHA-256 сырого HTML
    parser_version VARCHAR(20),
    status VARCHAR(20) DEFAULT 'pending' CHECK (status IN ('pending','matched','merged','rejected')),
    -- Per-field provenance в JSONB
    parsed_facts JSONB NOT NULL DEFAULT '{}',
    -- Поля для ToS
    terms_url TEXT,
    terms_checked_at TIMESTAMPTZ,
    -- Связь с каноническим чаем (заполняется после слияния)
    tea_id BIGINT REFERENCES tea(id) ON DELETE RESTRICT ON UPDATE CASCADE,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE UNIQUE INDEX idx_source_record_uniq ON source_record(source, external_id);
CREATE INDEX idx_source_record_hash ON source_record USING hash(content_hash);
CREATE INDEX idx_source_record_tea ON source_record(tea_id);
-- GIN-индекс для поиска по parsed_facts
CREATE INDEX idx_source_record_facts ON source_record USING gin(parsed_facts);
```

**Формат `parsed_facts`** (JSONB):
```json
{
  "name_ru": {"value": "Да Хун Пао", "source": "https://...", "license": "CC-BY-4.0", "confidence": 0.98},
  "type": {"value": "Улун", "source": "...", "license": "...", "confidence": 0.95}
}
```

**Идемпотентность повторного импорта:** ключ `(source, external_id)` — UPSERT (см. раздел 3). При повторном запуске импортёр загружает запись с тем же `(source, external_id)`, вычисляет новый `content_hash`. Если хеш совпадает — `ON CONFLICT DO NOTHING` (или обновить `retrieved_at`), иначе — обновляет `parsed_facts` и сбрасывает статус на `pending`, чтобы снова запустить каскад слияния.

---

## 3. Схемные изменения (вопрос 3)

### 3.1. Миграция `tea.source` CHECK

Текущий CHECK: `('wikidata','curated','ai','user')`. Необходимо добавить `'scrape'`. **Способ:** не удалять старый CHECK, а создать новый с добавленным значением:

```sql
ALTER TABLE tea DROP CONSTRAINT tea_source_check;
ALTER TABLE tea ADD CONSTRAINT tea_source_check CHECK (source IN ('wikidata','curated','ai','user','scrape'));
```

Либо, если используется enum — пополнить его.

### 3.2. UPSERT-путь

В сервере сейчас нет UPSERT. Добавить в Java/Kotlin (Spring Data JDBC) императивный импортёр, который для таблицы `tea` использует `INSERT ... ON CONFLICT (public_uuid) DO UPDATE SET ...` [postgrespro.ru](https://postgrespro.ru/docs/postgresql/12/sql-insert)[www.postgresql.org](https://www.postgresql.org/docs/current/sql-insert.html). Для `tea_name` и `tea_description` — тоже UPSERT по ключу `(tea_id, locale)`. Важно: UPSERT с CHECK-ограничением — если обновляемое поле нарушает CHECK, весь запрос падает [habr.com](https://habr.com/ru/articles/264281/); поэтому перед UPSERT нужно гарантировать, что значения проходят CHECK.

### 3.3. Per-field provenance

Вместо одной колонки `tea.source` (которая указывает первичный источник канонической записи) добавить колонку `provenance JSONB` в таблицу `tea` по аналогии с `source_record.parsed_facts`. Это позволяет хранить для каждого поля канонического чая (например, имя, тип, регион) ссылку на конкретный source_record и оценить достоверность.

---

## 4. Стабильный публичный ID + откат (вопрос 4)

**Проблема:** API отдаёт `tea.id` (BIGINT identity). После пересоздания данных идентификаторы меняются, ломая кэш Android-клиента.

**Решение:**
- Добавить колонку `public_id UUID DEFAULT gen_random_uuid() UNIQUE NOT NULL` в таблицу `tea`.
- Android-клиент сохраняет `catalogTeaId` как UUID, а не BIGINT.
- Миграция: создать таблицу `tea_id_alias` для отображения старых BIGINT → новые UUID для обратной совместимости существующих клиентов.
- **Политика мягкого удаления (soft delete):** колонка `is_active BOOLEAN DEFAULT true` (или `deleted_at TIMESTAMP`). Никогда не удалять запись физически; при отзыве чая — установить `is_active = false`. UUID не переиспользуется [www.dev-notes.ru](https://www.dev-notes.ru/articles/eloquent/complete-guide-to-soft-delete/).
- Взаимодействие с v7 `CatalogTeaRef`: реф сохраняет `public_id` как строку.

---

## 5. Граница «только факты» и шлюзы ToS/robots (вопрос 5)

### 5.1. Юридические основания

- **Факты не защищены авторским правом:** согласно ГК РФ ст.1259 п.6, сообщения о событиях и фактах, имеющие информационный характер, не являются объектами авторских прав [base.garant.ru](https://base.garant.ru/10164072/2017e42bb4f69a83a4bd03b67d862357/). Сорт чая, регион, степень окисления — это факты.
- **Vendor-описания** — творческие произведения (ст.1259 п.1) и защищены. Их хранение на сервере создаёт риск нарушения авторских прав. Решение: **нигде не сохранять сырой текст поставщиков**. Вместо этого извлекать структурированные факты и генерировать собственные описания через LLM (решение #22).
- **ToS перевешивает robots.txt:** дело Ryanair v PR Aviation (CJEU) подтвердило, что условия использования сайта (ToS) являются юридически обязывающими даже при сборе данных [thunderbit.com](https://thunderbit.com/ru/blog/web-scraping-legal-europe-guide). Robots.txt — техническая рекомендация, но не правовой инструмент. Поэтому **обязателен ToS-гейт**: для каждого источника хранить `terms_url`, `terms_checked_at` и факт согласования с владельцем. Проверять robots.txt при каждом запуске скрейпера с помощью `urllib.robotparser`.
- **Miss-log:** текст запросов из `catalog_miss` никогда не должен использоваться как URL или shell-аргумент (защита от injection).

### 5.2. Схема хранения гейтов

В таблицу `source_record` добавлены поля `terms_url` и `terms_checked_at`. Отдельная таблица `source_gate` для постоянных настроек источника:

```sql
CREATE TABLE source_gate (
    source_id VARCHAR(50) PRIMARY KEY,     -- например, 'teavendor.ru'
    robots_url TEXT,
    robots_checked_at TIMESTAMPTZ,
    robots_allowed BOOLEAN,
    terms_url TEXT,
    terms_checked_at TIMESTAMPTZ,
    terms_accepted BOOLEAN,
    owner_sign_off TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);
```

---

## 6. OSS стек и операционная модель (вопрос 6)

### 6.1. Пинованные библиотеки

| Библиотека | Версия (последняя стабильная) | Лицензия | Назначение |
|------------|-------------------------------|----------|------------|
| `cyrtranslit` | 0.4 | MIT | Кириллица → латиница [pythonhosted.org](https://pythonhosted.org/cyrtranslit/) |
| `pypinyin` | 0.55.0 | MIT | Латиница → Pinyin [pypi.org](https://pypi.org/project/pypinyin/) |
| `httpx` | 0.28.x / 1.0.0-dev3 | BSD-3-Clause [pypi.org](https://pypi.org/project/httpx/) | Асинхронный HTTP-клиент (пилот) |
| `selectolax` | 0.4.0 | MIT [generalistprogrammer.com](https://generalistprogrammer.com/tutorials/selectolax-python-package-guide) | Быстрый HTML-парсер (пилот) |
| `urllib.robotparser` | stdlib | PSF | Проверка robots.txt (пилот) |
| `scrapy` | 2.13.3 | BSD [generalistprogrammer.com](https://generalistprogrammer.com/tutorials/scrapy-python-package-guide) | Полноценный краулер (будущее) |
| `protego` | 0.37.x | BSD-3-Clause [coder.social](https://coder.social/scrapy/protego) | Современный парсер robots.txt (будущее) |

Все библиотеки совместимы с Apache-2.0 (лицензия TeaTiers). Библиотеки с GPL (например, `transliterate`) исключены из-за копилефта.

### 6.2. Где запускать скрейпинг

Скрейпинг — **локальный one-off процесс** на машине разработчика, не в production VM и не на пути API-запросов. Результат (source_record + утверждённые канонические записи) загружается в базу через миграцию или seed-скрипт. Автоматизация через GitHub Actions на demand-driven основе (по жалобам из `catalog_miss`) возможна, но в первом релизе не требуется.

### 6.3. Сосуществование с существующими компонентами

- **Курированный seed** (ручные записи) остаётся, но теперь может быть дополнен или исправлен scrape-записями через каскад слияния.
- **Miss-log** (`catalog_miss`) будет потребляться скрейпером: топ-запросы запускают scraping.
- **Enrichment** (LLM-генерация описаний) остаётся тем же, но использует структурированные факты из канонической записи.
- **v7 split** (CatalogTeaRef vs TeaSample) использует `public_id`, а не BIGINT.

---

## 7. Рекомендация и последовательность

**Наименьший корректный первый релиз** — это **фундамент**, а не краулер. Отсортированные по порядку PR-шаги:

1. **PR #1: Миграция схемы** — добавить `public_id UUID`, `is_active`, table `source_record` (с per-field JSONB), table `source_gate`, обновить CHECK на `tea.source`, создать индексы.
2. **PR #2: Пагинация импорта с UPSERT** — реализовать `UpsertableImporter` на Spring Data, проверяющий `(source, external_id)` и выполняющий `INSERT ... ON CONFLICT DO UPDATE`.
3. **PR #3: Каскад слияния** — имплементировать в Python или Kotlin сервисе 5-уровневый каскад (Unicode → Pinyin → Lev → trigram → review). Для пилота можно оставить Python-скрипт, вызываемый перед заливкой.
4. **PR #4: ToS/robots гейт** — добавить логику валидации перед каждым scraping-запуском: проверять `source_gate` и текущий robots.txt.
5. **PR #5: MVP скрейпер (httpx+selectolax)** — реализовать для 1–2 выбранных источников (с наименьшим правовым риском, например, русскоязычные чайные магазины). Включить Pinyin-преобразование для названий.

**Итоговая однопараграфная рекомендация (заменяет нестроимую часть #131):**
> Построить систему scrape→catalog как комбинацию предложенной схемы source_record с per-field provenance, 5-уровневого Pinyin-каскада для кросс-скриптового слияния, стабильных UUID для публичного API и мягкого удаления, ToS-гейта для каждого источника и MIT/BSD OSS-стека. Первый шаг — миграция базы и импортёр с UPSERT, второй — каскад слияния, третий — лёгкий скрейпер на httpx+selectolax. Следующие итерации добавят Scrapy+Protego для полной обходной карты. Главное ограничение: никогда не сохранять сырой текст поставщиков на сервере. Решение юридически безопасно для RU-on-RU скрейпинга фактов о чае.