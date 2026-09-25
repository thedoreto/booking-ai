# SESSIONS_LOG

Планът и идеите за следващи сесии са в `PLAN.md`.

## Сесия 2026-09-25 (2) – структурирани логове за отчетите

### Решения
- **Логовете се пишат директно в Mongo** (`logs_<hotelId>`), вече не през Kafka. `KafkaMessageConsumer` (`topicPattern=".*"`) е изтрит: той записваше и RPC заявките/отговорите като логове и гърмеше с NPE на съобщенията без `hotelId`. `test-topic` вече не се ползва.
- Един запис на действие: `{timestamp, hotelId, userId, type, outcome, errorType, durationMs, userMessage, reply, details}`.
  - `type`: `chat`, `shortcut`, `rooms_search`, `booking`, `cancel`, `my_bookings`
  - `outcome`: `ok`, `no_result`, `rejected`, `error`
  - `errorType`: `GEMINI_QUOTA_429`, `GEMINI_OVERLOADED_503`, `BACKEND_TIMEOUT`, `BACKEND_ERROR`, `INTERNAL`
- Текстовете на госта и асистента се пазят съкратени до 500 знака. Записите се трият след 180 дни чрез TTL индекс `timestamp_ttl`, който се създава при първия запис в колекцията.
- Записът е асинхронен: не забавя отговора, а грешка при записа не проваля заявката.
- `GeminiUsageTracker` (`ChatModelListener`) брои за всяка чат заявка извикванията, грешките, input/output токените и поисканите tools → в `details`. Всеки повторен опит при 503 се брои като отделно извикване.
- `RoomBookingService` връща `outcome`/`errorType` в `UiAction` (`rejected`, `noResult`, `backendError`, `backendTimeout` вместо `textOnly`), за да може контролерът да ги запише.

Проверено: компилация, unit тест `ChatLogEntryTest` (без облак). **Не е проверено срещу истинския Mongo**: записът и TTL индексът.

### Отворени задачи
- [ ] Провери в Atlas, че записите в `logs_<hotelId>` се появяват и че индексът `timestamp_ttl` е създаден. Ако в колекцията вече има индекс по `timestamp` с други настройки, TTL индексът не се създава. Тогава се вижда `Could not create TTL index` в логовете.
- [ ] Старите записи от `KafkaMessageConsumer` в `logs_<hotelId>` имат друга структура (`{timestamp, hotelId, event}` без `type`). Отчетите трябва да ги филтрират по наличие на `type`. Имат `timestamp`, затова TTL индексът ще ги изтрие след 180 дни.
- [ ] Отчетите в админ страницата (следваща стъпка от „Смислено логване“ в `PLAN.md`).

## Сесия 2026-09-25 – търсене по тип стая, бутон „Нова резервация“

### Решения
- **Типовете стаи и имената им живеят само в booking-system** (`RoomType` с `displayName`). booking-ai и UI не пазят собствен списък. Затова нов тип се добавя само в бекенда.
- Заявката за типовете минава **по Kafka, не през LLM**, така че не харчи токени. booking-ai ги кешира за всеки хотел за 10 минути, а след неуспешна заявка не пита отново 1 минута. Иначе всеки чат би чакал 5s timeout.
- Връщат се само **типовете, които хотелът реално има** в стаите си, а не целия enum.
- Моделът научава типовете от `{roomTypes}` в system prompt-а, в същото извикване. Отделен tool call не е нужен.
- Бутонът „Нова резервация“ е shortcut в `shortcuts_<hotelId>` с `actionType: 'open_date_picker'`. UI го обработва сам, без бекенд и без LLM.

### Направени commit-и
| Repo | Commit | Какво |
|---|---|---|
| booking-system | `f959b41` | `findAvailableRooms(checkIn, checkOut, roomType)`: типът не е задължителен, непознат тип връща 400 `Invalid room type`. `roomType` в Kafka `get_available_rooms_by_dates` и в `GET /rooms/available`. Нов `GET /rooms/types` и Kafka `get_room_types` → `[RoomTypeDTO{code, name}]`. `RoomType` има `displayName`. |
| booking-ai | `32a54c3` | `RoomTypeService` (кеш, `normalize`, `nameOf`, `describeForPrompt`). `GET /api/rooms/types?hotelId=`. `{roomTypes}` в `Assistant`. `getAvailableRoomsByDates` приема `roomType` → `OPEN_DATE_PICKER` data `{startDate?, endDate?, roomType?}`. `/api/rooms/available` приема `roomType`, а `SELECT_ROOMS` data има `roomType`. Shortcut `open_date_picker` в `/api/chat` → `OPEN_DATE_PICKER`. |
| booking-ui | `cb65925` | Типовете се зареждат от `/api/rooms/types` (`useChat`: `roomTypes`, `roomTypeName`). Бутони за тип в `DateSelectorModal`, попълнени от чата. Shortcut `open_date_picker` → подменю с типовете → календар с избран тип. Твърдо зададените имена са махнати от `RoomSelection`. |

