# MAX Commercial License Key Generator (Owner / Developer Tool)

This is an **owner-only** utility for generating and hashing commercial lifetime license keys for MAX AI Assistant.

> **CRITICAL SECURITY NOTE:**
> - This directory is strictly developer-side and is **never** packaged into the Android APK.
> - Never commit private Firebase service account credentials (`serviceAccountKey.json`) to Git.

## Key Specification
- **Format:** `MAX-LIFE-XXXX-XXXX-XXXX`
- **Character Set:** Crockford Base32-inspired `[2-9A-HJ-NP-Z]` (high entropy, avoiding confusing characters like 0/O, 1/I)
- **Entropy:** > 1 quintillion unique combinations (collision-resistant)
- **Hash Algorithm:** SHA-256 (matches Android app `LicenseKeyHelper.kt`)
- **Firestore Collection:** `licenseKeys/{sha256Hex}`

## Quick Commands for Owners

### 1. Generate & Register 1 Lifetime Key in Firestore (Recommended)
```bash
node generate.js --register
```
Or:
```bash
npm run create-key
```
This generates the key and immediately registers its hash into Firestore with `status: "UNUSED"`. It also outputs a ready-to-copy WhatsApp/SMS message to send directly to your customer!

### 2. Register a Key That Was Created Earlier (Offline)
```bash
node generate.js --register-key MAX-LIFE-XXXX-XXXX-XXXX
```
Registers that specific key in Firestore so the user can activate it.

### 3. Directly Grant Lifetime Premium to a User (No Key Needed!)
```bash
node generate.js --grant-user <USER_ID>
```
Instantly activates Lifetime Premium for that Google user ID in Firestore.

### 4. Check Status of a Key
```bash
node generate.js --status MAX-LIFE-XXXX-XXXX-XXXX
```
Shows if the key is `UNUSED`, or `CLAIMED` by which account and when.

### 5. List Registered Keys
```bash
node generate.js --list
```

### 6. Bulk Export Keys (e.g. 50 keys to CSV / JSON)
```bash
node generate.js --count 50 --format csv --out keys.csv --register
node generate.js --count 50 --format json --out keys.json --register
```

