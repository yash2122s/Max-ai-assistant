const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
test('activity view encodes hostile event payloads at the actual HTML sink', async () => {
  const source = fs.readFileSync('../../windows-agent/dashboard/assets/js/views/activity.js', 'utf8')
    .replace('import AppState from "../state.js";', 'const AppState = globalThis.__securityAppState;');
  const payload = {text: '<img src=x onerror="attack()"> & legitimate input'};
  const element = {innerHTML: ''};
  globalThis.__securityAppState = {subscribe(fn) {fn({developerMode: true,
    events: [{type: '<script>synthetic</script>', timestamp: 1, payload}]}); return () => {};}};
  globalThis.document = {getElementById() {return element;}};
  try {
    const module = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
    module.default.show({});
    assert.ok(!element.innerHTML.includes('<img'));
    assert.ok(!element.innerHTML.includes('<SCRIPT>'));
    assert.ok(element.innerHTML.includes('&lt;img'));
    assert.ok(element.innerHTML.includes('&amp; legitimate input'));
    assert.equal(payload.text, '<img src=x onerror="attack()"> & legitimate input');
    assert.equal(module.escapeEventText('ordinary Telugu తెలుగు'), 'ordinary Telugu తెలుగు');
    module.default.destroy();
  } finally {
    delete globalThis.document;
    delete globalThis.__securityAppState;
  }
});
