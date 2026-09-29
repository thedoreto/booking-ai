# ADMIN_USERS

Админите на хотелите в админ панела на booking-ai: къде стоят, как се създават и какво трябва на booking-ai, за да ги пусне. Админите са напълно отделни от потребителите на сайта на хотела – booking-system и Kafka не участват във входа.

## Къде стои админът
В колекция `hotel_settings` (база `HotelAI`), в документа на хотела – поле `admin`:
```json
{
  "hotelId": "40_robbers",
  "languages": [ ... ],
  "defaultLanguage": "bg",
  "admin": {
    "email": "the.doreto@gmail.com",
    "name": "Теодора",
    "passwordHash": "$2a$10$..."
  }
}
```
- `passwordHash` – **BCrypt хеш** на паролата, не самата парола. Хешът е необратим: от него паролата не може да се получи обратно. Ако в полето се сложи паролата като текст, входът просто не работи.
- Един хотел има един админ. Един и същ имейл може да е админ на няколко хотела – входът е по **хотел + имейл + парола**, с отделна парола за всеки хотел.
- Имейлът се сравнява без значение от главни/малки букви.
- Входът чете документа направо от Mongo (без кеш): нова парола важи веднага, а махнат или сменен админ губи достъп веднага, дори с валиден токен.

## Как се създава админ
Засега на ръка (после – при регистрацията на хотел).

1. Хеш на паролата – от папката на booking-ai:
   ```bash
   mvn -q compile exec:java -Dexec.mainClass=com.hotel.admin.AdminPasswordHash
   ```
   (`./mvnw` не работи – липсва `.mvn/wrapper/maven-wrapper.properties`; вместо `mvn` може `$(ls -d ~/.m2/wrapper/dists/*/*/*/bin/mvn | head -1)`.) Пита `Password:`, паролата не се вижда, докато се пише, и не остава в историята на командите. Отпечатва хеша (`$2a$10$...`).
2. В Atlas, в документа на хотела в `hotel_settings`, се добавя полето `admin` с `email`, `name` и хеша в `passwordHash`.

Смяна на парола – същото: нов хеш в `passwordHash`.

## Ключът за токените (`admin.jwt-secret`)
След вход booking-ai дава на страницата токен (JWT), подписан с този ключ; с него страницата доказва кой е влязъл при следващите заявки. Токенът съдържа хотела и имейла и важи 8 часа.

| `application.properties` | Env (Render, IntelliJ) | По подразбиране |
|---|---|---|
| `admin.jwt-secret` | `ADMIN_JWT_SECRET` | няма – **задължителна**, най-малко 32 знака; без нея booking-ai не стартира |

- Нов ключ: `openssl rand -base64 48`.
- Ключът е един за всички хотели и е само в booking-ai. Който го има, може да си направи токен за админ на всеки хотел – затова не се дава на никого.
- Смяна на ключа = всички админи трябва да влязат отново.

## Страницата (`admin-ui/`)
Vite + React + MUI в `admin-ui/`; booking-ai я сервира на `/admin/` (`config/AdminUiConfig`).
- Вход: хотел, имейл, парола. Последният хотел се помни в браузъра; може и от адреса: `/admin/?hotel=40_robbers`.
- Токенът е в `sessionStorage` – затварянето на таба е изход. При отваряне страницата проверява пазения токен с `/api/admin/me`.
- Локално, докато се пише: `cd admin-ui && npm install && npm run dev` → `http://localhost:5174/admin/` (заявките `/api` отиват на локалния booking-ai на порт 8081).
- В jar-а: `mvn package` сваля Node в `target/` и билдва страницата в `target/classes/static/admin` (`frontend-maven-plugin`, фаза `prepare-package` – `test` не я пуска). Docker билдът прави същото.

