# Chat preview acceptance

## Verified baseline

Upstream `bba38ab34026414a99e8b63e9d914253db1c4b26`: Actions run 39400959266
completed successfully and published `ilmar-debug-apk`. This confirms compilation
and artifact availability, not phone or real-provider behavior.

The authoritative product roadmap remains in Obsidian (`Ilmar/ROADMAP.md`). This
file is a release test procedure, not a second roadmap.

## Implemented changes

- Global registry of OpenAI-compatible HTTPS connections and manually registered
  models. Connection credentials are stored separately in Android Keystore-backed
  AES-GCM ciphertext. Blank key input preserves the current key.
- Stable per-chat model references, searchable model bottom sheet, explicit
  unavailable states and global default. No silent provider fallback.
- Legacy settings migration preserves the SQLite history and original encrypted
  key. Matching legacy chat overrides migrate to IDs; unknown overrides remain
  unavailable until explicitly reselected. Metadata snapshot version is 1.
- Activity-scoped generation survives settings navigation and rotation; only
  complete SSE answers are committed. Stop cancels the actual HTTP call. Failure,
  stop or process interruption leaves the user message available for explicit
  retry without duplicating it. Only one generation runs at a time.
- Graphite UI, bounded sheets, scrolling composer and disabled unsupported modes.
- Bounded diagnostics store fixed codes, time and local chat IDs only. No prompt,
  answer, key, endpoint, exception body, or response body is recorded.

## Automated checks

Actions compiles Kotlin, runs unit/Robolectric tests, runs Android lint and builds
an APK on PRs as well as main. Reports and debug APK are separate artifacts. No
release/signing credentials or API keys are required. Confirm final run outcome;
inspect reports before treating this preview as accepted.

Tests cover registry migration/defaults, key separation using a fake secret vault,
per-chat selection, stable IDs after editing, URL validation, SSE completion and
truncation, cancellation/retry without duplicate messages, reply routing, explicit
HTTP failures and process-interruption restoration. The fake vault does not test
Android Keystore hardware behavior.

## Phone and real-provider gate — NOT VERIFIED

1. Install over the existing app without uninstalling. Confirm old chats, search,
   favorites, compact setting and key still work. Debug signing identity must
   match the installed app; never uninstall as an automatic workaround.
2. Confirm Settings → Diagnostics displays the expected version and SHA for the
   specific Actions run used to download the APK.
3. Add two real connections and multiple model IDs. Select different models in
   two chats. Restart; confirm selections and keys remain independent. Remove
   one key and verify that chat does not fall back to another provider.
4. Send a real streaming request. Open settings and rotate the device during it;
   return and verify one complete reply in the original chat. Switch chats during
   a request and confirm it never appears in the new chat.
5. Stop while the server sends no data: the network request must cancel promptly.
   Retry and confirm the user message appears only once. Disable network during
   streaming; incomplete assistant output must not become a completed message.
6. Check 401, 429, timeout and malformed/truncated SSE with a controlled endpoint;
   there must be no automatic repeat or hidden paid request.
7. Kill the process mid-response and relaunch. Confirm the user message remains,
   interruption is shown, and no request runs until the user retries. Clear or
   delete a chat during generation; a late reply must not recreate its messages.
8. Check small screen, font scale 1.5–2, long messages, keyboard, gesture and
   three-button navigation, model search, bottom sheets and history search.
   Scroll up during a response; streaming must not pull the reader down.
9. Inspect diagnostics: no secret, endpoint, prompt or answer appears. Clear it
   only through its confirmation dialog.

No phone screenshot or real-provider acceptance is claimed by automated tests.

## Deferred

GitHub execution, plugin runtime, SSH/Shell/browser, capabilities/reasoning/search,
Context Compact, background task scheduling, encrypted backups and production APK
signing remain separate work. History schema stays at SQLite version 1; this
change does not recreate or migrate that database. Process termination interrupts
requests; the app does not attempt to bypass Android force-stop behavior.

