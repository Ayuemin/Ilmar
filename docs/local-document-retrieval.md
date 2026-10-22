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

## Stage B extraction

The temporary entry now owns a deterministic structure alongside the raw source. HTML h1–h6 and Markdown ATX/setext headings map to `s0` (whole document), `s1`, … within a SHA-256 source version. Section paths and half-open block ranges include nested sections. Text offsets are UTF-16 positions in extracted text, not HTML byte coordinates. Paragraphs are segmented at sentence boundaries; list items, code blocks and table rows stay atomic. HTTP(S) link destinations remain next to their labels. Original source is always retained for inspection; no model summarizes or rewrites sentences.

Unlike legacy extraction, the new projection reads the entire body, preserves header/footer conditions, removes executable/style/template elements, and removes only simple all-link nav menus. This intentionally favors retaining useful conditions over aggressive boilerplate removal. Markdown fences protect apparent headings in code. Unknown/poor hierarchy falls back to `s0`. Resource limits: 12,000 blocks, less than 4 Mi UTF-16 text characters, HTML walk depth below 128; exceeding them rejects indexing explicitly. The store quota includes extracted strings and block metadata as a conservative payload estimate; this is still not a device heap measurement.

`DocumentExtractionTest` covers heading paths/version stability, half-open bounds, table rows, nested lists without duplicate text, links, late text, negations/exceptions, footer conditions, malformed HTML, Markdown/fences/setext, plain text and excessive structures. Retrieval is not wired to the agent in this stage.

## Stage C lexical retrieval

`DocumentIndex` probes CREATE FTS5, MATCH and `bm25()` on the executing SQLite. The database is in memory with `temp_store=MEMORY`. If SQLite lacks FTS5, deterministic Kotlin BM25 is used; no history FTS4 or embedding API is called. Search keys use NFKC/lowercase and small RU/EN suffix stemmers; originals stay unchanged. Encoded ASCII keys prevent unicode61 from splitting error codes/versions/URLs. This preprocessing explicitly implements Russian stemming; the SQLite tokenizer does not. English stemming is deliberately lightweight and neither stemmer is a semantic model.

Five explicit bilingual synonym groups, at most two trigram-filtered edit-distance-one typo candidates per term, at most 24 original terms, and a bounded 16,000-word typo vocabulary avoid large dictionaries/form generation. Exact identifiers/numbers/URLs/CamelCase/all-caps names have no morphology/synonym/typo expansion. Quoted phrases require literal normalized phrase presence. A query containing protected tokens cannot rank a passage using only an unrelated generic word; comparisons can retrieve separate passages for different requested identifiers.

Heat ranking uses weighted original/stem/synonym/typo matches, query-term coverage, proximity, heading paths, literal phrases and anchored numeric facts. Scores are heuristic, not probabilities. Optional feedback takes at most three terms from the first three matches with weight 0.15; it cannot introduce passages without any initial-query anchor. It is off by default. Neighbor windows stay within sections, merge overlaps up to seven blocks/6,000 characters, and retain complete atomic units. Repeated window text is deduplicated. Structure/heat/morphology/feedback/context options support ablation; exact lexical matching/BM25 remain available without heat. Memory payload accounting bounds index construction (16 MiB by default); device heap includes further SQLite/runtime overhead and is not inferred from it.

Tests cover RU/EN forms and NFKC, toggles, synonyms, typos, a late answer, exceptions, exact versions/codes/product names, literal phrases, deduplication, feedback bounds, empty unrelated searches, index quotas/close, actual runtime FTS probing and forced Kotlin fallback. Device-specific FTS5 support remains a runtime observation, not a claim about all Android phones.

## Stage D agent integration

`DocumentSession` is a coroutine capability created only for an opted-in authorized agent run with `fetch_url`. The three small document schemas inherit its current global grant and chat selection; no global document tool/ID catalogue is installed. Every local operation rechecks authorization. Fetch checks again after HTTP IO before retaining anything. All IDs/ranges/types/extra arguments are validated; they cannot name files. Unknown/expired/previous-run IDs fail closed.

