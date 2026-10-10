# Implementation Plan: Мост «FlipperZero → сканер штрихкодов» через BLE

**Branch**: `001-flipper-barcode-bridge` | **Date**: 2026-10-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-flipper-barcode-bridge/spec.md`

## Summary

Android-приложение на Kotlin превращает телефон в беспроводной сканер: подключается к FlipperZero
по BLE, показывает статус, автоматически запускает приложение `bc_scanner` на FlipperZero в
RPC-режиме, сканирует штрихкод/QR через CameraX + ML Kit и отправляет значение по BLE через
RPC-протокол (protobuf `app_data_exchange_request`). FlipperZero принимает значение и ретранслирует
его в USB CDC (эмуляция COM-сканнера) для целевого устройства. Доставка подтверждается ответом RPC
(ack). Требуется доработка существующего Flipper-приложения `bc_scanner_emulate`: добавление
RPC-режима приёма данных.

Ключевой вывод исследования: Flipper уже предоставляет BLE Serial-профиль с RPC поверх protobuf
(`targets/f7/ble_glue/services/serial_service.c`, `applications/services/rpc/rpc_app.c`), включая
обмен произвольными байтами (`RpcAppEventTypeDataExchange`) и подтверждение
(`rpc_system_app_confirm`) — это и транспорт, и механизм ack из FR-015.

## Technical Context

**Language/Version**:
- Android: Kotlin 2.x, JDK 17, Android Gradle Plugin 8.x, Gradle Kotlin DSL
- Flipper: C (firmware SDK, сборка через `fbt`)

**Primary Dependencies**:
- Android: Jetpack Compose (Material 3), CameraX (`camera-camera2`, `camera-lifecycle`,
  `camera-view`), ML Kit Barcode Scanning, Kotlin Coroutines/Flow, protobuf-javalite (классы
  генерируются из `flipperdevices/flipperzero-protobuf`), DataStore Preferences, Android
  `BluetoothGatt` (платформенный API)
- Flipper: `furi`, `rpc_app` (RPC-подсистема приложений), USB CDC (`usb_cdc_single`), `storage`

**Storage**:
- Android: DataStore — MAC-адрес последнего FlipperZero (язык интерфейса определяется локалью ОС
  и не хранится); последний отправленный код хранится в памяти (в пределах сессии), истории нет
- Flipper: SD-карта нужна только для legacy file-режима; в RPC-режиме файл `.bc` не требуется

**Testing**:
- Android: JUnit + `kotlinx-coroutines-test` для unit-тестов (кодек RPC-фреймов, use-cases,
  ViewModel, конечные автоматы), фейковый транспорт для RPC; Robolectric для логики UI;
  instrumented-тесты — по необходимости (CameraX/BLE на реальном устройстве)
- Flipper: сборка `fbt`, ручная проверка на устройстве

**Target Platform**:
- Android: `minSdk` 26, `targetSdk` 34+
- Flipper: `f7` (FlipperZero) с прошивкой, поддерживающей RPC DataExchange

**Project Type**: mobile-app (Android) + external Flipper application (C, sibling repo)

**Performance Goals**: распознавание и доставка ≤ 3 c (SC-004); отражение статуса ≤ 5 c (SC-002)

**Constraints**:
- Максимальный размер полезной нагрузки BLE-кадра — 486 байт (`BLE_SVC_SERIAL_DATA_LEN_MAX`);
  RPC-фрейминг — varint-длина + protobuf (`PB_ENCODE_DELIMITED`)
- Успешной считается только доставка с полученным ack (`CommandStatus OK`)
- UI без бизнес-логики (MVVM); платформенные зависимости (BLE, камера, USB) скрыты за интерфейсами

**Scale/Scope**: 2 экрана (главный со статусом, сканер); 2 кодовые базы (Android-приложение и
Flipper-приложение)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Принцип | Как соблюдается | Статус |
|---------|-----------------|--------|
| I. SOLID Design | Интерфейсы `FlipperTransport`, `FlipperRpcClient`, `BarcodeScanner`, `DeviceStore`; платформенный код за абстракциями; DI вручную в composition root | PASS |
| II. Test-First (NON-NEGOTIABLE) | Unit-тесты первыми для кодека RPC-фреймов, корреляции команд, use-cases и ViewModel; BLE/камера подменяются фейками | PASS |
| III. Simple UI | Два экрана (главный, сканер); без истории и лишних настроек; состояния загрузка/пусто/ошибка обработаны | PASS |
| IV. Permissioned Hardware Access | Runtime-разрешения: камера + Bluetooth (различаются для API 26–30 и 31+); запрос в момент использования; корректная деградация; привязка к жизненному циклу | PASS |
| V. One Commit = One Task | Разбивка на атомарные задачи — в `tasks.md` (следующая команда) | PASS |

**Технологические ограничения конституции**: Kotlin, `minSdk` 26, только BLE, CameraX + ML Kit,
код/комментарии/коммиты на английском, документация на русском, UI по локали ОС (ru → русский,
иначе английский).

**Примечание о второй кодовой базе**: конституция регулирует Android-приложение. Правки
Flipper-приложения `bc_scanner_emulate` (appid `bc_scanner`) — существующий внешний C-проект; они выполняются по
конвенциям прошивки Flipper (`fbt`, стиль C). Это не нарушение, а согласованная граница: новый
Kotlin-код живёт в Android-приложении, а Flipper-сторона минимально расширяется (RPC-режим).

**Итог гейта**: PASS (нарушений нет, раздел Complexity Tracking не заполняется).

## Project Structure

### Documentation (this feature)

```text
specs/001-flipper-barcode-bridge/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── ble-serial-profile.md
│   ├── rpc-app-messages.md
│   └── flipper-app-rpc-mode.md
├── checklists/
│   └── requirements.md
├── spec.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created here)
```

### Source Code (repository root)

Фича охватывает **две** кодовые базы: новое Android-приложение в текущем репозитории
`bc_scanner_android` и доработку Flipper-приложения в соседнем репозитории `../bc_scanner_emulate`.

```text
# Android-приложение (текущий репозиторий: bc_scanner_android)
app/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/polarikus/bcscanner/
│   │   ├── BcScannerApplication.kt        # composition root (ручной DI)
│   │   ├── MainActivity.kt
│   │   ├── domain/
│   │   │   ├── model/                     # FlipperDevice, ConnectionState, ScannedCode, DeliveryStatus
│   │   │   └── usecase/                   # ConnectToFlipper, DisconnectFromFlipper, SendScannedCode
│   │   ├── data/
│   │   │   ├── ble/                       # FlipperTransport (interface), BleFlipperTransport (BluetoothGatt)
│   │   │   ├── rpc/                       # FlipperRpcClient (interface), RpcFlipperClient, RpcFrameCodec
│   │   │   └── prefs/                     # DeviceStore (последний MAC)
│   │   ├── scanner/                       # BarcodeScanner (interface), MlKitBarcodeScanner
│   │   └── ui/
│   │       ├── main/                      # MainScreen, MainViewModel (статус, кнопка, последний код)
│   │       └── scanner/                   # ScannerScreen, ScannerViewModel (CameraX preview)
│   └── res/
│       ├── values/strings.xml             # английский (fallback)
│       └── values-ru/strings.xml          # русский (system locale ru)
├── src/test/                              # unit-тесты (JVM)
└── src/androidTest/                       # instrumented-тесты (по необходимости)
settings.gradle.kts
build.gradle.kts
gradle.properties

# Flipper-приложение (соседний репозиторий: ../bc_scanner_emulate)
applications_user/bc_scanner_emulate/
├── bc_scanner_app.c                       # + разбор args "RPC %lX", RPC-callback, переход в RPC-режим
├── bc_scanner_app_i.h                     # + поле RpcAppSystem* и состояние режима
├── bc_scanner_script.c/.h                 # + отправка переданных байтов в USB CDC (без файла)
├── scenes/                                # + сцена/состояние RPC (индикация приёма и отправки)
└── application.fam                        # при необходимости + requires "rpc"
```

**Structure Decision**: Android реализуется как один Gradle-модуль `app` с пакетной слоистостью
(`domain` / `data` / `scanner` / `ui`) — это обеспечивает SOLID и тестируемость без
преждевременной модуляризации (YAGNI). Flipper-сторона расширяется аддитивно: RPC-режим
добавляется к существующему file-режиму, legacy-поведение сохраняется.

## Complexity Tracking

> Нарушений Constitution Check нет; раздел не заполняется.