## Follow-up: base API address (2026-10-20)

The phone screenshot showed HTTP 404. The supplied address was
`https://openrouter.ai/api/v1`; the previous transport posted directly to that
base. The transport now resolves it to `/api/v1/chat/completions` on the same
host. `/v1` and bare-host API bases are also supported. Full and custom routes
remain unchanged, including their query parameters. This happens once before
sending; a 404 never triggers probing, retries, or a model change.

Settings show the final request address. Existing saved connections receive this
behavior without rewriting the registry or touching credentials/history. HTTP
401/403/404/429 have actionable messages, and errors link to global settings.

Before acceptance, retry the existing message on the phone. If 404 remains after
the route is correct, verify the model ID against the provider's actual catalog;
this change does not assert that the screenshot's model ID exists.

## Composer model placement

By user request, the current model selector lives inside the composer toolbar,
between Add (+) and the microphone. The chat header contains the chat title.
Verify that tapping the selector opens the model bottom sheet, that the current
chat selection persists, and that long names truncate without displacing the
microphone/send buttons on a small screen or with enlarged fonts.

The composer displays the model ID without its organization prefix (for example
`deepseek-v4.1-flash`), with the configured provider name on a smaller second
line. Full IDs remain unchanged in storage and requests. For legacy default
connection names, OpenRouter/OpenAI are identified from their exact known host;
custom connection names are respected.

## Provider catalog and Umnik menu

- Connections contain only provider name, API URL and key. Add a provider without
  selecting any model; it must appear in Settings → Models and the chat picker.
- Expand a provider: first use fetches GET /models from that same provider using
  its key. Later opens reuse the saved list; Refresh requests it explicitly.
- Verify full ID copy, favorites per provider, favorite-only filtering, search,
  chat-local selection and global default. Restart and check favorite/selection
  persistence. Offline/401/malformed responses must retain the saved catalog.
- A model missing after refresh stays visible but unavailable; its stable ID and
  favorite remain saved. The app must not silently substitute another model.
- Drawer: Projects is above history with an explicit future-feature explanation;
  New chat and Settings are on one fixed bottom row. Check long histories.
- Menu +: Umnik action tiles, compact mode toggles, expandable tools. Text file
  insertion and system dictation work; unavailable camera/image/agent/tools are
  disabled. Text import stays in the draft, is limited to UTF-8/64 KiB and never
  makes an automatic API request. Try denied access, binary and oversized files.
- Check a small screen, enlarged font and keyboard with both model and action
  sheets open. Successful CI does not constitute phone acceptance.

## Tool-selection layout follow-up

Check that expanding Tools shows Select all with Enable to its right on one row,
followed by the tool checkbox list. The duplicate dictation/Shell shortcut row
must be absent. Dictation through the composer microphone and the Shell catalog
entry remain available in their previous states. Verify small screens and large
font sizes; unavailable tools still cannot be enabled.

## Markdown, usage, capabilities and agent protection

1. Upgrade without clearing app data. Verify old chats and keys. SQLite version 2
   adds request_usage; no existing message table is recreated.
2. Ask for headings, bold/italic, lists, links, a quote, a Markdown table and a
   fenced code block. Verify copy returns code without Markdown fences. Long
   code/tables must scroll horizontally. Plain user messages remain plain text.
3. Refresh the provider's models to fetch capabilities. Check Reasoning/Tools and
   context/output limits in catalog rows. Reasoning is enabled only for supported
   protocol/metadata combinations. Toggle it per chat and select only advertised
   effort values; unknown models must not send invented parameters.
4. After completed streaming replies, inspect Chat → Statistics. Input/output,
   total, cached, reasoning tokens and cost come from provider usage. Missing
   values say no data. Restart and verify persisted totals. Cancel/retry/offline
   failures must not fabricate a zero cost or duplicate successful usage.
