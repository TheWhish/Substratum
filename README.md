# Substratum

Кооперативный психологический хоррор про Закулисье на движке Minecraft: своя сборка, свой лаунчер, команда до 4 человек.

## Состав

| Папка | Что внутри | Чем открывать |
|---|---|---|
| [`game`](game) | сама игра: мод Fabric для Minecraft 1.21.1 на Kotlin и Java | IntelliJ IDEA |
| [`launcher`](launcher) | лаунчер на Rust и Slint | RustRover |
| [`backend`](backend) | сервис команд на VPS: коды, состав, старт, пересылка трафика | RustRover |
| [`protocol`](protocol) | сообщения между лаунчером и сервисом | RustRover |

## Сборка

Игра:

```bash
cd game
./gradlew build
```

Лаунчер:

```bash
cd launcher
cargo run
```

Для лаунчера на Windows нужны Visual Studio Build Tools с компонентом «Разработка классических приложений на C++».

Лаунчер ставит сборку в `%APPDATA%\Substratum` по манифесту [`launcher/manifest.json`](launcher/manifest.json), который публикуется в GitHub Releases. Чтобы проверить другой манифест, укажите его адрес в переменной `SUBSTRATUM_MANIFEST`. Полная проверка установки, около 1 ГБ загрузки:

```bash
cd launcher
cargo test --release -- --ignored
```

## Сервис команд

Локально:

```bash
cd backend
cargo run
```

Сервис слушает `127.0.0.1:7878` (меняется переменной `SUBSTRATUM_BIND`). Лаунчер по умолчанию ходит на боевой адрес, для локальной проверки укажите `SUBSTRATUM_BACKEND=ws://127.0.0.1:7878`. Второй лаунчер на том же компьютере запускается с отдельной папкой через `SUBSTRATUM_HOME`.

На VPS сервис собирается `cargo build --release`, работает как служба systemd, а TLS и WebSocket снаружи принимает Caddy:

```
team.example.com {
    reverse_proxy 127.0.0.1:7878 {
        header_up X-Real-IP {client_ip}
    }
}
```

Сервис ограничивает число подключений с одного адреса и берёт адрес из `X-Real-IP`, поэтому строка `header_up` обязательна.

Для прямого соединения между игроками сервис принимает метки лаунчеров на UDP `0.0.0.0:7878` (переменная `SUBSTRATUM_BEACON`): так он узнаёт внешние адреса и сообщает их напарникам. Этот UDP-порт должен быть открыт снаружи, Caddy в нём не участвует. Если порт закрыт, игра идёт через сервис, как раньше.

Проверка туннеля целиком (нужен запущенный сервис): `SUBSTRATUM_BACKEND=ws://127.0.0.1:7878 cargo test a_guest_reaches -- --ignored` в `launcher/`, с `SUBSTRATUM_ROUTE=relay`, когда UDP сервиса закрыт.

## Лицензия

Исходный код открыт только для чтения: [Substratum Source-Available License](LICENSE). Смотреть, изучать и предлагать правки можно; распространять игру, лаунчер, мод или их части, выкладывать сборки и поднимать свои сервисы команд без разрешения автора нельзя. Играть — только через официальный лаунчер. Версии до этой лицензии остаются под MIT.

Сторонние части сохраняют свои лицензии: Immersive Portals — Apache-2.0, шрифты — SIL OFL 1.1, интерфейс лаунчера сделан на [Slint](https://slint.dev) (Royalty-free License 2.0), сглаживание — [SMAA](https://github.com/iryoku/smaa) (MIT).
