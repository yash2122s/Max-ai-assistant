#!/usr/bin/env node

/**
 * MAX AI Assistant - Owner Commercial License Generator Tool
 *
 * Requirements:
 * - Generate cryptographically secure lifetime keys (MAX-LIFE-XXXX-XXXX-XXXX)
 * - SHA-256 hash generation matching Android LicenseKeyHelper
 * - Formats: text, json, csv
 * - Optional Firestore direct seeding using Firebase Admin SDK (with serviceAccountKey.json)
 * - Never packaged into Android APK
 */

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// Crockford Base32-like characters (high entropy, avoids 0/O, 1/I confusion)
const CHARSET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';

function generateRandomSegment(length = 4) {
    let result = '';
    const randomBytes = crypto.randomBytes(length);
    for (let i = 0; i < length; i++) {
        result += CHARSET[randomBytes[i] % CHARSET.length];
    }
    return result;
}

function generateLicenseKey(prefix = 'MAX-LIFE') {
    const s1 = generateRandomSegment(4);
    const s2 = generateRandomSegment(4);
    const s3 = generateRandomSegment(4);
    return `${prefix}-${s1}-${s2}-${s3}`;
}

function sha256Hex(str) {
    return crypto.createHash('sha256').update(str.trim().toUpperCase(), 'utf-8').digest('hex');
}

function parseArgs() {
    const args = process.argv.slice(2);
    const config = {
        count: 1,
        plan: 'lifetime',
        format: 'text', // 'text' | 'json' | 'csv' | 'raw'
        out: null,
        register: false,
        registerKey: null,
        grantUser: null,
        verifyKey: null,
        listKeys: false,
        serviceAccount: null
    };

    for (let i = 0; i < args.length; i++) {
        const arg = args[i];
        if (arg === '--count' || arg === '-c') {
            config.count = parseInt(args[++i], 10) || 1;
        } else if (arg === '--plan' || arg === '-p') {
            config.plan = (args[++i] || 'lifetime').toLowerCase();
        } else if (arg === '--format' || arg === '-f') {
            config.format = (args[++i] || 'text').toLowerCase();
        } else if (arg === '--out' || arg === '-o') {
            config.out = args[++i];
        } else if (arg === '--register' || arg === '-r') {
            config.register = true;
        } else if (arg === '--register-key') {
            config.registerKey = args[++i];
        } else if (arg === '--grant-user' || arg === '-g') {
            config.grantUser = args[++i];
        } else if (arg === '--status' || arg === '--verify' || arg === '-s') {
            config.verifyKey = args[++i];
        } else if (arg === '--list' || arg === '-l') {
            config.listKeys = true;
        } else if (arg === '--service-account') {
            config.serviceAccount = args[++i];
        } else if (arg === '--help' || arg === '-h') {
            printHelp();
            process.exit(0);
        }
    }

    if (!config.serviceAccount) {
        const defaultPath = path.join(__dirname, 'serviceAccountKey.json');
        if (fs.existsSync(defaultPath)) {
            config.serviceAccount = defaultPath;
        }
    }

    return config;
}

function printHelp() {
    console.log(`
MAX License Key Generator & Management Tool (Owner CLI)
======================================================

Usage:
  node generate.js [options]

Commands & Options:
  --register, -r               Generate and register new key(s) in Firestore in 1 step
  --register-key <KEY>         Register an existing key (e.g. MAX-LIFE-XXXX-XXXX-XXXX) in Firestore
  --grant-user <USER_ID>       Directly grant LIFETIME PREMIUM to a user without needing a key
  --status, -s <KEY>           Check status of a key (UNUSED, CLAIMED, claimedBy)
  --list, -l                   List registered keys in Firestore
  --count, -c <number>         Number of keys to generate (default: 1)
  --plan, -p <lifetime>        License plan (default: lifetime)
  --format, -f <text|json|csv> Output format (default: text)
  --out, -o <file>             Output file path (default: stdout)
  --service-account <file>     Path to Firebase Service Account JSON
  --help, -h                   Show this help message

Examples:
  # 1. Generate and immediately register 1 key in Firestore:
  node generate.js --register

  # 2. Register a key that was already created:
  node generate.js --register-key MAX-LIFE-FPT4-HDTK-LCL9

  # 3. Directly give a user Lifetime Premium without a key:
  node generate.js --grant-user g3nGmeybdTYacojqIxwEFiTno3I2

  # 4. Check if a key was claimed:
  node generate.js --status MAX-LIFE-FPT4-HDTK-LCL9
`);
}

