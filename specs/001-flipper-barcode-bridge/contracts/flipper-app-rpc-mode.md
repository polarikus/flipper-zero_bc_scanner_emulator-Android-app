# Контракт: RPC-режим Flipper-приложения `bc_scanner`

**Feature**: `001-flipper-barcode-bridge` | **Date**: 2026-10-10

Описывает доработку существующего Flipper-приложения `../bc_scanner_emulate`: приём значения по
RPC и ретрансляция в USB CDC (эмуляция COM-сканнера). Источник образцов:
`applications/debug/rpc_debug_app/`, `applications/main/infrared/infrared_app.c`.

## Запуск

- Приложение запускается загрузчиком с аргументом:
  - `RPC <hex-ptr>` — RPC-режим (инициируется Android-приложением через `StartRequest args="RPC"`).
  - `<путь к .bc>` или пусто — legacy file-режим (без изменений).

## Разбор аргумента

```c
uint32_t rpc_ctx = 0;
if(args && sscanf(args, "RPC %lX", &rpc_ctx) == 1) {
    app->rpc = (RpcAppSystem*)rpc_ctx;
    rpc_system_app_set_callback(app->rpc, bc_scanner_rpc_callback, app);
    rpc_system_app_send_started(app->rpc);
    // RPC-режим: файл не требуется
}
```

## Обработка событий RPC

| Событие | Действие приложения |
|---------|---------------------|
| `RpcAppEventTypeDataExchange` | Инициализировать USB CDC; отправить `event.data.bytes` в USB CDC; вызвать `rpc_system_app_confirm(app->rpc, true)` при успехе, иначе `false`; обновить индикацию |
| `RpcAppEventTypeAppExit` | Остановить view dispatcher/scene manager; `rpc_system_app_confirm(app->rpc, true)`; выход |
| `RpcAppEventTypeSessionClose` | Остановиться; снять callback (`rpc_system_app_set_callback(app->rpc, NULL, NULL)`) |
| прочие | `rpc_system_app_confirm(app->rpc, false)` |

## Вывод данных

- Байты значения отправляются в USB CDC без изменений (без суффикса CR/LF — решение R10).
- Используется существующая инициализация USB CDC (`usb_cdc_single`, `UART_PORT`), как в
  `bc_scanner_script.c`.
- Если USB заблокирован (`furi_hal_usb_is_locked()`), приложение сообщает об ошибке
  (`BarCodeAppErrorCloseRpc`) и подтверждает `false`.

## Индикация (UI)

- Экран показывает состояние приёма: ожидание, «принято N байт», «отправлено», ошибка.
- Звук сканирования (`scan_sound`) может использоваться как подтверждение приёма.

## Совместимость и требования к прошивке

- Требуется заголовок `rpc/rpc_app.h` и подсистема RPC приложений (есть в актуальной прошивке).
- При необходимости добавить в `application.fam` зависимость `requires = ["rpc", ...]`.

## Legacy-поведение (сохраняется)

- File-режим: выбор `.bc` файла, кнопка «Run», отправка содержимого файла в USB CDC — без изменений.