In Local/Compare runs, NativeTools Fetch bypasses the installation cache and uses the run store. Repeated URLs reuse that source. Short extracted documents (up to 3,000 chars) are delivered directly; long documents use the latest user query to select initial windows. Search can be reformulated locally, outline can be paged, and read accepts section or fragment IDs with optional neighbor blocks and explicit `repeat`. Output carries source URL/title/version, document/section/fragment IDs, UTF-16 extracted offsets, block bounds and kinds. Sent blocks are tracked only after a successfully bounded result; repeated reads skip them unless explicitly requested. Read pagination uses `next_fragment_id`, preserving whole sentences/list items/table rows/code units. Output defaults to 8,000 selected characters and at most 24,000 serialized UTF-8 bytes, further bounded by existing global limits. An individual oversized atomic unit is reported as blocked, never cut and presented as complete; use a narrower query/section where possible. A truly unbroken oversized sentence/code unit remains a known presentation limit.

TTL timers remove raw source, structure and in-memory indices while a run waits; run finally closes them on success/error/cancel. Process death inherently loses the store, with no disk recovery debris. The existing history projection preserves document/continuation IDs. Main conversation traces may retain selected quotes as existing tool history; raw long pages and indices are never automatically saved as Ilmar memory or passed to archive embeddings. No Context Compact behavior, signing or user database schema changes are required.

Compare formats the legacy and selected variants from one full fetch and emits numeric metadata through diagnostics, delivering only the selected variant to the model. This is not paid usage or end-answer evaluation. Preferences are isolated in `ilmar_document_experiment`, default Legacy; UI and browser experiment policy are completed in stage E. Existing browser permission/confirmation checks remain in force.

## Stage E experiment and measured results

Settings → «Эксперимент: документы» contains Legacy/Local/Compare, the five algorithm switches, initial maximum 3/5/10 and three browser policies. They apply to subsequent tasks and do not grant Fetch/browser access. Legacy and existing browser behavior remain defaults. Limited browser escalation removes general browser search/batches, requires a specific HTTPS URL plus javascript/authentication/interaction reason and native approval, then delegates all original checks/confirmations. It cannot authorize a revoked capability. Disabling automatic browser routing preserves available Fetch/provider tools and bounds provider-search addons rather than accidentally enabling them on every round.

Precision regressions reproduced locally before correction: case-distinct URL paths were conflated and an explicit HTML title was not searchable. URL identity now preserves path case, explicit metadata titles are indexed without treating the first section's fallback title as global, and non-comparison product/version/code queries require all protected identifiers. Explicit compare/versus queries can retrieve separate requested products. Ordinary word case folding remains. Structure-off really uses a flat map/path and omits heading weights. Markdown .MD/query/fragment URLs and equivalent Fetch URLs are normalized for reuse. A pre-DOM cap of 64,000 `<` characters rejects tag-dense HTML before Jsoup allocation; HTTP byte caps, extraction depth/size and index quotas still apply. Errors explicitly deny indexing, never silently index a prefix.

The [corpus](../app/src/test/resources/documents/cases.json) has **12 questions, 10 fixed RU/EN HTML/Markdown/plain documents and 22 gold passages**. The long page has 1,400 ordinary paragraphs before the answer. Separate infrastructure/session tests cover HTTP/structure/index limits, revocation, lifecycle and fresh-run recovery. They do not substitute for a real process-kill test on a phone.

Local result after payload/schema optimization (exact values and per-case timings in [evidence](evidence/local-retrieval.json); CI regenerates full reports):

| Measure | Legacy | Local |
| --- | ---: | ---: |
| Gold passages in initial model-facing fixture result | 20/22 | 22/22 |
| cl100k result tokens, all 12 questions | 8,127 | about 4,664 |
| Extra document schemas, once per question | 0 | 3,216 |
| Result + added schemas | 8,127 | about 7,880 |
| Long-page result + added schemas | 7,282 | about 1,259 |
| Fetch in each Compare pair / model / browser | 1 / 0 / 0 | 1 / 0 / 0 |

This is **about 3% less serialized input on this corpus**, with most short pages more expensive, and **about 83% less on its one long page**. Per-run UUIDs cause small token-count variation. It is not an estimate of billed savings: existing schemas, system/history, outputs, additional model rounds and proprietary provider search are excluded. There are no generated final answers in this benchmark. Exact gold containment is not semantic answer correctness or completeness. The earlier unoptimized measurement was 10,750 tokens including added schemas, above Legacy; collapsing short sources to one provenance record and shortening the three fixed schema descriptions reduced that overhead without deleting source text.

