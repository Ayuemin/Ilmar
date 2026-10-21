// npm install --prefix /tmp/ilmar-benchmark js-tiktoken@1.0.21
// NODE_PATH=/tmp/ilmar-benchmark/node_modules node scripts/measure_iana_tokens.cjs
const fs = require('fs');
const enc = require('js-tiktoken').getEncoding('cl100k_base');
const rows = JSON.parse(fs.readFileSync('app/build/reports/iana-economy.json','utf8'));
const history={before:[],after:[]}, cumulative={before:0,after:0};
const steps=rows.map(row=>{
  const result={operation:row.operation};
  for(const key of ['before','after']){
    const raw=row[key];history[key].push(raw);
    result[key]={chars:raw.length,utf8_bytes:Buffer.byteLength(raw),tokens_cl100k:enc.encode(raw).length};
    cumulative[key]+=enc.encode(history[key].join('\n')).length;
  }
  return result;
});
const total={};
for(const key of ['before','after'])total[key]=Object.fromEntries(['chars','utf8_bytes','tokens_cl100k'].map(unit=>[unit,steps.reduce((s,r)=>s+r[key][unit],0)]));
const report={baseline:'f554c6d',tokenizer:'cl100k_base / js-tiktoken 1.0.21',method:'Saved IANA HTML, synthetic search result, fixture geometry; no live WebView or model. Cumulative input counts result strings only, without schemas/prompts/model outputs.',steps,total,cumulative_result_only_input_tokens:cumulative};
fs.writeFileSync('app/build/reports/iana-tokens.json',JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