### Бутон „Нова резервация“ в Mongo
```js
db.shortcuts_<hotelId>.insertOne({
  label: 'Нова резервация',
  category: 'booking',
  is_active: true,
  actionType: 'open_date_picker',
  shortcutId: 'new_booking'
})
```
Няма `targetKnowledgeIds`, защото не сочи към знание. Бутоните със знания остават с `actionType: 'knowledge_reference'`.

Проверено: компилация на трите проекта, `vite build`, eslint (само двете стари грешки). Търсенето по тип е тествано ръчно от потребителката. **Бутонът „Нова резервация“ не е тестван в браузъра.**

**Ред при deploy:** booking-system → booking-ai → booking-ui. Без нов booking-system `get_room_types` не получава отговор: асистентът работи без типове, а UI не показва бутони за тип.

### Отворени задачи
- [ ] Тест в браузъра на бутона „Нова резервация“ и подменюто, и за двата хотела.
- [ ] `Shortcut.isActive` не се чете от `is_active` в Mongo (различно име на полето), а неактивните бутони и без това не се филтрират.
- [ ] Грешки от бекенда при търсене на стаи се превеждат през `translateBackendError`. При нови съобщения от booking-system обнови и там.

## Сесия 2026-09-24 (2) – 503 от Gemini, по-малко LLM извиквания, резервация от чата

### Въпроси и изводи
- **Отделни Kafka топици за всеки хотел не са нужни** при 2 хотела. Заявките се маршрутизират по ключ `hotelId` + consumer група на хотел (booking-system), отговорите – по `correlationId` + уникална група на инстанция (booking-ai). Отделни топици имат смисъл при много хотели/трафик или нужда от изолация (ACL).
- **Топиците не се създават автоматично:** няма `NewTopic`/`TopicBuilder` бийнове, а в Aiven `auto_create_topics_enable` по подразбиране е изключено. При нови топици – ръчно в Aiven или `NewTopic` бийн (изисква admin права).
- **„Грешка“ при две едновременни заявки не беше от Kafka**, а Gemini **503 UNAVAILABLE** („high demand“) – претоварен модел при Google. Различно от 429 (изчерпана квота).
- Проверката за календара **не може** да се качи преди `assistant.chat()`: флагът се вдига от tool-а по време на извикването. Реалното излишно извикване беше второто – LangChain4j връща грешката от tool-а на модела и той пише текст, който контролерът изхвърля.
- **Календарът се отваряше празен въпреки казаните дати:** най-вероятно Gemini слагаше грешна година (2025) → датите излизаха в миналото и се изпускаха без лог. След прехвърлянето на година напред работи.

### Направени commit-и
| Repo | Commit | Какво |
|---|---|---|
| booking-ai | `9489cc3` | `RetryingChatLanguageModel`: при 503 нов опит след 2s, 5s, 10s (вграденият retry чакаше ~0.5–0.75s); Gemini модел с `.maxRetries(1)`; контролерът връща „В момента асистентът е претоварен…“ |
| booking-ai | `1df19fc` | Short-circuit модел: след като tool поиска календара, второто извикване към Gemini се прескача и се връща готов текст (`OpenDatePickerException.DATE_PICKER_REPLY`) |
| booking-ai | `f9039b9` | Стаи и резервация без LLM: `POST /api/rooms/available`, `POST /api/bookings`; `HotelBackendClient` (Kafka request/reply, изнесен от `HotelTools`, разбира `error` в отговора); `RoomBookingService`; общ `TenantContext.UiAction` + `UiActionShortCircuitChatModel`; `NewChatResponse(reply, actionType, data)` |
| booking-system | `1a46f23` | Event `create_booking`, отговор `{correlationId, error}` при грешка, `createBooking` – всички стаи или нито една |
| booking-ui | `fc34f92` | `RoomSelection` в чата (чекбоксове + „Резервирай (N нощи, сума)“) |
| booking-ai | `81a13d9` | Календарът се отваря попълнен с казаните дати (`OpenDatePickerException(start, end)` → `data`); `@P` описания на параметрите; приема и `30.09.2026`, `2026-9-30`; дата в миналото се мести с година напред; лог `Date picker prefill: ...` |
| booking-ui | `0a2cf74` | `DateSelectorModal` приема `initialStartDate`/`initialEndDate`; hooks преди early return |

