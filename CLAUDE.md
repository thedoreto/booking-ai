# CLAUDE.md

## 1. Описание
Мултитенант (multi-hotel) чат асистент за хотелски резервации. REST API (`POST /api/chat`) приема съобщение от UI, отговаря чрез Gemini LLM (LangChain4j) с RAG върху знания за хотела в MongoDB (vector search) и с "tools", които през Kafka питат друг микросервис за стаи/резервации. Има и "shortcuts" – готови бързи въпроси, които връщат отговор директно от Mongo без LLM.

## Архитектурни правила (задължителни)
Важат за трите проекта (booking-ui, booking-ai, booking-system). При всяка промяна първо провери, че не ги нарушава; ако задачата изисква нарушение – спри и питай.
1. **Възможно най-малко заявки към LLM (Gemini).** Това е най-важното правило. Всичко, което може без LLM (бутони, календар, избор на стаи, резервация, отказ, знания по бутон), се прави без LLM. Никога не добавяй второ извикване, когато tool вече е дал отговора (виж `UiActionShortCircuitChatModel`).
2. **UI (чат прозорецът) не знае нищо** освен това, че има чат прозорец, и праща данните на AI асистента (booking-ai). Чатът не вика booking-system, няма бизнес логика и не знае какво прави бутон – показва това, което AI асистентът върне (текст или действие за показване).
3. **AI асистентът праща заявки към бекенда (booking-system) само през Kafka.**
4. **AI асистентът получава данните от бекенда само през Kafka.** Собствената база на booking-ai (`HotelAI`: `knowledge_`, `shortcuts_`, `logs_`) не е бекендът.
5. **Правило 2 важи само за чата.** Страниците на сайта (Hotel Info, Rooms, Bookings и т.н.) са сайтът на хотела и си говорят директно с booking-system през `api.js` – това е правилно и не се мести в AI асистента.
6. **Действията в чата имат отделни адреси в booking-ai и така остават:** `/api/chat` (съобщение или бутон по `shortcutId`), `/api/rooms/available` (избрани дати), `/api/bookings` (избрани стаи), `/api/bookings/cancel` (отказ), `/api/shortcuts`, `/api/rooms/types`. Не ги сливай в `/api/chat` – отделните адреси минават без LLM (правило 1).

## 2. Технологичен стек
- Java 17, Spring Boot 3.4.2 (parent), **Maven** (има `mvnw`)
- LangChain4j `1.0.0-beta1` (`langchain4j-google-ai-gemini-spring-boot-starter`, `@AiService`); модел от `gemini.base.model`, embeddings `gemini-embedding-001`
- MongoDB Atlas (`spring-boot-starter-data-mongodb`, `$vectorSearch`, индекс `autoembed_index`)
- Kafka (`spring-kafka`, Aiven, SSL/PKCS12)
- Lombok, jjwt 0.11.5 и okhttp (в pom, но в кода не се ползват), Actuator
- Docker multi-stage (Temurin 17) за Render; порт през `$PORT`

