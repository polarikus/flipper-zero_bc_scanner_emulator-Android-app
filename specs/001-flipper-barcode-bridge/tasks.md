---
description: "Список задач для реализации фичи"
---

# Задачи: Мост «FlipperZero → сканер штрихкодов» через BLE

**Вход**: Проектные документы из `/specs/001-flipper-barcode-bridge/`

**Предварительные условия**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Тесты**: Включены обязательно — конституция проекта требует Test-First (Принцип II, NON-NEGOTIABLE). Тесты пишутся до реализации и должны падать до неё.

**Организация**: Задачи сгруппированы по пользовательским историям (US1, US2, US3) для независимой реализации и проверки.

## Формат: `[ID] [P?] [Story] Описание`

- **[P]**: можно выполнять параллельно (разные файлы, нет зависимостей от незавершённых задач)
- **[Story]**: к какой истории относится задача (US1, US2, US3)
- У каждой задачи указан точный путь к файлу

## Соглашения о путях

- Android-приложение (текущий репозиторий): `app/src/main/java/com/polarikus/bcscanner/`,
  тесты — `app/src/test/java/com/polarikus/bcscanner/`
- Flipper-приложение (соседний репозиторий): `../bc_scanner_emulate/`

---

## Фаза 1: Setup — Flipper (подготовка Flipper-приложения)

**Цель**: Подготовить Flipper-кодовую базу (`../bc_scanner_emulate`) к доработке (RPC-режим)

- [x] T001 [P] Проверить, что базовое Flipper-приложение собирается: выполнить `./fbt fap_bc_scanner` из корня прошивки Flipper (ожидается существующий `.fap`, изменений пока нет)

**Контрольная точка**: Flipper-кодовая база собирается и готова к добавлению RPC-режима

---

## Фаза 2: Setup — Android (инициализация Android-проекта)

**Цель**: Инициализировать Android-проект: Gradle, зависимости, манифест, protobuf и ресурсы

- [ ] T002 Создать каркас Gradle-проекта в `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- [ ] T003 Создать `app/build.gradle.kts` с зависимостями (Compose Material3, CameraX camera2/lifecycle/view, ML Kit Barcode Scanning, protobuf-javalite + Gradle-плагин protobuf, DataStore Preferences, coroutines, JUnit, kotlinx-coroutines-test, Robolectric), `minSdk 26`, `targetSdk 34`, `compileSdk 34` (зависит от T002)
- [ ] T004 [P] Настроить `app/src/main/AndroidManifest.xml`: разрешения `CAMERA`, `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (maxSdkVersion 30), `BLUETOOTH_SCAN` (usesPermissionFlags neverForLocation), `BLUETOOTH_CONNECT`; объявить `BcScannerApplication` и `MainActivity`
- [ ] T005 [P] Вендорить `.proto`-файлы из `flipperzero-protobuf` в `app/src/main/proto/` (как минимум `main.proto`, `application.proto` и транзитивные импорты)
- [ ] T006 [P] Создать строковые ресурсы интерфейса в `app/src/main/res/values/strings.xml` (английский) и `app/src/main/res/values-ru/strings.xml` (русский; применяется только при локали ОС ru, `values` — fallback)

**Контрольная точка**: Android-проект собирается и готов к доменному слою

---

## Фаза 3: Foundational (блокирующие предпосылки)

**Цель**: Общая инфраструктура и словарь домена, необходимые всем историям

**⚠️ КРИТИЧНО**: Ни одна пользовательская история не начинается до завершения этой фазы

