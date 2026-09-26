# SESSIONS_LOG

Планът и идеите за следващи сесии са в `PLAN.md`.

## Сесия 2026-09-26 – анонимен гост, архитектурни правила, бутони, JWT, снимки през Kafka

### Commit-и
| Repo | Commit | Какво |
|---|---|---|
| booking-system | `078a378` | Публично четене без вход: `GET /hotelinfo`, `GET /rooms/**`, `GET /images/**` |
| booking-ui | `9a0c372` | Гостът вижда Hotel Info, Rooms и чата |
| booking-ai | `732ce6e` | Бутонът е препратка към знание или tool (`action`); `ShortcutToolRunner` пуска tool по име без Gemini |
| booking-ai | `04b996c` | Нов tool `getRoomTypes` – работи и за гост |
| booking-ai | `89d20f2` | `guest.isActive: false` скрива бутона от госта (в списъка и при директна заявка) |
| booking-ui | `49451fb` | Кои бутони вижда гостът, решава booking-ai |
| booking-ui | `28ba492` | Махнат кодът, с който чатът знаеше какво прави всеки бутон |
| booking-system | `fc0ced6` | Потребителят в Kafka заявките се взима от JWT-то, не от `userId` |
| booking-ai | `89b99bd` | Токенът от header `Authorization` отива през Kafka; паметта е по токена |
| booking-ui | `51a7044` | Чатът праща JWT на AI асистента вместо `userId` |
| booking-system | `1b8d8e6` | Свободните стаи в Kafka отговора идват със снимките си |
| booking-ui | `0d3d62e` | Чатът показва снимките от отговора на AI асистента, без `GET /images` |
| booking-ai | `7dceeee` | Отделна памет за всеки гост (`sessionId`), най-много 5000 разговора в RAM |
| booking-ui | `5d41147` | Чатът праща `sessionId` (нов при всяко отваряне), резервен UUID по `http://` |

### Архитектурни правила
Записани в `CLAUDE.md` на трите проекта (раздел „Архитектурни правила (задължителни)“):
1. Възможно най-малко заявки към LLM – най-важното правило.
2. UI чатът не знае нищо – само праща данните на AI асистента и показва каквото той върне.
3. AI асистентът праща заявки към бекенда само през Kafka.
4. AI асистентът получава данни от бекенда само през Kafka (собствената му база `HotelAI` не е бекендът).
5. Правило 2 важи само за чата – страниците на сайта си говорят директно с booking-system.
6. Действията в чата остават на отделни адреси в booking-ai (не се сливат в `/api/chat`).

Проверка след промените: booking-ai няма HTTP клиенти – всичко към booking-system е през Kafka (`get_room_types`, `get_available_rooms_by_dates`, `create_booking`, `get_upcoming_bookings`, `cancel_booking`, `get_reservations`, `get_all_rooms`). Чатът в UI вика само booking-ai.

### Анонимен гост
- **Сайтът:** гостът вижда Hotel Info и Rooms (`NavBar`, публични маршрути `/hotelinfo`, `/rooms`, `/rooms/:id`; стаята е само за четене, ако не си админ). `*` води към `/hotelinfo`. booking-system чете тези данни без вход; записът иска вход.
- **Чатът:** показва се на всички; `ChatWindow key={user?.id || "guest"}` – при вход/изход започва нов разговор. Поздрав без име за гост.
- **Бутоните:** кой бутон вижда гостът, е настройка в базата – `guest: { isActive: false }` на бутона в `shortcuts_<hotelId>` го скрива от госта (`GET /api/shortcuts` без токен). Скрит бутон не се изпълнява и при директна заявка със `shortcutId` („Информацията не е намерена.“).
- **Поведение при гост (уточнено накрая на деня):** гостът отваря календара и вижда свободните стаи; при „Резервирай“, отказ и „Моите резервации“ получава „влезте в профила си“ – така работи и сега. Поведението при гост решава **tool-ът / `RoomBookingService`** (еднакво от бутона и от чата), не бутонът – затова `guest.action` беше добавено и махнато.
- **Преглед на сигурността при гост:** няма достъп до чужди данни или резервация без вход. Слабите места (произволен `hotelId` → колекции в Mongo, без лимит на заявки и дължина на съобщението, `guest.isActive` не е защита, подправяне на логовете, чужд хотел) са записани в `PLAN.md`.
- **Отделна памет за всеки гост** (преди всички гости на хотел деляха `hotelId:anonymous` – чужди въпроси и лични данни можеше да стигнат до друг гост през Gemini): UI праща `sessionId` (UUID, нов при всяко отваряне на чата, вход/изход и презареждане) с `/api/chat`; паметта е `hotelId:guest:<sessionId>`. Гост без валиден `sessionId` получава памет само за заявката. Влезлият потребител е с `hotelId:user:<memoryKey>`. `BoundedChatMemoryStore` пази най-много 5000 разговора (LRU) вместо `InMemoryChatMemoryStore`, който не триеше нищо. `crypto.randomUUID` го има само по https/localhost – по `http://` UUID v4 се сглобява от `crypto.getRandomValues` (`newSessionId`).