function getAdmin(serviceAccountPath) {
    let admin;
    try {
        admin = require('firebase-admin');
    } catch (e) {
        console.error('\n[Error] firebase-admin package is required.');
        console.error('Run: npm install in tools/license-generator/');
        process.exit(1);
    }

    if (!serviceAccountPath || !fs.existsSync(serviceAccountPath)) {
        console.error(`\n[Error] Service account file not found: ${serviceAccountPath}`);
        console.error('Download serviceAccountKey.json from Firebase Console -> Project Settings -> Service Accounts');
        process.exit(1);
    }

    if (!admin.apps.length) {
        const serviceAccount = JSON.parse(fs.readFileSync(serviceAccountPath, 'utf-8'));
        admin.initializeApp({
            credential: admin.credential.cert(serviceAccount)
        });
    }

    return admin;
}

async function registerInFirestore(records, serviceAccountPath) {
    const admin = getAdmin(serviceAccountPath);
    const db = admin.firestore();
    const batch = db.batch();

    for (const record of records) {
        const docRef = db.collection('licenseKeys').doc(record.keyHash);
        batch.set(docRef, {
            type: record.plan.toUpperCase(),
            status: 'UNUSED',
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            claimedBy: null,
            claimedAt: null
        });
    }

    await batch.commit();
    console.log(`\n[Success] Registered ${records.length} license key(s) in Firestore (licenseKeys collection).`);
}

async function handleRegisterSpecificKey(rawKey, serviceAccountPath) {
    const normalizedKey = rawKey.trim().toUpperCase();
    const hash = sha256Hex(normalizedKey);
    const admin = getAdmin(serviceAccountPath);
    const db = admin.firestore();
    const docRef = db.collection('licenseKeys').doc(hash);

    const doc = await docRef.get();
    if (doc.exists) {
        console.log(`\n[Info] Key already registered in Firestore!`);
        console.log(`Status: ${doc.data().status}`);
        if (doc.data().claimedBy) {
            console.log(`Claimed by: ${doc.data().claimedBy}`);
        }
        return;
    }

    await docRef.set({
        type: 'LIFETIME',
        status: 'UNUSED',
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        claimedBy: null,
        claimedAt: null
    });

    console.log(`
=============================================================
✅ KEY SUCCESSFULLY REGISTERED IN FIRESTORE
=============================================================

🔑 Key:    ${normalizedKey}
🛡️  Hash:   ${hash}
⚡ Status: UNUSED (Ready for Customer Activation)

-------------------------------------------------------------
📩 WHATSAPP / CUSTOMER MESSAGE (Copy & Send):
-------------------------------------------------------------
🎉 Here is your MAX AI Assistant Lifetime License Key:

👉 ${normalizedKey}

How to activate:
1. Open the MAX app on your Android phone
2. Sign in with your Google account
3. Go to License Screen and paste this key!
=============================================================
`);
}

