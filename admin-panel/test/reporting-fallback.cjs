const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const { readFileSync } = require('node:fs');
const serverRequire = createRequire(require.resolve('../../functions/package.json'));
const emulatorRequire = createRequire(require.resolve('../../tools/security-tests/package.json'));
const admin = serverRequire('firebase-admin');
const client = require('firebase/firestore');
const projectId = 'demo-max-browser';
assert.equal(process.env.FIRESTORE_EMULATOR_HOST, '127.0.0.1:18080');
process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID = projectId;
process.env.NEXT_PUBLIC_FIREBASE_API_KEY = 'demo-api-key';
const fallback = require('../.test-build/admin-panel/src/lib/api');
const { firestore, functions } = require('../.test-build/admin-panel/src/lib/firebase');
const server = serverRequire('./lib/admin');
let app, db, env;
before(async () => {
  const { initializeTestEnvironment } = emulatorRequire('@firebase/rules-unit-testing');
  env = await initializeTestEnvironment({ projectId, firestore: {
    host: '127.0.0.1', port: 18080, rules: readFileSync(require.resolve('../../firestore.rules'), 'utf8')
  }});
  client.connectFirestoreEmulator(firestore, '127.0.0.1', 18080, {mockUserToken:{sub:'fixture-admin', admin:true}});
  require('firebase/functions').connectFunctionsEmulator(functions, '127.0.0.1', 5011);
  app = admin.initializeApp({projectId});
  db = app.firestore();
  const now = Date.now();
  const batch = db.batch();
  for (const age of [0, 3, 20, 40]) {
    batch.set(db.collection('userAnalytics').doc('user-'+age), {lastActiveAt:new Date(now-age*86400000),lastFeatureUsed:'Chat, "quoted"',appVersion:'+legacy'});
  }
  batch.set(db.collection('activityEvents').doc('event'), {eventType:'CHAT_REQUEST_FAILED',durationMs:700,serverTimestamp:new Date(now),status:'FAILURE',errorCategory:'OTHER'});
  await batch.commit();
});
after(async () => { await client.terminate(firestore); await app.delete(); await env.cleanup(); });
test('overview fallback reads distinct user windows and the measured latency', async () => {
  const data = await fallback.getOverview();
  assert.equal(data.totalUsers, 4);
  assert.deepEqual(data.activeUsers, {dau:1,wau:2,mau:3});
  assert.equal(data.avgLatencyMs, 700);
});
test('performance fallback matches the callable histogram and failure counts', async () => {
  const data = await fallback.getErrorsAndPerformance();
  const expected = await server.getErrorsAndPerformance(db);
  assert.deepEqual(data.latencyBuckets, expected.latencyBuckets);
  assert.deepEqual(data.errorCategories, expected.errorCategories);
});
test('both CSV fallback reports match callable output byte for byte', async () => {
  for (const kind of ['users','activity']) {
    assert.equal((await fallback.exportReport(kind)).csv, await server.generateCsvExport(db,kind));
  }
});
test('user fallback applies UID search and represents inaccessible license data', async () => {
  const data = await fallback.getUsers({searchQuery:'USER-20'});
  assert.equal(data.users.length,1);
  assert.equal(data.users[0].licensePlan,'UNKNOWN');
  await assert.rejects(fallback.getUsers({filterPlan:'PRO'}));
});

test('both user readers advance past a filtered page with no matches', async () => {
  for (const read of [fallback.getUsers, options => server.getUsersList(db, options)]) {
    const options = { limit: 2, searchQuery: 'user-40' };
    const first = await read(options);
    assert.deepEqual(first.users, []);
    assert.equal(first.hasMore, true);
    assert.equal(first.nextCursor, 'user-3');
    const second = await read({ ...options, startAfter: first.nextCursor });
    assert.deepEqual(second.users.map(user => user.uid), ['user-40']);
    assert.equal(second.nextCursor, null);
  }
});
