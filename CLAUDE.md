# CLAUDE.md

## 1. Описание
Мултитенант (multi-hotel) чат асистент за хотелски резервации. REST API (`POST /api/chat`) приема съобщение от UI, отговаря чрез Gemini LLM (LangChain4j) с RAG върху знания за хотела в MongoDB (vector search) и с "tools", които през Kafka питат друг микросервис за стаи/резервации. Има и "shortcuts" – готови бързи въпроси, които връщат отговор директно от Mongo без LLM.

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
- `langchain/context/TenantContext` – ThreadLocal за hotelId/userId
- `langchain/config` – `AiConfig` (Gemini beans), `LangChainConfig` (ChatMemory), `KafkaCertInitializer`
- `langchain/service|repository|model` – Shortcuts и `KafkaService` (producer)
- `langchain/log/KafkaMessageConsumer` – слуша всички топици и пише логове в `logs_<hotelId>`
- `knowledge/` – `KnowledgeService` (embed + vector search), `KnowledgeRepository`, `KnowledgeDocument`

## 4. Команди
```bash
./mvnw clean package -DskipTests   # build (jar в target/)
./mvnw test                        # тестове (само contextLoads – изисква реални Mongo/Kafka/Gemini)
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
- Kafka payload = JSON `Map` с `hotelId` и `event`; топици: `test-topic` (логове), `hotel-requests-topic` / `hotel-replies-topic` (tools RPC).

## 6. Капани
- **Тайни в `application.properties`** (Mongo URI с парола, Gemini ключ, Kafka пароли) и приватни ключове/keystore в `src/main/resources/certs/` са в git индекса. Не ги печатай/копирай; препоръчително е да се преместят в env променливи и да се ротират.
- `KafkaCertInitializer` копира сертификатите в `/tmp/certs` при старт; `application.properties` сочи натам (`file:///tmp/certs/...`). Работи само на Linux/Unix-подобна среда и без сертификатите приложението не стартира.
- Тестът `contextLoads` и стартът изискват достъп до Atlas и Aiven Kafka – няма mock/embedded конфигурация.
- `Assistant.chat(hotelName, ...)` получава `hotelId` като `{hotelName}`. Конфигурираният `ChatMemory` е **един общ бийн без memoryId** – историята не е по потребител/хотел; контролерът подава само последното съобщение.
- `TenantContext.getHotelId()` връща дефолт `"knowledge_seven_stars"`, ако липсва hotelId (а retriever добавя още `knowledge_` префикс → двойно).
- `HotelTools.getAvailableRoomsByDates` не търси стаи: винаги хвърля `OpenDatePickerException` с валидните казани дати (минали/невалидни се изпускат); стаите идват после от `/api/rooms/available`. Късен отговор след timeout се игнорира – при `create_booking` резервацията може да е записана, въпреки че потребителят вижда грешка. Има и шеговит tool `getStrawberryMuffinRecipe`.
- `/api/rooms/available` и `/api/bookings` вярват на `userId` от body-то (няма auth) и не минават през chat паметта – LLM-ът не знае за резервации, направени с бутона.
- `KafkaMessageConsumer` е с `topicPattern=".*"`: консумира и собствените си съобщения (вкл. RPC заявки/отговори) и ги записва като логове; очаква поле `event` като низ.
- Backend микросервисът, който отговаря на `hotel-requests-topic`, не е в това repo – tools зависят от него (5s timeout).
- `KnowledgeRepository` е с твърдо кодирани `numCandidates=100`, `limit=5`; Atlas vector индексът трябва да съществува във всяка `knowledge_<hotelId>` колекция.
- В `Shortcut` анотацията `@Document(collection="shortcuts_#hotelId#")` е само декоративна – реалното име се подава през `MongoTemplate`.
- Модел `gemini-3.6-flash` в properties – провери, че е валиден при грешки от API.