async function handleGrantUser(userId, serviceAccountPath) {
    const admin = getAdmin(serviceAccountPath);
    const db = admin.firestore();
    const docRef = db.collection('licenses').doc(userId.trim());

    await docRef.set({
        userId: userId.trim(),
        plan: 'LIFETIME',
        status: 'ACTIVE',
        licenseId: 'ADMIN_MANUAL_GRANT',
        activatedAt: admin.firestore.FieldValue.serverTimestamp(),
        expiresAt: null,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    console.log(`
=============================================================
🎉 USER DIRECTLY GRANTED LIFETIME PREMIUM
=============================================================
User ID:   ${userId}
Plan:      LIFETIME
Status:    ACTIVE
Expires:   NEVER (Lifetime)

👉 The user can now restart the MAX app or tap 'Sync Now'
   to immediately enjoy Lifetime Premium!
=============================================================
`);
}

async function handleStatus(keyOrHash, serviceAccountPath) {
    const admin = getAdmin(serviceAccountPath);
    const db = admin.firestore();
    const cleaned = keyOrHash.trim().toUpperCase();
    const hash = cleaned.length === 64 && /^[0-9A-F]+$/i.test(cleaned) ? cleaned.toLowerCase() : sha256Hex(cleaned);

    const doc = await db.collection('licenseKeys').doc(hash).get();
    if (!doc.exists) {
        console.log(`\n❌ Key NOT FOUND in Firestore! (Hash: ${hash})`);
        return;
    }

    const data = doc.data();
    console.log(`
=============================================================
📋 LICENSE KEY DETAILS
=============================================================
Document ID (Hash): ${hash}
Type:               ${data.type}
Status:             ${data.status}
Claimed By:         ${data.claimedBy || 'None (UNUSED)'}
Claimed At:         ${data.claimedAt ? data.claimedAt.toDate().toISOString() : 'None'}
Created At:         ${data.createdAt ? data.createdAt.toDate().toISOString() : 'Unknown'}
=============================================================
`);
}

async function handleList(serviceAccountPath) {
    const admin = getAdmin(serviceAccountPath);
    const db = admin.firestore();
    const snap = await db.collection('licenseKeys').limit(50).get();

    console.log(`\nTotal registered keys in Firestore (first 50): ${snap.size}`);
    console.log('-------------------------------------------------------------');
    snap.forEach(doc => {
        const d = doc.data();
        console.log(`Hash: ${doc.id.substring(0, 16)}... | Status: ${d.status.padEnd(8)} | Type: ${d.type} | ClaimedBy: ${d.claimedBy || '-'}`);
    });
    console.log('-------------------------------------------------------------\n');
}

async function main() {
    const config = parseArgs();

    if (config.registerKey) {
        await handleRegisterSpecificKey(config.registerKey, config.serviceAccount);
        return;
    }

    if (config.grantUser) {
        await handleGrantUser(config.grantUser, config.serviceAccount);
        return;
    }

    if (config.verifyKey) {
        await handleStatus(config.verifyKey, config.serviceAccount);
        return;
    }

    if (config.listKeys) {
        await handleList(config.serviceAccount);
        return;
    }

    const records = [];

    for (let i = 0; i < config.count; i++) {
        const rawKey = generateLicenseKey();
        const keyHash = sha256Hex(rawKey);
        records.push({
            rawKey,
            keyHash,
            plan: config.plan.toUpperCase(),
            status: 'UNUSED',
            createdAt: new Date().toISOString()
        });
    }

    let output = '';
    if (config.format === 'json') {
        output = JSON.stringify(records, null, 2);
    } else if (config.format === 'csv') {
        output = 'rawKey,keyHash,plan,status,createdAt\n' +
            records.map(r => `${r.rawKey},${r.keyHash},${r.plan},${r.status},${r.createdAt}`).join('\n');
    } else if (config.format === 'raw') {
        output = records.map(r => r.rawKey).join('\n');
    } else {
        if (records.length === 1 && !config.out) {
            const r = records[0];
            output = `
=============================================================
🔑 NEW MAX COMMERCIAL LICENSE KEY GENERATED
=============================================================

1️⃣  CUSTOMER KEY:
    👉  ${r.rawKey}

${config.register ? '⚡ STATUS: Registered in Firestore (UNUSED)' : `-------------------------------------------------------------
⚠️  NOT YET REGISTERED IN FIRESTORE!
    To register this key so users can activate it, run:
    node generate.js --register-key ${r.rawKey}
    (Or next time use: node generate.js --register)`}

-------------------------------------------------------------
📩 WHATSAPP / CUSTOMER MESSAGE (Copy & Send):
-------------------------------------------------------------
🎉 Here is your MAX AI Assistant Lifetime License Key:

👉 ${r.rawKey}

How to activate:
1. Open the MAX app on your Android phone
2. Sign in with your Google account
3. Go to License Screen and paste this key!
=============================================================
`;
        } else {
            output = records.map(r => `${r.rawKey}  ->  Hash: ${r.keyHash}`).join('\n');
        }
    }

    if (config.out) {
        fs.writeFileSync(config.out, output, 'utf-8');
        console.log(`Saved ${records.length} keys to ${config.out}`);
    } else {
        console.log(output);
    }

    if (config.register) {
        await registerInFirestore(records, config.serviceAccount);
    }
}

main().catch(err => {
    console.error('Fatal error:', err);
    process.exit(1);
});

