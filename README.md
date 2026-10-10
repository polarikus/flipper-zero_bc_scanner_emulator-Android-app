# BC Scanner Android — мост «FlipperZero → сканер штрихкодов» через BLE

Android-приложение на Kotlin превращает телефон в беспроводной сканер штрихкодов. Оно
подключается к FlipperZero по Bluetooth Low Energy (BLE), показывает статус соединения,
автоматически запускает на FlipperZero приложение `bc_scanner` в RPC-режиме, распознаёт
штрихкод/QR-код через камеру (CameraX + ML Kit) и доставляет значение на FlipperZero по
RPC-протоколу (protobuf). FlipperZero ретранслирует принятое значение в USB CDC, эмулируя
обычный COM-сканер для целевого устройства. Успешной считается только доставка, подтверждённая
ответом RPC (ack).

Спецификация и проектные документы фичи: [`specs/001-flipper-barcode-bridge/`](specs/001-flipper-barcode-bridge/).

## Возможности

- **Статус подключения (US1, P1)** — автоматическое подключение к последнему использованному
  FlipperZero, отображение актуального состояния (≤ 5 с). Кнопка «Сканировать» доступна только
  при подключении.
- **Сканирование и доставка (US2, P1)** — распознавание кода камерой, отправка значения на
  FlipperZero, отображение последнего отправленного кода и статуса доставки.
- **Устойчивость к обрыву (US3, P2)** — корректная обработка разрыва связи и ошибок отправки,
  повтор без перезапуска приложения.

## Архитектура

Приложение построено по слоистой схеме MVVM с инверсией зависимостей: платформенный код (BLE,
камера, USB, хранилище) скрыт за интерфейсами, а конкретные реализации внедряются извне в
ручном DI-контейнере (`BcScannerApplication.AppContainer`).

```text
app/src/main/java/com/polarikus/bcscanner/
├── BcScannerApplication.kt   # composition root (ручной DI)
├── MainActivity.kt           # оболочка навигации Compose (main / scanner)
├── domain/
│   ├── model/                # FlipperDevice, ConnectionState, ScannedCode, DeliveryStatus, ...
│   ├── usecase/              # ConnectToFlipper, DisconnectFromFlipper, SendScannedCode, RetrySend
│   ├── ConnectionStateMachine.kt
│   ├── FlipperTransport.kt   # интерфейс транспорта
│   ├── FlipperRpcClient.kt   # интерфейс RPC-клиента
│   └── FlipperDeviceScanner.kt
├── data/
│   ├── ble/                  # BleFlipperTransport, AndroidGattConnection
│   ├── rpc/                  # RpcFlipperClient, RpcFrameCodec, RpcCommandDispatcher
│   ├── prefs/                # DataStoreDeviceStore (последний MAC-адрес)
│   └── session/              # LastSentCodeStore (последний код в памяти)
├── scanner/                  # MlKitBarcodeScanner, BarcodeSelection
└── ui/
    ├── main/                 # MainScreen, MainViewModel
    ├── scanner/              # ScannerScreen, ScannerViewModel
    └── permissions/          # PermissionManager (BLE/камера по версии API)
```

Ключевые принципы:

- **SOLID / DIP** — домен не зависит от платформы; BLE, камера и хранилище подменяемы.
- **Test-First** — бизнес-логика покрыта unit-тестами; платформа заменяется фейками.
- **Simple UI** — два экрана (главный и сканер), без истории и лишних настроек.
- **Permissioned Hardware Access** — runtime-разрешения запрашиваются в момент использования,
  привязка к жизненному циклу, корректная деградация.
- **One Commit = One Task** — атомарные коммиты, трассируемые к задачам.

## Обзор BLE / RPC

### Транспорт

Используется штатный BLE Serial-профиль FlipperZero. UUID (как их видит BLE-централь):

| Назначение | UUID |
|------------|------|
| Сервис | `8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000` |
| TX (Flipper → телефон, indicate) | `19ed82ae-ed21-4c9d-4145-228e61fe0000` |
| RX (телефон → Flipper, write) | `19ed82ae-ed21-4c9d-4145-228e62fe0000` |
| Flow control (notify) | `19ed82ae-ed21-4c9d-4145-228e63fe0000` |
| RPC status (notify/write) | `19ed82ae-ed21-4c9d-4145-228e64fe0000` |

