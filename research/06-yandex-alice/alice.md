# Alice AI LLM в Yandex Cloud: характеристика, сравнение с YandexGPT Lite и рекомендация для использования в проекте TeaTiers

## Главное

**Alice AI LLM** — это отдельная модель семейства Yandex Foundation Models, доступная в Yandex Cloud AI Studio, а не потребительский голосовой ассистент «Алиса» или платформа Alice Skills. Она использует те же API-эндоинты, авторизацию (IAM-токен/API-ключ) и настройки логирования, что и YandexGPT Lite, но имеет вдвое больший контекст (64k против 32k токенов) и стоит **в 2,5 раза дороже за входные токены и в 6 раз дороже за выходные**. Для задачи **транслитерации китайских чайных названий (иероглифы → пиньинь → кириллица) отсутствуют специализированные бенчмарки**, однако независимые обзоры называют Alice AI LLM сильнейшей российской моделью по качеству русского языка в целом. **Рекомендуется оставить YandexGPT Lite основным (primary) для обогащения** ввиду его адекватного соотношения цена/качество и достаточности для 80% случаев, а Alice AI LLM использовать **только для сложных или высокорисковых случаев**, когда Lite даёт низкую уверенность. В качестве компромиссного варианта по цене можно также рассмотреть **Alice AI LLM Flash** — самую дешёвую модель среди всех трёх.

---

## 1. Факт-лист: Alice AI LLM vs YandexGPT Lite vs Alice AI LLM Flash

### 1.1. Идентификация и базовая информация

| Параметр | Alice AI LLM | YandexGPT Lite 5 | Alice AI LLM Flash |
|---|---|---|---|
| **Точный URI модели** | `gpt://<folder_ID>/aliceai-llm` | `gpt://<folder_ID>/yandexgpt-5-lite` | `gpt://<folder_ID>/aliceai-llm-flash` |
| **Контекстное окно** | 64k (65 536 токенов) [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models.html)[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models/) | 32k (32 768 токенов) [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models.html)[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models/) | 64k (65 536 токенов) [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models.html)[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models/) |
| **Доступные API** | Text generation API + OpenAI-compatible API [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models.html)[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models/) | Text generation API + OpenAI-compatible API [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models.html)[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models/) | OpenAI-compatible API [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models.html)[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models/) |
| **Связь с потребительской «Алисой»** | Разные продукты; Alice AI LLM — это модель для разработчиков через Yandex Cloud API, она **не является** голосовым ассистентом или платформой Alice Skills | — | То же, что и Alice AI LLM |
| **Поддержка структурированного вывода (JSON Schema)** | Да, через параметр `json_schema` [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/structured-output) | Да, через параметр `json_schema` [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/structured-output) | Не указана в документации, но как OpenAI-compatible модель может поддерживать; не подтверждено |

Вывод: Alice AI LLM — это полноценная генеративная модель Яндекса, **отличная** от потребительского ассистента, с вдвое бо́льшим контекстом, чем у YandexGPT Lite.

### 1.2. Цены (синхронный режим, USD без НДС)

| Модель | Input (за 1K токенов) | Output (за 1K токенов) | Cached (за 1K токенов) | Tool tokens (за 1K токенов) |
|---|---|---|---|---|
| **Alice AI LLM** | $0,00409836 | $0,009836064 | $0,00409836 | $0,0010655736 |
| **YandexGPT Lite 5** | $0,001639344 | $0,001639344 | $0,001639344 | $0,001639344 |
| **Alice AI LLM Flash** | $0,000819672 | $0,001639344 | $0,000204918 | $0,000204918 |

[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/pricing)

Ключевое различие: Alice AI LLM **выходит в 6 раз дороже по выходным токенам**, чем YandexGPT Lite. Flash-версия наоборот — самая дешёвая, но её возможности для сложной транслитерации не подтверждены.

**Грант и квоты:** на странице pricing [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/pricing) не указано, что Alice AI LLM исключена из бесплатного гранта для новых аккаунтов (обычно грант покрывает все Foundation Models). Однако точного подтверждения этому найти не удалось — **рекомендуется проверить в личном кабинете**. Квоты на генерацию общие: 10 одновременных синхронных запросов [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/limits).

### 1.3. Аутентификация, эндоинты и отключение логирования

- **Эндоинты**: идентичны для всех моделей:
  - REST (Text Generation API): `https://ai.api.cloud.yandex.net/foundationModels/v1/completion`
  - OpenAI-compatible: `https://ai.api.cloud.yandex.net/v1` (base_url), с указанием `project=<folder_ID>` [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/operations/generation/create-prompt).