### Как работи резервацията сега
1. „направи ми резервация [за 30 октомври [до 3 ноември]]“ → Gemini вика `getAvailableRoomsByDates` → `OPEN_DATE_PICKER` с `data: {startDate?, endDate?}` → календар, попълнен с казаните дати (без второ извикване към Gemini).
2. Избрани дати → `POST /api/rooms/available` → Kafka `get_available_rooms_by_dates` → `SELECT_ROOMS` с `{startDate, endDate, rooms}` (без LLM).
3. Избрани стаи → `POST /api/bookings` → Kafka `create_booking` → `BOOKING_CONFIRMED` с текст (стаи, цени, обща сума) или преведена грешка (напр. „някоя от избраните стаи вече е заета“).

Проверено: компилация на трите проекта, `vite build`, ръчен тест в UI от потребителката (календар с дати, стаи, резервация). **Порядък при деплой:** booking-system → booking-ai → booking-ui (стар booking-system игнорира `create_booking` → timeout).

### Бележки
- Push от сесията не работи (няма GitHub credentials) – push-ва се ръчно (`! git -C <repo> push` или от IntelliJ).
- Build: `./mvnw` липсва wrapper, ползван е Maven от IntelliJ: `~/.local/share/JetBrains/Toolbox/apps/intellij-idea/plugins/maven/lib/maven3/bin/mvn -o compile`.
- Kafka listener-ът за отговорите вече е в `HotelBackendClient` (не в `HotelTools`), със същата уникална група на инстанция.

### Отворени задачи
- [ ] `/api/rooms/available` и `/api/bookings` вярват на `userId` от body-то – няма auth (важи и за tools).
- [ ] При timeout (5s) на `create_booking` резервацията може да е записана, а потребителят да види грешка – текстът го насочва към „Моите резервации“. Възможно подобрение: идемпотентен ключ на заявката.
- [ ] Резервациите през бутона не влизат в chat паметта – Gemini не знае за тях (tool `getReservations` ги вижда).
- [ ] Цените се показват с „лв.“ – в проекта няма указана валута.
- [ ] Ако 503 продължи често – по-стабилен модел в `gemini.base.model` или резервен модел.

## Сесия 2026-09-24 – Render + Aiven Kafka, два хотела

### Сетъп (важно)
- **Една** booking-ai инстанция в Render обслужва и двата хотела (multi-tenant по `hotelId`):
  - **40_robbers** – booking-system + booking-ui вървят **локално**; локалният UI вика booking-ai в Render (`VITE_AI_API_URL=https://booking-ai-k2yp.onrender.com/`, localhost е закоментиран).
  - **seven_stars** – всичко в Render: `booking-ui-81fb`, `booking-system-1-jfv3`, `booking-ai-k2yp`.
- И двете среди ползват **един и същ Aiven Kafka** (SSL/mTLS, без SASL) и едни и същи топици: `hotel-requests-topic`, `hotel-replies-topic`, `test-topic`.
- Един и същ `GEMINI_API_KEY` локално и в Render → **общата безплатна квота** (429).

### Какво се оказа проблемът (по ред)
1. **UI викаше стара, изтрита/пресъздадена booking-ai услуга** (`booking-ai-3s50`) със стар код → генерично „Възникна техническа грешка…“ и никакви логове. Доказателство: отговорът нямаше поле `actionType`. Решение: `VITE_AI_API_URL` → `booking-ai-k2yp` + rebuild на UI (Vite вгражда URL-а при build).
2. **Обща `ChatMemory` за всички хотели/потребители** (10 съобщения). `MessageWindowChatMemory` изтрива най-старото съобщение и може да остави AI function call в началото → Gemini 400 *„function call turn comes immediately after a user turn“*. Също: моделът „помни“ стари отговори и понякога не вика tool-а за календара.
3. **Споделени Kafka consumer групи** между локално и Render → заявки/отговори отиват при грешната инстанция и се губят тихо.
4. DEBUG логове идваха от `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK` / `LOGGING_LEVEL_COM_HOTEL` в Render (махнати). Съобщението на Micrometer „subsequent logs will be logged at debug level“ е безобидно.

