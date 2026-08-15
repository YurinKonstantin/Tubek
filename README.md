# Tubek

Android-приложение для поиска и скачивания видео с YouTube (без YouTube Data API).
Интерфейс только на русском. Планируется публикация в RuStore.

## Возможности v1

- Экран согласия: политика конфиденциальности + отказ от ответственности
- Поиск видео по запросу
- Открытие по ссылке (вставка / Share / Deep link)
- Выбор качества (muxed-потоки, video-only + авто-аудио с объединением, отдельно аудио)
- Фоновая загрузка в `Download/Tubek` с уведомлением о прогрессе

## Важно

Приложение **не является** официальным клиентом YouTube. Скачивание может нарушать условия YouTube и авторские права. Ответственность за использование несёт пользователь.

Технически используется библиотека [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (без ключа Google API).

## Как открыть в Android Studio

1. Установите Android Studio (Ladybug или новее).
2. **File → Open** → папка `C:\Users\yurin\Projects\Tubek`
3. Важно: Gradle должен использовать **JDK 17**, не JBR 25.
   - **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**
   - Выберите `Tubek/.jdk/jdk-17.0.20+8`  
     (или **Add JDK…** и укажите эту папку)
4. **File → Sync Project with Gradle Files**
5. Подключите устройство/эмулятор и нажмите Run.

Ошибка `IllegalArgumentException: 25.0.2` значит Studio взяла Java 25. В проекте уже прописан JDK 17 в `gradle.properties` (`org.gradle.java.home`).

## Сборка релиза для RuStore

1. Создайте keystore (Build → Generate Signed Bundle / APK).
2. Соберите **Android App Bundle (AAB)** или APK.
3. Загрузите в консоль RuStore вместе с политикой конфиденциальности.

`applicationId`: `ru.tubek.app`  
`versionName`: `1.0.0`

## Структура

- `app/src/main/java/ru/tubek/app/youtube` — поиск и разбор потоков
- `app/src/main/java/ru/tubek/app/download` — WorkManager-загрузки
- `app/src/main/java/ru/tubek/app/ui` — Compose UI

## Ограничения v1

- Нет полноценного встроенного плеера (фокус на скачивании)
- Слияние video-only + audio через MediaMuxer; при несовместимых кодеках объединение может не удаться
- Некоторые потоки (live/DASH-сегменты) недоступны как progressive HTTP