- [ ] T007 [P] Написать падающие unit-тесты для конечного автомата соединения в `app/src/test/java/com/polarikus/bcscanner/domain/ConnectionStateMachineTest.kt` (переходы `Disconnected → Connecting → Connected → Disconnected/Error` согласно data-model.md)
- [ ] T008 [P] Создать доменные модели в `app/src/main/java/com/polarikus/bcscanner/domain/model/`: `FlipperDevice(address: String обязателен, name: String?)`, `ConnectionState`, `ScannedCode(value непустое, format, confidence, scannedAt)`, `DeliveryStatus(Pending/Delivered/Error)`, `LastSentCode`, `AppSettings(lastDeviceAddress)`
- [ ] T009 [P] Определить интерфейсы: `FlipperTransport` (connect/disconnect/`connectionState: Flow`, `incoming: Flow`) в `domain/`, `FlipperRpcClient` (startApp/sendData/disconnect) в `domain/`, `BarcodeScanner` в `scanner/`, `DeviceStore` в `data/prefs/`
- [ ] T010 [P] Реализовать `DeviceStore` (последний MAC) на DataStore в `app/src/main/java/com/polarikus/bcscanner/data/prefs/` (язык интерфейса берётся из локали ОС и не хранится)
- [ ] T011 [P] Реализовать утилиты разрешений (BLE: API 26–30 против 31+; камера) в `app/src/main/java/com/polarikus/bcscanner/ui/permissions/PermissionManager.kt`
- [ ] T012 Создать тонкий DI-контейнер (composition root) `app/src/main/java/com/polarikus/bcscanner/BcScannerApplication.kt`, предоставляющий привязки интерфейсов; конкретные реализации регистрируются в конце своей истории (BleFlipperTransport после T017, RpcFlipperClient после T032, MlKitBarcodeScanner после T033)
- [ ] T013 Создать `app/src/main/java/com/polarikus/bcscanner/MainActivity.kt` с оболочкой навигации Compose (экраны main и scanner)
- [ ] T014 Реализовать `ConnectionStateMachine` согласно тестам в `app/src/main/java/com/polarikus/bcscanner/domain/ConnectionStateMachine.kt`

**Контрольная точка**: Фундамент готов — истории можно реализовывать

---

## Фаза 4: Пользовательская история 1 - Просмотр статуса подключения FlipperZero (Приоритет: P1) 🎯 MVP

**Цель**: Подключиться к FlipperZero по BLE и показывать актуальный статус соединения

**Независимая проверка**: Запустить приложение при включённом и выключенном FlipperZero — статус меняется в течение 5 с без дополнительных действий

### Тесты для пользовательской истории 1 ⚠️

> **ВНИМАНИЕ: Пишите эти тесты ПЕРВЫМИ, убедитесь, что они ПАДАЮТ до реализации**

- [ ] T015 [P] [US1] Написать падающие unit-тесты обработки состояния соединения `BleFlipperTransport` с фейковым GATT в `app/src/test/java/com/polarikus/bcscanner/data/ble/BleFlipperTransportTest.kt`
- [ ] T016 [P] [US1] Написать падающие unit-тесты маппинга статуса в `MainViewModel` (кнопка видна только при `Connected`) в `app/src/test/java/com/polarikus/bcscanner/ui/main/MainViewModelTest.kt`

### Реализация для пользовательской истории 1

- [ ] T017 [US1] Реализовать `BleFlipperTransport` (BluetoothGatt: подключение по сохранённому MAC с fallback-сканированием, обнаружение сервиса `fe60`, GAP-состояние → `ConnectionState`) в `app/src/main/java/com/polarikus/bcscanner/data/ble/BleFlipperTransport.kt` (зависит от T015, T009)
- [ ] T018 [US1] Реализовать use-case-ы `ConnectToFlipper` и `DisconnectFromFlipper` в `app/src/main/java/com/polarikus/bcscanner/domain/usecase/` (сохранение последнего MAC через `DeviceStore`)
- [ ] T019 [US1] Реализовать `MainViewModel` (состояние соединения, авто-подключение к последнему устройству) в `app/src/main/java/com/polarikus/bcscanner/ui/main/MainViewModel.kt` (зависит от T016)
- [ ] T020 [US1] Реализовать `MainScreen` (индикатор статуса соединения, кнопка «Сканировать» скрыта, пока не `Connected`) в `app/src/main/java/com/polarikus/bcscanner/ui/main/MainScreen.kt`

**Контрольная точка**: US1 полностью функциональна и проверяема независимо (MVP)

---

## Фаза 5: Пользовательская история 2 - Сканирование кода и отправка значения на FlipperZero (Приоритет: P1)

**Цель**: Сканировать штрихкод/QR камерой и доставить значение на FlipperZero с подтверждением (ack)

**Независимая проверка**: При подключённом FlipperZero нажать «Сканировать», навести камеру на известный код — значение доставлено и подтверждено, целевое устройство получило его через COM-порт