### Бутоните – една форма
В базата имаше две форми (`actionType: knowledge_reference` + `targetKnowledgeIds` и `actionType: open_date_picker` / `my_bookings`, чието значение беше твърдо в кода и в UI), а полето за активност беше ту `isActive`, ту `is_active` (кодът четеше само `is_active`). Потребителката обнови записите; старата форма е махната от кода.
```js
{ shortcutId, label, category, isActive, guest: { isActive }, action: { type: 'knowledge', knowledgeIds: [ObjectId(...)] } }
{ shortcutId, label, category, isActive, guest: { isActive }, action: { type: 'tool', tool: 'getAvailableRoomsByDates' } }
```
- **Бутон с tool** – `ShortcutToolRunner` намира `@Tool` метода в `HotelTools` по име и го вика с `null` параметри, без Gemini. Бутонът и чатът викат един и същ tool – затова всичко, което променя поведението, трябва да е в tool-а, а не в бутона, контролера или UI.
- **Бутон със знание** – всички документи по `_id` от `knowledge_<hotelId>`, в реда на ids (преди – само първият).
- Имена на tool-овете: `getAvailableRoomsByDates`, `showMyBookings`, `getReservations`, `getAllRooms`, `getRoomTypes`, `getStrawberryMuffinRecipe`.
- **UI:** всеки бутон само праща `shortcutId` към `/api/chat`. Махнати са проверките за `open_date_picker` / `my_bookings`, подменюто с типове стаи (типът се избира в календара) и `/api/bookings/mine` от UI.
- `bookingsShown` в логовете се записва за всеки показан списък „Моите резервации“ (бутон, чат, `/api/bookings/mine`).

### JWT вместо `userId` (действия от чуждо име)
Преди booking-ai вярваше на `userId` от body-то – чужд `userId` даваше достъп до чужди резервации. Избран е вариант „токенът минава през Kafka и booking-system решава кой е потребителят“:
- **booking-ui:** `aiApi.js` добавя `Authorization: Bearer <token>` към заявките към booking-ai; `userId` не се праща.
- **booking-ai:** `ChatUser.fromAuthorization` – без токен е гост. В Kafka заявките за `create_booking`, `get_upcoming_bookings`, `cancel_booking`, `get_reservations` отива **токенът**. booking-ai не проверява подписа; `userId` от токена (непроверен) е само за логовете. Паметта на чата е по SHA-256 на токена (подправен токен с чужд `userId` не стига до чужда памет). Изтекла сесия → „сесията ви е изтекла. Моля, влезте отново в профила си.“
- **booking-system:** `GlobalKafkaConsumer.userIdFromToken` проверява подписа със своя `jwt.secret` и взима `userId` от токена; отказите са `Login required`, `Invalid token`, `Session expired`.
- Токенът изтича след 30 мин. – след това действията в чата искат нов вход; при всеки вход паметта на чата започва наново.

### Снимките на стаите през Kafka
- **booking-system:** отговорът на `get_available_rooms_by_dates` е `RoomWithImagesDTO` – стаите със `images: [{id, url, title}]` в реда на `imageIds`, изтритите се пропускат, една заявка за всички снимки. REST API-то не е променено.
- **booking-ai:** препраща стаите без промяна в кода, без LLM.
- **booking-ui:** махнати `loadImagesOnce` и директното `GET /images`; `roomImages` взима до 3 https снимки от `room.images`. Гостът също вижда снимките.

