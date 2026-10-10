# Контракт: BLE Serial-профиль FlipperZero

**Feature**: `001-flipper-barcode-bridge` | **Date**: 2026-10-10

Описывает интерфейс обмена между Android-приложением (BLE-централь) и FlipperZero (BLE-периферия).
Источник: `targets/f7/ble_glue/services/serial_service.c` и `serial_service_uuid.inc`.

## UUID

| Роль | UUID | Свойства | Направление |
|------|------|----------|-------------|
| Сервис | `0000fe60-cc7a-482a-984a-7f2ed5b3e58f` | Primary | — |
| TX | `0000fe61-8e22-4541-9d4c-21edae82ed19` | Read, Indicate | Flipper → телефон |
| RX | `0000fe62-8e22-4541-9d4c-21edae82ed19` | Write, WriteWithoutResponse, Read | телефон → Flipper |
| Flow control | `0000fe63-8e22-4541-9d4c-21edae82ed19` | Read, Notify | Flipper → телефон |
| RPC status | `0000fe64-8e22-4541-9d4c-21edae82ed19` | Read, Write, Notify | двунаправленно |

## Поведение

1. **Подключение**: телефон устанавливает GATT-соединение с сервисом `fe60`. При подключении
   прошивка автоматически открывает RPC-сессию и переводит Serial-профиль в состояние RPC active
   (`bt_on_gap_event_callback`, `GapEventTypeConnected`).
2. **Подписки**: телефон включает indicate на TX (`fe61`) и notify на flow control (`fe63`).
3. **Отправка**: телефон пишет RPC-кадры в RX (`fe62`). Кадр может быть больше одного BLE-write;
   максимальный размер полезной нагрузки BLE — `BLE_SVC_SERIAL_DATA_LEN_MAX` = 486 байт.
4. **Приём**: ответы RPC приходят indicate-уведомлениями на TX (`fe61`).
5. **Flow control**: Flipper уведомляет о готовности буфера через `fe63`; телефон обязан
   приостанавливать отправку, пока буфер не освободится (в MVP значения штрихкодов малы, но
   правило соблюдается для надёжности).
6. **RPC status**: характеристика `fe64` отражает активность RPC-сессии (active/not active).
7. **Разрыв**: при GAP-разрыве прошивка закрывает RPC-сессию
   (`GapEventTypeDisconnected`); телефон переводит состояние в `Disconnected`.

## Кодирование кадра

- Формат: `varint(длина) || PB_Main` (protobuf delimited).
- Соответствует прошивке: `pb_decode_ex(..., PB_DECODE_DELIMITED)` /
  `pb_encode_ex(..., PB_ENCODE_DELIMITED)` в `applications/services/rpc/rpc.c`.

## Ошибки и деградация

| Ситуация | Ожидаемое поведение телефона |
|----------|------------------------------|
| Сервис `fe60` не найден | Не подключаться; показать «Не подключено» |
| Разрыв соединения | `Disconnected`; попытка восстановления (FR-009) |
| Нет ответа на RPC-команду | Таймаут → `Error` (FR-015) |
| Нет разрешений Bluetooth | Не сканировать; показать понятное состояние (FR-004) |