## 3. Структура (`src/main/java/com/hotel`)
- `BookingAiApplication` – entry point
- `config/CorsConfig` – CORS за `/api/**` (всички origins)
- `langchain/controller` – `AiLangChainController`: `/api/chat`, `/api/shortcuts`, `/api/rooms/available` и `/api/bookings` (последните две – директно към booking-system, без LLM)
- `langchain/assistant/Assistant` – `@AiService` интерфейс със system prompt (на български)
- `langchain/tools/HotelTools` – `@Tool` методи; ползват `HotelBackendClient` и `RoomBookingService`
- `langchain/service/HotelBackendClient` – Kafka request/reply към booking-system (correlationId + `CompletableFuture`, timeout 5s, `@KafkaListener` за отговорите); `error` в отговора → `HotelBackendException`
- `langchain/service/RoomBookingService` – свободни стаи и създаване на резервации (event `create_booking`); връща `TenantContext.UiAction`
- `langchain/content/HotelContentRetriever` – RAG retriever, чете `TenantContext`
- `langchain/context/TenantContext` – ThreadLocal за hotelId и `ChatUser`
- `langchain/context/ChatUser` – влезлият потребител от header `Authorization: Bearer <JWT>` (без токен – гост). booking-ai **не** проверява подписа: токенът отива през Kafka и booking-system взима потребителя от него. `id` (непроверен) е само за логовете; `memoryKey()` – SHA-256 на токена за паметта на чата
- `langchain/config` – `AiConfig` (Gemini beans), `LangChainConfig` (ChatMemory), `KafkaCertInitializer`
- `langchain/service|repository|model` – Shortcuts и `KafkaService` (producer)
- `langchain/tools/ShortcutToolRunner` – бутон с `action.type: "tool"` изпълнява tool от `HotelTools` по име (`@Tool` name или името на метода), без Gemini; параметрите са `null`
- `langchain/log` – структурирани логове за отчетите в `logs_<hotelId>`, по един запис на действие на госта:
  - `ChatFlow` – действие от няколко заявки (`new_booking`: календар → търсене → резервация; `cancel_booking`: списък → откази) е **един документ**, който се допълва (`flowId`, `status`, `steps[]`, `gemini`). `flowId` се връща на UI в `data` и UI го праща обратно със следващата стъпка.
  - `ChatLogEntry` – отделен запис (въпрос в чата, бутон със знание) или стъпка в `ChatFlow` (`outcome`, `errorType`, `durationMs`, текстове до 500 знака)
  - `ChatLogService` – асинхронен запис в Mongo в отделна нишка `chat-log-writer` (опашка 1000), upsert по `flowId` за стъпките, TTL индекс 180 дни по `timestamp`
  - `GeminiUsageTracker` (`ChatModelListener`) – извиквания, токени и tools на Gemini за текущата заявка
- `knowledge/` – `KnowledgeService` (embed + vector search), `KnowledgeRepository`, `KnowledgeDocument`

## 4. Команди
```bash
./mvnw clean package -DskipTests   # build (jar в target/)
./mvnw test                        # всички тестове; contextLoads изисква реални Mongo/Kafka/Gemini
./mvnw -o test -Dtest='Chat*Test,Shortcut*Test,KnowledgeRepositoryTest,RoomBookingServiceTest,BoundedChatMemoryStoreTest'   # само тестовете без облак
./mvnw spring-boot:run             # локално, порт 8081
docker build -t booking-ai .       # Docker образ (слуша на $PORT, default 8080)
```
Няма линтер/форматер конфигуриран.

## 5. Конвенции
- Слоеве: controller → service → repository; конструкторна инжекция (без Lombok в сегашния код).
- **Multi-tenancy чрез имена на колекции**: `knowledge_<hotelId>`, `shortcuts_<hotelId>`, `logs_<hotelId>`. hotelId идва от request body → `TenantContext` (ThreadLocal), който контролерът чисти във `finally`.
- Потребителските съобщения и промптове са на български; отговорите на асистента също.
- Error handling: контролерът хваща всичко и връща `NewChatResponse(reply, actionType)` с приятелско съобщение – без HTTP error кодове. Грешките се логват предимно с `System.out/err` (не с SLF4J).
- UI действие: tool записва `TenantContext.UiAction(actionType, reply, data)` (`OpenDatePickerException(start, end)` → `OPEN_DATE_PICKER` с `data` = казаните дати за попълване на календара; `/api/rooms/available` → `SELECT_ROOMS` с `{startDate, endDate, rooms}`). `UiActionShortCircuitChatModel` прескача следващото извикване към Gemini, а контролерът връща `NewChatResponse(reply, actionType, data)`.
- Kafka payload = JSON `Map` с `hotelId` и `event`; действията от името на потребител (`create_booking`, `get_upcoming_bookings`, `cancel_booking`, `get_reservations`) пращат `token` (JWT-то от UI), не `userId`. Топици: `hotel-requests-topic` / `hotel-replies-topic` (tools RPC). Логовете не минават през Kafka.
- **Git:** commit само когато потребителката изрично каже „комитни“. Одобрен план, в който има commit, или „продължи“ не са достатъчни – след промените спри и попитай. Push не се прави никога, прави го тя ръчно.
- **Ръчни проверки:** потребителката сама чете кода и тества локално и в Render, преди да приеме промени. Не поставяй задачи и бележки от вида „тест в браузъра“, „провери в Atlas“, „провери/изтрий след deploy“, „не е тествано в браузъра“ – нито в отговорите, нито в `PLAN.md`/`SESSIONS_LOG.md`. Казвай само какво си проверил ти (компилация, unit тестове, build, lint).

