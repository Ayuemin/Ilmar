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
    tagName: 'A', type: '', getAttribute: () => null, matches: () => false,
    getClientRects: () => [rect], getBoundingClientRect: () => rect
  }));
  let index = 0;
  const document = {
    title: 'Fixture', body: {innerText: 'Opening text ' + 'x'.repeat(13000)},
    documentElement: {scrollHeight: 2000},
    querySelectorAll: selector => selector.startsWith('input[') || selector.startsWith('iframe[') ? [] : links,
    createTreeWalker: () => ({nextNode: () => nodes[index++]}),
    createRange: () => {
      let node;
      return {selectNodeContents: n => {node = n}, getClientRects: () => [node.rect]};
    }
  };
  return JSON.parse(vm.runInNewContext(script, {
    window: {}, document, location: {href: 'https://example.com/'}, scrollY,
    innerHeight: 800, innerWidth: 400, NodeFilter: {SHOW_TEXT: 4}
  }, {timeout: 1000}));
}
const first = snapshot(0);
assert.equal(first.text.length, 4000);
assert.equal(first.links.length, 2);
assert.equal(first.has_more, true);
const later = snapshot(900);
assert.equal(later.text.trim(), 'Later useful fact');
assert.equal(later.links.length, 1);
assert.equal(later.links[0].url, 'https://example.com/1');
assert.equal(later.scroll_y, 900);
assert.equal(snapshot(1500).has_more, false);
console.log('Browser snapshot JavaScript: bounded text, viewport scrolling, links and end-of-page passed');
// Exercise exact shipped action guards: stale refs and credentials never execute.
const actions = fs.readFileSync('app/src/main/java/com/ayuemin/ilmar/browser/BrowserActions.kt', 'utf8').match(/const val BODY = """([\s\S]*?)"""/)[1];
function actionFixture({sensitive = false, label = 'Search', stale = false, command = 'click', credentialForm = false} = {}) {
  let clicks = 0;
  const element = {isConnected: true, innerText: label, value: sensitive ? 'private-password' : '', type: sensitive ? 'password' : 'button', tagName: 'BUTTON',
    getAttribute: () => '', matches: () => sensitive, scrollIntoView: () => {}, click: () => {clicks++}};
  if (credentialForm) element.form = {querySelectorAll: () => [{matches: () => true}]};
  const state = {id: 'page', elements: new Map([[1, element]])};
  const result = JSON.parse(vm.runInNewContext('(function(){const command=' + JSON.stringify(command) + ';const args=' + JSON.stringify({page_id: stale ? 'old' : 'page', ref: 1, text: 'ordinary text'}) + ';' + actions + '})()', {window: {__ilmarBrowser: state}, innerWidth: 400, innerHeight: 800}, {timeout: 1000}));
  return {result, clicks};
}
assert.equal(actionFixture().clicks, 1);
assert.equal(actionFixture({stale: true}).result.error, 'STALE_PAGE');
assert.equal(actionFixture({stale: true}).clicks, 0);
for (const opts of [{sensitive: true, command: 'type'}, {credentialForm: true}, {label: 'Delete account'}]) {
  const value = actionFixture(opts);
  assert.equal(value.clicks, 0); assert.equal(value.result._awaiting_user, true);
  assert.equal(JSON.stringify(value.result).includes('private-password'), false);
}
assert.equal(actionFixture({label: 'Display results'}).clicks, 1);
console.log('Browser actions JavaScript: click, stale-page guard, credential and high-impact handoff passed');
// Normal inputs/selects and coordinate clicks use the same shipped action implementation.
function editableAction(command, args, tag = 'INPUT', viewportWidth) {
  const events = []; let submitted = 0;
  const el = {isConnected: true, tagName: tag, type: 'text', innerText: '', value: '', options: [{value: 'chosen'}],
    matches: selector => selector === 'input,textarea,[contenteditable="true"]', getAttribute: () => '',
    closest: () => null, scrollIntoView: () => {}, focus: () => {},
    dispatchEvent: event => {events.push(event)}, form: {querySelectorAll: () => [], requestSubmit: () => {submitted++}}};
  class Input { set value(value) { this.value = value; } }
  class Event { constructor(type, options) {this.type = type; Object.assign(this, options)} }
  const state = {id: 'page', elements: new Map([[1, el]]), viewportWidth, viewportHeight:800};
  const value = vm.runInNewContext('(function(){const command=' + JSON.stringify(command) + ';const args=' + JSON.stringify({page_id: 'page', ref: 1, text: 'chosen', ...args}) + ';' + actions + '})()', {
    window: {__ilmarBrowser: state}, innerWidth: 400, innerHeight: 800,
    document: {elementFromPoint: () => el}, HTMLInputElement: Input, HTMLTextAreaElement: Input,
    Event, MouseEvent: Event
  }, {timeout: 1000});
  return {result: JSON.parse(value), events, submitted, value: el.value};
}
const typed = editableAction('type', {submit: true});
assert.equal(typed.value, 'chosen'); assert.equal(typed.submitted, 1);
assert.deepEqual(typed.events.map(e => e.type), ['input', 'change']);
assert.equal(editableAction('select', {}, 'SELECT').value, 'chosen');
const point = editableAction('click', {x: 500, y: 250});
assert.equal(point.result.action_performed, true);
assert.deepEqual(point.events.map(e => e.type), ['mousedown', 'mouseup', 'click']);
assert.equal(point.events[2].clientX, 200); assert.equal(point.events[2].clientY, 200);
console.log('Browser input/select/submit and coordinate-click JavaScript passed');
// Same-document mutation must invalidate both page ID and old element map before an action.
{
  let records = [];
  class Observer {
    constructor(callback) { this.callback = callback; }
    observe() {}
    takeRecords() { const result = records; records = []; return result; }
  }
  const button = {innerText: 'Search', tagName: 'BUTTON', type: 'button', isConnected: true,
    getAttribute: () => '', matches: () => false, getClientRects: () => [{top: 1, bottom: 5, left: 1, right: 5}],
    getBoundingClientRect: () => ({top: 1, bottom: 5, left: 1, right: 5})};
  const context = vm.createContext({window: {}, MutationObserver: Observer,
    document: {title: 'SPA', body: {innerText: 'Results'}, documentElement: {scrollHeight: 200},
      querySelectorAll: selector => selector.startsWith('a[href],') ? [button] : []},
    location: {href: 'https://example.com/'}, scrollY: 0, innerHeight: 800, innerWidth: 400});
  const before = JSON.parse(vm.runInContext(script, context));
  records = [{type: 'childList'}];
  const result = vm.runInContext('(function(){const command="click";const args=' + JSON.stringify({page_id: before.page_id, ref: before.controls[0].ref}) + ';' + actions + '})()', context);
  assert.equal(JSON.parse(result).error, 'STALE_PAGE');
  const after = JSON.parse(vm.runInContext(script, context));
  assert.notEqual(after.page_id, before.page_id);
  assert.equal(JSON.parse(vm.runInContext(script, context)).page_id, after.page_id);
}
console.log('Same-document DOM mutation invalidates stale actions; unchanged DOM retains page ID');

