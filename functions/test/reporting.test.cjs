const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const admin = require('firebase-admin');
const { getOverviewMetrics, getErrorsAndPerformance } = require('../lib/admin');

let app;
let db;
before(() => {
  assert.equal(process.env.FIRESTORE_EMULATOR_HOST, '127.0.0.1:18080');
  app = admin.initializeApp({ projectId: 'demo-max-reporting' }, 'reporting-tests');
  db = app.firestore();
});
after(async () => { if (app) await app.delete(); });

test('reports sample timed completions among the latest 100 events, including zero ms', async () => {
  const batch = db.batch();
  const events = db.collection('activityEvents');
  for (let i = 0; i <= 100; i++) {
    const doc = { serverTimestamp: new Date(1000 + i), eventType: 'SCREEN_VIEW' };
    if (i === 0) Object.assign(doc, { eventType: 'AI_REQUEST_SUCCESS', durationMs: 90000 });
    if (i === 1) Object.assign(doc, { eventType: 'AI_REQUEST_FAILED', durationMs: 0 });
    if (i === 2) Object.assign(doc, { eventType: 'TOOL_EXECUTION_SUCCESS', durationMs: 3000 });
    if (i === 3) Object.assign(doc, { eventType: 'SESSION_END', durationMs: 3600000 });
    batch.set(events.doc(String(i)), doc);
  }
  await batch.commit();
  const overview = await getOverviewMetrics(db);
  assert.equal(overview.avgLatencyMs, 1500);
  const errors = await getErrorsAndPerformance(db);
  assert.equal(errors.latencyBuckets['<200ms'], 1);
  assert.equal(errors.latencyBuckets['≥3s'], 1);
  assert.equal(Object.values(errors.latencyBuckets).reduce((a, b) => a + b, 0), 2);
});

test('reports every active session and counts users in distinct rolling windows', async () => {
  const now = Date.now();
  const sessions = db.collection('userSessions');
  const writer = db.bulkWriter();
  for (let i = 0; i < 501; i++) {
    writer.set(sessions.doc(String(i)), { isActive: true, lastHeartbeatAt: new Date(now) });
  }
  writer.set(sessions.doc('ended'), { isActive: false, lastHeartbeatAt: new Date(now) });
  writer.set(sessions.doc('stale'), { isActive: true, lastHeartbeatAt: new Date(now - 3600000) });
  for (const days of [0, 3, 15, 40]) {
    writer.set(db.collection('userAnalytics').doc(String(days)), { lastActiveAt: new Date(now - days * 86400000) });
  }
  await writer.close();
  const overview = await getOverviewMetrics(db);
  assert.equal(overview.activeSessionsCount, 501);
  assert.equal(overview.totalUsers, 4);
  assert.deepEqual(overview.activeUsers, { dau: 1, wau: 2, mau: 3 });
});
