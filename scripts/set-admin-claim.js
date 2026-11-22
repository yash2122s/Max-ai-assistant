/**
 * MAX AI Assistant - Administrator Custom Claim Provisioning Script
 * 
 * Sets or removes the 'admin: true' custom claim on a Firebase Authentication user.
 * 
 * Usage:
 *   node scripts/set-admin-claim.js <USER_UID>
 *   node scripts/set-admin-claim.js <USER_UID> --remove
 * 
 * Prerequisites:
 *   Set GOOGLE_APPLICATION_CREDENTIALS to the path of your Firebase service account JSON,
 *   or ensure the Firebase Admin SDK can locate default credentials.
 */

const path = require('path');
const fs = require('fs');

let admin;
try {
  admin = require('firebase-admin');
} catch (e) {
  try {
    admin = require(path.resolve(__dirname, '../functions/node_modules/firebase-admin'));
  } catch (e2) {
    console.error('[ERROR] Could not resolve firebase-admin from root or functions/node_modules.');
    process.exit(1);
  }
}

// Initialize Firebase Admin
try {
  const serviceAccountPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  if (serviceAccountPath && fs.existsSync(serviceAccountPath)) {
    const serviceAccount = require(path.resolve(serviceAccountPath));
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
      projectId: 'max-ai-assistant-5ffda'
    });
    console.log(`[INFO] Initialized Firebase Admin using service account from: ${serviceAccountPath}`);
  } else {
    // Attempt default initialization with project ID
    admin.initializeApp({
      projectId: 'max-ai-assistant-5ffda'
    });
    console.log('[INFO] Initialized Firebase Admin using default credentials with project: max-ai-assistant-5ffda');
  }
} catch (err) {
  console.error('[ERROR] Failed to initialize Firebase Admin SDK:', err.message);
  console.error('Make sure GOOGLE_APPLICATION_CREDENTIALS environment variable points to a valid service account JSON.');
  process.exit(1);
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length === 0) {
    console.error('Usage: node scripts/set-admin-claim.js <USER_UID> [--remove]');
    process.exit(1);
  }

  const uid = args[0];
  const remove = args.includes('--remove');

  try {
    const user = await admin.auth().getUser(uid);
    console.log(`[INFO] Found user: ${user.email || uid} (${user.uid})`);

    const currentClaims = user.customClaims || {};
    const newClaims = {
      ...currentClaims,
      admin: !remove
    };

    if (remove) {
      delete newClaims.admin;
    }

    await admin.auth().setCustomUserClaims(uid, newClaims);
    console.log(`[SUCCESS] User ${uid} claims updated.`);
    console.log(`[SUCCESS] Admin privilege: ${!remove ? 'GRANTED' : 'REVOKED'}`);

    // Task 3: Independent read-back verification
    const verifiedUser = await admin.auth().getUser(uid);
    if (!remove) {
      if (verifiedUser.customClaims && verifiedUser.customClaims.admin === true) {
        console.log(`[VERIFIED] Read-back confirmed from Firebase: customClaims.admin is TRUE for UID: ${uid}`);
      } else {
        console.error(`[VERIFICATION FAILED] Read-back check failed: claims do not reflect admin: true`);
        process.exit(1);
      }
    } else {
      if (!verifiedUser.customClaims || verifiedUser.customClaims.admin !== true) {
        console.log(`[VERIFIED] Read-back confirmed from Firebase: admin claim successfully revoked for UID: ${uid}`);
      } else {
        console.error(`[VERIFICATION FAILED] Read-back check failed: admin claim still present`);
        process.exit(1);
      }
    }

    console.log(`Note: The user must refresh their Firebase ID token (or re-login) for new claims to take effect.`);
  } catch (err) {
    console.error(`[ERROR] Failed to set claims for user ${uid}:`, err.message);
    process.exit(1);
  }
}

main().catch(err => {
  console.error('[FATAL]', err);
  process.exit(1);
});
