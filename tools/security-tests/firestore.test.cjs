// Synthetic local-emulator tests only. Never initialize production credentials.
const {before, after, beforeEach, test} = require('node:test');
const fs = require('node:fs');
const {initializeTestEnvironment, assertFails, assertSucceeds} = require('@firebase/rules-unit-testing');
const {doc, setDoc, updateDoc, getDoc, getDocs, collection, writeBatch, serverTimestamp, Timestamp} = require('firebase/firestore');
let env;
const key = 'a'.repeat(64);
const trial = (uid, days = 7) => {
  const expiry = Timestamp.fromMillis(Date.now() + days * 86400000 - 10000);
  return {userId: uid, plan: 'TRIAL', status: 'TRIAL', licenseId: null,
    trialStartedAt: serverTimestamp(), trialExpiresAt: expiry, expiresAt: expiry,
    issuedAt: serverTimestamp(), updatedAt: serverTimestamp()};
};
const lifetime = uid => ({userId: uid, plan: 'LIFETIME', status: 'ACTIVE',
  licenseId: key, expiresAt: null, activatedAt: serverTimestamp(), updatedAt: serverTimestamp()});
before(async () => {
  if (process.env.FIRESTORE_EMULATOR_HOST !== '127.0.0.1:18080') throw Error('Local emulator required');
  env = await initializeTestEnvironment({projectId: 'demo-max-security', firestore: {
    host: '127.0.0.1', port: 18080, rules: fs.readFileSync('../../firestore.rules', 'utf8')}});
});
after(async () => { if (env) await env.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async ctx => {
    await setDoc(doc(ctx.firestore(), 'licenseKeys', key), {
      type: 'LIFETIME', status: 'UNUSED', claimedBy: null, claimedAt: null, createdAt: Timestamp.now()});
  });
});
test('owner can create a bounded trial', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(doc(db, 'licenses/alice'), trial('alice')));
});
test('anonymous and cross-user license reads and writes denied', async () => {
  for (const db of [env.unauthenticatedContext().firestore(), env.authenticatedContext('bob').firestore()]) {
    await assertFails(setDoc(doc(db, 'licenses/alice'), trial('alice')));
    await assertFails(getDoc(doc(db, 'licenses/alice')));
  }
});
test('future, missing, malformed and forged trial timestamps denied', async () => {
  const db = env.authenticatedContext('alice').firestore();
  for (const data of [trial('alice', 365), {...trial('alice'), trialStartedAt: Timestamp.fromMillis(0)},
      {...trial('alice'), trialExpiresAt: 'forever'}, {...trial('alice'), expiresAt: null},
      {userId: 'alice', plan: 'TRIAL', status: 'TRIAL', updatedAt: serverTimestamp()}]) {
    await assertFails(setDoc(doc(db, 'licenses/alice'), data));
  }
});
test('UNUSED key cannot grant lifetime without atomic claim', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertFails(setDoc(doc(db, 'licenses/alice'), lifetime('alice')));
});
test('atomic claim and activation succeeds, cross-user reuse fails', async () => {
  const db = env.authenticatedContext('alice').firestore();
  const batch = writeBatch(db);
  batch.update(doc(db, 'licenseKeys', key), {status: 'CLAIMED', claimedBy: 'alice', claimedAt: serverTimestamp()});
  batch.set(doc(db, 'licenses/alice'), lifetime('alice'), {merge: true});
  await assertSucceeds(batch.commit());
  await assertSucceeds(getDoc(doc(db, 'licenses/alice')));
  const bob = env.authenticatedContext('bob').firestore();
  await assertFails(setDoc(doc(bob, 'licenses/bob'), lifetime('bob')));
});
test('metadata update cannot alter expiry or key reference', async () => {
  const db = env.authenticatedContext('alice').firestore();
  const ref = doc(db, 'licenses/alice');
  await setDoc(ref, trial('alice'));
  await assertSucceeds(updateDoc(ref, {updatedAt: serverTimestamp()}));
  await assertFails(updateDoc(ref, {expiresAt: null, updatedAt: serverTimestamp()}));
  await assertFails(updateDoc(ref, {licenseId: key, updatedAt: serverTimestamp()}));
});
test('claim cannot mutate admin key fields', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertFails(updateDoc(doc(db, 'licenseKeys', key), {status: 'CLAIMED', claimedBy: 'alice',
    claimedAt: serverTimestamp(), createdAt: Timestamp.fromMillis(0)}));
});
test('license collection listing and key creation denied', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertFails(getDocs(collection(db, 'licenseKeys')));
  await assertFails(setDoc(doc(db, 'licenseKeys', 'b'.repeat(64)), {type: 'LIFETIME', status: 'UNUSED'}));
});

