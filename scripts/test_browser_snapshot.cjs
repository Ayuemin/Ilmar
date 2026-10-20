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
function editableAction(command, args, tag = 'INPUT') {
  const events = []; let submitted = 0;
  const el = {isConnected: true, tagName: tag, type: 'text', innerText: '', value: '', options: [{value: 'chosen'}],
    matches: selector => selector === 'input,textarea,[contenteditable="true"]', getAttribute: () => '',
    closest: () => null, scrollIntoView: () => {}, focus: () => {},
    dispatchEvent: event => {events.push(event)}, form: {querySelectorAll: () => [], requestSubmit: () => {submitted++}}};
  class Input { set value(value) { this.value = value; } }
  class Event { constructor(type, options) {this.type = type; Object.assign(this, options)} }
  const state = {id: 'page', elements: new Map([[1, el]])};
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