## 6. Капани
- **Тайни в `application.properties`** (Mongo URI с парола, Gemini ключ, Kafka пароли) и приватни ключове/keystore в `src/main/resources/certs/` са в git индекса. Не ги печатай/копирай; препоръчително е да се преместят в env променливи и да се ротират.
- `KafkaCertInitializer` копира сертификатите в `/tmp/certs` при старт; `application.properties` сочи натам (`file:///tmp/certs/...`). Работи само на Linux/Unix-подобна среда и без сертификатите приложението не стартира.
- Тестът `contextLoads` и стартът изискват достъп до Atlas и Aiven Kafka – няма mock/embedded конфигурация.
- `Assistant.chat(memoryId, hotelName, ...)` получава `hotelId` като `{hotelName}`. Паметта (`ChatMemoryProvider`, 10 съобщения) е по `hotelId:user:<memoryKey>` за влязъл потребител и `hotelId:guest:<sessionId>` за гост (`sessionId` – UUID от UI в body-то на `/api/chat`; без валиден – памет само за заявката, никога обща). `BoundedChatMemoryStore` пази в RAM най-много 5000 разговора (LRU); при рестарт паметта се губи. Контролерът подава само последното съобщение.
- `TenantContext.getHotelId()` връща дефолт `"knowledge_seven_stars"`, ако липсва hotelId (а retriever добавя още `knowledge_` префикс → двойно).
- `HotelTools.getAvailableRoomsByDates` не търси стаи: винаги хвърля `OpenDatePickerException` с валидните казани дати (минали/невалидни се изпускат); стаите идват после от `/api/rooms/available`. Късен отговор след timeout се игнорира – при `create_booking` резервацията може да е записана, въпреки че потребителят вижда грешка. Има и шеговит tool `getStrawberryMuffinRecipe`.
- Потребителят идва само от JWT-то в header `Authorization` (не от body-то); проверката е в booking-system. Токенът изтича след 30 мин. – после действията искат нов вход. Резервациите и отказите с бутон минават без LLM, но `ChatHistoryService` ги записва в паметта на разговора.
- Backend микросервисът, който отговаря на `hotel-requests-topic`, не е в това repo – tools зависят от него (5s timeout).
- `KnowledgeRepository` е с твърдо кодирани `numCandidates=100`, `limit=5`; Atlas vector индексът трябва да съществува във всяка `knowledge_<hotelId>` колекция.
- В `Shortcut` анотацията `@Document(collection="shortcuts_#hotelId#")` е само декоративна – реалното име се подава през `MongoTemplate`.
- **Бутоните (shortcuts) са препратка:** `{ shortcutId, label, category, isActive, guest: { isActive }, action }`, където `action` е `{ type: "knowledge", knowledgeIds: [...] }` (текстовете директно от `knowledge_<hotelId>` по `_id`, в реда на ids, без vector search) или `{ type: "tool", tool: "<име на tool>" }`. Кодът не познава конкретни бутони. Запис без `action` → „Информацията не е намерена.“. `guest.isActive: false` скрива бутона от госта (списъкът без токен и директна заявка със `shortcutId`). Поведението при гост се решава в **tool-а** (еднакво от бутона и от чата), не в бутона. Имена на tool-овете (`HotelTools`): `getAvailableRoomsByDates` (календар), `showMyBookings` (картички с „Откажи“), `getReservations` (резервациите като текст/JSON), `getAllRooms` (всички стаи като JSON), `getRoomTypes` (имената на типовете, и за гост), `getStrawberryMuffinRecipe` (шега).
- Модел `gemini-3.6-flash` в properties – провери, че е валиден при грешки от API.
