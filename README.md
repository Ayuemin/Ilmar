# Ilmar

Модульный Android AI-агент с расширяемой системой инструментов.

Документация и Roadmap ведутся отдельно в Obsidian.

## CI
- `android-checks.yml`: быстрая компиляция и модульные тесты без APK на push и pull request.
- `android-apk.yml`: сборка debug APK вручную через Actions → Run workflow.
- Кэш Gradle сохраняется между запусками.
- Пока Android-проект не добавлен, workflow завершается без сборки.

Сборка релизного подписанного APK будет добавлена после настройки подписи и согласования release-процесса.
