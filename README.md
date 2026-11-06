# Substratum

Кооперативный психологический хоррор про Закулисье на движке Minecraft: своя сборка, свой лаунчер, команда до 4 человек.

## Состав

| Папка | Что внутри | Чем открывать |
|---|---|---|
| [`game`](game) | сама игра: мод Fabric для Minecraft 1.21.1 на Kotlin и Java | IntelliJ IDEA |
| [`launcher`](launcher) | лаунчер на Rust и Slint | RustRover |

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

## Лицензия

Код проекта под [MIT](LICENSE). Immersive Portals распространяется под Apache-2.0, интерфейс лаунчера сделан на [Slint](https://slint.dev).
