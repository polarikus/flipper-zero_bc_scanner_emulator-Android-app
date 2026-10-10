# Phase 0 Research: Мост «FlipperZero → сканер штрихкодов» через BLE

**Feature**: `001-flipper-barcode-bridge` | **Date**: 2026-10-10

Этот документ фиксирует решения по техническим неопределённостям плана. Формат: решение,
обоснование, рассмотренные альтернативы.

## Источники, изученные при исследовании

- Локально: Flipper-приложение `../bc_scanner_emulate` (C): `bc_scanner_app.c`,
  `bc_scanner_script.c`, `application.fam`, `README.md`.
- Локально: прошивка `flipperzero-firmware`:
  - `applications/services/rpc/rpc_app.c`, `rpc_app.h` — RPC-подсистема приложений (в т.ч.
    `RpcAppEventTypeDataExchange`, `rpc_system_app_confirm`).
  - `applications/services/bt/bt_service/bt.c` — жизненный цикл BLE-сессии и RPC-сессии.
  - `targets/f7/ble_glue/services/serial_service.c` + `serial_service_uuid.inc` — UUID сервиса и
    характеристик Serial-профиля.
  - `applications/debug/rpc_debug_app/` — эталонный пример приёма `DataExchange`.
  - `applications/main/infrared/infrared_app.c` — пример запуска приложения в RPC-режиме.
- Внешне: `flipperdevices/flipperzero-protobuf` (`application.proto` — `StartRequest`,
  `DataExchangeRequest`, `AppStateResponse`), `flipperdevices/Flipper-Android-App`,
  `flipperdevices/flipperzero-firmware`.

## R1. Транспорт между телефоном и FlipperZero

- **Decision**: Использовать штатный BLE Serial-профиль FlipperZero и RPC-протокол поверх него
  (protobuf, delimited framing).
- **Rationale**: Прошивка уже предоставляет Serial-сервис и автоматически открывает RPC-сессию
  при BLE-подключении (`bt_on_gap_event_callback`, `GapEventTypeConnected`). RPC-подсистема
  приложений поддерживает обмен произвольными байтами (`RpcAppEventTypeDataExchange`) и
  подтверждение (`rpc_system_app_confirm`) — это закрывает FR-007 и FR-015 без правок ядра
  прошивки. Совпадает с тем, как работает официальное приложение Flipper.
- **Alternatives considered**:
  - Собственный BLE GATT-сервис внутри FAP — невозможен: внешние приложения не управляют BLE-стеком.
  - BLE HID (Flipper как клавиатура) — неверное направление: нужно принять данные на Flipper, а не
    эмулировать ввод с телефона.
  - Только USB — противоречит требованию беспроводной работы.
  - BLE Serial без RPC (сырые байты) — нет сессии, адресации команд и ack.

## R2. RPC-контракт для доставки значения

- **Decision**: Запускать приложение `bc_scanner` через `PB_App::StartRequest` с `args="RPC"`
  (автозапуск), отправлять значение через `PB_App::DataExchangeRequest`, ждать подтверждения
  (`CommandStatus OK` для соответствующего `command_id`).
- **Rationale**: `rpc_system_app_start_process` при `args == "RPC"` передаёт приложению контекст
  `RPC <hex>`; приложение регистрирует callback и получает `DataExchange` с байтами.
  `rpc_system_app_confirm` отправляет ответ с `command_id` и статусом — это и есть ack для FR-015.
- **Alternatives considered**:
  - Отдельные protobuf-сообщения под нашу задачу — потребовали бы изменений в
    `flipperzero-protobuf` и прошивке; избыточно.
  - Передача значения через `ButtonPress`/`LoadFile` — семантически неверно, ограничено.

## R3. Генерация protobuf-классов на Android

- **Decision**: Вендорить `.proto` из `flipperdevices/flipperzero-protobuf` (как минимум `main.proto`,
  `application.proto` и их транзитивные зависимости) и генерировать классы через
  `protobuf-gradle-plugin` (lite-режим, protobuf-javalite).
- **Rationale**: Соответствует контракту прошивки, устойчиво к изменениям, даёт типобезопасный
  доступ к `PB_Main`. Framing (varint-длина) добавляется вручную поверх
  `getSerializedSize()`/`writeTo()`.
- **Alternatives considered**:
  - Переиспользовать protobuf-модуль `Flipper-Android-App` — тяжёлая связность и лишние зависимости.
  - Ручное кодирование нужных сообщений — хрупко, дублирует схему, плохо тестируется.
  - gRPC/protobuf-java (не lite) — избыточный размер для Android.

## R4. Реализация BLE-клиента на Android

- **Decision**: Платформенный `android.bluetooth.BluetoothGatt` без сторонних BLE-библиотек.
- **Rationale**: Конституция требует минимизировать зависимости (YAGNI). Нам нужен один
  сервис/несколько характеристик и предсказуемый поток — этого достаточно на платформенном API.
  Платформенный код изолируется за интерфейсом `FlipperTransport`.
- **Alternatives considered**: RxAndroidBle, Kable — дополнительная зависимость и сложность без
  необходимости; обоснованного выигрыша для одного устройства нет.

## R5. Параметры BLE Serial-профиля

- **Decision**: Использовать следующие UUID (из `serial_service_uuid.inc`):
  - Сервис: `0000fe60-cc7a-482a-984a-7f2ed5b3e58f`
  - TX (Flipper → телефон, indicate): `0000fe61-8e22-4541-9d4c-21edae82ed19`
  - RX (телефон → Flipper, write): `0000fe62-8e22-4541-9d4c-21edae82ed19`
  - Flow control (notify): `0000fe63-8e22-4541-9d4c-21edae82ed19`
  - RPC status (notify/write): `0000fe64-8e22-4541-9d4c-21edae82ed19`