## Адресите
| Адрес | Какво | Отговор |
|---|---|---|
| `POST /api/admin/login` `{ hotelId, email, password }` | Вход | `200 { token, hotelId, email, name }`; `401 { error: "INVALID_CREDENTIALS" }`; `429 { error: "TOO_MANY_ATTEMPTS" }` |
| `GET /api/admin/me` с `Authorization: Bearer <token>` | Кой е влязъл (дали токенът още важи) | `200 { hotelId, email, name }`; `401 { error: "UNAUTHORIZED" }` |
| `GET /api/admin/knowledge` с `Authorization: Bearer <token>` | Знанията на хотела от токена, по категория и заглавие; `usedBy` – бутоните, които ги ползват (и неактивните) | `200 [{ id, title, category, tags, source, text, usedBy: [{ shortcutId, label }] }]`; `401` |
| `POST /api/admin/knowledge` `{ title, category, tags, source, text }` | Ново знание; embedding (Gemini) – винаги | `201` новият документ; `400`; `503 EMBEDDING_FAILED` – нищо не е записано |
| `GET /api/admin/settings` | Езиците на хотела | `200 { languages: [{ code, name }], defaultLanguage }` |
| `POST /api/admin/knowledge/{id}/translations/{език}/suggest` | Предложение за превод от Gemini – **не се записва** | `200 { language, text }`; `400 UNKNOWN_LANGUAGE`; `404`; `503 TRANSLATION_FAILED` |
| `PUT /api/admin/knowledge/{id}/translations/{език}` `{ text }` | Записва превода на един език; другите не се пипат | `200` документът; `400 TEXT_REQUIRED` / `TEXT_TOO_LONG` / `UNKNOWN_LANGUAGE`; `404` |
| `POST /api/admin/knowledge/{id}/translate-all` | Gemini превежда основния текст на всички езици на хотела и ги записва (заменя и ръчните поправки) | `200` документът; `400 NO_LANGUAGES`; `404`; `503 TRANSLATION_FAILED` – нищо не е записано |
| `DELETE /api/admin/knowledge/{id}` | Изтриване. **Знание, което се ползва от бутон, не се трие** – първо се сменя бутонът | `204`; `404 NOT_FOUND`; `409 { error: "IN_USE", usedBy: [...] }` |
| `PUT /api/admin/knowledge/{id}` `{ title, category, tags, source, text }` | Редакция. Празните `title`, `category`, `source`, `tags` махат полето; `metadata` не се пипа. Нов embedding (Gemini) – само ако текстът е друг | `200` новият документ; `400 TEXT_REQUIRED` / `TEXT_TOO_LONG` (над 10 000 знака); `404 NOT_FOUND`; `503 EMBEDDING_FAILED` – Gemini не отговори, нищо не е записано |
| `GET /api/admin/shortcuts` | Бутоните на хотела, и неактивните, в реда в чата | `200 [{ shortcutId, label: { bg, en }, category, isActive, guestVisible, action: { type, tool, knowledgeIds }, order }]` |
| `GET /api/admin/shortcuts/tools` | Tool-овете, които бутон може да пусне (от `ShortcutToolRunner`) | `200 [{ name, description }]` (описанието е от `@Tool`) |
| `POST /api/admin/shortcuts` `{ shortcutId, label, category, isActive, guestVisible, action }` | Нов бутон; отива последен, целият ред се записва наново (1..n) | `201` бутонът; `400`; `409 SHORTCUT_ID_TAKEN` |
| `PUT /api/admin/shortcuts/{shortcutId}` `{ label, category, isActive, guestVisible, action }` | Редакция; `shortcutId` и `order` не се сменят | `200` бутонът; `400`; `404 NOT_FOUND` |
| `PUT /api/admin/shortcuts/order` `{ shortcutIds: [...] }` | Новият ред в чата – всички бутони, всеки веднъж | `200` бутоните в новия ред; `400 INVALID_ORDER` – нищо не е записано |
| `DELETE /api/admin/shortcuts/{shortcutId}` | Изтриване (знанията остават) | `204`; `404 NOT_FOUND` |

## Бутоните
- `shortcutId` е задължителен, уникален в хотела и не се сменя: латински букви, цифри, `_` и `-`, до 50 знака, не `order` и `tools` (адресите) – иначе `400 SHORTCUT_ID_REQUIRED` / `SHORTCUT_ID_INVALID`.
- `label` – надписът на езика по подразбиране е задължителен (`LABEL_REQUIRED`), другите – само на езиците на хотела (`UNKNOWN_LANGUAGE`); празните се махат.
- `action`: `{ type: "knowledge", knowledgeIds }` – поне едно знание, всички съществуват в `knowledge_<hotelId>`, в чата се показват в този ред (`KNOWLEDGE_REQUIRED` / `UNKNOWN_KNOWLEDGE`); `{ type: "tool", tool }` – само от `/tools` (`UNKNOWN_TOOL`); без тип – `ACTION_REQUIRED`.
- `guestVisible: false` се записва като `guest: { isActive: false }`; `true` маха `guest`.
- `order` – място в чата (`/api/shortcuts` подрежда по него; бутоните без `order` – накрая, по `shortcutId`). В страницата – стрелки ↑↓, всяка праща целия ред.
- Уникалността на `shortcutId` се проверява в кода, без уникален индекс в Mongo.

## Преводите на знанията
- `text` е на езика по подразбиране на хотела – от него са embedding-ът и RAG (въпросите в чата работят на всеки език и без преводи – Gemini отговаря на езика на чата). `translations: { en: "...", ... }` са само за **бутоните** със знание: бутонът връща превода на езика на чата, без превод – `text`.
- Езиците на хотела добавя админът на AI асистента (засега в `hotel_settings`), а преводите поддържа админът на хотела: „Добави на …“ (предложение от Gemini, което той поправя и записва), „Редактирай“ за един език, „Преведи на всички езици“. Промяна на основния текст **не** сменя преводите сама.
- Gemini превежда само по бутон в админа, никога в чата; не се брои в лимита на чата.

Всички адреси под `/api/admin/` освен `/login` минават през `AdminAuthInterceptor` – нов адрес е защитен по подразбиране.

- Всяка грешка при вход – непознат хотел, хотел без админ, друг имейл, грешна парола – дава същия `401`. Отговорът отнема еднакво време, за да не може да се разбере кои хотели и имейли съществуват.
- **Лимит на опитите:** 5 грешни опита за хотел + имейл → 15 минути отказ (`429`), дори с правилната парола. Броячът е в паметта – при рестарт започва отначало. Успешен вход го нулира.
- За разлика от чата тук има HTTP кодове; грешките са кодове, текстовете са в страницата.
