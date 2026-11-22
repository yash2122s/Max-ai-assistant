const { test } = require('node:test');
const assert = require('node:assert/strict');
const { summarizeLatency, csvCell } = require('../lib/reporting');

test('no measured completions gives an absent mean', () => {
  const sample = summarizeLatency([{ eventType: 'AI_REQUEST_START' },
    { eventType: 'AI_REQUEST_SUCCESS', durationMs: -1 }]);
  assert.equal(sample.avgLatencyMs, null);
  assert.ok(Object.values(sample.latencyBuckets).every(count => count === 0));
});

test('a measured zero stays zero', () => {
  assert.equal(summarizeLatency([{ eventType: 'CHAT_REQUEST_SUCCESS', durationMs: 0 }]).avgLatencyMs, 0);
});

test('CSV preserves punctuation, embedded quotes and literal spreadsheet text', () => {
  assert.equal(csvCell('Chat, "mobile"'), '"Chat, ""mobile"""');
  assert.equal(csvCell('+archived'), '"\'+archived"');
  assert.equal(csvCell(0), '0');
});
