const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const admin = require('firebase-admin');
const { processIngestEvents, processSessionHeartbeat, processSessionEnd } = require('../lib/analytics');
const { generateCsvExport } = require('../lib/admin');

let app;
let db;
before(() => {
  assert.equal(process.env.FIRESTORE_EMULATOR_HOST, '127.0.0.1:18080');
  app = admin.initializeApp({ projectId: 'demo-max-security' }, 'analytics-tests');
  db = app.firestore();
});
after(async () => { if (app) await app.delete(); });

test('retrying the same Android event counts it once and preserves CSV text', async () => {
  const event = {
    eventId: 'retry-fixture', eventType: 'chat_request_complete', status: 'SUCCESS',
    feature: 'Chat, "mobile"', sessionId: 'session-fixture', appVersion: '1'
  };
  const uid = 'analytics-test-user';
  const ingest = () => processIngestEvents(uid, { ownerUid: uid, events: [event, event] }, db);
  const results = await Promise.all([ingest(), ingest()]);
  assert.equal(results.reduce((sum, result) => sum + result.processed, 0), 1);
  const summary = await db.collection('userAnalytics').doc(uid).get();
  assert.equal(summary.get('totalAiRequests'), 1);
  assert.equal(summary.get('totalEvents'), 1);
  const csv = await generateCsvExport(db, 'activity');
  assert.ok(csv.includes('"Chat, ""mobile"""'));
});

test('late heartbeats and repeated session ends preserve the original end time', async () => {
  const uid = 'analytics-test-user';
  const data = { sessionId: 'end-fixture' };
  const ref = db.collection('userSessions').doc(data.sessionId);
  await processSessionHeartbeat(uid, data, db);
  assert.ok((await ref.get()).get('startedAt'));
  await processSessionEnd(uid, data, db);
  const first = (await ref.get()).data();
  await processSessionHeartbeat(uid, data, db);
  await processSessionEnd(uid, data, db);
  const last = (await ref.get()).data();
  assert.equal(last.isActive, false);
  assert.ok(first.endedAt.isEqual(last.endedAt));
});

test('admin role updates require a boolean and preserve unrelated claims', async t => {
  const { setAdminRole } = require('../lib/index');
  const auth = admin.auth();
  t.mock.method(auth, 'getUser', async () => ({ customClaims: { role: 'tester', admin: true } }));
  const write = t.mock.method(auth, 'setCustomUserClaims', async () => {});
  const update = isAdmin => setAdminRole.run({
    auth: { uid: 'fixture-admin', token: { admin: true } }, data: { targetUid: 'fixture', isAdmin }
  });
  await assert.rejects(update('false'), { code: 'invalid-argument' });
  assert.equal(write.mock.callCount(), 0);
  await update(false);
  assert.deepEqual(write.mock.calls[0].arguments, ['fixture', { role: 'tester', admin: false }]);
});