### Flipper-приложение (RPC-режим) для пользовательской истории 2

- [ ] T021 [P] [US2] Разобрать аргумент `RPC %lX`, сохранить `RpcAppSystem*`, зарегистрировать callback и вызвать `rpc_system_app_send_started` в `../bc_scanner_emulate/bc_scanner_app.c` и `../bc_scanner_emulate/bc_scanner_app_i.h`
- [ ] T022 [P] [US2] Добавить in-memory путь отправки в USB CDC (отправка принятых байтов без файла) в `../bc_scanner_emulate/bc_scanner_script.c` и `../bc_scanner_emulate/bc_scanner_script.h`
- [ ] T023 [US2] Обработать `RpcAppEventTypeDataExchange` (отправка байтов, `rpc_system_app_confirm(true/false)`), `AppExit`, `SessionClose` в `../bc_scanner_emulate/bc_scanner_app.c` (зависит от T021, T022)
- [ ] T024 [US2] Добавить RPC-сцену/индикацию (ожидание / принято / отправлено / ошибка) в `../bc_scanner_emulate/scenes/` (зависит от T021)
- [ ] T025 [US2] Собрать и установить обновлённый FAP (`./fbt fap_bc_scanner`), проверить запуск в RPC-режиме и приём данных (зависит от T023, T024)

### Тесты для пользовательской истории 2 ⚠️

- [ ] T026 [P] [US2] Написать падающие unit-тесты для `RpcFrameCodec` (varint-длина + delimited-кодирование/декодирование `PB_Main`) в `app/src/test/java/com/polarikus/bcscanner/data/rpc/RpcFrameCodecTest.kt`
- [ ] T027 [P] [US2] Написать падающие unit-тесты для `RpcCommandDispatcher` (корреляция по `command_id`, таймаут → ошибка) в `app/src/test/java/com/polarikus/bcscanner/data/rpc/RpcCommandDispatcherTest.kt`
- [ ] T028 [P] [US2] Написать падающие unit-тесты выбора штрихкода (выбор с наибольшей confidence при нескольких) в `app/src/test/java/com/polarikus/bcscanner/scanner/BarcodeSelectionTest.kt`
- [ ] T029 [P] [US2] Написать падающие unit-тесты для `SendScannedCode` (ack → `Delivered`, таймаут → `Error`) в `app/src/test/java/com/polarikus/bcscanner/domain/usecase/SendScannedCodeTest.kt`

### Реализация для пользовательской истории 2

- [ ] T030 [US2] Реализовать `RpcFrameCodec` в `app/src/main/java/com/polarikus/bcscanner/data/rpc/RpcFrameCodec.kt` (зависит от T026)
- [ ] T031 [US2] Реализовать `RpcCommandDispatcher` в `app/src/main/java/com/polarikus/bcscanner/data/rpc/RpcCommandDispatcher.kt` (зависит от T027)
- [ ] T032 [US2] Реализовать `RpcFlipperClient` (автозапуск приложения `bc_scanner` с `args="RPC"` при подключении (FR-019), отправка `app_data_exchange_request`, ожидание ack, таймауты) в `app/src/main/java/com/polarikus/bcscanner/data/rpc/RpcFlipperClient.kt` (зависит от T030, T031)
- [ ] T033 [US2] Реализовать `MlKitBarcodeScanner` (CameraX ImageAnalysis + ML Kit, выбор с наибольшей confidence) в `app/src/main/java/com/polarikus/bcscanner/scanner/MlKitBarcodeScanner.kt` (зависит от T028)
- [ ] T034 [US2] Реализовать use-case `SendScannedCode` в `app/src/main/java/com/polarikus/bcscanner/domain/usecase/SendScannedCode.kt` (зависит от T029, T032)
- [ ] T035 [US2] Реализовать `ScannerViewModel` (сканирование → отправка → статус; одиночный возврат на главный экран) в `app/src/main/java/com/polarikus/bcscanner/ui/scanner/ScannerViewModel.kt` (зависит от T034)
- [ ] T036 [US2] Реализовать `ScannerScreen` (предпросмотр CameraX + анализатор, гейтинг разрешения камеры) в `app/src/main/java/com/polarikus/bcscanner/ui/scanner/ScannerScreen.kt` (зависит от T033, T035)
- [ ] T037 [US2] Связать действие «Сканировать» и отображение последнего отправленного кода (значение + статус) в `app/src/main/java/com/polarikus/bcscanner/ui/main/MainScreen.kt` и `MainViewModel.kt` (зависит от T019, T035)
- [ ] T038 [US2] Сквозная проверка: сканировать известный код → доставлено → целевое устройство получает значение через COM (зависит от T025, T037)