- **Аутентификация**: IAM-токен (заголовок `Authorization: Bearer <IAM_token>`) или API-ключ (заголовок `Authorization: Api-Key <API_key>`) [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/operations/disable-logging)[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/operations/generation/create-prompt).
- **Отключение логирования**: поддерживается для всех моделей через заголовок `x-data-logging-enabled: false` (или `enable_server_data_logging=False` в SDK) [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/operations/disable-logging). Никаких Alice-специфичных ограничений по логированию нет.

### 1.4. Качество для задачи (русский язык и транслитерация китайских названий)

- **Русский язык**: независимый обзор mysummit.school (март 2026) называет Alice AI LLM **«сильнейшей российской моделью в целом»**, сильнее YandexGPT Pro 5.1 и Lite. При этом отмечается проблема с «обрыванием» ответов в сценариях обучения и развития [mysummit.school](https://mysummit.school/blog/en/yandexgpt-review-2026/). YandexGPT Pro 5.1 и Lite в тесте оказались в «нижнем ярусе» (часто отказываются отвечать) [mysummit.school](https://mysummit.school/blog/en/yandexgpt-review-2026/).
- **Транслитерация  → «Да Хун Пао»**: **специализированных бенчмарков или тестов для этой конкретной задачи не найдено**. Обе модели обучены на русскоязычных и мультиязычных данных, но их способность корректно передавать китайский пиньинь кириллицей (вместо буквального перевода «Большой красный халат») — **не подтверждена** и требует самостоятельного тестирования.

**Оценка**: Alice AI LLM, вероятно, будет лучше понимать контекст задачи и выдавать более литературный русский текст, чем YandexGPT Lite. Однако для гарантии результата — особенно для редких китайских сортов — **потребуется отдельный booster** (Groq с Qwen или DeepSeek), как уже запланировано.

---

## 2. Пример запроса для обогащения чая (JSON Schema)

Ниже — минимальный рабочий пример для **OpenAI-совместимого API** на Python (curl-версия строится аналогично [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/operations/generation/create-prompt)).

```python
import openai

YANDEX_API_KEY = "<API_key>"          # API-ключ сервисного аккаунта
YANDEX_FOLDER_ID = "<folder_ID>"      # ID каталога

client = openai.OpenAI(
    api_key=YANDEX_API_KEY,
    base_url="https://ai.api.cloud.yandex.net/v1",
    project=YANDEX_FOLDER_ID,
    default_headers={
        "x-data-logging-enabled": "false"   # отключаем логирование
    }
)

response = client.chat.completions.create(
    model="gpt://<folder_ID>/aliceai-llm",  # или yandexgpt-5-lite
    messages=[
        {
            "role": "system",
            "text": (
                "Ты — эксперт по китайскому чаю. Твоя задача — для каждого названия чая "
                "определить его имя на русском (транслитерация, НЕ перевод), пиньинь, "
                "иероглифы и тип чая. Никогда не переводи название на русский буквально — "
                "используй устоявшуюся транслитерацию (например,  → 'Да Хун Пао', "
                "а не 'Большой красный халат')."
            )
        },
        {
            "role": "user",
            "text": "Чай: , , "
        }
    ],
    response_format={
        "type": "json_schema",
        "json_schema": {
            "schema": {
                "type": "object",
                "properties": {
                    "teas": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "name_ru": {"type": "string"},
                                "name_pinyin": {"type": "string"},
                                "name_hanzi": {"type": "string"},
                                "tea_type": {"type": "string"}
                            },
                            "required": ["name_ru", "name_pinyin", "name_hanzi", "tea_type"]
                        }
                    }
                },
                "required": ["teas"]
            }
        }
    },
    temperature=0.1,
    max_tokens=800
)

print(response.choices[0].message.content)
```

**Важно**: 
- Для Alice AI LLM Flash в параметре `response_format` поддержка JSON Schema **официально не подтверждена** — в таблице моделей у Flash указаны только OpenAI-compatible API, но пример с `json_schema` в документации [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/structured-output) показан для YandexGPT. Для безопасности используйте `json_schema` с `aliceai-llm` (флагман), а для Flash сначала протестируйте без строгой схемы.
- Вместо `chat.completions.create` можно использовать `.responses.create` ([aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/operations/generation/create-prompt)).

---

## 3. Рекомендация для TeaTiers

### Какой моделью делать primary (основную)?

**Оставить YandexGPT Lite 5 (`gpt://<folder_ID>/yandexgpt-5-lite`).**

Обоснование:
1. **Цена**: Alice AI LLM стоит в 2,5–6 раз дороже. Для массового обогащения «неизвестных» чаёв это даст значительный рост затрат без гарантированного улучшения результата для 80% случаев.
2. **Контекст**: 32k Lite достаточно, т.к. типичный промпт обогащения занимает 0.5–2k токенов.
3. **Качество**: для простых, известных чаёв (зелёный, улун, пуэр) Lite не уступает; для сложных случаев в любом случае нужен booster (Groq/DeepSeek).
4. **Надёжность**: Lite — зрелая модель, её поведение предсказуемо.

### Когда использовать Alice AI LLM?

Использовать **только для сложных или высокорисковых случаев**, например:
- Чай с неочевидной транслитерацией;
- Если Lite даёт `confidence < 0.7` (в вашей системе оценки);
- Для ручного верификатора/модератора.

### Когда использовать Alice AI LLM Flash?

Flash — самая дешёвая модель ($0,00082 input, $0,00164 output) — может быть **отличным вариантом для массовой обработки простых запросов**, если после тестирования подтвердится, что её качество не уступает Lite. Однако **строгий JSON Schema пока не гарантирован**.

### Итоговая конфигурация (рекомендуемая)

```python
# Основной эндоинт — YandexGPT Lite
YANDEX_MODEL_PRIMARY = "gpt://<folder_ID>/yandexgpt-5-lite"
# Для сложных случаев — Alice AI LLM
YANDEX_MODEL_FALLBACK = "gpt://<folder_ID>/aliceai-llm"
# (Опционально) Для массовой дешёвой обработки — Alice AI LLM Flash
YANDEX_MODEL_CHEAP = "gpt://<folder_ID>/aliceai-llm-flash"
```

Эндоинт: `https://ai.api.cloud.yandex.net/v1` (OpenAI-совместимый).  
Аутентификация: `api_key=<API_ключ>`.  
Логирование: `x-data-logging-enabled: false` (обязательно).  
Структурированный вывод: `response_format = {"type": "json_schema", "json_schema": {...}}`.

---

## 4. Неподтверждённая информация / зоны неопределённости

| Что не удалось подтвердить | Пояснение |
|---|---|
| Включена ли Alice AI LLM в бесплатный грант для новых аккаунтов | На странице pricing [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/pricing) нет явного исключения, но и прямого указания на включение тоже нет. Скорее всего, да, но стоит проверить. |
| Поддерживает ли Alice AI LLM Flash JSON Schema (`response_format.json_schema`) | В документации [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models.html)[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models/) у Flash указаны только OpenAI-compatible API, пример с json_schema [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/structured-output) — только для YandexGPT. |
| Точное качество транслитерации китайских чайных названий у Alice AI LLM vs YandexGPT Lite | Бенчмарков по этой задаче не найдено. Оценка основана на общих обзорах русского языка [mysummit.school](https://mysummit.school/blog/en/yandexgpt-review-2026/) и логике. |
| Есть ли у Alice AI LLM отдельные, более строгие региональные ограничения (например, недоступность в определённых дата-центрах) | В документации [aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models.html)[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models/)[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/operations/disable-logging) Alice ничем не выделяется на фоне YandexGPT. |

---

## 5. Ссылки по тексту (все использованные источники)

[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models.html) Доступные модели AI Studio (модели и URI) — aistudio.yandex.ru, обновлено 06.06.2026  
[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/models/) Список моделей общего инстанса — aistudio.yandex.ru  
[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/structured-output) Форматирование ответов моделей (JSON Schema) — aistudio.yandex.ru  
[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/limits) Квоты и лимиты AI Studio — aistudio.yandex.ru  
[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/operations/disable-logging) Отключение логирования запросов — aistudio.yandex.ru  
[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/operations/generation/create-prompt) Отправка базового запроса через Responses API — aistudio.yandex.ru  
[aistudio.yandex.ru](https://aistudio.yandex.ru/docs/en/ai-studio/pricing) Ценовая политика AI Studio — aistudio.yandex.ru  
[mysummit.school](https://mysummit.school/blog/en/yandexgpt-review-2026/) Обзор YandexGPT 2026 (mysummit.school) — март 2026  
[yandex.cloud](https://yandex.cloud/ru/blog/alice-ai-llm-flash) Анонс Alice AI LLM Flash (блог Yandex Cloud) — 28 мая 2026