# Исправления после аудита issue #9

База: ce2a836c7e11e93148da976022b31b65a7912699, PR #10 поверх feature/context-compact-ring. Это описание изменения и приёмки, не новый Roadmap. Подпись APK, ключи и совместимость установки оставлены без изменений по решению владельца.

| Находка | Изменение | Регрессия |
|---|---|---|
| F01 activity | Одинаковые соседние состояния streaming не добавляются повторно. Activity и очередь имеют ограниченную высоту и прокрутку | AuditRuntimeTest: 1000 delta, один request, один streaming phase; device IME/TalkBack ещё нужен |
| F02 ring | Manual Compact публикует projection; model/config fingerprint инвалидирует stale gauges, clear их удаляет. Auto-off/no-op/fallback видимы. После restart неизвестно до нового запроса | AuditRuntimeTest, AuditCompactTest |
| F03 usage | Attempts/sent/not_sent отдельно; Compact включён в стоимость текущего ответа; его запросы и цена видны в общей статистике. Старые reply metadata не переписываются | AuditRuntimeTest, UsageMigrationTest |
| F04 deletion | Сначала cancel/join generation/indexing, затем clear/delete всех chat-owned данных и per-chat maps. Import epoch блокирует позднее сохранение. Interventions удаляются. Shared user downloads сохраняются | AuditRuntimeTest, existing deletion/embedding tests |
| F05 browser grants | Глобальный запрет учитывается в schemas, beforeExecute и executor, включая fallback/download/batch | BrowserAuditPermissionTest |
| F06 modifying actions | Все click/type/select, включая coordinate и batch, требуют native approval конкретных аргументов; mode revoke во время согласования блокирует исполнение. DOM risky guard остаётся дополнительной проверкой | BrowserAuditPermissionTest, BrowserBatchTest и exact shipped JS suite |
| F07 session | Явная общая cookie/auth session приложения. Первый доступ и переход к другому чату требуют подтверждения; UI объясняет общий scope. Изоляция cookies не заявляется | BrowserAuditPermissionTest; реальная WebView-приёмка отдельно |
| F08 logs | Default journal/export только metadata; prompts, tool bodies, task goals и plan text исключены. Старые raw записи также не экспортируются. Tool traces в приватной scoped БД сохраняются для восстановления | MetadataExportTest, DiagnosticsAcceptanceTest |
| F09 signing | Отложено владельцем | Никаких новых ключей или подписей |
| F10 SSE | Malformed/error/incomplete-tool failures сохраняют уже известный usage, не принимают partial reply и не повторяют запрос. Общий response/event cap ограничивает память | SseUsageFailureTest, existing transport suite |
| F11 Compact no-op | Persisted no-op fingerprint не позволяет снова покупать неизменённый неэффективный Compact; manual force и изменённый prefix разрешены | AuditCompactTest, repeated preparation + DB reopen |
| F12 queue | SQLite v12 добавляет nullable task_id без удаления данных. NOW связывается с intended task, follow-up выбирает обе категории одним ORDER BY id. Legacy unbound/старые задачи не доставляются автоматически; можно редактировать/удалить и отправить заново | AuditRuntimeTest migration/order/scope, existing delivery tests |
| F13 economy | Экономичный профиль готовит ограниченные rounds/time/output, а контекст — 8192/1024. Применяется только кнопкой сохранения; существующие предпочтения не перезаписываются при обновлении | RuntimeLimits/BudgetCaching + fake workloads; реальной USD-экономии пока не измерено |
| F14 maintenance | UI attachment limits из RuntimeLimits; message actions 48dp/контрастнее, drawer action label. Official Gradle 8.11.1 wrapper с release checksum, Actions закреплены commit SHA. Исторические документы помечены | CI и wrapper checksum verification |

MODEL_START/MODEL_FINISH связаны request_id/task_id/run_id/round_id и purpose; MODEL_REQUEST/MODEL_RESPONSE сохранены для старого анализатора. Не складывать обе пары как отдельные оплачиваемые запросы. not_sent terminal не означает provider call. После аварийного завершения process recovery использует persistent reservations; это не гарантия exactly-once внешнего действия.

Полная история и вложения остаются локально. Compact меняет рабочую проекцию и может использовать fallback excerpts; originals и pinned rules не переписываются. Бюджеты/токены — локальные оценки, provider usage — полученные данные, invoice — отдельный источник. Embeddings, TTS и отдельно выставляемые search fees не входят в сумму генерации/Compact. Reasoning off отправляет явный false для подтверждённого OpenRouter adapter и none только при поддержке API; другие протоколы не выдумываются.

Shipped Node JS suite выполнен локально. Android compile/tests/lint/APK проверяются CI соответствующего HEAD PR #10. Новый AuditCompactTest включает manual projection и persistent no-op; длинный fake dialogue расширен до 120 пользовательских turns / 240 сообщений. Реальные API не вызывались.

Ещё нужны: device IME/fontScale/TalkBack/реальные WebView формы/auth/cookies и background/process kill; invoice comparison и качество RAG; полный migration/corruption/disk-full matrix; независимый dependency CVE scan/SBOM и полный dependency locking/verification. Сканирование не выдаётся за выполненное. Старые подробные записи остаются в приватной БД до явной очистки журнала; обычный экспорт их содержимое скрывает. In-place APK update не проверяется этим PR, подпись отложена.