**Контрольная точка**: US1 и US2 работают; ядро ценности доставлено

---

## Фаза 6: Пользовательская история 3 - Устойчивость к обрыву связи и повторное сканирование (Приоритет: P2)

**Цель**: Корректно переживать обрыв связи и ошибки отправки, позволять повтор без перезапуска

**Независимая проверка**: Во время работы отключить FlipperZero, затем включить снова — статус восстанавливается, сканирование и отправка снова работают

### Тесты для пользовательской истории 3 ⚠️

- [ ] T039 [P] [US3] Написать падающие unit-тесты логики переподключения и повтора в `app/src/test/java/com/polarikus/bcscanner/ui/main/MainViewModelReconnectTest.kt` и `app/src/test/java/com/polarikus/bcscanner/domain/usecase/RetrySendTest.kt`

### Реализация для пользовательской истории 3

- [ ] T040 [US3] Реализовать авто-переподключение при GAP-разрыве в `app/src/main/java/com/polarikus/bcscanner/data/ble/BleFlipperTransport.kt` и `app/src/main/java/com/polarikus/bcscanner/domain/usecase/ConnectToFlipper.kt`
- [ ] T041 [US3] Реализовать путь повтора и состояния ошибок в `app/src/main/java/com/polarikus/bcscanner/ui/main/MainViewModel.kt` и `app/src/main/java/com/polarikus/bcscanner/ui/scanner/ScannerViewModel.kt` (зависит от T039)
- [ ] T042 [US3] Обработать «Flipper не готов» (`ERROR_APP_NOT_RUNNING` / сбой запуска) → `Error` + повтор в `app/src/main/java/com/polarikus/bcscanner/data/rpc/RpcFlipperClient.kt`
- [ ] T043 [US3] Отображать обрыв связи во время отправки как `Error` без зависания в `SendScannedCode` и UI
- [ ] T044 [US3] Сквозная проверка сценария восстановления (обрыв → восстановление → повторное сканирование) согласно quickstart Scenario 4

**Контрольная точка**: Все истории функциональны независимо

---

## Фаза 7: Polish и сквозные вопросы

**Цель**: Сквозные улучшения, затрагивающие несколько историй

- [ ] T045 [P] Следовать локали ОС для языка интерфейса: `values-ru` применяется при русской локали системы, иначе `values`; без переключения внутри приложения, без экрана настроек
- [ ] T046 [P] Добавить unit-тесты, проверяющие маппинг локаль→ресурс (ru → `values-ru`, иначе `values`) и полноту строковых ресурсов в `app/src/test/java/com/polarikus/bcscanner/ui/main/LocaleResourcesTest.kt`
- [ ] T047 [P] Обновить документацию проекта в `README.md` (на русском): архитектура, обзор BLE/RPC, шаги сборки и запуска
- [ ] T048 Запустить полную проверку по `specs/001-flipper-barcode-bridge/quickstart.md` (все сценарии)
- [ ] T049 [P] Запустить линтер/форматтер для исходников Android и Flipper-приложения

---

## Зависимости и порядок выполнения

### Зависимости фаз

- **Setup — Flipper (Фаза 1)**: без зависимостей — можно начинать сразу; независима от Android-фазы
- **Setup — Android (Фаза 2)**: без зависимостей от Фазы 1 — можно вести параллельно с ней
- **Foundational (Фаза 3)**: зависит от обеих Setup-фаз (Фазы 1–2) — БЛОКИРУЕТ все истории
- **User Stories (Фаза 4+)**: зависят от Foundational
  - US1 и US2 (обе P1) можно вести параллельно при наличии исполнителей
  - US3 (P2) — после US1/US2 или параллельно, интегрируясь с ними
- **Polish (Фаза 7)**: после завершения нужных историй

