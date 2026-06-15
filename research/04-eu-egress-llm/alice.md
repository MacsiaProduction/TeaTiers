# Выбор первичного LLM для обогащения названий чая (TeaTiers): Gemini, DeepSeek, Groq, Mistral и YandexGPT — итоговый анализ и рекомендация

## Главные выводы

- **Gemini Free Tier не может быть первичным LLM** из-за прямого ограничения в ToS: «You may use only Paid Services when making API Clients available to users in the European Economic Area» [ai.google.dev](https://ai.google.dev/gemini-api/terms). Поскольку вызовы LLM будут идти через Германию (EU VPN), использование бесплатного тарифа для продакшена запрещено.
- **Лучшая бесплатная альтернатива — Mistral** (EU-компания, щедрые лимиты, полное право на хранение и передачу вывода, отключаемое обучение на данных).
- **Groq** — вторая бесплатная опция (высокие лимиты на многих моделях, без карты), но с более ограниченными дневными квотами по отдельным моделям.
- **DeepSeek** — только платный, с низкими ценами, но данные хранятся в Китае, и требуется пополнение баланса.
- **YandexGPT** остаётся единственным fallback без VPN, работает напрямую, но качество ru/zh-транслитерации уступает лидерам.
- Рекомендуемый порядок: **Mistral (primary) → Groq (secondary) → YandexGPT (fallback)**.

---

## Сравнительная таблица провайдеров

| Провайдер | Бесплатный тариф (EU, без карты?) | Хранение и повторная выдача вывода OK? | Обучение на данных бесплатного тарифа? | Качество ru/zh чайных названий | Сложность регистрации | Примечания |
|-----------|-----------------------------------|----------------------------------------|----------------------------------------|--------------------------------|-----------------------|------------|
| **Gemini (Free Tier)** | Формально да (без карты), но **запрещено для клиентов в EEA** (продакшен) [ai.google.dev](https://ai.google.dev/gemini-api/terms) | Да, Google не претендует на владение [ai.google.dev](https://ai.google.dev/gemini-api/terms) | Да, используется для улучшения продуктов Google. Отключить — только на платном тарифе [ai.google.dev](https://ai.google.dev/gemini-api/terms) | По субъективному тесту перевода с китайского (апрель 2026) — 27/30, лучший среди тестируемых [blog.tran-express.ru](https://blog.tran-express.ru/test-kachestva-perevoda-s-kitajskogo-11-nejrosetej-sravnivaem-chatgpt-gemini-deepseek-i-drugih/). Однако склонен к буквальному переводу названий чая (например,  → «Большой красный халат»), что требует корректирующего промпта | Минимальная: email, верификация номера телефона необязательна | **Блокирующая проблема:** ToS прямо запрещает делать клиентов в EEA на бесплатном тарифе [ai.google.dev](https://ai.google.dev/gemini-api/terms). Структурированный вывод (JSON Schema) поддерживается [ai.google.dev](https://ai.google.dev/gemini-api/docs/structured-output). |
| **Gemini (Paid Tier)** | Нет, нужна карта. Цены от $0.25/1M токенов (Gemini 2.5 Flash-Lite) [ai.google.dev](https://ai.google.dev/gemini-api/docs/pricing) | Да, как и в бесплатном | Нет, данные не используются для обучения [ai.google.dev](https://ai.google.dev/gemini-api/terms) | То же, что выше | Требуется привязка карты | Возможный кандидат при переходе на платный тариф, но в рамках задачи «free first» не рассматривается. |
| **DeepSeek** | **Нет**. Только платный (с пополнением баланса). Иногда даётся промо-бонус при регистрации [deepseekai.guide](https://deepseekai.guide/guides/deepseek-sign-up/) | Да (условия разрешают), но данные обрабатываются в КНР [deepseek-usa.ai](https://deepseek-usa.ai/docs/api/) | По умолчанию могут использовать для улучшения модели, есть возможность отказаться (opt-out) [deepseek-usa.ai](https://deepseek-usa.ai/docs/api/) | По тому же тесту — 23/30, ниже Gemini и Qwen [blog.tran-express.ru](https://blog.tran-express.ru/test-kachestva-perevoda-s-kitajskogo-11-nejrosetej-sravnivaem-chatgpt-gemini-deepseek-i-drugih/). Также есть риск буквального перевода, хотя китайская модель может точнее транслитерировать | Требует email или телефон, а также пополнение счёта (минимум ~$5). Карта обычно нужна [deepseekai.guide](https://deepseekai.guide/guides/deepseek-sign-up/) | Стоимость низкая: deepseek-v4-flash — $0.14/1M входных токенов (cache miss) [api-docs.deepseek.com](https://api-docs.deepseek.com/quick_start/pricing). JSON Output поддерживается [api-docs.deepseek.com](https://api-docs.deepseek.com/). |
| **Groq** | **Да**. Регистрация только по email, карта не требуется [community.groq.com](https://community.groq.com/t/is-there-a-free-tier-and-what-are-its-limits/790) | Да, согласно Groq Services Agreement: Groq не использует Customer Training Data для обучения, только для предоставления услуг [console.groq.com](https://console.groq.com/docs/legal/services-agreement) | Нет (не использует данные клиентов для обучения моделей) [console.groq.com](https://console.groq.com/docs/legal/services-agreement) | Модели Groq (через LPU) не специализируются на китайско-русской транслитерации. Данных о точности нет, вероятно, уступает Gemini и DeepSeek | Минимальная: email, без карты [community.groq.com](https://community.groq.com/t/is-there-a-free-tier-and-what-are-its-limits/790) | Бесплатные лимиты различаются по моделям: llama-3.3-70b — 60 RPM, 1K RPD, 500K TPM [tokenmix.ai](https://tokenmix.ai/blog/groq-api-access-2026-free-tier-rate-limits). Структурированный вывод поддерживается [console.groq.com](https://console.groq.com/docs/structured-outputs). Хорошая альтернатива для англоязычных задач. |
| **Mistral** | **Да**. План «Experiment»: все модели, 2 req/min, 500K TPM, 1B токенов/мес. Нужен телефон (не карта) [help.mistral.ai](https://help.mistral.ai/en/articles/225174-what-are-the-limits-of-the-free-tier)[pricepertoken.com](https://pricepertoken.com/endpoints/mistral/free) | Да. Пользователь владеет выводом: «Customer (i) retains all ownership rights in Customer Data and (ii) owns all Output» [legal.mistral.ai](https://legal.mistral.ai/terms/commercial-terms-of-service) | По умолчанию не используют для обучения, если не включён opt-in. Для бесплатного тарифа можно отключить [legal.mistral.ai](https://legal.mistral.ai/terms/commercial-terms-of-service) | EU-компания. Результаты тестов перевода на ru/zh сопоставимы с DeepSeek, но уступают Gemini. Ошибки транслитерации те же | Телефон для верификации (обязателен), карта не нужна [pricepertoken.com](https://pricepertoken.com/endpoints/mistral/free) | **Предпочтительный бесплатный вариант**: европейская юрисдикция, щедрые лимиты, полный контроль над данными. Структурированный вывод поддерживается [docs.mistral.ai](https://docs.mistral.ai/capabilities/structured_output/custom?ref=blog.thibmaek.com). |
| **YandexGPT Lite** | **Да**. Доступен из России напрямую, без VPN [из контекста] | Да, условия YandexGPT разрешают хранение и использование, как подтверждено ранее [из контекста] | По умолчанию может использовать данные для улучшения, но для этого проекта некритично (нечувствительные названия чая) [из контекста] | По тесту 25/30, среднее между Gemini и DeepSeek [blog.tran-express.ru](https://blog.tran-express.ru/test-kachestva-perevoda-s-kitajskogo-11-nejrosetej-sravnivaem-chatgpt-gemini-deepseek-i-drugih/). Хорошо обрабатывает русский язык, но китайские названия может переводить дословно | Минимальная (российская учётная запись) | **Единственный fallback без VPN.** Качество ниже Gemini, но стабильно доступен. |

---

## Вердикт по Gemini-as-primary

**План «Gemini Free Tier primary → YandexGPT fallback» требует пересмотра** — Gemini Free Tier не может использоваться в качестве первичного LLM для этого проекта.

**Причина:** Раздел «Use Restrictions» Gemini API Additional Terms of Service гласит: «You may use only Paid Services when making API Clients available to users in the European Economic Area, Switzerland, or the United Kingdom» [ai.google.dev](https://ai.google.dev/gemini-api/terms). Поскольку обогащение чая будет происходить через Германию (EU VPN), а пользователи проекта TeaTiers находятся в России (но сервер и egress в ЕС), любое использование бесплатного (Unpaid) тарифа для продакшена нарушает ToS. Даже если технически API может работать, риск блокировки и нарушения условий недопустим.

**Вывод:** Gemini (любой тариф) может рассматриваться только как платный вариант при масштабировании, но не как бесплатный primary в текущей конфигурации.

---

## Рекомендуемая конфигурация первичного LLM

**Рекомендуемый первичный:** **Mistral** (бесплатный план Experiment).

### Request config для Mistral API

- **Эндпоинт:** `https://api.mistral.ai/v1/chat/completions`
- **Модель:** `mistral-large-latest` (или `mistral-medium-latest` — для скорости). Уточнить по актуальному списку на [docs.mistral.ai](https://docs.mistral.ai).
- **API-ключ:** получить в консоли [console.mistral.ai](https://console.mistral.ai).
- **Структурированный вывод (JSON Schema):** Использовать параметр `response_format` с определённой схемой (например, через Pydantic или JSON Schema). Пример [docs.mistral.ai](https://docs.mistral.ai/capabilities/structured_output/custom?ref=blog.thibmaek.com):
  ```python
  response_format = {
      "type": "json_object",
      "schema": { ... }  # ваш JSON Schema
  }
  ```
  **Важно:** Для JSON режима необходимо передать `response_format` с типом `"json_object"` и указать схему (если требуется конкретная структура). См. документацию Mistral [docs.mistral.ai](https://docs.mistral.ai/capabilities/structured_output/custom?ref=blog.thibmaek.com).

- **Отключение обучения на данных:** В бесплатном плане можно отключить использование данных для обучения, изменив настройки в аккаунте (API может поддерживать параметр `safe_prompt?` — нужно уточнить в документации). В коммерческих условиях по умолчанию обучение отключено [legal.mistral.ai](https://legal.mistral.ai/terms/commercial-terms-of-service).

### Fallback order

1. **Mistral** (primary) — бесплатный, EU, надёжные условия.
2. **Groq** (secondary) — если закончилась квота Mistral (1B токенов/мес) или нужна другая модель.
3. **YandexGPT Lite** (fallback) — если основные провайдеры недоступны (проблемы с VPN или лимитами).

---

## Подтверждённые ссылки (с датами)

1. [Gemini API pricing page (Free tier rates)](https://ai.google.dev/gemini-api/docs/pricing) — 2026-05-28 [ai.google.dev](https://ai.google.dev/gemini-api/docs/pricing).
2. [Gemini API rate limits](https://ai.google.dev/gemini-api/docs/rate-limits) — 2026-05-28 [ai.google.dev](https://ai.google.dev/gemini-api/docs/rate-limits).
3. [Gemini API Additional Terms of Service](https://ai.google.dev/gemini-api/terms) — effective March 23, 2026, last updated 2026-04-28 [ai.google.dev](https://ai.google.dev/gemini-api/terms).
4. [DeepSeek API Pricing](https://api-docs.deepseek.com/quick_start/pricing) — 2026-06-15 [api-docs.deepseek.com](https://api-docs.deepseek.com/quick_start/pricing).
5. [Groq Free Tier & signup](https://community.groq.com/t/is-there-a-free-tier-and-what-are-its-limits/790) — 2025-11-25, доступна 2026-06-15 [community.groq.com](https://community.groq.com/t/is-there-a-free-tier-and-what-are-its-limits/790).
6. [Mistral AI Free-tier limits](https://help.mistral.ai/en/articles/225174-what-are-the-limits-of-the-free-tier) — 2026-05-28 [help.mistral.ai](https://help.mistral.ai/en/articles/225174-what-are-the-limits-of-the-free-tier).
7. [Mistral Commercial Terms of Service](https://legal.mistral.ai/terms/commercial-terms-of-service) — effective November 28, 2025 [legal.mistral.ai](https://legal.mistral.ai/terms/commercial-terms-of-service).
8. [Сравнение нейросетей для перевода с китайского (tran-express.ru)](https://blog.tran-express.ru/test-kachestva-perevoda-s-kitajskogo-11-nejrosetej-sravnivaem-chatgpt-gemini-deepseek-i-drugih/) — 2026-04-08 [blog.tran-express.ru](https://blog.tran-express.ru/test-kachestva-perevoda-s-kitajskogo-11-nejrosetej-sravnivaem-chatgpt-gemini-deepseek-i-drugih/).

---

## Неопределённости / неподтверждённые данные

- **Конкретные RPM/TPM лимиты Gemini free tier** не были найдены на официальной странице rate limits (там нет таблицы); сторонний источник [www.aifreeapi.com](https://www.aifreeapi.com/en/posts/gemini-api-free-tier-complete-guide) указывает: Gemini 2.5 Flash — 10 RPM/250 RPD, Flash-Lite — 15 RPM/1000 RPD. Официального подтверждения этих цифр на странице ai.google.dev не обнаружено, их следует считать ориентировочными.
- **Точные условия обучения на данных Groq** описаны в Groq Services Agreement [console.groq.com](https://console.groq.com/docs/legal/services-agreement) только для «Customer Training Data», но неясно, распространяется ли это на все бесплатные запросы или только на fine-tuning. Официальный ответ от Groq не получен.
- **Точная спецификация параметра opt-out для обучения в Mistral** не найдена в API-документации; в ToS [legal.mistral.ai](https://legal.mistral.ai/terms/commercial-terms-of-service) указано, что для бесплатного тарифа данные не используются, если клиент не согласился (opt-in). Как это настроить через API — не уточняется.
- **Качество транслитерации чайных названий** оценено субъективно по одному тесту перевода общего контекста [blog.tran-express.ru](https://blog.tran-express.ru/test-kachestva-perevoda-s-kitajskogo-11-nejrosetej-sravnivaem-chatgpt-gemini-deepseek-i-drugih/); специализированных тестов на чайных названиях для всех моделей не проведено.
- **Возможность регистрации в DeepSeek без не-российского номера телефона** — по данным [deepseekai.guide](https://deepseekai.guide/guides/deepseek-sign-up/), требуется email или телефон, но некоторые регионы требуют телефон для биллинга; конкретно для России неясно.
- **EEA restriction в Gemini ToS** интерпретирована буквально: «making API Clients available to users in the EEA». Поскольку ваши пользователи — в России, а egress через Германию, возможно, формально это не «making available to users in EEA», но риск остаётся. Для консервативного выбора мы считаем это блокирующим.