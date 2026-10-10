# Контракт: BLE Serial-профиль FlipperZero

**Feature**: `001-flipper-barcode-bridge` | **Date**: 2026-10-10

Описывает интерфейс обмена между Android-приложением (BLE-централь) и FlipperZero (BLE-периферия).
Источник: `targets/f7/ble_glue/services/serial_service.c` и `serial_service_uuid.inc`.

## UUID

> **Важно (проверено на реальном устройстве).** Прошивка хранит 128-битные UUID
> little-endian (`targets/f7/ble_glue/services/serial_service_uuid.inc`), поэтому «по воздуху»
> значения байтово развёрнуты относительно литералов в `.inc`. Ниже — фактические UUID,
> которые видит BLE-централь.

| Роль | UUID | Свойства | Направление |
|------|------|----------|-------------|
| Сервис | `8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000` | Primary | — |
| TX | `19ed82ae-ed21-4c9d-4145-228e61fe0000` | Read, Indicate | Flipper → телефон |
| RX | `19ed82ae-ed21-4c9d-4145-228e62fe0000` | Write, WriteWithoutResponse, Read | телефон → Flipper |
| Flow control | `19ed82ae-ed21-4c9d-4145-228e63fe0000` | Read, Notify | Flipper → телефон |
| RPC status | `19ed82ae-ed21-4c9d-4145-228e64fe0000` | Read, Write, Notify | двунаправленно |

Сопоставление с `.inc`-литералами: `fe60`→сервис, `fe61`→TX, `fe62`→RX, `fe63`→flow control,
`fe64`→RPC status (после байтового разворота).

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