### RPC-протокол

Поверх Serial-профиля работает RPC-протокол FlipperZero (protobuf `PB_Main`, delimited framing):

- Кадр = `varint(длина)` + сериализованный `PB_Main` (`PB_ENCODE_DELIMITED`). Максимальный размер
  полезной нагрузки BLE-кадра — 486 байт; более длинные значения отправляются несколькими записями.
- **Автозапуск приложения.** При подключении Android-клиент отправляет
  `PB_App::StartRequest { name = "bc_scanner", args = "RPC" }`. При `ERROR_INVALID_PARAMETERS`
  клиент повторяет запуск с полным путём `/ext/apps/USB/bc_scanner.fap`.
- **Передача значения.** `PB_App::DataExchangeRequest { data = <байты> }`. Ответ с тем же
  `command_id` и статусом `OK` (формируется `rpc_system_app_confirm`) означает доставку.
- **Корреляция и таймауты.** Ответы сопоставляются по `command_id`; при отсутствии ответа в
  течение таймаута выставляется ошибка отправки, доступен повтор.

Подробности контракта: [`contracts/rpc-app-messages.md`](specs/001-flipper-barcode-bridge/contracts/rpc-app-messages.md).

### Язык интерфейса

Язык определяется локалью ОС: при русской системной локали применяется `values-ru`, иначе —
`values` (английский). Внутриприложенческого переключения языка и экрана настроек нет.

## Требования

- Android: `minSdk` 26, `targetSdk` 34; сопряжённый с телефоном FlipperZero; разрешения Bluetooth
  и камеры.
- Flipper: прошивка с поддержкой RPC DataExchange; установленное приложение `bc_scanner`
  (репозиторий [`bc_scanner_emulate`](../bc_scanner_emulate), RPC-режим).
- Целевое устройство, эмулирующее COM-сканер, подключено к FlipperZero по USB.

## Сборка и запуск

### Android-приложение

```bash
# debug-сборка
./gradlew assembleDebug

# установка на подключённое устройство
./gradlew installDebug
```

Сборка требует JDK 17. При необходимости укажите его явно:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

### Flipper-приложение (соседний репозиторий)

Сборка и запуск выполняются строго из каталога приложения `bc_scanner_emulate`:

```bash
cd ../bc_scanner_emulate

# сборка
ufbt

# сборка и запуск на подключённом FlipperZero
ufbt launch
```

## Тесты

```bash
# unit-тесты (JVM): кодек RPC-фреймов, корреляция команд, use-cases, ViewModel, локаль
./gradlew testDebugUnitTest
```

Покрыто как минимум: кодирование/декодирование кадра `varint + PB_Main`; корреляция ответа по
`command_id` и таймаут → `Error`; выбор кода с максимальной `confidence` при нескольких в кадре;
переходы `ConnectionState` и `DeliveryStatus`; маппинг локали на строковые ресурсы.

Сквозная проверка (ручные сценарии с реальным FlipperZero и целевым устройством) описана в
[`quickstart.md`](specs/001-flipper-barcode-bridge/quickstart.md).

## Документация фичи

| Документ | Содержание |
|----------|------------|
| [`spec.md`](specs/001-flipper-barcode-bridge/spec.md) | Спецификация требований |
| [`plan.md`](specs/001-flipper-barcode-bridge/plan.md) | Технический план и стек |
| [`research.md`](specs/001-flipper-barcode-bridge/research.md) | Технические решения (R1–R14) |
| [`data-model.md`](specs/001-flipper-barcode-bridge/data-model.md) | Модель данных и состояния |
| [`contracts/`](specs/001-flipper-barcode-bridge/contracts/) | Контракты BLE/RPC |
| [`quickstart.md`](specs/001-flipper-barcode-bridge/quickstart.md) | Сквозные сценарии проверки |
| [`tasks.md`](specs/001-flipper-barcode-bridge/tasks.md) | Список задач реализации |
