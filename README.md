# Substratum

Мерцающие разломы в Закулисье: психологический хоррор про Level 0 для Minecraft.

## Требования

Minecraft 1.21.1, Fabric Loader 0.18.6+, Fabric API, Fabric Language Kotlin, Architectury API.
Immersive Portals 6.1.0 уже вложен в jar мода, ставить его отдельно не нужно.
Мод нужен и на сервере, и на клиенте.

## Команды

Все под `/substratum`, нужен уровень прав 2.

| Команда | Что делает |
|---|---|
| `/substratum enter` | отправляет на Level 0 в обход разлома |
| `/substratum rift spawn` | открывает разлом рядом с игроком |
| `/substratum exit locate` | ищет ближайший выход в радиусе 1024 блоков |
| `/substratum sanity get` | показывает таймер рассудка |
| `/substratum sanity set <ticks>` | задаёт таймер рассудка |

Для другого игрока: `/execute as <игрок> run substratum ...`.

## Настройки

Файлы создаются в `config/` при первом запуске.

`substratum-server.toml` читается при запуске мира или сервера: шанс и кулдаун разломов, шанс
выходов, сколько живёт увиденный разлом, сохранять ли вещи при смерти на Level 0.

`substratum-client.toml` перечитывается по F3+T: `intensity` (0 полностью выключает
пост-обработку) и `motion_sickness_safe` (убирает любые изменения поля зрения).

## Сборка

```bash
./gradlew build
```

Готовый jar: `fabric/build/libs/substratum-fabric-<версия>.jar`, без суффиксов `-dev-shadow` и `-sources`.

Собираются только платформы из `enabled_platforms` в `gradle.properties`, сейчас это `fabric`.
Модуль `neoforge` лежит в репозитории, но в сборку не входит: у Immersive Portals нет порта на NeoForge.

## Проверки

`./gradlew build` запускает их сам. Отдельно:

```bash
./gradlew :common:check
```

`:common:shaderCheck` компилирует шейдеры локальным драйвером OpenGL, поэтому в `check` не входит.
`:common:mazeCheck --args="--map"` печатает ASCII-план этажа.

## Запуск из IDE

Конфигурации в `.run/` IntelliJ IDEA подхватывает сама: `Fabric Client`, `Fabric Server`,
`Build All`, `Checks`, `Maze Map`.

## Структура

| Модуль | Содержимое |
|---|---|
| `common` | вся логика, миксины, ресурсы; официальные маппинги Mojang |
| `fabric` | точки входа Fabric, `fabric.mod.json`, миксины в Immersive Portals |
| `neoforge` | точки входа NeoForge, вне сборки |
| `libs` | вложенный Immersive Portals и его зависимости для dev-запуска |

## Лицензия

Код мода под [MIT](LICENSE). Immersive Portals распространяется под Apache-2.0.
