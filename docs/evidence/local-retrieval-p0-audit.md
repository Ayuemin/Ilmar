# P0 retrieval audit — 2026-10-22

Baseline APK SHA in supplied JSONL: `1544d4c3ac97600e025a6f99813167be39ca2052` (PR #12). Read the sole current Obsidian Roadmap and architecture/security/model economy/memory/plugins/CI notes. No AGENTS.md in either checkout. Numeric evidence: [phone baseline](local-retrieval-phone-baseline.json).

## Observed phone evidence

The user supplied `Ilmar-log-1792613416157.jsonl` during this task. Its SHA-256 is recorded in the numeric evidence file; the raw journal is not committed. There are three runs: a separate 1-request warm-up, Local 55 requests, Legacy 20 requests. All 76 provider requests succeeded; neither experiment invoked Compact or browser/provider search.

| Observed metric | Local | Legacy |
| --- | ---: | ---: |
| Model requests | 55 | 20 |
| Provider input | 474499 | 156483 |
| Provider output | 9760 | 4424 |
| Provider cached input | 13568 | 2048 |
| Provider USD | 0.11113031457 | 0.03758574897 |
| Completed tool groups | 54 | 19 |
| TOOL_ROUND_ERROR groups | 24 | 0 |
| Fetch executions | 1 | 1 |
| document_search / outline / read executions | 14 / 4 / 80 | 0 / 0 / 0 |
| Other executions | 1 search_history | 28 read_file |

The Local cost in the actual JSONL differs from the supplied $0.1113031457. Use $0.11113031457 for this journal (about 2.957 times Legacy). Do not combine the warm-up with either experiment.

Joining TASK_EXECUTION_LINK → TASK_STEP by step_id and TOOL_ROUND_ERROR by trace prefix identifies **39 failed document_read execution links in the 24 error groups**, 38 distinct operation signatures. One failing signature occurs twice. The remaining 41 document_read executions have successful final step status. Two error groups also contain a successful search. Successful repeated signatures are not classified as bugs: reads can legitimately expand or restore context.

Exactly 24 next model requests follow error groups: 204821 input / 3369 output / $0.04734907947. This is **temporal adjacency, not measured avoidable cost**: some groups also supply successful evidence, and final answers still require model calls. No exact counterfactual extra-token total can be recovered from this log.

Local input-breakdown estimates over all 55 requests: tool history **420093**, schemas **115225**, system **121585**, chat **17600**. These are local ContextEstimate sums, not provider token counts. Tool history is about 62% of the summed estimate. Thus repeated working history and orchestration materially matter beyond the search index itself.

## What the errors establish, and what was removed

Diagnostics.Redaction.metadata deletes messages/arguments/results. All 24 events contain only task/run/trace IDs. TASK_STEP gives tool/status/signature identity, but not arguments or error text. Exact argument values and the specific failed validation **cannot be reconstructed**.

For the pinned implementation, document_read returns no error itself. AgentLoopRunner converts JSON/IllegalArgumentException to INVALID_ARGUMENTS, or a tool timeout to TOOL_TIMEOUT. The run lasted about 113.9 seconds with a 120-second per-tool timeout, so these failed reads fit the validation branch; a full per-tool timeout cannot fit in that run. Missing section/store IllegalStateExceptions and revoked permissions would abort the run; this run completed successfully. Registration is present once in every Local schema set, there is one successful Fetch, and document navigation succeeds repeatedly. These observations rule out repeated Fetch/LLM transport retries, missing registration, Compact and browser escalation as explanations for the 55 calls. They do not distinguish bad selector names, malformed IDs/ranges, disabled expansion or serialized-output validation within INVALID_ARGUMENTS.

There are 38 distinct failing signatures: protecting only identical arguments would miss almost all failures. The original guard also hashes outputs, requires two repeated series and resets progress; interleaved/varied bad calls evade it. The 55 requests are 54 model-selected tool rounds plus one final response, not 55 fetches or transport retries.

## Reproduced code defects and repairs

* document_read advertises section/fragment reads and emits fragment_id, but accepted only a required section_id field. Passing the returned field name fragment_id caused INVALID_ARGUMENTS. Added an explicit alternative selector, exactly-one validation and a regression using the actual returned ID. This contract defect is reproduced; the old journal cannot prove which of its failed calls used that field.
* Identical permanent failures execute once; transient IO/timeouts allow one retry. A separate four-invalid-corrections budget per tool handles changing bad arguments. Successful reads are not blocked by identical-failure tracking. Exhaustion permits one schema-free final response with a limitation, rather than dozens of paid correction rounds. Global max rounds/calls are unchanged.
* Known missing-document/section exceptions now become recoverable results; unrelated internal/permission exceptions remain terminal. Tests cover old IDs, corrected selectors, different sections, permission termination and cleanup. These are hardening cases, not asserted causes of the phone errors.
* Fetch classifies temporary IO/429/502/503/504 failures. No extra network/model retry is added.
* Initial multi-question retrieval uses deterministic per-question searches, bounded section introductions and fair budgets; weak coverage gets one bounded local anchor search. Exact original text is retained. No extra model or embeddings are used.
* Working history removes repeated provenance, retains exact quotations, and supplies explicit window/trace references when the existing budget removes a group. Global Compact is unchanged.
* TASK_SUMMARY and safe fixed error metadata preserve provider versus estimated usage, attempts, fragment retransmission and stop reasons without argument/document content.

## Remaining evidence

The exact failed arguments/validation messages require the original saved tool traces or a fresh run with the new safe error metadata. CI/scripted results cannot establish paid savings or final answer correctness. Repeat the phone experiment using identical model/settings/source/questions, record TASK_SUMMARY and inspect final conditions/citations. Root-cause acceptance for every historical argument variant remains open rather than invented from redacted data.
