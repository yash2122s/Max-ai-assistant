---
name: CI/CD Pipelines
description: Automated workflows for building, testing, and deploying.
compliance_level: MANDATORY
tags: [ci, cd, github-actions, workflows, automation]
version: 2.2.0
---

# CI/CD Pipelines

## Context
Automated checks ensure code quality and prevent incomplete releases. This guide covers GitHub Actions setup for Android projects.

**Related Guides:**
- [17-build-configuration.md](./17-build-configuration.md) - Build setup
- [11-testing.md](./11-testing.md) - Testing standards
- [12-security.md](./12-security.md) - Security configuration

---

## 🎯 AI Quick Reference

```
WORKFLOWS:
ci.yml       → On PR: lint, test, build
release.yml  → On tag: sign, build bundle, deploy

JOBS:
lint         → detekt, ktlint
test         → unit tests, coverage
build        → assemble debug APK
release      → sign, bundle, upload

SECRETS:
KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
PLAY_STORE_SERVICE_ACCOUNT_JSON (for deployment)
```

---

## 1. CI Workflow

### ✅ DO: Comprehensive CI Workflow
```yaml
# .github/workflows/ci.yml
name: CI

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

env:
  JAVA_VERSION: '17'
  GRADLE_OPTS: "-Dorg.gradle.daemon=false -Dorg.gradle.workers.max=2"

jobs:
  # ════════════════════════════════════════════════════════════════
  # Lint job
  # ════════════════════════════════════════════════════════════════
  lint:
    name: Lint
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}

      - name: Run Detekt
        run: ./gradlew detekt

      - name: Run Ktlint
        run: ./gradlew ktlintCheck

      - name: Upload Lint Reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: lint-reports
          path: |
            **/build/reports/detekt/
            **/build/reports/ktlint/

  # ════════════════════════════════════════════════════════════════
  # Unit Test job
  # ════════════════════════════════════════════════════════════════
  test:
    name: Unit Tests
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}

      - name: Run Unit Tests
        run: ./gradlew testDebugUnitTest

      - name: Upload Test Results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: '**/build/test-results/'

      - name: Upload Coverage Report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: coverage-report
          path: '**/build/reports/jacoco/'

  # ════════════════════════════════════════════════════════════════
  # Build job
  # ════════════════════════════════════════════════════════════════
  build:
    name: Build
    needs: [lint, test]
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}

      - name: Build Debug APK
        run: ./gradlew assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: app/build/outputs/apk/debug/*.apk
```

---

## 2. Release Workflow

### ✅ DO: Automated Release
```yaml
# .github/workflows/release.yml
name: Release

on:
  push:
    tags:
      - 'v*'

env:
  JAVA_VERSION: '17'

jobs:
  # ════════════════════════════════════════════════════════════════
  # Release Build
  # ════════════════════════════════════════════════════════════════
  release:
    name: Build Release
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3

      # Decode keystore from base64 secret
      - name: Decode Keystore
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
        run: |
          echo $KEYSTORE_BASE64 | base64 --decode > app/release.keystore

      # Build signed bundle
      - name: Build Release Bundle
        env:
          RELEASE_STORE_FILE: release.keystore
          RELEASE_STORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: ./gradlew bundleRelease

      # Upload bundle artifact
      - name: Upload Bundle
        uses: actions/upload-artifact@v4
        with:
          name: release-bundle
          path: app/build/outputs/bundle/release/*.aab

      # Create GitHub Release
      - name: Create GitHub Release
        uses: softprops/action-gh-release@v1
        with:
          files: |
            app/build/outputs/bundle/release/*.aab
            app/build/outputs/mapping/release/mapping.txt
          generate_release_notes: true

  # ════════════════════════════════════════════════════════════════
  # Deploy to Play Store (optional)
  # ════════════════════════════════════════════════════════════════
  deploy:
    name: Deploy to Play Store
    needs: release
    runs-on: ubuntu-latest
    if: startsWith(github.ref, 'refs/tags/v')
    steps:
      - name: Download Bundle
        uses: actions/download-artifact@v4
        with:
          name: release-bundle

      - name: Upload to Play Store
        uses: r0adkll/upload-google-play@v1
        with:
          serviceAccountJsonPlainText: ${{ secrets.PLAY_STORE_SERVICE_ACCOUNT_JSON }}
          packageName: com.example.myapp
          releaseFiles: '*.aab'
          track: internal  # internal, alpha, beta, production
          status: completed
```

---

## 3. Secrets Configuration

### Required Secrets
| Secret | Description | How to Generate |
|--------|-------------|-----------------|
| `KEYSTORE_BASE64` | Base64 encoded keystore | `base64 -i release.keystore` |
| `KEYSTORE_PASSWORD` | Keystore password | From keystore creation |
| `KEY_ALIAS` | Key alias name | From keystore creation |
| `KEY_PASSWORD` | Key password | From keystore creation |
| `PLAY_STORE_SERVICE_ACCOUNT_JSON` | Google Play API key | Play Console → API access |

### Setting Up Secrets
```bash
# Encode keystore to base64 (macOS/Linux)
base64 -i path/to/release.keystore | pbcopy

# Then paste in GitHub:
# Repository → Settings → Secrets → Actions → New repository secret
```

---

## 4. Composite Actions

### ✅ DO: Reusable Setup Action
```yaml
# .github/actions/setup-android/action.yml
name: Setup Android
description: Setup JDK and Gradle for Android builds

inputs:
  java-version:
    description: JDK version
    required: false
    default: '17'
  cache-read-only:
    description: Whether Gradle cache is read-only
    required: false
    default: 'false'

runs:
  using: composite
  steps:
    - name: Setup JDK
      uses: actions/setup-java@v4
      with:
        java-version: ${{ inputs.java-version }}
        distribution: 'temurin'

    - name: Setup Gradle
      uses: gradle/actions/setup-gradle@v3
      with:
        cache-read-only: ${{ inputs.cache-read-only }}
```

### Usage in Workflow
```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: ./.github/actions/setup-android
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}
      - run: ./gradlew assembleDebug
```

---

## 5. Screenshot Tests

### ✅ DO: Paparazzi in CI
```yaml
# Add to ci.yml
  screenshot-tests:
    name: Screenshot Tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - uses: ./.github/actions/setup-android
      
      - name: Run Screenshot Tests
        run: ./gradlew verifyPaparazziDebug
      
      - name: Upload Screenshot Failures
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: screenshot-failures
          path: '**/build/paparazzi/failures/'
```

---

## 6. Verification Checklist

### CI Workflow
- [ ] Lint job runs detekt/ktlint
- [ ] Test job runs unit tests
- [ ] Build job produces APK
- [ ] Artifacts uploaded

### Release Workflow
- [ ] Triggered on tag push
- [ ] Keystore decoded from secrets
- [ ] Bundle signed correctly
- [ ] GitHub Release created

### Secrets
- [ ] Keystore stored as base64
- [ ] All passwords in secrets
- [ ] No secrets in logs

### Optimization
- [ ] Gradle caching enabled
- [ ] Concurrency group set
- [ ] Jobs run in parallel where possible
