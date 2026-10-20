# Components reused from Umnik

Owner-authorized adaptation, 2026-10-20. Source: https://github.com/Ayuemin/Umnik

- `ui/YmnikApp.kt`: `ComposerActionTile`, `ComposerToggleTile`,
  `ComposerSectionHeader`, `CompactComposerTool` moved into Ilmar's
  `chat/ComposerMenuComponents.kt`. Purpose: reuse the existing actions/compact
  toggles/expandable-tools interface without importing the large chat screen.
  Local shapes/theme were substituted; no Umnik runtime dependencies were added.
- `network/OpenRouterCatalogClient.kt`: the GET `/models` catalogue pattern (including OpenRouter `output_modalities=all`) was
  adapted to Ilmar's provider connections. Purpose: retrieve real model IDs,
  without importing Umnik's multimodal parser, Gson model graph or diagnostics.
- The action-sheet composition and file-picker/dictation wiring follow Umnik's
  chat flow. Only UTF-8 text attachments are enabled in Ilmar (64 KiB maximum),
  appended to the editable draft and never sent automatically. Camera and image
  generation remain visibly unavailable. Agent/search/reasoning are unavailable
  until their execution capabilities exist; switches cannot grant permissions.

Ilmar-specific cache/favorites and provider grouping live separately in
`ProviderRegistry`, `ModelCatalogClient`, `ModelCatalogViewModel` and
`ui/ModelCatalogBrowser`. No changes are made to the Umnik repository.

## Review of next candidates (2026-10-20)

These are inspected candidates, not features implemented by this menu fix.

| Priority | Umnik source | Benefit to Ilmar | Adaptation boundary |
| --- | --- | --- | --- |
| 1 | `ui/YmnikApp.kt`: MessageBody, MarkdownText, IsolatedBlock | Formatted answers, tables, isolated code blocks with Copy | Extract parsing and Compose rendering into separate small files; omit Umnik guide links and app state. |
| 2 | `network/OpenRouterStreamParser.kt`, `OpenRouterResponseParser.kt` | Handle errors inside SSE choices, content arrays, finish reason and actual usage | Adapt to existing cancellable transport/org.json; keep Ilmar's strict incomplete-stream handling and sanitized errors. Do not copy the permissive EOF fallback blindly. |
| 2 | `RequestCostLedger.kt` | Accurate sums of reported costs; distinguish unknown cost from zero | Reuse BigDecimal accounting after a typed generation result and persistent usage storage exist. Do not infer billing from character counts. |
| 3 | `network/OpenRouterCatalogClient.kt`: model metadata parser | Supported reasoning/tool parameters and context limits | Extract only relevant capabilities into provider-neutral types; validate actual adapter support before enabling switches. |
| Later | `network/AgentToolLoopGuard.kt` | Stop repetitive tool cycles without progress | Needed with the first real agent executor, together with independent permission checks. |
| Later | `data/AtomicJsonFile.kt` | Atomic writes and last-good recovery for plugin/config files | Existing SQLite history and SharedPreferences do not need to be replaced. |

Also inspected RequestConcurrencyLimiter and ContextUsageTracker. The limiter
is unnecessary while Ilmar permits only one generation. The large context
tracker depends on Umnik-specific memory/RAG/skill markers; extract the small
ContextSizeEstimator only when adding an explicitly approximate context meter.

## Menu follow-up

The duplicate `В текст` and `Shell` shortcut row was removed at the owner's
request. Dictation remains available through the composer's microphone; the
Shell catalogue entry remains. `Включить` is now on the same row as `Выбрать всё`.
No additional Umnik runtime code was copied in this follow-up.

## Four completed adaptations (2026-10-20)

- MessageBody/MarkdownText/MarkdownTable/IsolatedBlock and rich-block splitting:
  extracted into `ui/markdown/RichMessageContent.kt` and `MarkdownText.kt`.
  Umnik-specific guide navigation is omitted. Code copy, basic inline formatting,
  headings, lists, quotes and horizontally scrollable tables are available.
- OpenRouterStreamParser/ResponseParser: adapted to Ilmar's cancellable transport
  and typed GenerationResult. Handles content arrays, complete tool-call fragments,
  errors in choices, finish reason and usage. Ilmar requires [DONE], preserving its
  existing protection against incomplete streams. Gson is used only to preserve
  the exact lexical USD amount, avoiding binary-double rounding.
- RequestCostLedger: BigDecimal accounting adapted in `UsageLedger`. Actual input,
  output, cached and reasoning tokens and reported costs are persisted per request.
  Missing values remain unknown; older messages are not assigned invented usage.
- Catalog capabilities: supported parameters, reasoning options and context/output
  limits are parsed and cached per model. Request parameters are revalidated at
  send time; unsupported/unknown modes stay unavailable. OpenRouter uses its own
  reasoning object; generic reasoning_effort requires advertised effort values.
- AgentToolLoopGuard: generic progress/repetition detection adapted separately
  from Umnik's browser heuristics. JSON keys are canonicalized without collapsing
  whitespace inside string values. AgentLoopRunner is called from ChatViewModel,
  uses complete tool rounds, and enforces round/call/time limits. Only registered,
  configured, chat-selected and globally granted tools can execute, with a fresh
  check before every call. No SSH/Shell/browser executors or plugin runtime were
  introduced by this feature set.

SQLite migrates version 1 → 2 by adding request_usage without rebuilding history.
Completed, failed, cancelled and interrupted requests are tracked without automatic
retry. Usage request IDs prevent duplicate accounting after restart. Agent tests
exercise the actual ChatViewModel-to-dispatcher path using an explicitly granted
fake executor; no fake executor is registered in the production APK.
