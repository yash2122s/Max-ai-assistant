// Synthetic local-emulator tests only. Never initialize production credentials.
const {before, after, beforeEach, test} = require('node:test');
const fs = require('node:fs');
const {initializeTestEnvironment, assertFails, assertSucceeds} = require('@firebase/rules-unit-testing');
const {doc, setDoc, updateDoc, getDoc, getDocs, collection, writeBatch, serverTimestamp, Timestamp} = require('firebase/firestore');
let env;
const key = 'a'.repeat(64);
const trial = (uid, days = 3) => {
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