5. Agent mode requires advertised tools support, an installed executor, a global
   permission and per-chat selection. Production has no SSH/Shell/browser
   executors yet. Automated tests use a fake registered tool to prove normal tool
   rounds, revoked-permission rejection, repeated-cycle stopping and step limits.
6. Confirm malformed/error SSE never saves a partial assistant message as a
   successful completion. A completed earlier agent round can still have a
   reported charge even when a later tool/round fails; statistics retain it.
7. Recheck stop, clear/delete during generation, process termination and retry.
   Unknown interrupted charges remain unknown. No automatic paid retry occurs.

Full CommonMark compliance, syntax highlighting, external tool installation and
production device acceptance are not claimed by the unit test suite.

## Действия сообщений, озвучка и вложения (v7)

- Текст запросов и ответов без пузырей. Под запросом: копирование и спросить снова; под ответом: копирование, системная отправка, озвучка, ветка и информация.
- Старый запрос: «спросить снова» создаёт новую ветку только с префиксом до запроса, исходный чат остаётся целым. Незавершённый последний запрос повторяется на месте без дублирования. Ответвление ответа копирует префикс, включая выбранный ответ. Биллинг копий не дублируется.
- «i» сохраняет использованные модель/подключение, время и совокупный usage раундов ответа; прежние ответы явно без метаданных. Проверить после перезапуска.
- Системная TTS: установленный Android-движок/язык; выбор, запуск, остановка, выход с экрана. API-TTS: отдельные подключение/ID модели/голос; OpenRouter audio chat или совместимый /audio/speech. Нужны реальная аудиомодель и поддерживаемый голос. Нет автоматической замены провайдера при ошибке. Проверить ошибки 401/404 и отмену; расходы API-TTS отдельно от статистики чата.
- В «+»: файл, изображение и фото через системную камеру. Отмена камеры не прикрепляет файл. Отказ/отсутствие приложения обрабатываются. Проверить поворот и возврат после камеры на реальном устройстве.
- Вложения до отправки видны в ограниченной по высоте области; удаляются по X, открываются по нажатию. Прикреплённые черновики сохраняются в SQLite. Проверить перезапуск после завершённого импорта, отправку без текста, переход между чатами.
- До 8 файлов по 20 МБ; UTF-8 текст до 256 КБ; DOCX с извлечением текста; PDF до 12 страниц, отправляемых изображениями. Изображения нормализуются до 1600px, PDF — до 1400px. Шифрованные PDF, неподдерживаемые/повреждённые файлы и превышение лимитов дают ошибку без отправки. Исходники остаются в приватном хранилище.
- Для изображения/PDF требуется вход image в каталоге модели; обновить список моделей после установки APK. В каталоге видны модальности. Неподтверждённая поддержка блокирует отправку с объяснением. Автоматический выбор другой модели не выполняется.
- Проверить запрос с картинкой/фото/PDF на реальном API, следующий ход и повтор после перезапуска, сохранность оригинала в ответвлении и удаление исходного чата. Общие файлы ветвей не удаляются до последней ссылки.
- Аудит памяти и токенов: docs/context-and-token-audit.md. Compact и управление caching в этом APK не реализованы.

## Озвучка и экономия контекста (v8)

