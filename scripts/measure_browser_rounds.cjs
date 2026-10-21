// Offline serialization comparison; tokenizer counts are not provider usage or a bill.
const fs = require('fs');
const enc = require('js-tiktoken').getEncoding('cl100k_base');
const source = JSON.parse(fs.readFileSync('app/build/reports/browser-rounds-comparison.json', 'utf8'));
const report = {
  fixture: source.fixture, tokenizer: 'cl100k_base / js-tiktoken 1.0.21',
  method: 'Known five-step plan, same current tool schemas, fixed fake BrowserPage. Counts serialized tool schemas and working tool history for every simulated generation. Excludes chat/system prompt, provider protocol overhead and generated output; no paid API.',
};
for (const name of ['sequential', 'batch']) {
  const inputs = source[name + '_inputs'];
  const rows = inputs.map(input => {
    const raw = JSON.stringify(input);
    return {utf8_bytes: Buffer.byteLength(raw), tokens_cl100k: enc.encode(raw).length};
  });
  report[name] = {model_requests: inputs.length, input_utf8_bytes: rows.reduce((s,r) => s+r.utf8_bytes,0), input_tokens_cl100k: rows.reduce((s,r) => s+r.tokens_cl100k,0), rounds: rows};
}
fs.writeFileSync('app/build/reports/browser-rounds-tokens.json', JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
