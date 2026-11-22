# MAX AI — Public Website

A responsive public marketing website for MAX AI, built with plain HTML, CSS and JavaScript.

## Files
- `index.html` — page structure and copy
- `styles.css` — responsive design, gradients, motion and layouts
- `script.js` — mobile navigation, scroll reveals, and placeholder-link notices

## Run locally
Open `index.html` in a browser, or run:
```bash
python -m http.server 8000
```
Then visit `http://localhost:8000`.

## Before publishing
Search `script.js` for `const downloadLink = '#'` and replace it with the official Google Play URL or verified APK URL. Replace the privacy and terms placeholder handlers with real pages/links. Confirm every advertised feature and plan matches the product you actually ship. The Premium tier is explicitly marked as planned, with no invented price.

## Backend integrations needed
This public landing page is static and does not require a backend to serve. The following integrations are needed for real product features:
1. **AI chat:** A secure backend endpoint calling your chosen Gemini or other model. Keep API secrets server-side, validate requests, enforce quotas, and return safe errors.
2. **Authentication:** Firebase Authentication or another identity provider if users need accounts, sync, or paid entitlements.
3. **Data and memory:** Firestore or PostgreSQL/Supabase with per-user access rules, retention limits, deletion/export paths, and privacy disclosures.
4. **Subscriptions:** Google Play Billing for purchases inside the Android app. For web subscriptions, use a payment provider supported in your country and verify webhook events server-side. Never trust a client-side premium flag.
5. **Android download:** Publish the app on Google Play and use its public listing URL. If distributing an APK directly, host a signed release securely and publish integrity/version details.
6. **Agent tools and automation:** A backend tool router with authentication, per-action authorization, rate limits, audit logs, and user confirmation for sensitive actions. Android device automation generally must run on the user's device with explicit Android permissions; a website cannot silently control their phone.
7. **Operations:** Abuse protection, rate limiting, monitoring, backups, error tracking, and a published privacy policy/terms/support contact.

## Suggested deployment
- Static frontend: Vercel, Netlify, or Firebase Hosting.
- Backend: Firebase Cloud Functions, Cloud Run, or another server you control.
- Secrets: environment variables or a managed secret store, never committed into browser JavaScript.
