// npm install --prefix /tmp/ilmar-benchmark jsdom@26
// NODE_PATH=/tmp/ilmar-benchmark/node_modules node scripts/benchmark_iana.cjs
// No API calls. Saved public HTML + synthetic search result; geometry is a fixture, not Chromium.
const {JSDOM} = require('jsdom');
const {readFileSync, writeFileSync} = require('fs');
const extract = source => source.match(/const val SCRIPT = """([\s\S]*?)"""/)[1];
const beforeScript = extract(readFileSync('scripts/fixtures/iana/baseline-snapshot.kt', 'utf8'));
const afterScript = extract(readFileSync('app/src/main/java/com/ayuemin/ilmar/browser/BrowserSnapshot.kt', 'utf8'));
const search = '<title>IANA search fixture</title><main><h1>IANA example domains</h1><a href="https://www.iana.org/help/example-domains">Example Domains — IANA</a><p>Reserved example domains for documentation.</p></main>';
const pages = [
  {operation:'search', url:'https://www.google.com/search?q=IANA+example+domains', html:search},
  {operation:'open', url:'https://www.iana.org/help/example-domains', html:readFileSync('scripts/fixtures/iana/example-domains.html', 'utf8')},
  {operation:'read'},
  {operation:'click', url:'https://www.iana.org/domains/reserved', html:readFileSync('scripts/fixtures/iana/reserved.html', 'utf8')},
  {operation:'scroll'} // fixture viewport contains document: scroll is a no-op at its end
];
function run(script) {
  let dom;
  return pages.map((step, i) => {
    if (step.html) {
      dom?.window.close();
      dom = new JSDOM(step.html, {url:step.url, runScripts:'outside-only'});
      const w = dom.window;
      w.document.querySelectorAll('script,style,noscript').forEach(el => el.remove());
      Object.defineProperty(w.HTMLElement.prototype, 'innerText', {get(){return this.textContent.replace(/\s+/g,' ').trim()}});
      w.HTMLElement.prototype.getClientRects = () => [{top:20,bottom:40,left:0,right:300}];
      w.HTMLElement.prototype.getBoundingClientRect = () => ({top:20,bottom:40,left:0,right:300});
      w.innerHeight=8000;w.innerWidth=400;
      w.document.documentElement.scrollHeight=6000;
      w.__ilmarBrowser = {id:'fixture-page-'+i,next:1,refs:new WeakMap(),elements:new Map()};
    }
    const result = JSON.parse(dom.window.eval(script));
    return {operation:step.operation, snapshot:result};
  });
}
const before=run(beforeScript), after=run(afterScript);
const fixture=before.map((value,i)=>({operation:value.operation,before:value.snapshot,after:after[i].snapshot}));
// Verify the link used by the fixture click was discoverable by the agent.
if (!fixture[2].after.links.some(link=>link.url==='https://www.iana.org/domains/reserved')) throw Error('Reserved Domains link missing');
writeFileSync('app/src/test/resources/browser/iana-dom.json', JSON.stringify(fixture,null,2)+'\n');
console.log('IANA DOM fixtures generated from f554c6d and current extraction; 5 steps, no paid calls');
