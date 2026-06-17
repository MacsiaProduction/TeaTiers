# Выбор метода реализации typo-tolerant поиска по каталогу чаев в проекте TeaTiers

## Главные выводы
- Для каталога 300–50 000 чаёв с четырьмя локалями (en, ru, zh-Hans, pinyin) наиболее рациональным решением для MVP является **Meilisearch**. Он обеспечивает автоматическую типокоррекцию, встроенную многоязычную сегментацию (включая CJK через jieba) и минимальные затраты на конфигурацию. RAM-потребление (~50–200 МБ) и размер Docker-образа (~100 МБ) приемлемы для одиночной Yandex Cloud VM.
- **Pg_trgm** в PostgreSQL может использоваться как временное решение для ru/en/pinyin-типов, но не обеспечивает качественной коррекции опечаток для китайских иероглифов и плохо работает с короткими строками (<3 символов). Расширение **zhparser** для CJK-полнотекстового поиска требует отдельной сборки образа и повышает операционную сложность.
- **Typesense** сопоставим по качеству поиска и превосходит Meilisearch по эффективности RAM (~10–14 МБ против 50–200 МБ), но требует минимум 2 vCPU и его Java-клиент менее зрелый.
- **OpenSearch** избыточен для данного объёма данных и потребует 512+ МБ JVM heap, что критично для маленькой VM.

---

## 1. Сравнительная таблица подходов

