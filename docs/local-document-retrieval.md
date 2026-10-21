# Local web document retrieval — evidence and acceptance

## Baseline audit (2026-10-21)

Audited main `a44e94c8a82b5b10c7a0a6107e04766957afcac0`. GitHub API confirms Actions [39599875438](https://github.com/Ayuemin/Ilmar/actions/runs/39599875438) succeeded. The live open PR list is #6, #7, #8, #10 (draft); the current main includes #11 and the runtime/security changes. Historical Roadmap snapshots are not a substitute for the current tree. No AGENTS.md was present in either repository tree. Read the sole Obsidian `Ilmar/ROADMAP.md` and architecture, security, models/token economy, chats/memory, plugins/bus and repository/CI notes before editing.

* `tools/WebTools.kt`: bounded decoded response (8 MiB default, 64 MiB configurable maximum), content length and streaming checks; HTTPS, credential rejection, socket DNS validation, no redirects/retries, cancellation cancels the HTTP call. Jsoup removes whole nav/footer/header/form elements and chooses first article/main, flattens text, then takes `excerpt_chars` (64,000 by default). The late part of a long page is irretrievable from that result.
* `tools/NativeTools.kt`, `ToolDataStore.kt`: `fetch_url` uses an installation-wide SQLite cache of the excerpt, not the source. Cache entry count/TTL are configurable; source retrieval must bypass this durable cache. Tool logs store metadata. Persistent user memory, attachments and traces must not be used for web originals.
* `browser/SearchRouting.kt`, `ProviderSearch.kt`, `ChatViewModel.kt`: provider search protocol depends on configuration/capabilities; AUTO chooses browser when no provider protocol exists. Provider error is not an automatic second request. Browser fallback can be advertised after provider search. Local retrieval cannot intercept proprietary provider search content or promise to eliminate provider search tokens.
* `browser/BrowserTools.kt`, `BrowserPolicy.kt`: global + chat authorization rechecked; actions/session confirmation retained. Page snapshots are bounded separately. Retrieval will use Fetch only, without driving WebView or extending its permissions.
* `chat/ToolRegistry.kt`: bound trusted executors check grant and per-chat selection at execution; additional document tools must share Fetch authorization and an active run capability, not a new global document catalogue.
* `agent/AgentLoopRunner.kt`, `LocalToolExecutor.kt`, `ToolOperationPolicy.kt`: per-run history/call/time/result budgets, execution journaling and replay guard. `memory/ToolHistoryProjection.kt` reduces complete call groups, with original traces retained. New document IDs must survive projection, but a closed run must never resolve them again.
* `memory/ContextStore.kt`: existing history index is FTS4, not FTS5. `ArchiveRetrieval.kt` invokes optional embedding APIs for chat history. Neither is suitable for temporary no-model web search; do not route documents through either.
* `settings/RuntimeLimits.kt`, `memory/ContextPolicy.kt`, `chat/UsageLedger.kt`: actual provider usage/cost and local byte-based ContextEstimate are distinct. A local estimate is not measured billed tokens; model requests include schemas/history/system overhead.

Reviewed WebTools/NativeTools/SearchRegistry, SearchRouting/BrowserAuditPermission/BrowserRunControl, AgentLoop/AgentRecovery, ContinuousContext/TokenEconomy tests and corresponding implementations. Device SQLite builds differ: only an actual FTS5 CREATE/MATCH/bm25 probe can establish support, and unicode61 is not Russian morphology.

## Stage A contract

`fetchDocument` returns only after the complete bounded HTTP body reaches EOF. It retains the raw source and the unchanged legacy extraction. `fetch` formats only the legacy projection; response size errors never become partial successful documents.

`TemporaryDocuments` is owned by one chat/run and holds no disk files. Eight documents, 24 MiB retained UTF-16 string payload, 15 minutes monotonic TTL; quota rejection never silently evicts sources. This measures retained string payload, not actual process RAM. Extraction/index overhead and transient HTTP/Jsoup buffers require separate bounds and device measurements. Disk usage for this store is zero. Process death inherently removes its data; there is no orphan directory or index to recover. Close is idempotent and denies subsequent reads/writes. No source content enters chat memory or the global cache through this component.

This infrastructure commit does not activate retrieval or change existing defaults. Integration must attach close to the run's finally block and recheck Fetch permission for every local read, including after revocation while Fetch is in flight.

## Reproduction and limitations

`./gradlew :app:testDebugUnitTest --tests '*WebToolsTest' --tests '*DocumentInfrastructureTest'` exercises complete-source retention, one load for projections, unknown-length oversized rejection, isolation, TTL/count/memory quota and close. `DocumentInfrastructureTest` emits `app/build/reports/fetch-baseline.json`; its synthetic late-page fact is absent from the legacy excerpt. Values labelled estimated tokens use existing ContextEstimate, not a model tokenizer/usage. No model/browser requests are needed by this fixture.

Later acceptance must measure Recall@3/5/10, repeated/expanded reads, tokens including schemas and complete model rounds, latency, heap and APK deltas; actual answer precision/completeness and phone/process-kill acceptance remain separate. No cost or quality improvement is established by this infrastructure. Signing and existing user databases are unchanged.
