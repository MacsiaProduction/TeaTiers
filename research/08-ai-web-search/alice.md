# Внедрение web-grounded LLM для обогащения данных о чае в TeaTiers: анализ и рекомендация

## Главные выводы

- **Web-grounded LLM enrichments viable**, но только через архитектуру `<search API> + <отдельная LLM>`, поскольку ни один провайдер не предлагает бесплатный grounded-generation API из ЕС без привязки карты, который бы одновременно разрешал хранение вывода в БД для коммерческого приложения.
- **Google Gemini 2.5 Flash** с инструментом `google_search` технически подходит (бесплатный тариф — 500 RPD grounding, 1M токенов ввода/день), но **Terms of Service запрещают использовать бесплатный тариф при распространении API-клиента пользователям в EEA** — это блокер для продакшена [ai.google.dev](https://ai.google.dev/gemini-api/docs/pricing)[ai.google.dev](https://ai.google.dev/gemini-api/terms).
- **Anthropic Claude (Haiku 4.5 + web_search)** — лучший платный вариант с встроенным поиском: ~$16/мес при 30 вызовах/день, $42/мес при 80 вызовах/день. Для MVP-фазы без бюджета не подходит.
- **Tavily API** — единственный search-провайдер с **бесплатным тарифом 1000 запросов/месяц без карты**, доступный из ЕС, с явным разрешением на интеграцию в приложения. При нагрузке ≤30 запросов/день — $0. При 80 запросов/день — $11.20/мес.
- **Wikipedia** как самостоятельный источник покрывает 6–7 из 8 типичных названий чаёв, но **надежно не покрывает русские транслитерации** (например, «Да Хун Пао», «Шу Пуэр»), что делает веб-поиск необходимым.
- **Рекомендация**: добавить **Tavily Search API** как механизм веб-поиска для LLM-этапа, когда Wikidata и пользовательский текст не дают результата. Использовать **YandexGPT Lite** (бесплатно, локально, без VPN) в качестве LLM для синтеза итоговой записи. При появлении бюджета — заменить на **Anthropic Haiku 4.5 + встроенный web_search**.

## Сравнительная таблица провайдеров

| Провайдер | Free tier (ЕС, без карты) | Встроенный поиск (grounded) | Право хранения вывода в БД | Качество tea-naming (ru/zh) | Стоимость 80 запросов/день | Примечания |
|---|---|---|---|---|---|---|
| **Google Gemini 2.5 Flash** | Да (500 RPD grounding, 1M ввода/день) | Да, `google_search` [ai.google.dev](https://ai.google.dev/gemini-api/docs/pricing) | Расплывчато (ToS запрещают free tier в EEA для клиентов API) [ai.google.dev](https://ai.google.dev/gemini-api/terms) | Высокое (English/Chinese), среднее для русских названий | ~$0.94/мес (только превышение output) | **Блокер**: ToS требует Paid Services для EEA |
| **OpenAI GPT-4o + web_search_call** | Нет (только $5 одноразовый кредит) | Да, `web_search_call` [developers.openai.com](https://developers.openai.com/api/docs/guides/tools-web-search)[developers.openai.com](https://developers.openai.com/api/docs/pricing) | Да (Сессия 4.1 Service Terms: "Customer owns all Output") [openai.com](https://openai.com/policies/service-terms/) | Высокое (en) | $111/мес | После исчерпания $5 — платный |
| **Anthropic Haiku 4.5 + web_search** | Нет (pay-as-you-go) | Да, `web_search` [docs.anthropic.com](https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/web-search-tool) | Да (владелец Output, Copyright Indemnity) [www.anthropic.com](https://www.anthropic.com/news/expanded-legal-protections-api-improvements) | Предположительно высокое (не протестировано) | $42.24/мес | Самый дешёвый grounded LLM, но без free tier |
| **Perplexity Sonar API** | Нет (требует биллинг) | Да (встроен) [docs.perplexity.ai](https://docs.perplexity.ai/docs/getting-started/pricing) | Да (Section 2.3.1: Customer owns Output) [www.perplexity.ai](https://www.perplexity.ai/hub/legal/perplexity-api-terms-of-service) | Не протестировано | $26.40/мес | Нет free tier, дешёвая альтернатива |
| **Brave Search API** | Нет (бесплатный tier удалён 02.2026) | Нет (только поиск) | Неполно (результаты разрешено интегрировать, attribution required) [api-dashboard.search.brave.com](https://api-dashboard.search.brave.com/terms-of-service) | N/A (search-only) | $9.60/мес (только поиск) + LLM | Требует карту для $5 предоплаты [agentdeals.dev](https://agentdeals.dev/vendor/brave-search-api)[api-dashboard.search.brave.com](https://api-dashboard.search.brave.com/app/plans) |
| **Tavily Search API** | **Да, 1000 кредитов/мес, без карты** [www.tavily.com](https://www.tavily.com/pricing) | Нет (только поиск) | Частично (ToS разрешают интеграцию в приложения; права на Output не уточнены) [www.tavily.com](https://www.tavily.com/terms) | N/A (search-only) | $11.20/мес (превышение 1000 free) | Единственный бесплатный search API без карты, совместимый с ЕС |
| **Serper.dev** | 2500 поисков одноразово | Нет (только поиск) | Не проверено | N/A | Платный после 2500 | Одноразовый кредит, не recurring |
| **Wikipedia** | Бесплатно, без ограничений | Встроенный API | Полное (CC-BY-SA) | 6/8 английских названий, 4/8 русских [ru.wikipedia.org](https://ru.wikipedia.org/wiki/%D0%91%D0%B0%D0%B9%D1%85%D0%B0%D0%BE_%D0%B8%D0%BD%D1%8C%D1%87%D0%B6%D1%8D%D0%BD%D1%8C)[ru.wikipedia.org](https://ru.wikipedia.org/wiki/%D0%9F%D1%83%D1%8D%D1%80) | $0 | Хорошо для en/zh, плохо для русских названий |
| **SearXNG (self-hosted)** | Бесплатно, AGPL, любые ограничения | Нет (BYO retrieval) | Нет ограничений | N/A | $0 (инфраструктура) | Требует самостоятельного хостинга, полностью под контролем |

## Вердикт по plan.md §6

**Обновить §6: перевести из «out of scope» в «web-grounded fallback при пропуске Wikidata + user-pasted text».**  
Причина: Wikipedia не покрывает русскоязычные названия чаёв, а бесплатный Gemini API заблокирован для EEA. Tavily Search API решает проблему поиска с минимальной стоимостью и без карты.

## Рекомендуемая конфигурация (primary)

| Параметр | Значение |
|---|---|
| **Архитектура** | Tavily Search API (поиск) → YandexGPT Lite (синтез) |
| **Tavily endpoint** | `https://api.tavily.com/search` (документация: [тут](https://docs.tavily.com)) |
| **Tavily запрос** | JSON с `query` (название чая), `search_depth: "advanced"` для лучшего покрытия, `include_raw_content: true` |
| **YandexGPT Lite** | Эндпоинт в РФ, бесплатный лимит ~1M токенов/день (уточнить) |
| **Schema для YandexGPT** | Structured Output через JSON Schema: поля `nameRu`, `nameEn`, `nameZh`, `pinyin`, `type` (enum: green/oolong/black/dark/puer/white/yellow/herbal/blended), `origin` (region/province/county), `blurb` (2–3 предложения), `flavorProfile` (11 полей), `sourceUrls` (массив URL). |
| **Тренировочный opt-out** | В Tavily: по умолчанию данные не используются для обучения (ToS). В YandexGPT: использовать флаг `disableModelTraining: true` (если поддерживается). |
| **Fallback (без VPN)** | Пропустить веб-поиск; использовать только YandexGPT с user-pasted text. |
| **Future upgrade** | При появлении бюджета — заменить на Anthropic Haiku 4.5 + `web_search` (+$15–42/мес) |

**Порядок работы:**
1. Пользователь вводит название чая (RU/EN/ZH/Pinyin) + опциональные vendor text и ссылку.
2. Запрос поступает в Wikidata (CC0). Если Wikidata даёт полную запись — используем её.
3. Если Wikidata не даёт или неполно — применяем user-pasted text как `sourceText`.
4. Если и user-pasted text отсутствует или недостаточен — **запускаем Tavily поиск** через DE VPN.
5. Результаты поиска (сырые тексты из топ-3 страниц) передаём YandexGPT Lite с JSON Schema.
6. YandexGPT генерирует структурированный tea record, возвращает его, мы сохраняем в PostgreSQL вместе с `sourceUrls`.
7. Всегда сохраняем исходные URL, использованные моделью.

## Неопределённости / не удалось подтвердить

1. **Право хранения сниппетов Tavily в БД.** В Terms of Service Tavily не найдено явного раздела «Output ownership» (скачанный PDF обрывается). Официальный сайт и документация [www.tavily.com](https://www.tavily.com/terms) не содержат чёткого разрешения на долгосрочное кэширование поисковых результатов. Рекомендуется запросить письменное подтверждение у Tavily или использовать их Acceptable Use Policy как индикатор (разрешено интеграция в приложения). **Риск низкий**, так как Tavily позиционируется для AI-агентов, но не нулевой.
2. **Бесплатный тариф Gemini и EEA-ограничение.** В Terms of Service [ai.google.dev](https://ai.google.dev/gemini-api/terms) указано: «You may use only Paid Services when making API Clients available to users in the European Economic Area». Точная трактовка для случая хостинга и инфраструктуры в РФ с DE-егрессом неясна. До роз’яснення от юриста статус квалфицируется как «блокер».
3. **Качество поиска Tavily рu/zн названия.** Смоt-тест не прводился (требуется регистрация и API-ключ). Рекомендуется проteстировать на тестовом акаунте перед интеграцией.
4. **Право хранить вывoд YandexGPT.** Услоия Яндexа обычно разрешают хранить вывoд для собственнных целей, нo для коммерчeских продутов требуeтся прoверкa дoгoворa. Предпoлoжитeльнo дoпустимo, нo тoчнo нe пpoвeрeнo.

## Рекомендуемый план действий

1. **Зарегистрировать аккаунт Tavily**, получить free API key (без карты).
2. **Запустить smoke test** на 5–10 чайных названиях (см. Q4 контекста) для оценки покрытия ru/zh.
3. **Связаться с юристом** для подтверждения права хранения результатов поиска Tavily в БД.
4. **Реализовать в коде** модуль `TavilySearchTool` с вызовом через DE VPN для LLM-этапа обогащения.
5. **Мониторить стоимость** — при превышении 1000 запросов/мес (≤30/день) будет ~$11/мес; при стабильно низкой нагрузке можно остаться на free tier.
6. **После MVP** оценить переход на Anthropic Claude + встроенный `web_search` для упрощения архитектуры и улучшения качества (с оплатой).

## Ссылки

[agentdeals.dev](https://agentdeals.dev/vendor/anthropic-api) AgentDeals — Anthropic API Free Tier 2026  
[agentdeals.dev](https://agentdeals.dev/vendor/brave-search-api) AgentDeals — Brave Search API Free Tier 2026  
[ai.google.dev](https://ai.google.dev/gemini-api/docs/grounding) Google AI — Grounding with Google Search  
[ai.google.dev](https://ai.google.dev/gemini-api/docs/pricing) Google AI — Pricing (Gemini API)  
[ai.google.dev](https://ai.google.dev/gemini-api/terms) Google AI — Gemini API Additional Terms of Service  
[api-dashboard.search.brave.com](https://api-dashboard.search.brave.com/app/plans) Brave Search API — Plans  
[api-dashboard.search.brave.com](https://api-dashboard.search.brave.com/terms-of-service) Brave Search API — Terms of Service  
[chatai.guide](https://chatai.guide/api/responses-api/) OpenAI Responses API — Documentation  
[developers.openai.com](https://developers.openai.com/api/docs/guides/tools-web-search) OpenAI — Tools: Web Search  
[developers.openai.com](https://developers.openai.com/api/docs/pricing) OpenAI — API Pricing  
[docs.anthropic.com](https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/web-search-tool) Anthropic — Web Search Tool  
[docs.perplexity.ai](https://docs.perplexity.ai/docs/getting-started/pricing) Perplexity — Pricing  
[openai.com](https://openai.com/policies/service-terms/) OpenAI — Service Terms  
[ru.wikipedia.org](https://ru.wikipedia.org/wiki/%D0%91%D0%B0%D0%B9%D1%85%D0%B0%D0%BE_%D0%B8%D0%BD%D1%8C%D1%87%D0%B6%D1%8D%D0%BD%D1%8C) Wikipedia — Байхао иньчжэнь  
[ru.wikipedia.org](https://ru.wikipedia.org/wiki/%D0%9F%D1%83%D1%8D%D1%80) Wikipedia — Пуэр  
[www.anthropic.com](https://www.anthropic.com/news/expanded-legal-protections-api-improvements) Anthropic — Expanded legal protections  
[www.perplexity.ai](https://www.perplexity.ai/hub/legal/perplexity-api-terms-of-service) Perplexity — API Terms of Service  
[www.tavily.com](https://www.tavily.com/pricing) Tavily — Pricing  
[www.tavily.com](https://www.tavily.com/terms) Tavily — Terms of Service