const memory = (id, extra = {}) => ({memoryId: id, revision: 1, deleted: false,
  title: 'title', content: 'content', category: 'general', tags: '', enabled: true, pinned: false,
  createdAt: 1, updatedAt: 1, contentHash: 'hash', ...extra});
test('memories are owner scoped and malformed fields are denied', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  await assertSucceeds(setDoc(doc(alice, 'users/alice/memories/m1'), memory('m1')));
  await assertSucceeds(getDoc(doc(alice, 'users/alice/memories/m1')));
  await assertFails(getDoc(doc(bob, 'users/alice/memories/m1')));
  await assertFails(setDoc(doc(bob, 'users/alice/memories/m1'), memory('m1')));
  await assertFails(setDoc(doc(alice, 'users/alice/memories/m2'), memory('m2', {content: 4})));
  await assertFails(setDoc(doc(alice, 'users/alice/memories/m3'), memory('m3', {memoryId: 'other'})));
  await assertFails(setDoc(doc(alice, 'users/alice/memories/m4'), memory('m4', {unexpected: true})));
  await assertSucceeds(setDoc(doc(alice, 'users/alice/memories/m1'), {memoryId: 'm1', revision: 2, deleted: true}));
  await assertFails(setDoc(doc(alice, 'users/alice/memories/m1'), memory('m1', {revision: '2'})));
});
test('revoked license cannot be reactivated using its previously claimed key', async () => {
  await env.withSecurityRulesDisabled(async ctx => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'licenseKeys', key), {type: 'LIFETIME', status: 'CLAIMED', claimedBy: 'alice', claimedAt: Timestamp.now()});
    await setDoc(doc(db, 'licenses/alice'), {...lifetime('alice'), status: 'REVOKED'});
  });
  const db = env.authenticatedContext('alice').firestore();
  await assertFails(setDoc(doc(db, 'licenses/alice'), lifetime('alice'), {merge: true}));
});

test('analytics: non-admin cannot read activityEvents or admin aggregates, admin can', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const admin = env.authenticatedContext('admin_user', {admin: true}).firestore();
  const unauth = env.unauthenticatedContext().firestore();

  const event = {
    eventId: 'e1',
    userId: 'alice',
    eventType: 'tool_execution_success',
    feature: 'Tool:OpenApp',
    status: 'SUCCESS',
    sessionId: 's1',
    appVersion: '1.0',
    platform: 'Android',
    serverTimestamp: serverTimestamp()
  };
  await assertSucceeds(setDoc(doc(alice, 'activityEvents/e1'), event));

  // Non-admin (alice, unauth) cannot read activityEvents
  await assertFails(getDoc(doc(alice, 'activityEvents/e1')));
  await assertFails(getDoc(doc(unauth, 'activityEvents/e1')));
  await assertFails(getDocs(collection(alice, 'activityEvents')));

  // Admin CAN read activityEvents
  await assertSucceeds(getDoc(doc(admin, 'activityEvents/e1')));

  // Admin aggregates: non-admin cannot read or write
  await assertFails(getDoc(doc(alice, 'adminAggregates/overview')));
  await assertFails(setDoc(doc(alice, 'adminAggregates/overview'), {totalUsers: 100}));
  await assertSucceeds(setDoc(doc(admin, 'adminAggregates/overview'), {totalUsers: 100}));
  await assertSucceeds(getDoc(doc(admin, 'adminAggregates/overview')));

  // Cross-user spoofing on activityEvents fails
  const forgedEvent = {
    eventId: 'e2',
    userId: 'bob',
    eventType: 'tool_execution_success',
    feature: 'Tool:OpenApp',
    status: 'SUCCESS',
    sessionId: 's1',
    appVersion: '1.0',
    platform: 'Android',
    serverTimestamp: serverTimestamp()
  };
  await assertFails(setDoc(doc(alice, 'activityEvents/e2'), forgedEvent));
});

