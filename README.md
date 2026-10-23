# Recaf Discord RPC

Discord Rich Presence для **Recaf 4**. Показывает текущий класс, метод в ассемблере, режим редактора, имя JAR и время сессии.

```text
Assembler · Main.main()
Workspace: app.jar
```

Поддерживает декомпилятор, ассемблер, hex, структуру класса, ресурсы, поиск, маппинги и скрипты. Метод/поле отображается, если он доступен в пути редактора Recaf.

## Установка

1. Скачай JAR из [Releases](https://github.com/virtuoz57/recaf-discord-rpc/releases/latest).
2. Помести его в `%APPDATA%\Recaf\plugins`, удалив предыдущую версию плагина.
3. Запусти настольный Discord и перезапусти Recaf.

Требования: **Recaf 4.0.0-alpha, Java 22+**. Для нестандартного каталога Recaf используй его папку `plugins`. Показ активности должен быть включён в Discord.

## Настройки

Файл: `%APPDATA%\Recaf\config\discord-rpc.properties`. Создаётся при первом запуске, перечитывается автоматически. Интервал обновления статуса по умолчанию — 15 секунд.

```properties
applicationId=1352044516411965551
showActivity=true
showWorkspaceName=true
showClassName=true
showMemberName=true
showFullClassName=false
showElapsedTime=true
```

`false` скрывает соответствующие данные; `showActivity=false` включает фиксированный текст `details`. Необязательный `largeImage` — ключ изображения в приложении Discord. Полный конфиг: [discord-rpc.properties](src/main/resources/discord-rpc.properties).

## Сборка

```powershell
.\gradlew.bat build
```

Результат: `build/libs/recaf-discord-rpc-1.1.0.jar`. Для другого JAR Recaf: `-PrecafJar=C:/path/to/recaf.jar`.

Проверено на Windows: 19 тестов, включая загрузку плагина, вкладки JavaFX и IPC. Unix-транспорт и отображение в реальном Discord отдельно не проверялись.

[MIT](LICENSE) · Gson и Gradle Wrapper: Apache-2.0.
