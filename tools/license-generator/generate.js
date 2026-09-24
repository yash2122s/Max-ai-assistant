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
        format: 'text', // 'text' | 'json' | 'csv'
        out: null,
        register: false,
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
        } else if (arg === '--register') {
            config.register = true;
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
MAX License Key Generator (Owner / Developer CLI)
=================================================

Usage:
  node generate.js [options]

Options:
  --count, -c <number>         Number of keys to generate (default: 1)
  --plan, -p <lifetime>        License plan (default: lifetime)
  --format, -f <text|json|csv> Output format (default: text)
  --out, -o <file>             Output file path (default: stdout)
  --register                   Register generated key hashes into Firestore (requires firebase-admin)
  --service-account <file>     Path to Firebase Service Account JSON (required if --register)
  --help, -h                   Show this help message

Examples:
  node generate.js --count 5
  node generate.js --count 10 --format json --out keys.json
  node generate.js --count 5 --register --service-account ./service-account.json
`);
}

async function registerInFirestore(records, serviceAccountPath) {
    let admin;
    try {
        admin = require('firebase-admin');
    } catch (e) {
        console.error('\n[Error] firebase-admin package is required for --register.');
        console.error('Run: npm install firebase-admin in tools/license-generator/');
        process.exit(1);
    }

    if (!serviceAccountPath || !fs.existsSync(serviceAccountPath)) {
        console.error(`\n[Error] Service account file not found: ${serviceAccountPath}`);
        console.error('Download serviceAccountKey.json from Firebase Console -> Project Settings -> Service Accounts');
        process.exit(1);
    }

    const serviceAccount = JSON.parse(fs.readFileSync(serviceAccountPath, 'utf-8'));
    if (!admin.apps.length) {
        admin.initializeApp({
            credential: admin.credential.cert(serviceAccount)
        });
    }

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
    console.log(`\n[Success] Registered ${records.length} license key hashes in Firestore (licenseKeys collection).`);
}

async function main() {
    const config = parseArgs();
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

1️⃣  CUSTOMER KEY (యూజర్‌కి / కస్టమర్‌కి ఇచ్చే కీ):
    👉  ${r.rawKey}

-------------------------------------------------------------
2️⃣  FIREBASE CONSOLE SETUP (Firestore లో 30 సెకన్లలో యాడ్ చేయండి):
    📁 Collection:   licenseKeys
    📄 Document ID:  ${r.keyHash}

    📝 Fields (Add these 5 fields):
       • status     : "UNUSED"       (Type: string)
       • type       : "${r.plan}"     (Type: string)
       • claimedBy  : null           (Type: null)
       • claimedAt  : null           (Type: null)
       • createdAt  : [Server timestamp] (Type: timestamp)
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
