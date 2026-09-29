# План: преводи на хотела (`translations_<hotelId>`)

Предложен на 2026-09-27, одобрен и **изпълнен на 2026-09-28** (commit `3a5edd2`). Първият опит (с `hotelId` като нов параметър във всяко извикване) беше върнат – твърде много шум в кода.

Разлики от предложението:
- Колекциите се четат през `TranslationRepository` (`findCommon()`, `findByHotelId(hotelId)`) – както всички колекции от commit `9340371`.
- Типовете стаи **не се местят**: могат да са и в общата `translations`, и в `translations_<hotelId>` (хотелът има предимство). Точката „Данни“ отпада.
- В четирите адреса за действия `forRequest` се вика направо в `catch`, където е единственото съобщение.
- `HotelTools.getRoomTypes` също превежда имената (`texts.translate`) – от бутон отговорът отива директно в UI.

## Цел
- `translations_<hotelId>` – текстовете на конкретния хотел, с **предимство** пред общата `translations`:
  - неговите текстове отвън (сега – имената на типовете стаи от бекенда му), търсени по съдържание;
  - собствени версии на общите текстове – същият `key` (напр. свой `booking.timeout` или `ui.checkRooms`).
- `translations` остава обща: съобщенията на асистента, `ui.*` за прозореца.
- Формат – същият: `{ _id, key?, texts: { bg, en, ... } }`.
- Бутоните не са тук – преводът им е в самия бутон (`label: { bg, en }` в `shortcuts_<hotelId>`).

## Ред на търсене
- `message(key)`: за всеки език поред – `[поисканият, defaultLanguage на хотела, "bg"]` (без повторения) – първо `translations_<hotelId>`, после `translations`. Нищо – самият `key` (както сега).
- `translate(текст)`: по съдържание, първо при хотела, после в общата, **само на поискания език**. Нищо – текстът, както е дошъл (той и без това е на някакъв език).
- `withPrefix("ui.")`: ключовете от двата слоя заедно, всеки – по правилата на `message`.

## Ключовото решение: как `TranslationService` разбира хотела
**Обект с контекста на заявката** (препоръчано):
```java
Texts texts = translations.forRequest(hotelId, language);
texts.message("booking.error", Map.of("error", ...));
texts.message("rooms.any");
texts.translate("Единична стая");
texts.withPrefix("ui.");
```
Хотелът и езикът се определят веднъж в началото на заявката. Помощните методи получават `Texts` **вместо** сегашния `String language` – броят на параметрите им не се сменя.

Отхвърлени:
- `hotelId` като нов параметър на всяко извикване (първият опит) – ~45 места и `hotelId` във всеки помощен метод на `RoomBookingService`.
- Сервизът да чете хотела сам от `TenantContext` – само `/api/chat` задава `TenantContext`; `/api/rooms/available`, `/api/bookings`, `/api/bookings/cancel` трябва да започнат да го задават (+ `finally { clear() }`), скрита зависимост от ThreadLocal, а `getHotelId()` хвърля без зададен хотел.

## По файлове

### 1. `service/Texts.java` – нов interface
`message(key)`, `message(key, params)`, `translate(text)`, `withPrefix(prefix)`. Interface е само заради тестовете (т. 6).

### 2. `service/TranslationService.java`
- Кеш: вместо едно поле `loaded` – `Map<String колекция, Cached(Loaded, Instant)>`; всяка колекция (`translations`, `translations_seven_stars`…) се зарежда и презарежда отделно на 5 мин. с `mongoTemplate.findAll(Translation.class, колекция)`. Липсваща колекция = празен списък = празна.
- Нова зависимост `HotelLanguages` (езикът по подразбиране на хотела). Без цикъл – `HotelLanguages` зависи само от `MongoTemplate`.
- `forRequest(hotelId, language)` → вътрешна реализация на `Texts` със слоеве `[translations_<hotelId>, translations]` и езици за опитване. `hotelId == null` (непознат хотел) – само общата колекция.
- Махат се старите `message(key, language…)`, `translate(text, language)`, `messagesWithPrefix` (без legacy).

### 3. `service/RoomBookingService.java`
- Публичните методи **не се сменят** (`findAvailableRooms(hotelId, …, language)` и т.н.); първият ред във всеки: `Texts texts = translations.forRequest(hotelId, language);`.
- `translations.message("x", language, …)` → `texts.message("x", …)` (редовете стават по-къси).
- Помощните `validatePeriod`, `period`, `confirmationText`, `describeBooking`, `roomTypeName` получават `Texts texts` вместо `String language`.
- `backendErrorText(reason, language)` (публичен, ползва го и `HotelTools`) → `backendErrorText(reason, Texts texts)`.

### 4. `tools/HotelTools.java`
- Помощният `message(key)` → `texts()`: `translations.forRequest(TenantContext.getHotelId(), TenantContext.getLanguage())` – tools се викат само от `/api/chat`, където `TenantContext` е зададен.
- `toolError` прави `texts()` веднъж и го подава на `backendErrorText`.

### 5. `controller/AiLangChainController.java`
- `text(key)` (за `/api/chat`) – `forRequest` от `TenantContext`.
- Адресите с `language` (`availableRooms`, `createBooking`, `myBookings`, `cancelBooking`): до `String language = …resolve(…)` – `Texts texts = translations.forRequest(request.hotelId(), language)`; сегашните `translations.message(...)` там → `texts.message(...)`.
- `hotelError`: `forRequest(null, requested(acceptLanguage))` – само общата колекция, за да не пълни измислен `hotelId` кеша.
- `getRoomTypes`, `getChatSettings`: `texts.translate(...)`, `texts.withPrefix("ui.")`.

### 6. Тестове
- `TestTranslations`: `forRequest(...)` връща `Texts`, който дава ключа (с параметри „ключ {име=стойност}“) – както сега; тестовете на контролера, `RoomBookingService` и `ShortcutToolRunner` не се пипат, освен ако стъбват `translations.message` директно.
- `TranslationServiceTest` – пренаписан за `forRequest`: хотелът има предимство за същия ключ; другият хотел вижда само общата; резервата е езикът по подразбиране → `bg` → ключът; `withPrefix` обединява слоевете; `translate` – първо при хотела; липсваща колекция е празна; всяка колекция се презарежда отделно; грешка от Mongo пази старите преводи.

### 7. `CLAUDE.md` – редът за `TranslationService`.

## Какво не се променя
UI, адресите, формата на отговорите, общата колекция `translations` и данните в нея.

## Данни
Трите типа стаи в `translations_40_robbers` и `translations_seven_stars`. В общата колекция – да останат като резерва или да се изтрият.

## Размер (приблизително)
`TranslationService` +100 реда; `RoomBookingService` ~35 реда (основно `language` → `texts`); контролерът ~15; `HotelTools` ~8; `Texts` ~15.