- Настройки → Озвучка: системный Android TTS либо подключение, модель и голос. Сохранить, вернуться в чат: кнопка динамика сразу озвучивает. Диалог выбора больше не появляется. Повторить на большом каталоге OpenRouter: ввод ID не должен зависать. Аудиомодели загружаются на worker; metadata разбирается один раз на версию.
- Запрос пользователя и его кнопки справа, без пузыря. Иконки обоих типов сообщений приглушены.
- Настройки → Память и контекст: предел, резерв ответа, последние 0–20 сообщений, автоматический Compact при 80%, локальные бюджеты токенов/USD на одну операцию отправки/сжатия. Реальное окно каталога ограничивает введённое значение. Unknown window использует локальный предел; оценка не является точным токенизатором.
- Меню чата → Память и сжатие: сохранить неизменяемые правила, выполнить ручной Compact, посмотреть/удалить/выбрать версию, найти оригинал через локальный FTS. Проверь факты/версии/ошибки/следующий шаг до и после сводки. История и вложения остаются целыми, последние сообщения — дословными.
- Создать длинную переписку и дождаться автоматического Compact. Индикатор показывает оценку входа, резерв и локальный предел; статистика — фактический usage, число/стоимость Compact и отдельно оценку сокращения входа. Не выдавать оценку сокращения за чистую денежную экономию: Compact сам стоит денег.
- Во время Compact остановить ответ/переключить чат/повернуть экран/завершить процесс. Предыдущая активная версия и исходники сохраняются; paid retry автоматически не выполняется. После явного повторения пригодная контрольная точка продолжает с последнего завершённого участка. Изменение правил/источников/модели/пакетизации инвалидирует checkpoint.
- Недопустимая сводка, length, превышение бюджета или непомещающиеся последние сообщения останавливают запрос с объяснением, без скрытой обрезки истории/цикла инструментов. Измени настройки в том же чате; новый чат не требуется.
- Для денежного preflight нужны текстовые тарифы каталога и поддерживаемый предел вывода. Изображения без денежной оценки блокируются при USD-лимите. Это локальная защита по тарифу, не жёсткая квота провайдера.
- Caching: стабильные правила/сводка не меняются на каждый запрос. OpenAI automatic caching не требует cache_control. Explicit OpenRouter/Anthropic ephemeral cache добавляется только для длинного префикса с известными выгодными тарифами (запись + 2 чтения дешевле 3 обычных входов), default TTL 5 минут. Для остальных API неподдерживаемое поле отсутствует. Попадание проверять по cached_tokens реального API.

## Текущая повторная приёмка (versionCode 11)

Проверенная сборка: 30395b3, Actions #39497159790 SUCCESS. Артефакт
ilmar-debug-apk: https://github.com/Ayuemin/Ilmar/actions/runs/39497159790/artifacts/12299170072.
Выше сохранены исторические процедуры; этот раздел описывает текущую сборку.

apksigner подтвердил обе подписи, package совпадает, versionCode 11 > 10.
Отчёт apk-update-report показывает update_compatible=false: ключ debug runner
отличается от установленного ef9eb8c1. Пользователь явно разрешил чистую установку
для этого теста. Удаление приложения уничтожит старую приватную SQLite, вложения,
настройки и ключи; автоматический перенос не заявляется. Для будущего обновления
с сохранением данных нужен постоянный исходный ключ подписи.

1. Распаковать APK из артефакта, установить начисто, заново настроить OpenRouter.
   Проверить SHA/version в диагностике. Не публиковать ключи вместе с журналом.
2. Проверить обычный чат, calculator/read_file и создание файла; повтор запроса в
   той же задаче должен показывать сохранённый результат без повторной записи.
3. В длинном чате выполнить ручной и несколько автоматических Compact. Проверить
   правила, план, версии файлов и поиск исходных сообщений. Отключить сеть при
   Compact: ошибка не должна становиться успешной capsule, история сохраняется.
4. Перезапустить приложение: история, текущие ключи, настройки, файлы и план должны
   сохраниться. UNKNOWN после эффекта и до результата покрыт fault-injection в
   Robolectric; обычный force-stop не доказывает попадание в это окно.
5. Проверить индексацию: фон копит batch 16, «Индексировать сейчас» отправляет хвост,
   остановка индекса не останавливает генерацию. Проверить собственный JSONL как
   вложение. Usage/cached/cost смотреть в диагностике, неизвестные расходы — null.

Полное шестипунктное ТЗ покрыто автоматическими регрессиями без платных API.
Реальный OpenRouter, Android Keystore, визуальная приёмка, сохранность при совместимом
обновлении и телефонная fault-injection остаются открыты. Exactly-once не обещается.