### Тестове (всички без облак)
- booking-ai: `ShortcutTest`, `ShortcutServiceTest`, `ShortcutToolRunnerTest`, `KnowledgeRepositoryTest`, `ChatUserTest`, `RoomBookingServiceTest`, `ChatHistoryServiceTest`, `BoundedChatMemoryStoreTest` (+ старите за `log`).
- booking-system: първите тестове – `GlobalKafkaConsumerTest` (4, токенът), `HotelServiceImagesTest` (1).
- booking-ui: `RoomImages.test.jsx` (4 – снимките от `room.images`), `ChatSession.test.jsx` (2 – `sessionId`).
- Проверено: компилация на трите проекта, всички горни тестове минават; booking-ui `npm run build`, eslint без нови грешки.

### Deploy
booking-system и booking-ai заедно (JWT: ако booking-system е нов, а booking-ai стар, резервациите и отказите се отказват), после booking-ui. Нов UI със стар booking-system показва стаите без снимки; стар UI (без `sessionId`) с нов booking-ai – гостът е без памет, но не се смесва с други.

### Дискусия: чатът като добавена услуга за други хотели
- **UI на хотела** подава `hotelId`, токена на влезлия потребител (`getToken()`), сигнал при вход/изход и къде е входът. Нужно преди това: токенът да не се чете от `localStorage.token`, адресът и `hotelId` да се подават при вграждане, валутата и текстовете да не са твърди.
- **Бекендът на хотела** изпълнява договора по Kafka (събития, формати, кодове на грешки, 5s), издава и сам проверява JWT с claim `userId`.
- **Задължително преди външен хотел:** топик на хотел + ACL – сега токените на гостите минават през общ топик и всеки бекенд ги вижда.

## Сесия 2026-09-25 (3) – преглед на кода, дребни поправки, един лог на действие

Продължение на (2) след неочакван рестарт на лаптопа. Работата по логването беше некомитната и беше възстановена от `git diff`.

### Правила за работа (записани в `CLAUDE.md` и в паметта на Claude)
- Commit само при изрично „комитни“. Одобрен план или „продължи“ не стигат.
- `.md` файловете (`CLAUDE.md`, `SESSIONS_LOG.md`, `PLAN.md`) не влизат в commit-ите с код. Комитват се накрая на деня.
- Push не се прави от сесията (без промяна).
- **Без задачи за ръчни проверки.** Потребителката сама чете кода и тества локално и в Render, преди да приеме промени. Затова в отговорите, `PLAN.md` и `SESSIONS_LOG.md` няма задачи и бележки „тест в браузъра“, „провери в Atlas“, „провери/изтрий след deploy“, „не е тествано“. Записано в `CLAUDE.md` на трите repo-та и в паметта на Claude. Вече вписаните такива задачи (и от по-старите сесии) са махнати от логовете и от плана.

### Направени commit-и (booking-ai)
| Commit | Какво |
|---|---|
| `5ea8361` | Структурираните логове от сесия (2). В него по грешка влязоха и `CLAUDE.md`, `PLAN.md`, `SESSIONS_LOG.md`, преди да е уговорено правилото за md файловете. |
| `7552073` | **Грешките в tools влизат в логовете:** `getReservations` и `getAllRooms` записват `TenantContext.reportToolError(...)` (`BACKEND_ERROR`, `BACKEND_TIMEOUT` или `INTERNAL`) и чатът се логва с `outcome: error`. Нарочно не е `UiAction`, защото той би спрял второто извикване към Gemini, а тук моделът трябва сам да обясни грешката. Текстът към модела минава през `RoomBookingService.translateBackendError` (сега `public static`), а при timeout е `BACKEND_UNAVAILABLE`, вместо „: null“. `/api/chat` без `hotelId` → „Липсва хотел.“. Конзолата не печата текста на съобщенията. Бутон със знание и `null` id → `no_result`. Дребна козметика. |
| `3df7e50` | `ChatLogService` пише в отделна нишка `chat-log-writer` с опашка от 1000 записа вместо в общия `ForkJoinPool`. При пълна опашка записът се изпуска със съобщение `Chat log queue is full…` и не хвърля грешка в заявката. При спиране (`@PreDestroy`) дописва чакащите записи до 5s. Нов `ChatLogServiceTest` (Mongo е mock, без облак). |
| `307217e` | **Един запис на действие на госта** (виж „Логове по действия“ по-долу): `ChatFlow`, `ChatLogService.logStep` (upsert по `flowId`), `flowId` в заявките и в `data` на отговорите. |
| `850dc53` | Проверките за 429 и 503 търсят `HTTP error (429)` / `RESOURCE_EXHAUSTED` и `HTTP error (503)` / `"UNAVAILABLE"` вместо голите числа „429“/„503“ в цялата верига от грешки. LangChain4j Gemini клиентът хвърля `RuntimeException("HTTP error (%d): <тяло>")`. `HotelBackendClient.request` проверява типа на причината, преди да я хвърли (`IllegalStateException` при неочакван тип вместо `ClassCastException`). |

