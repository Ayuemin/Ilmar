// Exercise the exact JavaScript shipped to WebView, without network or a paid API.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/java/com/ayuemin/ilmar/browser/BrowserSnapshot.kt', 'utf8');
const script = source.match(/const val SCRIPT = """([\s\S]*?)"""/)[1];
function snapshot(scrollY) {
  const visible = {top: 20, bottom: 80, left: 0, right: 200};
  const hidden = {top: -500, bottom: -400, left: 0, right: 200};
  const nodes = [
    {textContent: 'Old opening text', rect: hidden},
    {textContent: 'Later useful fact', rect: visible},
    {textContent: 'secret script', rect: visible, script: true}
  ].map(n => ({...n, parentElement: {closest: () => n.script}}));
  const links = [hidden, visible].map((rect, i) => ({
    innerText: `Link ${i}`, href: `https://example.com/${i}`,
    getClientRects: () => [rect], getBoundingClientRect: () => rect
  }));
  let index = 0;
  const document = {
    title: 'Fixture', body: {innerText: 'Opening text ' + 'x'.repeat(13000)},
    documentElement: {scrollHeight: 2000},
    querySelectorAll: () => links,
    createTreeWalker: () => ({nextNode: () => nodes[index++]}),
    createRange: () => {
      let node;
      return {selectNodeContents: n => {node = n}, getClientRects: () => [node.rect]};
    }
  };
  return JSON.parse(vm.runInNewContext(script, {
    document, location: {href: 'https://example.com/'}, scrollY,
    innerHeight: 800, innerWidth: 400, NodeFilter: {SHOW_TEXT: 4}
  }, {timeout: 1000}));
}
const first = snapshot(0);
assert.equal(first.text.length, 12000);
assert.equal(first.links.length, 2);
assert.equal(first.has_more, true);
const later = snapshot(900);
assert.equal(later.text.trim(), 'Later useful fact');
assert.equal(later.links.length, 1);
assert.equal(later.links[0].url, 'https://example.com/1');
assert.equal(later.scroll_y, 900);
assert.equal(snapshot(1500).has_more, false);
console.log('Browser snapshot JavaScript: bounded text, viewport scrolling, links and end-of-page passed');
