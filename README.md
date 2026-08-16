# Tubek

Android-приложение для поиска, просмотра и скачивания видео с YouTube.
Метаданные идут через **YouTube Data API v3**; потоки плеера/скачивания — через NewPipe Extractor.
Интерфейс только на русском. Планируется публикация в RuStore.

## Возможности

- Экран согласия: политика конфиденциальности + отказ от ответственности
- Главная лента, Shorts, поиск, подписки
- Вход через Google: подписки и «Понравившиеся» с аккаунта YouTube
- Гостевой режим: локальные подписки и история просмотров
- Выбор качества, фоновая загрузка в `Download/Tubek`

## Google Cloud (обязательно для сборки)

1. Включите **YouTube Data API v3** в [Google Cloud Console](https://console.cloud.google.com/).
2. Создайте **API key** и положите в `local.properties`:
   ```properties
   youtube.api.key=YOUR_API_KEY
   ```
3. Настройте OAuth consent screen (scopes `youtube` и `youtube.readonly`).
4. Создайте OAuth clients в одном Google Cloud проекте:
   - **Web application** — его Client ID кладётся в `local.properties` (см. ниже).
   - **Android (debug)** — package `ru.tubek.app.debug` + SHA-1 debug.keystore  
     (Android Client ID **не** пишется в приложение, только в консоли).
   - **Android (release)** — package `ru.tubek.app` + SHA-1 release keystore.
5. В `local.properties` (шаблон: `local.properties.example`):
   ```properties
   youtube.api.key=YOUR_API_KEY
   # общий Web Client ID
   youtube.oauth.web.client.id=XXXX.apps.googleusercontent.com
   # опционально отдельно для debug / release:
   # youtube.oauth.web.client.id.debug=XXXX.apps.googleusercontent.com
   # youtube.oauth.web.client.id.release=XXXX.apps.googleusercontent.com
   ```

Если debug-сборка не логинится: чаще всего в консоли нет Android-клиента для
`ru.tubek.app.debug` + debug SHA-1 (не путайте Android Client ID с Web Client ID).

SHA-1 debug:
```bash
keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android
```

Release keystore (если создан): `keystore/tubek-release.keystore` (папка в `.gitignore`).

## Важно

Приложение **не является** официальным клиентом YouTube. Скачивание может нарушать условия YouTube и авторские права. Ответственность за использование несёт пользователь.

История просмотров через Data API недоступна: у авторизованного пользователя экран «История» показывает **Понравившиеся**.

## Как открыть в Android Studio

1. Установите Android Studio (Ladybug или новее).
2. **File → Open** → папка `C:\Users\yurin\Projects\Tubek`
3. Важно: Gradle должен использовать **JDK 17**, не JBR 25.
   - **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**
   - Выберите `Tubek/.jdk/jdk-17.0.20+8`
4. Заполните `local.properties` (sdk.dir + youtube.*).
5. **File → Sync Project with Gradle Files**
6. В списке Run/Debug Configurations выберите **Tubek Debug** или **Tubek Release**
   (файлы в `.idea/runConfigurations/`). Для Release также в **Build Variants**
   выберите `release` у модуля `app`.
7. Подключите устройство/эмулятор и нажмите Run.

## Сборка релиза для RuStore

1. Используйте `keystore/tubek-release.keystore` (или свой).
2. Соберите **Android App Bundle (AAB)** или APK.
3. Загрузите в консоль RuStore вместе с политикой конфиденциальности.

`applicationId`: `ru.tubek.app`  
`versionName`: `1.0.0`

## Структура

- `app/.../youtube` — Data API, OAuth, NewPipe-потоки
- `app/.../download` — WorkManager-загрузки
- `app/.../ui` — Compose UI