| Ranking ablation | Recall@3 | Recall@5 | Recall@10 |
| --- | ---: | ---: | ---: |
| Default | 1.000 | 1.000 | 1.000 |
| Feedback on | 1.000 | 1.000 | 1.000 |
| Structure off | 0.909 | 0.909 | 0.909 |
| Heat off | 1.000 | 1.000 | 1.000 |
| Morphology off | 1.000 | 1.000 | 1.000 |
| Neighbor context off | 0.864 | 0.909 | 0.909 |

These easy fixtures do not establish an independent benefit for heat, morphology or feedback. Typo/synonym alternatives can compensate for disabled stemming. Feedback remains off. The strongest observed requirement is retention of neighbor conditions, and a larger independent evaluation set is needed before enabling Local by default.

Host JVM single-run medians were roughly 5 ms Legacy and 7 ms Local in interceptor fixtures; the long case was roughly 164 ms versus 457 ms, with about 379 ms index construction. These are unpaired warm-up-sensitive host observations, **not phone/network latency estimates**. Maximum accounted retained payload was about 3.09 MB on the long fixture. Host heap snapshots (including Robolectric/other allocations and GC) are recorded separately and do not measure Android incremental RAM or peak memory. The new source/index store creates zero disk files; existing chat traces still retain selected excerpts. Actual APK delta is recorded in the sole Obsidian Roadmap after fetching final CI artifacts.

Reproduction:

```sh
./gradlew :app:testDebugUnitTest --tests '*Document*Test' --tests '*WebToolsTest'
npm install --prefix /tmp/ilmar-tokenizer --ignore-scripts --no-audit --no-fund js-tiktoken@1.0.21 base64-js@1.5.1
NODE_PATH=/tmp/ilmar-tokenizer/node_modules node scripts/measure_document_retrieval.cjs
```

CI runs the complete unit suite/lint/APKs and the tokenizer script; its `android-check-reports` artifact includes fetch-baseline, retrieval-benchmark and retrieval-tokens. Raw fixture results, source hashes, rank variants, host times, heap snapshots, retained-payload estimates and one-Fetch Compare counters are inspectable. Only synthetic fixtures are written to these reports. The APK has no added runtime dependencies/models/dictionaries; Node tokenizer packages are analysis/CI tools only.

## Remaining device and end-answer acceptance protocol

Use the existing parallel-install `ilmar-phone-acceptance-apk` if testing would conflict with the installed app's signer. Do not uninstall production Ilmar or change its keys to work around signature incompatibility. Device acceptance has not been performed here.

| Test | Procedure / required evidence |
| --- | --- |
| Quality and full costs | Same model, main prompt, source URL and context policy in fresh Legacy/Local tasks; ask corpus questions, inspect exact conditions/versions/citations, score final answers; record actual input/output/cache/cost and every model/Fetch/browser call from existing usage/diagnostics. Keep provider search and Compact settings identical. |
| Source navigation | Ask for outline, a section, then a wider fragment; verify URL/bounds and no second Fetch, explicit repeat versus duplicate-free default. Use pinned GitHub raw corpus URLs over public HTTPS. |
| Permission | Revoke Fetch after a source is loaded and before another read; verify local access fails and data is cleared. Test revocation during a delayed real response separately. Browser deny/limited/native action confirmations must still hold. |
| Real process death | Force-stop only the acceptance package, relaunch/recover and try an old document ID. It must be unavailable; existing chat/user data must remain. No document files should survive. |
| Limits / resources | Measure Android PSS/heap/disk before fetch, after index, after close and TTL; test large/dense HTML and quotas. Heap allocation may remain reserved after objects are released, so retained references and PSS must be interpreted separately. |
| Compatibility / UI | Check FTS5 runtime result on real devices (fallback is supported), Compose/IME/TalkBack/small screens, APK update/data preservation and signing separately from green CI. |

Known limits: lexical matching is not arbitrary semantic paraphrase understanding; stemmers/typos/synonym groups are small; quoted phrases are literal normalized unit matches; huge indivisible sentences/code/table rows can exceed output budget; current response decoding follows declared HTTP charset or UTF-8, and meta-only legacy encodings are not validated. Search selects at most ten candidate windows per document/query; narrower queries, outline and read may be needed. Actual final-answer quality, cumulative tokens/calls, repeated-search/expansion frequency, device latency/RAM, real process death and install/update acceptance remain open. No blanket efficiency or quality improvement is claimed.