test('analytics: userSessions access control, heartbeat updates and owner isolation', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  const admin = env.authenticatedContext('admin_user', {admin: true}).firestore();
  const unauth = env.unauthenticatedContext().firestore();

  const sessionData = {
    sessionId: 'sess_1',
    userId: 'alice',
    startedAt: serverTimestamp(),
    lastHeartbeat: serverTimestamp(),
    lastHeartbeatAt: serverTimestamp(),
    appVersion: '1.0.0',
    isActive: true
  };

  // Alice creates her session
  await assertSucceeds(setDoc(doc(alice, 'userSessions/sess_1'), sessionData));

  // Alice can read her own session
  await assertSucceeds(getDoc(doc(alice, 'userSessions/sess_1')));

  // Bob cannot read Alice's session
  await assertFails(getDoc(doc(bob, 'userSessions/sess_1')));

  // Unauthenticated user cannot read Alice's session
  await assertFails(getDoc(doc(unauth, 'userSessions/sess_1')));

  // Admin can read Alice's session
  await assertSucceeds(getDoc(doc(admin, 'userSessions/sess_1')));

  // Alice can update permitted keys (lastHeartbeat, lastHeartbeatAt, isActive)
  await assertSucceeds(setDoc(doc(alice, 'userSessions/sess_1'), {
    lastHeartbeat: serverTimestamp(),
    lastHeartbeatAt: serverTimestamp(),
    isActive: true
  }, {merge: true}));

  // Bob cannot update Alice's session
  await assertFails(setDoc(doc(bob, 'userSessions/sess_1'), {
    lastHeartbeat: serverTimestamp(),
    isActive: true
  }, {merge: true}));

  // Alice cannot tamper with immutable userId
  await assertFails(setDoc(doc(alice, 'userSessions/sess_1'), {
    userId: 'attacker'
  }, {merge: true}));
});

test('analytics: userAnalytics privacy and admin visibility', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  const admin = env.authenticatedContext('admin_user', {admin: true}).firestore();
  const unauth = env.unauthenticatedContext().firestore();

  const profile = {
    userId: 'alice',
    totalAiRequests: 5,
    lastActiveAt: serverTimestamp()
  };

  await assertFails(setDoc(doc(alice, 'userAnalytics/alice'), profile));
  await assertSucceeds(setDoc(doc(admin, 'userAnalytics/alice'), profile));
  await assertFails(setDoc(doc(alice, 'dailyAnalytics/2026-01-01'), {totalEvents: 1}));
  await assertSucceeds(getDoc(doc(alice, 'userAnalytics/alice')));

  // Bob cannot read Alice's userAnalytics
  await assertFails(getDoc(doc(bob, 'userAnalytics/alice')));

  // Bob cannot write to Alice's userAnalytics
  await assertFails(setDoc(doc(bob, 'userAnalytics/alice'), {totalAiRequests: 999}, {merge: true}));

  // Unauthenticated user cannot read
  await assertFails(getDoc(doc(unauth, 'userAnalytics/alice')));

  // Admin can read Alice's userAnalytics
  await assertSucceeds(getDoc(doc(admin, 'userAnalytics/alice')));
});