### booking-ui
| Commit | Какво |
|---|---|
| `fa1c32b` | Снимките на стаите са центрирани в картичката. |
| `4dcfbc7` | Първите тестове в booking-ui: Vitest + jsdom + Testing Library (`npm test`), 7 теста за снимките на стаите. Подробно в `../booking-ui/SESSIONS_LOG.md`. |
| `fb0c316` | До 3 снимки на стая в списъка със свободни стаи: една заявка `GET /images` към booking-system (не през `api.js`, за да не пренасочва към вход при 401), после всяка стая си взима своите по `imageIds`. Клик върху снимка не прави нищо. booking-ai и booking-system не са променяни. Подробно в `../booking-ui/SESSIONS_LOG.md`. |
| `04f8d81` | `useChat` пази `flowId` на текущата нова резервация (до 30 мин. без стъпка, нулира се след успешна резервация) и на всеки списък „Моите резервации“ и го праща с търсенето, резервацията, отказа и чата. |
| `528e8a9` | Бутоните в чата се пренасят на редове (до 3 в нормален прозорец, после скрол надолу) и бутон „Цял екран“ в хедъра, където бутоните са на толкова реда, колкото е нужно. Подробно в `../booking-ui/SESSIONS_LOG.md`. |

### Логове по действия (`307217e`, `04f8d81`)
Идеята: адекватен лог не е по един запис на заявка, а по един запис на това, което гостът е направил. Една нова резервация през чата преди даваше 3 несвързани записа (`chat`, `rooms_search`, `booking`), а отказът по средата не личеше никъде.

- **Действие от няколко заявки = един документ**, който всяка стъпка допълва с upsert по `flowId` (`$setOnInsert` на началото, `$set` на `status`/`updatedAt`, `$push` в `steps`, `$inc` на `gemini.*`):
  ```
  { flowId, type, hotelId, userId, startedBy: chat|button, status,
    timestamp (начало, за TTL), updatedAt, steps: [{ step, at, outcome, errorType?, durationMs, ... }],
    gemini: { calls, inputTokens, outputTokens } }
  ```
  - `new_booking`: стъпки `chat` / `shortcut` (отворен календар) → `search` (може няколко) → `booking` (може няколко при неуспех). `status`: `date_picker`, `rooms_shown`, `no_rooms`, `rejected`, `error`, `booking_failed`, `booked`.
  - `cancel_booking`: стъпка `chat` / `shortcut` / `my_bookings` (показан списък) → `cancel` (по една на отказ). `status`: `bookings_shown`, `canceled`, `cancel_failed`.
  - `status` е състоянието след последната стъпка. Действие без `booked`/`canceled`, което дълго не е допълвано, отчетите ще броят за отказ на госта. Не е нужна отделна задача.
- **Как се свързват стъпките:** booking-ai връща `flowId` в `data` (календар, списък със стаи, текст „няма стаи“, списък с резервации), а UI го праща обратно. Без `flowId` (напр. календарът е отворен от бутона, който не вика бекенда) се започва ново действие. Невалиден `flowId` също започва ново. Всеки показан списък с резервации е ново действие.
- **Отделни записи остават:** обикновените въпроси в чата (по един на въпрос – данните за това какво питат гостите, за 👍/👎 и ескалацията), бутоните със знание и „Моите резервации“ без резервации. Решението е по препоръката на Claude, потребителката не е избирала изрично.
- **Грешките** (429, 503, timeout) са стъпка в действието, със статус и `errorType`, а не отделни записи.
- `ChatLogEntry`: данните от Gemini са отделно поле `gemini` (вместо в `details`), `toStepDocument()` слага `details` на първо ниво в стъпката и пази `reply` само при неуспех. Типът `rooms_search` е преименуван на `search`.
- Индекс `flowId` (sparse) в `logs_<hotelId>`, създава се заедно с TTL индекса. Стъпките на едно действие се записват по ред, защото пишещата нишка е една.