### Направени commit-и (всички pushed)
| Repo | Commit | Какво |
|---|---|---|
| booking-ai | `5078019` | Връща проверката за 429 в `AiLangChainController` (обхожда `getCause()` веригата) → „Изчерпахте безплатните заявки…“ + `log.warn`. Изтрита беше в `ca9c24c`. |
| booking-ai | `37506f9` | `HotelTools` reply listener: `groupId = "agent-tools-reply-#{T(java.util.UUID).randomUUID()}"` – уникална група на инстанция. |
| booking-system | `4412657` | `GlobalKafkaConsumer`: `groupId = "hotel-backend-${hotel.backend.id}"` + `log.debug` при пропуснато съобщение за друг хотел. |
| booking-ai | `69b57af` | `ChatMemoryProvider` (памет по `hotelId:userId`, `anonymous` без user) + `UserFirstChatMemory` (пропуска водещи AI/tool съобщения) + `@MemoryId` в `Assistant.chat`. |

Проверено: компилация на двата проекта; логиката на `UserFirstChatMemory` с малък тест; директна заявка към Gemini с ключа/модела работи. **Не е проверено end-to-end** преминаването на 10 съобщения – квотата на Gemini свърши (429) по време на теста.

### Env променливи в Render (без стойности)
**booking-ai:** `SPRING_DATA_MONGODB_URI`, `GEMINI_API_KEY`, `GEMINI_BASE_MODEL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SPRING_KAFKA_SECURITY_PROTOCOL=SSL`, `SPRING_KAFKA_SSL_TRUST_STORE_LOCATION=file:///tmp/certs/aiven-truststore.p12`, `SPRING_KAFKA_SSL_TRUST_STORE_PASSWORD`, `SPRING_KAFKA_SSL_TRUST_STORE_TYPE=PKCS12`, `SPRING_KAFKA_SSL_KEY_STORE_LOCATION=file:///tmp/certs/aiven-keystore.p12`, `SPRING_KAFKA_SSL_KEY_STORE_PASSWORD`, `SPRING_KAFKA_SSL_KEY_STORE_TYPE=PKCS12`, `SPRING_KAFKA_SSL_KEY_PASSWORD`. (`PORT` идва от Render.)

**booking-system:** горните Kafka + `SPRING_DATA_MONGODB_URI` (иначе ползва закомитнатия URI), `JWT_SECRET`, `HOTEL_BACKEND_ID` (= `hotelId` от UI, напр. `seven_stars`), `GEMINI_API_KEY`, `GEMINI_API_BASE_URL`, `GEMINI_EMBEDDING_MODEL`, `GEMINI_BASE_MODEL`.

### Полезни диагностики
- Кой booking-ai вика UI-ът: DevTools → Network → `chat` → Request URL, или grep за `onrender.com` в `/assets/index-*.js` на UI-а.
- Kafka конфигурацията в Render: в логовете търси `ProducerConfig values:` / `ConsumerConfig values:` → `bootstrap.servers`, `security.protocol`.
- Реалната грешка на чата: в Render Logs търси `Chat failed for hotelId=` (stack trace) или `Gemini API quota exceeded`.
- Aiven Console → Consumer Groups – виж членовете на групите.
- `./mvnw` в booking-ai не работи (липсва `.mvn/wrapper/maven-wrapper.properties`); ползван е Maven от `~/.m2/wrapper/dists/.../bin/mvn`.

### Отворени задачи
- [ ] **Сигурност** (отложено, приложението е тестово):
  - `application.properties` на booking-system е в git с Mongo URI и паролата; сертификати/ключове на Aiven (`src/main/resources/certs/`) са в git и в двата repo-та.
  - Тайни бяха поставени в чата – да се ротират: Mongo парола, Gemini ключ, `JWT_SECRET`, Kafka keystore/truststore пароли и сертификати.
  - Добави `application.properties` в `.gitignore` (booking-ai: в момента е untracked) или го комитвай само с `${ENV}` placeholder-и.
  - По избор: сертификатите като Render Secret Files вместо в classpath.
- [ ] Провери end-to-end календара/резервацията в Render след подновяване на квотата на Gemini.
- [ ] Провери, че booking-system в Render е на `4412657` и има `HOTEL_BACKEND_ID`; free план заспива след ~15 мин без HTTP → tools получават timeout.
- [ ] Провери дали старата `booking-ai-3s50` е наистина изтрита (още отговаряше).
- [ ] По-малки подобрения (предложени, не направени): лог в catch блоковете на `HotelTools` и `KafkaService`; `KafkaMessageConsumer` да слуша само `test-topic` вместо `.*` (сега гърми с NPE на отговорите без `hotelId`); `spring.kafka.producer.properties.max.block.ms=5000`; при грешка в чата да се праща събитие и към Kafka; `hotelService.getAllRooms()` не филтрира по хотел.
