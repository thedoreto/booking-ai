# JWT_PROPERTIES

Как се правят JWT ключовете на хотел, къде стоят и как се проверяват.

## Как работи
- Всеки хотел има **своя двойка RSA ключове**.
- **booking-system** на хотела подписва токена при вход с **частния** ключ (RS256) и го проверява с **публичния**.
- **booking-ai** има само **публичния** ключ на всеки хотел (Mongo `HotelAI.hotel_settings`). Може да проверява токените, но не и да ги издава.
- Токен от един хотел не минава в чата на друг хотел.
- **Частният ключ е единствената тайна.** Който го има, може да влезе като всеки потребител на хотела. Публичният ключ не е тайна.

## 1. Създаване на ключовете
За всеки хотел отделно, в папка **извън** проектите (или в `booking-system/secrets/`, която е в `.gitignore`). Пример за `seven_stars`:

```bash
# частен ключ – RSA 2048, PKCS#8 PEM
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out seven_stars_private.pem

# публичен ключ – от частния
openssl pkey -in seven_stars_private.pem -pubout -out seven_stars_public.pem
```

Публичният ключ **винаги** се извежда от частния с втората команда. Два отделни `genpkey` дават две несвързани двойки.

Копие на частния ключ – в password manager. Не се праща по чат/имейл и не влиза в git или Mongo.

## 2. Къде стоят

| Ключ | Къде | Как |
|---|---|---|
| частен | booking-system на **този** хотел | файл + настройка `jwt.private-key-location` |
| публичен | booking-system на **този** хотел | файл + настройка `jwt.public-key-location` |
| публичен | booking-ai – Mongo `HotelAI.hotel_settings` | документ `{ "hotelId": "<hotelId>", "jwtPublicKey": "<съдържанието на публичния .pem>" }` |

### booking-system – настройки

| `application.properties` | Env в Render | Стойност |
|---|---|---|
| `jwt.private-key-location` | `JWT_PRIVATE_KEY_LOCATION` | път до частния ключ с `file:` отпред |
| `jwt.public-key-location` | `JWT_PUBLIC_KEY_LOCATION` | път до публичния ключ с `file:` отпред |

Env името може да е и без `_` на мястото на тирето (`JWT_PRIVATEKEYLOCATION`) – Spring намира и двете. Без префикса `file:` Spring търси файла в jar-а.

**Локално (40_robbers):**
- файловете: `booking-system/secrets/40_robbers_private.pem` и `40_robbers_public.pem` (`secrets/` е в `.gitignore`);
- в `booking-system/src/main/resources/application.properties` (и той не е в git):
  ```properties
  jwt.private-key-location=file:secrets/40_robbers_private.pem
  jwt.public-key-location=file:secrets/40_robbers_public.pem
  ```
  Пътят е относителен към корена на booking-system – оттам IntelliJ пуска приложението.

**Render (seven_stars), услугата `booking-system-1-jfv3` → Environment:**
- **Secret Files:** `seven_stars_private.pem` и `seven_stars_public.pem` – целите файлове, с редовете `-----BEGIN …-----` / `-----END …-----`. Render ги слага в `/etc/secrets/<име>`.
- **Environment Variables:**
  - `JWT_PRIVATE_KEY_LOCATION` = `file:/etc/secrets/seven_stars_private.pem`
  - `JWT_PUBLIC_KEY_LOCATION` = `file:/etc/secrets/seven_stars_public.pem`

### booking-ai – Mongo
- База **`HotelAI`** (тази от `spring.data.mongodb.uri` на booking-ai), колекция **`hotel_settings`**, по един документ на хотел:
  ```json
  { "hotelId": "40_robbers",  "jwtPublicKey": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----" }
  { "hotelId": "seven_stars", "jwtPublicKey": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----" }
  ```
- `_id` се генерира от Mongo; `hotelId` е същото, което UI праща. В документа има и други настройки на хотела (езиците на чата). `jwtPublicKey` може да е целият PEM или само base64 на един ред.
- booking-ai **не иска env** за JWT. Нов или сменен ключ се вижда до 5 минути, без рестарт.

## 3. Проверка, че ключовете са една двойка
Двата отпечатъка трябва да са еднакви:
```bash
openssl pkey -in seven_stars_private.pem -pubout -outform DER | sha256sum
openssl pkey -pubin -in seven_stars_public.pem -outform DER | sha256sum
```
Същият отпечатък трябва да има и ключът в `hotel_settings` за този хотел.

## 4. Какво става при грешка
| Грешка | booking-system | booking-ai (чатът) |
|---|---|---|
| Файлът липсва / грешен път / без `file:` | не стартира: `Cannot read JWT key file` | – |
| Файлът не е RSA PEM | не стартира: `Invalid JWT private/public key` | – |
| Частният и публичният не са двойка | не стартира: `JWT private and public keys are not a pair` | – |
| Няма документ в `hotel_settings` или ключът там не е от двойката | работи | **всички в чата на хотела са гости** – бутоните и въпросите работят, резервация/отказ/„Моите резервации“ искат вход |

В конзолата на booking-ai при зареждане: `JWT public keys loaded for hotels: [...]` – за кои хотели има прочетен ключ.

## 5. Смяна на ключовете на хотел
1. Нова двойка (стъпка 1).
2. booking-system: сменя двата файла (локално в `secrets/`, в Render – Secret Files) и се рестартира.
3. booking-ai: сменя `jwtPublicKey` в `hotel_settings`.
4. Всички влезли потребители на хотела трябва да влязат наново (старите токени вече не минават).

Старият частен ключ се изтрива навсякъде.