assert.equal(editableAction('click', {x:500,y:250}, 'INPUT', 800).result.error, 'STALE_VIEWPORT');
console.log('Coordinate actions reject snapshots taken before a viewport resize');
// Fixed selective lookup uses current refs, excludes values, and never guesses an ambiguous target.
const findBody = fs.readFileSync('app/src/main/java/com/ayuemin/ilmar/browser/BrowserFind.kt', 'utf8').match(/const val BODY = """([\s\S]*?)"""/)[1];
function findFixture(labels, query, exact = true) {
  const elements = labels.map((label, index) => ({innerText: label, tagName:'BUTTON', type:'button', value:'private-value',
    getAttribute: () => '', getClientRects: () => index === 2 ? [] : [{}], matches: () => false}));
  const state = {id:'fresh-page',refs:new Map(),elements:new Map(),next:1};
  return JSON.parse(vm.runInNewContext('(function(){const query='+JSON.stringify(query)+';const exact='+exact+';const baseline={url:"https://example.com/",title:"Test"};'+findBody+'})()', {
    window:{__ilmarBrowser:state}, document:{querySelectorAll: () => elements}
  }, {timeout:1000}));
}
assert.equal(findFixture(['Search', 'Other', 'Hidden Search'], '  SEARCH ').matches.length, 1);
assert.equal(findFixture(['Search', 'Search'], 'Search').matches.length, 2);
assert.equal(findFixture(['Start Search'], 'Search').matches.length, 0);
assert.equal(findFixture(['Start Search'], 'Search', false).matches.length, 1);
assert.equal(findFixture(['Search'], 'Search').page_id, 'fresh-page');
assert.equal(JSON.stringify(findFixture(['Search'], 'Search')).includes('private-value'), false);
assert.equal(findFixture(['x'.repeat(2)], '";throw Error(1);//').matches.length, 0);
assert.equal(findFixture(Array(5001).fill('Search'), 'Search').matches_truncated, true);
console.log('Browser find JavaScript: unique/ambiguous labels, fresh refs, hidden nodes, bounded lookup and no input values passed');
