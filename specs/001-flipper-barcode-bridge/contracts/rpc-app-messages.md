# Контракт: RPC-сообщения приложения (protobuf)

**Feature**: `001-flipper-barcode-bridge` | **Date**: 2026-10-10

Описывает обмен между Android-приложением (RPC-клиент) и FlipperZero (RPC-сервер) для запуска
приложения `bc_scanner` и передачи значения штрихкода. Источник схемы:
`flipperdevices/flipperzero-protobuf` (`application.proto`), реализация прошивки:
`applications/services/rpc/rpc_app.c`.

## Сообщения (PB_App)

```proto
message StartRequest     { string name = 1; string args = 2; }
message AppStateResponse { AppState state = 1; }   // APP_CLOSED=0, APP_STARTED=1
message DataExchangeRequest { bytes data = 1; }
```

Все сообщения оборачиваются в `PB_Main` (см. `main.proto`): поле `command_id` (uint32) +
`oneof content`.

## Поток команд

### 1. Запуск приложения в RPC-режиме (автозапуск, R14)

- Запрос: `PB_Main { command_id = N, app_start_request { name = "bc_scanner", args = "RPC" } }`
- Прошивка передаёт приложению аргумент `RPC <hex-ptr>` (`rpc_system_app_start_process`).
- Ответ 1: `PB_Main { command_id = N, status = OK }` (успех/ошибка запуска).
- Ответ 2 (событие): `PB_Main { app_state_response { state = APP_STARTED } }` — приложение готово.

### 2. Передача значения штрихкода (FR-007, FR-015)

- Запрос: `PB_Main { command_id = M, app_data_exchange_request { data = <байты значения> } }`
- Ответ (ack): `PB_Main { command_id = M, status = OK | ERROR_APP_CMD_ERROR }`
  (`rpc_system_app_confirm`).
- `status = OK` ⇒ доставлено (`Delivered`); ошибка/таймаут ⇒ `Error`.

### 3. Завершение (опционально)

- `PB_Main { command_id = K, app_exit_request {} }` → приложение завершается и подтверждает.

## Корреляция и таймауты

- Каждый запрос имеет уникальный `command_id`; ответ сопоставляется по `command_id`.
- Если ответ не получен в течение таймаута (рекомендуется 3–5 с) — `Error` (FR-015).
- `app_state_response` не содержит `command_id` и обрабатывается как событие жизненного цикла.

## Статусы (PB_CommandStatus), используемые в фиче

| Статус | Значение |
|--------|----------|
| `OK` | Команда выполнена (успешный ack) |
| `ERROR_APP_CMD_ERROR` | Приложение вернуло ошибку (`confirm(false)`) |
| `ERROR_APP_NOT_RUNNING` | Приложение не запущено в RPC-режиме |
| `ERROR_APP_CANT_START` / `ERROR_INVALID_PARAMETERS` | Не удалось запустить приложение |

## Совместимость

- Требуется прошивка FlipperZero с поддержкой `PB_Main_app_data_exchange_request_tag`
  (RPC DataExchange). При несовместимости — показать ошибку «Flipper не готов» (FR-014).
- Схема protobuf фиксируется вендорингом `.proto` из `flipperzero-protobuf` (см. research R3).
