// npm install --prefix /tmp/ilmar-tokenizer js-tiktoken@1.0.21
// NODE_PATH=/tmp/ilmar-tokenizer/node_modules node scripts/measure_document_retrieval.cjs
// Tokenizer proxy for serialized fixture results + extra document schemas; not provider usage or a bill.
const fs = require('fs');
const enc = require('js-tiktoken').getEncoding('cl100k_base');
const source = JSON.parse(fs.readFileSync('app/build/reports/retrieval-benchmark.json', 'utf8'));
const rows = source.cases.map(row => ({
  id: row.id, backend: row.backend, gold_count: row.gold_count,
  legacy_gold_found: row.legacy_gold_found, local_gold_found: row.local_gold_found,
  legacy_result_tokens: enc.encode(row.legacy_result).length,
  local_result_tokens: enc.encode(row.local_result).length,
  extra_document_schema_tokens: enc.encode(row.document_tool_schemas).length,
  legacy_result_bytes: Buffer.byteLength(row.legacy_result), local_result_bytes: Buffer.byteLength(row.local_result),
  index_ms: row.index_ms, fixture_pipeline_ms: row.fixture_pipeline_ms,
  legacy_fixture_fetch_ms: row.legacy_fixture_fetch_ms, local_fixture_fetch_ms: row.local_fixture_fetch_ms,
  retained_payload_bytes: row.compare.retained_payload_bytes,
}));
const ranking = {};
for (const variant of ['default','feedback','no_structure','no_heat','no_morphology','no_context']) {
  ranking[variant] = {};
  for (const k of [3,5,10]) {
    const matches = source.cases.map(row => row.ranking.find(x => x.variant === variant && x.k === k));
    const found = matches.reduce((s,r) => s + r.found,0), total = matches.reduce((s,r) => s + r.gold_passages,0);
    ranking[variant]['recall_at_' + k] = found / total;
  }
}
const sum = key => rows.reduce((s,r) => s + r[key],0);
const report = {tokenizer:'cl100k_base / js-tiktoken 1.0.21',
  method:'12 synthetic questions. Exact gold-passage containment; no generated final answers. One selected Fetch result plus the added document tool schemas per case. Excludes existing schemas, conversation/system prompts, protocol, output and extra model rounds. Does not establish cost savings. Native SQLite/device RAM/process-kill/real provider usage remain unmeasured.',
  ranking, totals:{gold_passages:sum('gold_count'),legacy_gold_found:sum('legacy_gold_found'),local_gold_found:sum('local_gold_found'),
    legacy_result_tokens:sum('legacy_result_tokens'),local_result_tokens:sum('local_result_tokens'),extra_document_schema_tokens:sum('extra_document_schema_tokens'),
    local_result_plus_extra_schemas_tokens:sum('local_result_tokens')+sum('extra_document_schema_tokens')}, cases:rows};
fs.writeFileSync('app/build/reports/retrieval-tokens.json',JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
