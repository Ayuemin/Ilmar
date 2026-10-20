# Ilmar

Android-чат на Kotlin, Compose и Material 3 — основа будущего агента.

- Несколько OpenAI-compatible HTTPS-подключений и зарегистрированных моделей.
- Ключи отдельно для каждого подключения, зашифрованные через Android Keystore.
- Выбор модели для каждого чата, локальная SQLite-история, streaming и остановка.
- Сетевые ошибки не запускают автоматический повтор платных запросов.
- Нативные tools: настраиваемые API поиска (Brave/Tavily/SerpAPI/свой JSON API), HTTPS fetch, калькулятор, файлы, память, план и уточнение.
- Непрерывный чат: отдельный провайдер/модель Compact, карточка состояния, полная локальная история, SQLite FTS и опциональный RAG с API-эмбеддингами.
- Глобальные расширенные лимиты и подробный журнал с выгрузкой JSONL.
- GitHub-инструменты и плагины пока не исполняются.

Документация и единственный Roadmap: [Obsidian / Ilmar](https://github.com/Ayuemin/Ayuemin-obsidian-/tree/main/Ilmar).

## Проверка и APK

`android-checks.yml` компилирует Kotlin, запускает unit/Robolectric tests и lint,
затем собирает `ilmar-debug-apk` на push в main и pull request.
`android-apk.yml` позволяет отдельно собрать debug APK вручную через Actions.
Артефакты хранятся 14 дней. SHA и версия доступны в Настройки → Диагностика.

Локально нужны JDK 17, Gradle 8.11.1 и Android SDK 35:

```sh
gradle :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

[Проверки перед телефонной приёмкой](docs/chat-preview-acceptance.md).
[Android tools: реализованное, ограничения и отложенные пункты](docs/android-tools-mvp.md).
[Непрерывный контекст, расширенные лимиты, журнал и поисковые API](docs/continuous-context-and-diagnostics.md).
Успешный CI не заменяет проверку API и интерфейса на устройстве.
Обновление поверх старого APK требует совпадающей подписи; удалять приложение ради
установки без сохранения данных нельзя. Стабильная релизная подпись ещё не настроена.
