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

## Usage

### 1. Generate 1 Lifetime Key (stdout)
```bash
node generate.js
```
Output:
```
MAX-LIFE-7K4P-X9QM-28TZ
```

### 2. Generate 10 Keys in Text Format
```bash
node generate.js --count 10
```

### 3. Generate JSON with SHA-256 Hashes
```bash
node generate.js --count 10 --format json --out keys.json
```

### 4. Generate CSV for Customer Tracking
```bash
node generate.js --count 20 --format csv --out customers.csv
```

### 5. Direct Registration to Firestore (Optional)
If you have your Firebase Service Account JSON (`serviceAccountKey.json`):
```bash
npm install
node generate.js --count 5 --register --service-account ./serviceAccountKey.json
```
This registers the SHA-256 hashes with `status: "UNUSED"` directly in `licenseKeys/{hash}`.