### Зависимости пользовательских историй

- **US1 (P1)**: после Foundational — зависимостей от других историй нет
- **US2 (P1)**: после Foundational — использует `FlipperTransport` из US1, но проверяется независимо (после подключения)
- **US3 (P2)**: после Foundational — расширяет US1/US2, проверяется независимо

### Внутри каждой пользовательской истории

- Тесты пишутся и ПАДАЮТ до реализации
- Модели → сервисы → use cases → UI
- Базовая реализация → интеграция → сквозная проверка

### Возможности параллельного выполнения

- Обе Setup-фазы (Фаза 1 «Flipper» и Фаза 2 «Android») независимы и могут выполняться параллельно
- Все задачи Setup с [P] внутри фазы можно выполнять параллельно
- Все Foundational задачи с [P] — параллельно
- После Foundational истории можно вести параллельно (US1 и US2)
- Тесты одной истории с [P] — параллельно
- Flipper-задачи (T021, T022) и Android-задачи US2 можно вести параллельно (разные репозитории)

---

## Пример параллельного выполнения: Пользовательская история 1

```bash
# Тесты US1 вместе:
Task: "Написать падающие unit-тесты для BleFlipperTransport в app/src/test/java/com/polarikus/bcscanner/data/ble/BleFlipperTransportTest.kt"
Task: "Написать падающие unit-тесты для MainViewModel в app/src/test/java/com/polarikus/bcscanner/ui/main/MainViewModelTest.kt"
```

## Пример параллельного выполнения: Пользовательская история 2

```bash
# Тесты US2 вместе:
Task: "Тесты RpcFrameCodec в app/src/test/java/com/polarikus/bcscanner/data/rpc/RpcFrameCodecTest.kt"
Task: "Тесты RpcCommandDispatcher в app/src/test/java/com/polarikus/bcscanner/data/rpc/RpcCommandDispatcherTest.kt"
Task: "Тесты выбора штрихкода в app/src/test/java/com/polarikus/bcscanner/scanner/BarcodeSelectionTest.kt"
Task: "Тесты SendScannedCode в app/src/test/java/com/polarikus/bcscanner/domain/usecase/SendScannedCodeTest.kt"

# Flipper-задачи параллельно с Android-задачами (разные репозитории):
Task: "Flipper: разбор RPC-аргументов в ../bc_scanner_emulate/bc_scanner_app.c"
Task: "Flipper: отправка в USB CDC из памяти в ../bc_scanner_emulate/bc_scanner_script.c"
```

---

## Стратегия реализации

### MVP в первую очередь (Пользовательская история 1)

1. Завершить Фазу 1 (Setup — Flipper) и Фазу 2 (Setup — Android)
2. Завершить Фазу 3 (Foundational) — КРИТИЧНО
3. Завершить Фазу 4 (US1)
4. **СТОП и ПРОВЕРКА**: независимо проверить статус подключения
5. Демонстрация/деплой, если готово

> Примечание: US1 — первый проверяемый срез, но основную ценность (сканирование → доставка)
> даёт US2. Обе истории имеют приоритет P1, поэтому для полноценного MVP рекомендуется
> US1 + US2.

### Инкрементальная поставка

1. Setup (Flipper + Android) + Foundational → фундамент готов
2. US1 → проверка → демо (первый срез)
3. US2 → проверка → демо (ядро ценности)
4. US3 → проверка → демо (надёжность)
5. Каждая история добавляет ценность, не ломая предыдущие

### Стратегия параллельной работы команды

1. Команда завершает Setup + Foundational совместно
2. Далее:
   - Разработчик A: US1 (BLE-подключение и статус)
   - Разработчик B: Flipper-приложение RPC-режим (T021–T025)
   - Разработчик C: Android RPC-клиент и сканер (T026–T036)
3. Истории интегрируются независимо

---

## Примечания

- [P] = разные файлы, нет зависимостей
- [Story] связывает задачу с историей для трассируемости
- Каждая история независимо завершаема и проверяема
- Тесты должны падать до реализации (TDD, Принцип II конституции)
- Правило «один коммит = одна задача» (Принцип V): каждый коммит закрывает ровно одну задачу
- Изменения, затрагивающие разрешения или железо, проверять на реальном устройстве