- **Rationale**: Это официальные UUID Serial-профиля; телефон пишет в RX, слушает indicate на TX,
  учитывает flow control.
- **Alternatives considered**: Поиск сервиса по «магическому» имени — менее надёжно, чем UUID.

## R6. Фрейминг RPC

- **Decision**: Кадр = varint(длина сообщения) + сериализованный `PB_Main` (protobuf delimited,
  `PB_ENCODE_DELIMITED`). Размер полезной нагрузки ≤ 486 байт на BLE-кадр; более длинные значения
  отправляются несколькими write-операциями.
- **Rationale**: Совпадает с прошивкой (`pb_decode_ex(..., PB_DECODE_DELIMITED)` и
  `pb_encode_ex(..., PB_ENCODE_DELIMITED)` в `rpc.c`).
- **Alternatives considered**: Фиксированный префикс длины — несовместим с прошивкой.

## R7. Сканирование кодов (камера)

- **Decision**: CameraX (preview + ImageAnalysis) + ML Kit Barcode Scanning; при нескольких кодах
  выбирать распознанный с наибольшей уверенностью (confidence), иначе — первый валидный
  (реализует FR-016).
- **Rationale**: Задано конституцией (CameraX + ML Kit) и покрывает все форматы движка (FR-006).
- **Alternatives considered**: ZXing — отклонено на этапе конституции.

## R8. Доставка и подтверждение (ack)

- **Decision**: Успех доставки = получен ответ RPC с тем же `command_id` и статусом `OK` на
  `DataExchangeRequest` (ответ формирует `rpc_system_app_confirm`). При отсутствии ответа в течение
  таймаута — статус «ошибка отправки».
- **Rationale**: Прямо реализует FR-015 и различает «отправлено» и «ошибка» (FR-013).
- **Alternatives considered**: Считать успехом факт записи в BLE-характеристику — отклонено
  пользователем в clarify (выбран ack).

## R9. Flipper-приложение: RPC-режим

- **Decision**: В `bc_scanner_app.c` разбирать аргумент `RPC %lX` (как в `infrared_app.c`/
  `rpc_debug_app.c`), регистрировать callback, вызывать `rpc_system_app_send_started`. В обработчике
  `RpcAppEventTypeDataExchange` отправлять полученные байты в USB CDC
  (`usb_uart_serial_init` + `furi_hal_cdc_send`) и подтверждать `rpc_system_app_confirm(rpc, true)`.
  На `AppExit` — выход, на `SessionClose` — остановка. Legacy file-режим сохраняется.
- **Rationale**: Эталонные примеры уже есть в прошивке; минимальные аддитивные правки.
- **Alternatives considered**: Отдельное новое приложение — дублирование и лишняя работа; правка
  ядра прошивки — не требуется.

## R10. Суффикс/терминатор после значения

- **Decision**: Отправлять значение как есть, без добавления CR/LF (подтверждено пользователем).
- **Rationale**: Сохраняет точность значения; текущий эмулятор также отдаёт байты «как есть».
- **Alternatives considered**: CR / LF / CR+LF / настраиваемый суффикс — отклонены пользователем;
  возможное расширение на будущее (конфиг), вне объёма MVP.

## R11. Запоминание устройства и авто-подключение

- **Decision**: Хранить MAC-адрес последнего FlipperZero в DataStore. Подключаться по адресу; при
  неудаче — переходить к BLE-сканированию и выбору устройства с нужным сервисом.
- **Rationale**: Реализует FR-011 (авто-подключение без ручного выбора) и повышает надёжность.
- **Alternatives considered**: Всегда показывать список устройств — противоречит FR-011.

## R12. Модель разрешений

- **Decision**: Runtime-разрешения по версии ОС:
  - API 26–30: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (для BLE-сканирования).
  - API 31+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (с `neverForLocation` при возможности).
  - Камера: `CAMERA`.
  Запрос — в момент использования; при отказе — понятное состояние и деградация без падения.
- **Rationale**: Реализует FR-004 и Принцип IV конституции; корректно для `minSdk` 26.
- **Alternatives considered**: Единый набор разрешений — не соответствует требованиям Android 12+.

## R13. Инициализация USB CDC на Flipper

- **Decision**: В RPC-режиме инициализировать USB CDC (`usb_cdc_single`), как в текущем воркере,
  перед отправкой данных; при заблокированном USB показывать ошибку (существующий
  `BarCodeAppErrorCloseRpc`).
- **Rationale**: Целевое устройство видит COM-порт; переиспользуем существующий код.
- **Alternatives considered**: Оставлять USB-конфигурацию пользователю — усложняет сценарий.

## R14. Автозапуск Flipper-приложения

- **Decision**: Android-приложение при успешном подключении отправляет `StartRequest` для
  `bc_scanner` с `args="RPC"` (подтверждено пользователем).
- **Rationale**: Пользователь не должен вручную открывать приложение на Flipper (сценарий из
  спецификации этого не требует).
- **Alternatives considered**: Ручной запуск на Flipper — отклонён пользователем.

## Открытые вопросы (не блокируют)

- Точная длительность таймаута ожидания ack (рекомендация: 3–5 с) — параметр, уточняется при
  реализации.
- Обработка экзотических кодировок QR (не-ASCII) — отправляются сырые байты UTF-8; проверка на
  реальном целевом устройстве.