### Проверено
- Компилация и 11 unit теста в `log` (`ChatLogEntryTest`, `ChatLogServiceTest`, `ChatFlowTest`) – без облак. `vite build` и eslint (само двете стари грешки).
- Логовете се пишат асинхронно, директно в Mongo. В кода няма `test-topic`, а `kafkaService.send` се ползва само от `HotelBackendClient` към `hotel-requests-topic`.
- `GeminiUsageTracker` не брои двойно въпреки двете обвивки около модела. `GoogleAiGeminiChatModel` вика listener-ите сам, веднъж на всяко реално извикване (проверено в байткода на `1.0.0-beta1`).

### Build
`./mvnw` не работи. Ползва се Maven от wrapper-а:
```bash
$(ls -d ~/.m2/wrapper/dists/*/*/*/bin/mvn | head -1) -o test -Dtest='ChatLog*Test,ChatFlowTest' -Dsurefire.failIfNoSpecifiedTests=false
```

### Отворени задачи
- [ ] Отчетите (от сесия (2)). В `logs_<hotelId>` вече има три вида записи: от `KafkaMessageConsumer` (`{event}` без `type`), по един на заявка от сесия (2) (`type: rooms_search`, `booking`, `cancel`, без `flowId`) и новите. Отчетите трябва да ползват само записите с `flowId` или с `type` `chat`/`shortcut`/`my_bookings`.
- [ ] `flowId` идва от UI без проверка на собственика, както и `userId`. Гост с чужд `flowId` може да добави стъпка в чуждо действие. Малък риск (UUID), решава се заедно с проверката на `userId`.

## Сесия 2026-09-25 (2) – структурирани логове за отчетите

### Решения
- **Логовете се пишат директно в Mongo** (`logs_<hotelId>`), вече не през Kafka. `KafkaMessageConsumer` (`topicPattern=".*"`) е изтрит: той записваше и RPC заявките/отговорите като логове и гърмеше с NPE на съобщенията без `hotelId`. `test-topic` вече не се ползва.
- Един запис на заявка: `{timestamp, hotelId, userId, type, outcome, errorType, durationMs, userMessage, reply, details}`. **Сменено в сесия (3):** резервацията и отказът са един запис на действие (`ChatFlow`).
  - `type`: `chat`, `shortcut`, `rooms_search`, `booking`, `cancel`, `my_bookings`
  - `outcome`: `ok`, `no_result`, `rejected`, `error`
  - `errorType`: `GEMINI_QUOTA_429`, `GEMINI_OVERLOADED_503`, `BACKEND_TIMEOUT`, `BACKEND_ERROR`, `INTERNAL`
- Текстовете на госта и асистента се пазят съкратени до 500 знака. Записите се трият след 180 дни чрез TTL индекс `timestamp_ttl`, който се създава при първия запис в колекцията.
- Записът е асинхронен: не забавя отговора, а грешка при записа не проваля заявката.
- `GeminiUsageTracker` (`ChatModelListener`) брои за всяка чат заявка извикванията, грешките, input/output токените и поисканите tools → в `details`. Всеки повторен опит при 503 се брои като отделно извикване.
- `RoomBookingService` връща `outcome`/`errorType` в `UiAction` (`rejected`, `noResult`, `backendError`, `backendTimeout` вместо `textOnly`), за да може контролерът да ги запише.

Проверено: компилация, unit тест `ChatLogEntryTest` (без облак).

Бележка: ако в колекцията вече има индекс по `timestamp` с други настройки, TTL индексът не се създава и в логовете се вижда `Could not create TTL index`.

### Отворени задачи
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

Проверено: компилация на трите проекта, `vite build`, eslint (само двете стари грешки). Търсенето по тип е тествано ръчно от потребителката.

**Ред при deploy:** booking-system → booking-ai → booking-ui. Без нов booking-system `get_room_types` не получава отговор: асистентът работи без типове, а UI не показва бутони за тип.

### Отворени задачи
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

Проверено: компилация на двата проекта; логиката на `UserFirstChatMemory` с малък тест; директна заявка към Gemini с ключа/модела работи.

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
- [ ] По-малки подобрения (предложени, не направени): лог в catch блоковете на `HotelTools` и `KafkaService`; `KafkaMessageConsumer` да слуша само `test-topic` вместо `.*` (сега гърми с NPE на отговорите без `hotelId`); `spring.kafka.producer.properties.max.block.ms=5000`; при грешка в чата да се праща събитие и към Kafka; `hotelService.getAllRooms()` не филтрира по хотел.
