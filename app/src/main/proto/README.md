# Vendored protobuf schemas (Flipper RPC)

Эти `.proto`-файлы вендорены из репозитория
[`flipperdevices/flipperzero-protobuf`](https://github.com/flipperdevices/flipperzero-protobuf)
(сабмодуль `assets/protobuf` прошивки FlipperZero), чтобы обеспечить совместимость с прошивкой
и генерировать Java-классы (javalite) во время сборки.

- Источник: `flipperzero-firmware/assets/protobuf`
- Версия сабмодуля: `6c7c0d55e82cb89223cf4890a540af4cff837fa7` (0.11-15-g6c7c0d5)
- Дата вендоринга: 2026-10-10

`flipper.proto` содержит корневое сообщение `PB.Main` (поле `command_id` + `oneof content`), в
которое оборачиваются все RPC-команды и ответы. `application.proto` содержит сообщения приложения
(`StartRequest`, `AppStateResponse`, `DataExchangeRequest`). Остальные файлы — транзитивные импорты.

Не редактировать вручную: при обновлении схемы обновляйте файлы целиком из указанного источника.