## P0 task cost / loop repair (2026-10-22)

The phone comparison supplied by the user shows 55 versus 20 model calls and $0.1113031457 versus $0.03758574897. These numbers cover complete tasks, unlike the earlier single-result tokenizer fixture. They invalidate any assumption that smaller initial excerpts establish task savings. The supplied JSONL now confirms 24 error groups / 39 failed document_read links, with 38 distinct signatures; arguments and messages were removed by the old export. The actual logged Local cost is $0.11113031457. See the [audit and evidence boundary](evidence/local-retrieval-p0-audit.md).

The existing metadata-only diagnostic export removes messages/arguments/results. It can preserve an error event count while withholding the information required to classify those errors. The new `tool_failures` metadata records only a tool name, a fixed error code, canonical argument SHA-256 and retryability. It does not export argument values or selected source text. Old exports cannot retroactively recover removed values. `python3 scripts/analyze_retrieval_log.py <export.jsonl>` counts observed groups and provider usage without inventing missing details or attributing every subsequent model request causally to an error.

Identical failures now have a separate run-owned budget independent of changing output text, intervening calls and the configurable successful-operation repeat guard. A permanent failure executes once; a transient IO/timeout failure allows one identical retry. A further identical request returns TOOL_RETRY_EXHAUSTED and permits one final schema-free model response explaining the limitation; if that response still calls tools, a deterministic limitation message completes the run. A corrected argument or different section is a different operation. Four INVALID_ARGUMENTS corrections for one tool exhaust a separate correction budget, because the phone log shows predominantly different failing signatures; successful calls do not consume this failure budget. Permission/run failures remain terminal. Only known missing-document/section lookup exceptions are converted to DOCUMENT_UNAVAILABLE. Global round/call/time settings are unchanged. Fetch keeps existing HTTPS/SSRF/auth checks and marks IO/429/502/503/504 failures transient; there is no automatic network retry or extra model.

Long Fetch sources are queried per numbered question (up to eight, 1000 chars each), without an LLM decomposition request. Candidate windows and bounded introductions of matching sections are distributed across questions before filling remaining space. Weak lexical coverage can trigger one bounded anchor search per question. `query_coverage` is an overlap heuristic, never proof of answer correctness. Character/byte/fragment limits still apply; arbitrary cross-language paraphrases and indivisible blocks remain limitations. In-response overlapping windows are suppressed before committing the sent ledger. Already-delivered references distinguish available prior evidence and explain how `repeat=true` restores exact text after projection. Compact, run-scoped document IDs preserve isolation.

The working tool-history projection retains exact quote text, block/fragment/document IDs, section paths and source provenance while moving repeated URL/title/version fields to a result-level source map. It never rewrites negations, numbers or conditions. If the existing context budget removes a full group, the summary points to exact document windows and saved traces. Original traces, the complete temporary source, explicit repeat and expansion remain available; the global Context Compact implementation is unchanged.

`TASK_SUMMARY` covers all model/tool rounds in an agent run and is correlated by task_id/run_id; resumed runs have separate summaries. It reports successful/failed/not-sent model requests, Compact calls, known provider input/output/cache/cost with incompleteness flags, local input/history estimates, Fetch/search/read/outline attempts, internal index searches, unique/repeated tool fragments, unique/repeated fragments in model prompts, error groups, executed tool retries, stop reason and duration. Replayed confirmed results are not counted as executions. Provider sums exclude not-sent local zeros. The cost is only the known provider-reported part; local estimates are not billed usage. Summary records contain only numbers, fixed codes, IDs and hashes.

Regression coverage includes a pinned [SQLite FTS5 HTML](../app/src/test/resources/documents/sqlite-fts5.html), SHA-256 `de3092630358518a6869bf047055b3938e18b476e3fbc54f4e2146d1e593497f`, retrieved 2026-10-22. Scripted model callbacks exercise Fetch → Search → multiple Reads → Answer, exact evidence, permanent/transient failures, canonical arguments, permission termination, missing IDs, new sections, duplicate suppression, private exports and cleanup. Existing infrastructure tests cover TTL, limits, other sessions and revocation during HTTP. The script does not grade generated answers or predict paid rounds. `retrieval-task-benchmark.json` records scripted counts and evidence containment for Legacy/Local on the same pinned source/questions; the CI artifact retains this report. Real API spending and final answer quality still require paired phone experiments.