| Параметр | PostgreSQL `pg_trgm` (+ zhparser) | Meilisearch | Typesense | OpenSearch |
|----------|-----------------------------------|-------------|-----------|------------|
| **Docker-образ** | ~97 МБ (zhparser на Alpine) [hub.docker.com](https://hub.docker.com/r/zhparser/zhparser) | ~100 МБ [hub.docker.com](https://hub.docker.com/r/getmeili/meilisearch) | ~359 МБ [hub.docker.com](https://hub.docker.com/r/typesense/typesense/) | ~994 МБ [hub.docker.com](https://hub.docker.com/r/opensearchproject/opensearch) |
| **RAM (холостые / для 50K чаёв)** | ~100 МБ (+ данные в shared_buffers) / ~10–50 МБ на индексы | ~50–200 МБ (кэш ОС + LMDB) [www.meilisearch.com](https://www.meilisearch.com/docs/resources/internals/storage) | ~20 МБ базово + ~10–15 МБ на индексы [typesense.org](https://typesense.org/docs/guide/system-requirements.html) | 512 МБ–1 ГБ JVM heap [habr.com](https://habr.com/ru/companies/nixys/articles/588609/) |
| **CPU (минимум)** | 1 vCPU | 1 vCPU | 2 vCPU [typesense.org](https://typesense.org/docs/guide/system-requirements.html) | 1–2 vCPU |
| **Типокоррекция** | На основе триграмм, не работает для Hanzi; требует ручной порог similarity >0.3 [www.postgresql.org](https://www.postgresql.org/docs/current/pgtrgm.html) | Автоматическая (Damerau-Levenshtein), пороги по длине слова, сплит/конкатенация [www.meilisearch.com](https://www.meilisearch.com/docs/resources/internals/typo_tolerance) | Автоматическая (Levenshtein), настраиваемая per field | Через `fuzziness` per query, опционально |
| **Китайский (Hanzi)** | Триграммы на байтовом уровне (3 байта/символ) – нет семантики; zhparser – словарная сегментация [www.alibabacloud.com](https://www.alibabacloud.com/blog/performance-optimization-of-fuzzy-queries-for-chinese-characters-using-postgresql-trgm_595634) | Charabia tokenizer (jieba/lindera) – словарная сегментация [www.meilisearch.com](https://www.meilisearch.com/docs/resources/internals/typo_tolerance) | ICU locale `zh` – character-based segmentation [typesense.org](https://typesense.org/docs/guide/locale.html) | Требуется внешний плагин (IK, SmartCN) |
| **Русский / pinyin** | Триграммы на байтовом уровне (2 байта/символ); `unaccent` помогает | Автоматическая декомпозиция акцентов, lowercase | ICU locale `ru` для русского, `en` для pinyin (с диакритикой) | Анализаторы `russian`, `standard` |
| **Spring Boot Java-клиент** | Стандартный JDBC / JPA | meilisearch-java v0.20.1 (JDK 17+, MIT) [github.com](https://github.com/meilisearch/meilisearch-java) | typesense-java v1.3.0 (JDK 8+, Apache 2.0) [github.com](https://github.com/typesense/typesense-java) | opensearch-java + Spring Data OpenSearch [mvnrepository.com](https://mvnrepository.com/artifact/org.opensearch.client) |
| **Синхронизация из PostgreSQL** | Не требуется (данные уже в PG) | App-level upsert, LISTEN/NOTIFY, Debezium CDC [www.meilisearch.com](https://www.meilisearch.com/docs/resources/migration/postgresql_migration) | Polling updated_at, ORM hooks, Sequin (logical replication) [typesense.org](https://typesense.org/docs/guide/syncing-data-into-typesense.html) | Bulk API, Debezium CDC |
| **Лицензия** | PostgreSQL (PostgreSQL) | MIT | Apache 2.0 | Apache 2.0 |
| **Операционная сложность** | Низкая (встроен в PG) – но для zhparser нужен custom Dockerfile | Низкая (один образ, 0 конфигурации) | Средняя: необходимо указать locale per field, настроить 2 vCPU | Высокая: JVM настройки, плагины, больший объём конфигурации |

---

## 2. Рекомендуемая реализация для MVP

### Выбор: Meilisearch

**Обоснование**: Meilisearch предоставляет лучшее соотношение качества поиска (типокоррекция, многоязычие, префикс) и простоты развёртывания. Для каталога 50K чаёв потребление памяти (~50–200 МБ) и CPU (1 vCPU) комфортно для одиночной бюджетной VM. Java-клиент зрелый и активно поддерживается [github.com](https://github.com/meilisearch/meilisearch-java).

### Детали реализации

**Docker Compose (добавить в существующий проект)**:
```yaml
services:
  meilisearch:
    image: getmeili/meilisearch:v1.12   # зафиксировать версию
    ports:
      - "7700:7700"
    environment:
      - MEILI_MASTER_KEY=${MEILI_MASTER_KEY}
      - MEILI_ENV=production
    volumes:
      - meili_data:/meili_data
    restart: unless-stopped
```

**Spring Boot интеграция**:
1. Добавить зависимость `com.meilisearch.sdk:meilisearch-java:0.20.1` в `build.gradle` [github.com](https://github.com/meilisearch/meilisearch-java).
2. При старте приложения (или при первом запросе) выполнить полную загрузку каталога:
   - Запросить все `tea` + `tea_name` записи из PostgreSQL.
   - Построить документы вида:
     ```json
     {
       "id": "tea_<id>",
       "type": "black",
       "origin": "China",
       "name_en": "Earl Grey",
       "name_ru": "Эрл Грей",
       "name_zh": "",
       "name_pinyin": "bojue cha"
     }
     ```
   - Отправить через `index.addDocumentsInBatches()`.
3. Для инкрементальной синхронизации использовать **application-level sync**:
   - В сервисе после `save()`/`delete()` чая отправлять тот же документ в Meilisearch (upsert по `id`).
   - Для периодической сверки: раз в час запускать пакетную синхронизацию по `updated_at`.
4. Поисковый API:
   - Kotlin контроллер принимает query string и делегирует `index.search(new SearchRequest(query))`.
   - Результаты (id чаёв) возвращаются клиенту; дополнительные данные подгружаются из PostgreSQL при необходимости.

**Настройки индекса**:
```kotlin
index.updateSearchableAttributes(arrayOf("name_en", "name_ru", "name_zh", "name_pinyin"))
index.updateAttributesForFaceting(arrayOf("type", "origin"))
```

---

## 3. Запасной план (fallback)

Если тестирование покажет, что Meilisearch не справляется с качеством поиска по pinyin с тонами или китайским иероглифам (маловероятно, но возможно), перейти на **Typesense**.

**Различия с MVP**:
- Заменить образ `getmeili/meilisearch` на `typesense/typesense:30.1` (требуется минимум 2 vCPU, а значит, возможно, более дорогой тариф VM).
- В схеме коллекции явно указать locale для каждого поля:
  ```json
  {
    "name": "teas",
    "fields": [
      {"name": "name_en", "type": "string", "locale": "en"},
      {"name": "name_ru", "type": "string", "locale": "ru"},
      {"name": "name_zh", "type": "string", "locale": "zh"},
      {"name": "name_pinyin", "type": "string", "locale": "en"}
    ]
  }
  ```
- Использовать Java-клиент `org.typesense:typesense-java:1.3.0` [github.com](https://github.com/typesense/typesense-java).
- Синхронизация: добавить колонку `updated_at` в таблицы и настроить периодический polling раз в 30 секунд [typesense.org](https://typesense.org/docs/guide/syncing-data-into-typesense.html).

**Ожидаемые затраты RAM**: ~10–14 МБ для индекса, что значительно меньше, чем у Meilisearch, но требование к CPU (2 vCPU) может повысить стоимость VM.

---

## 4. План оценки (Gold Set)

Создать набор тестовых запросов, охватывающий все локали и типы ошибок. Ниже приведён план из 21 запроса, которые нужно выполнить на тестовом датасете (минимум 10 чаёв с именами во всех четырёх локалях) и сравнить результаты:

| # | Запрос | Ожидаемый результат | Локаль | Тип ошибки |
|---|--------|---------------------|--------|------------|
| 1 | `зеленый чай` | Правильное совпадение (зелёный чай) | ru | точный |
| 2 | `зёлёный чай` | Совпадение при опечатке (зёл→зел) | ru | опечатка |
| 3 | `чёрный чай улун` | Найти чёрный чай, улун | ru | многословный |
| 4 | `бай му ха` | Бай му ха сиунь (пирог чай) | ru | опечатка/транслит |
| 5 | `earl grey` | Earl Grey tea | en | точный |
| 6 | `arl grey` | Earl Grey tea (пропущена буква) | en | опечатка |
| 7 | `jasmin tea` | jasmine tea (пропущена e) | en | опечатка |
| 8 | `longjing` | Longjing /  | pinyin | точный |
| 9 | `lonjing` | Longjing (пропущена g) | pinyin | опечатка |
| 10 | `tie guanyin` | Tieguanyin /  | pinyin | точный |
| 11 | `tie guayin` | Tieguanyin (опечатка: guan→gua) | pinyin | опечатка |
| 12 | `mao feng` | Mao Feng /  | pinyin | точный |
| 13 | `` | Longjing /  | zh | точный |
| 14 | ` Jing` | Longjing /  (смешанный) | zh | смешанный |
| 15 | `` | Tieguanyin /  | zh | точный |
| 16 | `` | Biluochun /  | zh | точный |
| 17 | `oolong` | улун / Oolong /  | cross | транслитерация |
| 18 | `пуэр` | Pu'er /  | cross | транслитерация |
| 19 | `чай` | Все чаи (короткое слово) | ru | короткий |
| 20 | `tea` | Все чаи | en | короткий |
| 21 | `` | Чай с  (один иероглиф) | zh | короткий/граничный |

**Критерии успеха**:
- Для запросов 1–18: правильный чай входит в топ-5 результатов (идеально — top-1) и не требует точного совпадения без опечаток.
- Для запросов 19–20: возвращается хотя бы несколько чаёв (pg_trgm обычно терпит неудачу для 1–2 символьных запросов, а поисковый движок должен справляться).
- Для запроса 21: хотя бы один чай (Meilisearch и Typesense обрабатывают одиночные символы; pg_trgm даёт пустой результат).

---

## 5. «Не делать» (Do Not Do)

1. **Не использовать pg_trgm как единственный механизм поиска**. Он не обеспечивает коррекцию опечаток для кириллицы/пиньинь на уровне Levenshtein, а для иероглифов триграммы вообще лишены смысла; короткие строки (<3 символов) не находятся [www.postgresql.org](https://www.postgresql.org/docs/current/pgtrgm.html)[www.alibabacloud.com](https://www.alibabacloud.com/blog/performance-optimization-of-fuzzy-queries-for-chinese-characters-using-postgresql-trgm_595634).
2. **Не разворачивать OpenSearch на одиночной маленькой VM**. Минимальный JVM heap 512 МБ, а Docker-образ ~1 ГБ; это не оставит ресурсов PostgreSQL и Spring Boot [hub.docker.com](https://hub.docker.com/r/opensearchproject/opensearch).
3. **Не пытаться реализовать полноценную CDC-синхронизацию на этапе MVP**. Debezium/Kafka потребуют дополнительного always-on сервиса на VM. Application-level sync и периодический polling — достаточны.
4. **Не добавлять zhparser в основной образ PostgreSQL без тестов**. Оно может конфликтовать с `unaccent` при совместной работе; кроме того, потребуется отдельный кастомный Dockerfile и пересборка образа при обновлении PG [hub.docker.com](https://hub.docker.com/r/zhparser/zhparser).
5. **Не делать полную переиндексацию при каждом изменении** (например, не перезаписывать весь индекс). Использовать инкрементальный upsert.
6. **Не включать лишние поля в поисковый индекс** (например, `description` или `history`) — они увеличат RAM без пользы для поиска, так как поиск должен вестись только по именам.

---

## 6. Что стоит проверить до реализации

- **Текущую версию Meilisearch**: на момент написания последняя стабильная — v1.12. Проверить Docker registry [hub.docker.com](https://hub.docker.com/r/getmeili/meilisearch).
- **RAM на VM**: если Meilisearch потребляет >150 МБ при тестовом заполнении, рассмотреть Typesense с его меньшим RAM (но убедиться в наличии 2 vCPU).
- **Работу Charabia с pinyin с тонами**: небольшой тест: проиндексировать чай с именем `"mǎ fēng"` и поискать `"ma feng"` — должен находиться.
- **Наличие Java 17 в среде сборки**: meilisearch-java v0.20.1 требует JDK 17+ [github.com](https://github.com/meilisearch/meilisearch-java).
- **Совместимость версий**: строго зафиксировать версии образов и SDK, так как API могут меняться.